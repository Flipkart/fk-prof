#include <sys/types.h>
#include <sys/stat.h>
#include "logging.hh"
#include "ftrace/server.hh"
#include "ftrace/tracer.hh"
#include <sys/types.h>
#include <sys/socket.h>
#include <sys/un.h>
#include <sys/epoll.h>
#include <unordered_set>
#include <sys/uio.h>
#include <memory>
#include <unistd.h>

enum class FdType : std::uint8_t {
    listener = 0, client, tracer
};

namespace PollCtx {
    int fd(std::uint64_t val) {
        return (val >> 8) & 0xFFFFFFFF;
    }

    FdType type(std::uint64_t val) {
        return static_cast<FdType>(val & 0xFF);
    }

    std::uint64_t pack(FdType type, int fd) {
        assert(fd > 0);
        std::uint64_t val = fd;
        return (val << 8) | (static_cast<std::uint8_t>(type) & 0xFF);
    }
}

static void make_non_blocking(int fd) {
    int flags = fcntl(fd, F_GETFL);
    if (flags == -1) throw log_and_get_error("Couldn't create listener-socket", errno);

    flags |= O_NONBLOCK;

    auto ret = fcntl(fd, F_SETFL, flags);
    if(ret == -1) throw log_and_get_error("Couldn't create listener-socket", errno);
}

#define METRIC_TYPE "server"

static const size_t io_buff_sz = 16 * 1024;

ftrace::Server::Server(const std::string& tracing_dir, const std::string& _socket_path) :
    socket_path(_socket_path), keep_running(true), tracer(nullptr), io_buff(new std::uint8_t[io_buff_sz]),
    
    s_t_processing_time(get_metrics_registry().new_timer({METRICS_DOMAIN_TRACE, METRIC_TYPE, "processing_time"})),
    s_t_wait_time(get_metrics_registry().new_timer({METRICS_DOMAIN_TRACE, METRIC_TYPE, "wait_time"})),
    s_m_poll_events(get_metrics_registry().new_meter({METRICS_DOMAIN_TRACE, METRIC_TYPE, "poll", "events"}, "rate")),
    s_c_failed_clients(get_metrics_registry().new_counter({METRICS_DOMAIN_TRACE, METRIC_TYPE, "client", "failure"})),
    s_c_slow_read_failures(get_metrics_registry().new_counter({METRICS_DOMAIN_TRACE, METRIC_TYPE, "client", "slow_read_failures"})) {
    
    poll_fd = epoll_create1(EPOLL_CLOEXEC);
    if (poll_fd < 0) {
        throw log_and_get_error("Couldn't create epoll-fd", errno);
    }
    setup_listener();
    tracer.reset(new Tracer(tracing_dir, *this, [&](const ftrace::Tracer::DataLink& link) {
                auto fd = link.pipe_fd;
                make_non_blocking(fd);
                epoll_event evt { .events = EPOLLIN, .data = {.u64 = PollCtx::pack(FdType::tracer, fd)}};
                trace_links[fd] = &link;
                auto ret = epoll_ctl(poll_fd, EPOLL_CTL_ADD, fd, &evt);
                if (ret != 0) throw log_and_get_error("Couldn't add tracer-fd to epoll-ctx", errno);
            }));
}

ftrace::Server::~Server() {
    shutdown();
}

// We may need to tune this value upwards as servers/VMs with higher number of cores arrive (because ftrace-buffer is a per-cpu asset)
#define MAX_POLLED_EVENTS 256
#define SERVER_POLL_TIMEOUT_SECONDS 10

void ftrace::Server::run() {
    int num_evts;
    epoll_event evts[MAX_POLLED_EVENTS];

    while (keep_running) {{
            auto timer_ctx = s_t_wait_time.time_scope();
            num_evts = epoll_wait(poll_fd, evts, MAX_POLLED_EVENTS, SERVER_POLL_TIMEOUT_SECONDS * 1000);
        }
        auto timer_ctx = s_t_processing_time.time_scope();
        if (num_evts < 0) {
            logger->warn(error_message("IO-poll failed", errno));
        } else {
            s_m_poll_events.mark(num_evts);
            for (int i = 0; i < num_evts; i++) {
                io_evt(evts[i]);
            }
        }
    }

    shutdown();
}

void ftrace::Server::shutdown() {
    if (poll_fd < 0) return;

    logger->info("Shutting down server");

    epoll_ctl(poll_fd, EPOLL_CTL_DEL, listener_fd, nullptr);
    close(listener_fd);
    for (auto it = std::begin(client_sessions); it != std::end(client_sessions); ) {
        drop_client_session(it); //it erases the entry
    }
    for (auto it = std::begin(trace_links); it != std::end(trace_links); ) {
        epoll_ctl(poll_fd, EPOLL_CTL_DEL, it->first, nullptr);
        trace_links.erase(it);
    }
    close(poll_fd);
    tracer.reset(nullptr);
    poll_fd = -1;
    unlink(socket_path.c_str());
}

