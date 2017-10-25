#ifndef FTRACE_TRACER_H
#define FTRACE_TRACER_H

#include <cstdint>
#include <string>
#include <atomic>
#include <list>
#include <unordered_map>
#include <functional>
#include "ftrace/proto.hh"
#include "ftrace/events.hh"
#include "metrics.hh"

namespace ftrace {
    class Tracer {
    public:
        class Listener {
        public:
            virtual ~Listener() {}
            
            virtual void unicast(pid_t dest_pid, void* ctx, ftrace::v_curr::PktType pkt_type, const std::uint8_t* payload, ftrace::v_curr::PayloadLen payload_len) = 0;

            virtual void multicast(ftrace::v_curr::PktType pkt_type, const std::uint8_t* payload, ftrace::v_curr::PayloadLen payload_len) = 0;
            
        };

        typedef std::unordered_map<pid_t, void*> Tracees;

        class SwitchTrackingEventHandler : public EventHandler {
        public:
            SwitchTrackingEventHandler(Listener& _listener, const Tracees& tracees);

            virtual ~SwitchTrackingEventHandler();

            void handle(std::int32_t cpu, std::uint64_t timestamp_ns, const event::CommonFields& cf, const event::SyscallEntry& sys_entry);

            void handle(std::int32_t cpu, std::uint64_t timestamp_ns, const event::CommonFields& cf, const event::SyscallExit& sys_exit);

            void handle(std::int32_t cpu, std::uint64_t timestamp_ns, const event::CommonFields& cf, const event::SchedSwitch& sched_switch);

            void handle(std::int32_t cpu, std::uint64_t timestamp_ns, const event::CommonFields& cf, const event::SchedWakeup& sched_wakeup);

            void untrack_tid(pid_t tid);

        private:
            Listener& listener;

            const Tracees& tracees;

            std::unordered_map<pid_t, std::int64_t> current_syscall;
        };

        struct DataLink {
            int pipe_fd;
            int stats_fd;
            std::int32_t cpu;
        };
        
        explicit Tracer(const std::string& tracing_dir, Listener& _listener, std::function<void(const DataLink&)> data_link_listener);

        ~Tracer();

        void trace_on(pid_t pid, void* ctx);
        
        void trace_off(pid_t pid);

        void process(const DataLink& link);

    private:
        void start();

        void stop();

        Tracees tracees;

        SwitchTrackingEventHandler evt_hdlr;

        std::size_t pg_sz;

        std::unique_ptr<std::uint8_t> pg_buff;

        metrics::Ctr& s_c_read_failed;

        metrics::Mtr& s_m_read_bytes;

        std::string instance_path;

        struct {
            int tracing_on;
            int trace_options;
            int sched_switch_enable;
            int sched_wakeup_enable;
            int syscall_enter_enable;
            int syscall_exit_enable;
            int set_event_pid; // newer kernels support this (but 3.16 on jessie doesn't), after 1500 or more years, when it finally gets a new enough kernel, enhance this to write pids to be traced here -jj
        } ctrl_fds;

        std::list<DataLink> dls;

        std::unique_ptr<EventReader> evt_reader;

        std::unique_ptr<PageReader> pg_reader;

    };
}

#endif