#include "ctx_switch_tracer.hh"

#include <sys/types.h>
#include <sys/socket.h>
#include <sys/un.h>
#include "logging.hh"
#include "ftrace/proto.hh"
#include <sys/uio.h>

void ctx_switch_feed_reader(jvmtiEnv *jvmti_env, JNIEnv *jni_env, void *arg) {
    logger->trace("Ctx-Switch tracker thread target entered");
    auto cst = static_cast<CtxSwitchTracer*>(arg);
    cst->handle_trace_events();
}

void close_connection(int& fd) {
    if (fd >= 0) {
        close(fd);
        fd = -1;
    }
}

#define SEND_ME(h, p)                           \
    {                                           \
        auto h_sz = sizeof(h);                  \
        auto p_sz = sizeof(p);                  \
        h.len = h_sz + p_sz;                    \
        const size_t iov_len = 2;               \
        iovec iov[iov_len];                     \
        iov[0].iov_base = &h;                   \
        iov[0].iov_len = h_sz;                  \
        iov[1].iov_base = &p;                   \
        iov[1].iov_len = p_sz;                  \
        send_msg(iov, iov_len, h.len);          \
    }

void CtxSwitchTracer::track_pid(pid_t tid) {
    ftrace::v_curr::payload::AddTid p = tid;
    ftrace::v_curr::Header h { .v = ftrace::v_curr::VERSION, .type = ftrace::v_curr::PktType::add_tid };
    SEND_ME(h, p);
}

void CtxSwitchTracer::untrack_pid(pid_t tid) {
    ftrace::v_curr::payload::DelTid p = tid;
    ftrace::v_curr::Header h { .v = ftrace::v_curr::VERSION, .type = ftrace::v_curr::PktType::del_tid };
    SEND_ME(h, p);
}

void CtxSwitchTracer::send_msg(iovec *iov, std::size_t iov_len, std::size_t expected_len) {
    auto written = writev(trace_conn, iov, iov_len);
    if (written < expected_len) {
        logger->error("Couldn't send message to ftrace-server, failing work");
        s_c_send_errors.inc();
        fail_work();
    }
}

#define METRIC_TYPE "ctxsw_tracer"

#define THREAD_WATCHER_NAME "ctxsw_tracer"

CtxSwitchTracer::CtxSwitchTracer(JavaVM *jvm, jvmtiEnv *jvmti, ThreadMap& _thread_map, ProfileSerializingWriter& _serializer,
                                 std::uint32_t _max_stack_depth, ProbPct& _prob_pct, std::uint8_t _noctx_cov_pct,
                                 std::uint64_t _latency_threshold_ns, bool _track_wakeup_lag, bool _use_global_clock, bool _track_syscall,
                                 const char* listener_socket_path, const char* proc, std::function<void()>& _fail_work) :
    thread_map(_thread_map), do_stop(false), fail_work(_fail_work), trace_conn(-1),

    s_c_peer_disconnected(get_metrics_registry().new_counter({METRICS_DOMAIN, METRIC_TYPE, "peer", "disconnects"})),
    s_c_recv_errors(get_metrics_registry().new_counter({METRICS_DOMAIN, METRIC_TYPE, "recv", "errors"})),
    s_c_send_errors(get_metrics_registry().new_counter({METRICS_DOMAIN, METRIC_TYPE, "send", "errors"})),
    s_t_recv_wait(get_metrics_registry().new_timer({METRICS_DOMAIN, METRIC_TYPE, "read_wait_time"})),
    s_t_recv_total(get_metrics_registry().new_timer({METRICS_DOMAIN, METRIC_TYPE, "recv_total_time"})),
    s_m_data_received(get_metrics_registry().new_meter({METRICS_DOMAIN, METRIC_TYPE, "bytes", "received"}, "rate")),
    s_m_events_received(get_metrics_registry().new_meter({METRICS_DOMAIN, METRIC_TYPE, "events", "received"}, "rate")) {

    try {
        connect_tracer(listener_socket_path, proc);
    } catch(...) {
        close_connection(trace_conn);
        trace_conn = -1;
        logger->warn("Couldn't talk to ftrace-server, failing work.");
        fail_work();
    }

    thread_map.add_watch(THREAD_WATCHER_NAME, [&](const std::string& watch_id, const ThreadBucket& thd, ThreadOp op) {
            assert(watch_id == THREAD_WATCHER_NAME);
            if (trace_conn < 0) return;

            switch (op) {
            case ThreadOp::created:
                track_pid(thd.tid);
                break;
            case ThreadOp::retired:
                untrack_pid(thd.tid);
                break;
            default:
                throw std::runtime_error("Found unknown thd-op: " + std::to_string(static_cast<std::uint16_t>(op)));
            }
        });

    thd_proc = start_new_thd(jvm, jvmti, "Fk-Prof Ctx-Switch Tracker Thread", ctx_switch_feed_reader, this);
}