struct ftrace::ClientSession {
    std::unordered_set<pid_t> tids;

    std::uint8_t part_msg_len;

    const static size_t max_msg_sz = (1 << (sizeof(part_msg_len) * 8)) - 1;

    std::uint8_t part_msg[max_msg_sz];//partial message

    std::string sock_path;

    ClientSession(const char* _sock_path) : tids(), part_msg_len(0), sock_path(_sock_path) {}

    ~ClientSession() {};
};

void ftrace::Server::io_evt(epoll_event& evt) {
    auto type = PollCtx::type(evt.data.u64);
    assert(evt.events & EPOLLIN);
    auto fd = PollCtx::fd(evt.data.u64);
    switch (type) {
    case FdType::listener:
        assert(fd == listener_fd);
        accept_client_sessions();
        break;
    case FdType::client:
        handle_client_requests(fd);
        break;
    case FdType::tracer: {
        auto it = trace_links.find(fd);
        assert(it != std::end(trace_links));
        tracer->process(*it->second);}
        break;
    default:
        auto msg = "Received epoll-event for unknown fd-type";
        logger->error(msg);
        throw std::runtime_error(msg);
    }
}

void ftrace::Server::accept_client_sessions() {
    sockaddr_un addr;
    socklen_t addr_len = sizeof(addr);
    ClientFd client_fd = accept(listener_fd, reinterpret_cast<sockaddr*>(&addr), &addr_len);
    if (client_fd < 0) throw log_and_get_error("Couldn't accept client-connection", errno);
    assert(addr_len < (sizeof(addr) - offsetof(sockaddr_un, sun_path)));
    make_non_blocking(client_fd);
    client_sessions[client_fd] = std::unique_ptr<ClientSession>(new ClientSession(addr.sun_path));
    epoll_event evt { .events = EPOLLIN, .data = {.u64 = PollCtx::pack(FdType::client, client_fd)}};
    auto ret = epoll_ctl(poll_fd, EPOLL_CTL_ADD, client_fd, &evt);
    if (ret != 0) throw log_and_get_error("Couldn't add client-fd to epoll-ctx", errno);
    logger->info("Accepted connection from: {}", addr.sun_path);
}

void ftrace::Server::drop_client_session(std::unordered_map<ClientFd, std::unique_ptr<ClientSession>>::iterator it) {
    auto fd = it->first;
    assert(it != std::end(client_sessions));
    for (const auto tid : it->second->tids) {
        auto kill_count = pids_client.erase(tid);
        assert(kill_count == 1);
        tracer->trace_off(tid);
    }
    auto ret = epoll_ctl(poll_fd, EPOLL_CTL_DEL, fd, nullptr);
    if (ret != 0) throw log_and_get_error("Couldn't remove client-fd out of epoll-ctx", errno);
    ret = close(fd);
    if (ret != 0) throw log_and_get_error("Couldn't close client-fd", errno);
    logger->info("Dropped client-session for: {}", it->second->sock_path);
    client_sessions.erase(it);
}

void ftrace::Server::drop_client_session(ClientFd fd) {
    const auto& it = client_sessions.find(fd);
    drop_client_session(it);
}

void ftrace::Server::fail_client(ClientFd fd) {
    s_c_failed_clients.inc();
    drop_client_session(fd);
}

void ftrace::Server::do_add_tid(ClientFd fd, v_curr::payload::AddTid* payload) {
    auto it = client_sessions.find(fd);
    assert(it != std::end(client_sessions));
    auto pid = *payload;
    auto rev_it = pids_client.find(pid);
    if (rev_it != std::end(pids_client)) {
        // We are in a temporary state where some process has died
        //   and tid has been re-assigned to a new thread from some other process.
        // It may be _ok_ with silently replace the pid-mapping here, but it has likelihood
        //    of making debugging harder, so we fail insteed.
        auto conflicting_fd = rev_it->second;
        auto conflicting_client = client_sessions.find(conflicting_fd);
        assert(conflicting_client != std::end(client_sessions));
        logger->warn("Client [fd {} bound to '{}'] tried to trace conflicting pid-entry {} [fd {} bound to '{}'], "
                     "failing the client",
                     fd, it->second->sock_path,
                     pid,
                     conflicting_fd, conflicting_client->second->sock_path);
        fail_client(fd);
    }
    it->second->tids.insert(pid);
    pids_client[pid] = fd;
    tracer->trace_on(pid, reinterpret_cast<void*>(static_cast<std::uint64_t>(fd)));
    logger->info("Tracking pid {} for ftrace events", pid);
}

