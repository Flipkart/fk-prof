#ifndef FTRACE_SERVER_H
#define FTRACE_SERVER_H

#include <cstdint>
#include <string>
#include <atomic>
#include <memory>
#include "tracer.hh"
#include "metrics.hh"
#include <cmath>
#include <unordered_map>
#include <sys/epoll.h>
#include <sys/uio.h>

namespace ftrace {
    struct ClientSession;

    class Server : public Tracer::Listener {
    public:
        explicit Server(const std::string& tracing_dir, const std::string& socket_path);

        virtual ~Server();

        void run();

        void stop();

        void unicast(pid_t dest_pid, void* ctx, v_curr::PktType pkt_type, const std::uint8_t* payload, ftrace::v_curr::PayloadLen payload_len);

        void multicast(v_curr::PktType pkt_type, const std::uint8_t* payload, ftrace::v_curr::PayloadLen payload_len);

        typedef int ClientFd;

        typedef int TraceFd;

        typedef std::uint8_t CSessId;

    private:
        void shutdown();

        void setup_listener();

        void io_evt(epoll_event& evt);

        void accept_client_sessions();

        void handle_client_requests(ClientFd fd);

        void drop_client_session(std::unordered_map<ClientFd, std::unique_ptr<ClientSession>>::iterator it);

        void drop_client_session(ClientFd fd);

        void fail_client(ClientFd fd);

        void write(ClientFd fd, iovec* iov, size_t iov_len, size_t len);

        void handle_pkt(ClientFd fd, v_curr::PktType type, std::uint8_t* buff, size_t len);

        //RPC actions
        void do_add_tid(ClientFd fd, v_curr::payload::AddTid* add_tid);

        void do_del_tid(ClientFd fd, v_curr::payload::DelTid* add_tid);

        const std::string socket_path;

        std::atomic<bool> keep_running;

        std::unique_ptr<Tracer> tracer;

        std::unique_ptr<uint8_t[]> io_buff;

        metrics::Timer& s_t_processing_time;

        metrics::Timer& s_t_wait_time;

        metrics::Mtr& s_m_poll_events;

        metrics::Ctr& s_c_failed_clients;

        metrics::Ctr& s_c_slow_read_failures;

        int poll_fd;

        int listener_fd;

        std::unordered_map<ClientFd, std::unique_ptr<ClientSession>> client_sessions;

        std::unordered_map<pid_t, ClientFd> pids_client;

        std::unordered_map<TraceFd, const Tracer::DataLink*> trace_links;

    };
}

#endif