CtxSwitchTracer::~CtxSwitchTracer() {
    close(trace_conn);
    thread_map.remove_watch(THREAD_WATCHER_NAME);
}

void CtxSwitchTracer::run() {}

void CtxSwitchTracer::stop() {}

void CtxSwitchTracer::handle_trace_events() {
    std::uint8_t buff[4096];
    std::size_t capacity = sizeof(buff);
    while (! do_stop) {
        auto total_ctx = s_t_recv_total.time_scope();
        ssize_t len;
        {
            auto wait_ctx = s_t_recv_wait.time_scope();
            len = recv(trace_conn, buff, capacity, 0);
        }
        if (len > 0) {
            s_m_data_received.mark(len);
            //handle and mark s_m_events_received
        } else if (len == 0) {
            s_c_peer_disconnected.inc();
            logger->warn("Ftrace-server closed the connection (us reading too slow could be the cause), failing work.");
            fail_work();
            close_connection(trace_conn);
            break;
        } else {
            if (errno == EAGAIN || errno == EWOULDBLOCK) continue;
            logger->warn(error_message("Read (from ftrace-server bound connection) failed", errno));
            s_c_recv_errors.inc();
            //TODO: may be we want to fail work here too? -jj
        }
    }
}

void CtxSwitchTracer::connect_tracer(const char* listener_socket_path, const char* proc) {
    trace_conn = socket(AF_UNIX, SOCK_STREAM | SOCK_NONBLOCK | SOCK_CLOEXEC, 0);
    if (trace_conn < 0) throw log_and_get_error("Couldn't create client-socket for tracing", errno);
    
    sockaddr_un addr;
    std::memset(&addr, 0, sizeof(addr));
    addr.sun_family = AF_UNIX;
    std::string client_path = std::string("/tmp/fkp-trace.") + proc + ".client.sock";
    std::strncpy(addr.sun_path, client_path.c_str(), sizeof(addr.sun_path));

    if (Util::file_exists(client_path.c_str())) {
        logger->info("Client socket file {} exists, will try to unlink", client_path);
        if (unlink(client_path.c_str()) != 0) {
            throw log_and_get_error("Couldn't unlink tracing client-socket", errno);
        }
    }
    
    auto ret = bind(trace_conn, reinterpret_cast<sockaddr*>(&addr), sizeof(addr));
    if (ret != 0) throw log_and_get_error("Couldn't bind tracing client-socket", errno);

    std::strncpy(addr.sun_path, listener_socket_path, sizeof(addr.sun_path));
    ret = connect(trace_conn, reinterpret_cast<sockaddr*>(&addr), sizeof(addr));
    if (ret != 0) throw log_and_get_error("Couldn't connect", errno);

    timeval tval {.tv_sec = 1, .tv_usec = 0};
    
    ret = setsockopt(trace_conn, SOL_SOCKET, SO_RCVTIMEO, &tval, sizeof(tval));
    if (ret != 0) throw log_and_get_error("Couldn't set recv-timeout for client socket", errno);
    
    ret = setsockopt(trace_conn, SOL_SOCKET, SO_SNDTIMEO, &tval, sizeof(tval));
    if (ret != 0) throw log_and_get_error("Couldn't set send-timeout for client socket", errno);
}