void ftrace::Server::do_del_tid(ClientFd fd, v_curr::payload::DelTid* payload) {
    auto it = client_sessions.find(fd);
    assert(it != std::end(client_sessions));
    auto pid = *payload;
    auto rev_it = pids_client.find(pid);
    if (rev_it == std::end(pids_client)) {
        // The invariant was broken earlier, it no longer is broken after this call
        //   (since the plan was to take the pid off-tracking anyway). So we just warn.
        logger->warn("Client [fd {} bound to '{}'] tried to stop tracing an unknown tid: {}",
                     fd, it->second->sock_path, pid);
    }
    it->second->tids.erase(pid);
    pids_client.erase(pid);
    logger->info("Removing tracking for pid {}", pid);
}

void ftrace::Server::handle_pkt(ClientFd fd, v_curr::PktType type, std::uint8_t* buff, size_t len) {
    switch (type) {
    case v_curr::PktType::toggle_features:
        assert(len == sizeof(v_curr::payload::Features));
        logger->warn("Feature toggling is not implemented yet, request is being ignored.");
        break;
    case v_curr::PktType::add_tid:
        assert(len == sizeof(v_curr::payload::AddTid));
        do_add_tid(fd, reinterpret_cast<v_curr::payload::AddTid*>(buff));
        break;
    case v_curr::PktType::del_tid:
        assert(len == sizeof(v_curr::payload::DelTid));
        do_del_tid(fd, reinterpret_cast<v_curr::payload::DelTid*>(buff));
        break;
    default:
        logger->error("Received pkt with unexpected type ({}) of RPC", std::to_string(type));
        throw std::runtime_error("Unexpected rpc class: " + std::to_string(type));
    }
}

static const ftrace::v_curr::PayloadLen hdr_sz = sizeof(ftrace::v_curr::Header);

void ftrace::Server::handle_client_requests(ClientFd fd) {
    //TODO: version me!
    //  Wrap another method around this one, so the wrapper can deal with first 3 bits (for versioning) and
    //   dispatch this by version -jj
    auto buff = io_buff.get();
    auto read_sz = recv(fd, buff, io_buff_sz, 0);

    if (read_sz == 0) {
        drop_client_session(fd);
        return;
    }

    while (read_sz > 0) {
        auto it = client_sessions.find(fd);
        assert(it != std::end(client_sessions));
        auto& sess = *it->second.get();

        if (sess.part_msg_len == 0) {
            if (read_sz >= hdr_sz) {
                auto h = reinterpret_cast<v_curr::Header*>(buff);
                auto pkt_len = h->len;
                if (read_sz >= pkt_len) {
                    handle_pkt(fd, h->type, buff + hdr_sz, pkt_len - hdr_sz);
                    buff += pkt_len;
                    read_sz -= pkt_len;
                } else {
                    memcpy(sess.part_msg, buff, read_sz);
                    sess.part_msg_len = read_sz;
                    read_sz = 0;
                }
            } else {
                memcpy(sess.part_msg, buff, read_sz);
                sess.part_msg_len = read_sz;
                read_sz = 0;
            }
        } else {
            if (sess.part_msg_len >= hdr_sz) {
                auto h = reinterpret_cast<v_curr::Header*>(sess.part_msg);
                auto pkt_len = h->len;
                auto payload_sz = pkt_len - hdr_sz;
                auto missing_payload_bytes = pkt_len - sess.part_msg_len;
                assert(missing_payload_bytes > 0);
                if (read_sz >= missing_payload_bytes) {
                    memcpy(sess.part_msg + sess.part_msg_len, buff, missing_payload_bytes);
                    handle_pkt(fd, h->type, sess.part_msg + hdr_sz, payload_sz);
                    buff += missing_payload_bytes;
                    read_sz -= missing_payload_bytes;
                    sess.part_msg_len = 0;
                } else {
                    memcpy(sess.part_msg + sess.part_msg_len, buff, read_sz);
                    sess.part_msg_len += read_sz;
                    read_sz = 0;
                }
            } else {
                auto missing_header_bytes = (hdr_sz - sess.part_msg_len);
                assert(missing_header_bytes > 0);
                if (read_sz >= missing_header_bytes) {
                    memcpy(sess.part_msg + sess.part_msg_len, buff, missing_header_bytes);
                    buff += missing_header_bytes;
                    read_sz -= missing_header_bytes;
                    sess.part_msg_len += missing_header_bytes;
                    auto h = reinterpret_cast<v_curr::Header*>(sess.part_msg);
                    auto pkt_len = h->len;
                    auto payload_sz = pkt_len - hdr_sz;
                    if (read_sz >= payload_sz) {
                        handle_pkt(fd, h->type, buff, payload_sz);
                        buff += payload_sz;
                        read_sz -= payload_sz;
                        sess.part_msg_len = 0;
                    } else {
                        memcpy(sess.part_msg + sess.part_msg_len, buff, read_sz);
                        sess.part_msg_len += read_sz;
                        read_sz = 0;
                    }
                } else {
                    memcpy(sess.part_msg + sess.part_msg_len, buff, read_sz);
                    sess.part_msg_len += read_sz;
                    read_sz = 0;
                }
            }
        }
    }
    
    //reinterpret_cast<ftrace::v_curr::Header*>(buff);
}

void ftrace::Server::stop() {
    keep_running.store(false, std::memory_order_relaxed);
}

void ftrace::Server::setup_listener() {
    listener_fd = socket(AF_UNIX, SOCK_STREAM | SOCK_NONBLOCK | SOCK_CLOEXEC, 0);
    if (listener_fd < 0) throw log_and_get_error("Couldn't create listener-socket", errno);
    
    sockaddr_un addr;
    std::memset(&addr, 0, sizeof(addr));
    addr.sun_family = AF_UNIX;
    std::strncpy(addr.sun_path, socket_path.c_str(), sizeof(addr.sun_path));
    // We can unlink before bind, but that will be useful only when the server is misbehaving (crashing etc),
    //    in that kind of a situation, let us just fix the problem (we don't need that unlink hack) -jj
    auto ret = bind(listener_fd, reinterpret_cast<sockaddr*>(&addr), sizeof(addr));
    if (ret != 0) throw log_and_get_error("Couldn't bind server socket", errno);

    ret = fchmod(listener_fd, S_IRWXU | S_IRGRP | S_IWGRP | S_IXGRP | S_IROTH | S_IWOTH | S_IXOTH);
    if (ret != 0) throw log_and_get_error("Couldn't set permissions (chmod) on server socket", errno);

    ret = listen(listener_fd, 8);
    if (ret != 0) throw log_and_get_error("Couldn't start listening", errno);
 
    epoll_event evt { .events = EPOLLIN, .data = {.u64 = PollCtx::pack(FdType::listener, listener_fd)}};
    ret = epoll_ctl(poll_fd, EPOLL_CTL_ADD, listener_fd, &evt);
    if (ret != 0) throw log_and_get_error("Couldn't add listener to epoll-ctx", errno);
}

void ftrace::Server::write(ClientFd fd, iovec* iov, size_t iov_len, size_t len) {
    auto written = writev(fd, iov, iov_len);
    if (written < len) {
        auto it = client_sessions.find(fd);
        assert(it != std::end(client_sessions));
        logger->warn("Failing client [fd {} bound to '{}'] as something went wrong while writing to it (slow read?), tried to write {}, but only {} bytes went thru", fd, it->second->sock_path, len, written);
        s_c_slow_read_failures.inc();
        fail_client(fd);
    }
}

void init_client_bound_write(iovec* iov, size_t iov_len, ftrace::v_curr::PktType pkt_type, const std::uint8_t* payload, ftrace::v_curr::PayloadLen payload_len) {
    auto sz = payload_len + hdr_sz;
    assert(sz <= std::numeric_limits<ftrace::v_curr::PayloadLen>::max());
    ftrace::v_curr::Header h {ftrace::v_curr::VERSION, pkt_type, static_cast<ftrace::v_curr::PayloadLen>(payload_len + hdr_sz)};
    assert(iov_len >= 2);
    iov[0].iov_base = &h;
    iov[0].iov_len = hdr_sz;
    iov[1].iov_base = const_cast<std::uint8_t*>(payload);
    iov[1].iov_len = payload_len;
}

void ftrace::Server::unicast(pid_t dest_pid, void* ctx, v_curr::PktType pkt_type, const std::uint8_t* payload, ftrace::v_curr::PayloadLen payload_len) {
    //TODO: implement batching here else syscall overhead will get us -jj
    const size_t iov_len = 2;
    iovec iov[iov_len];
    auto fd = static_cast<int>(reinterpret_cast<std::uint64_t>(ctx));
    init_client_bound_write(iov, iov_len, pkt_type, payload, payload_len);
    write(fd, iov, iov_len, payload_len + hdr_sz);
}

void ftrace::Server::multicast(v_curr::PktType pkt_type, const std::uint8_t* payload, ftrace::v_curr::PayloadLen payload_len) {
    const size_t iov_len = 2;
    iovec iov[iov_len];
    init_client_bound_write(iov, iov_len, pkt_type, payload, payload_len);
    for (auto& c : client_sessions) {
        write(c.first, iov, iov_len, payload_len + hdr_sz);
    }
}
