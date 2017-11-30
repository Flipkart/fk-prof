#ifndef CTX_SWITCH_TRACER_H
#define CTX_SWITCH_TRACER_H

#include "globals.hh"
#include "processor.hh"
#include "thread_map.hh"
#include "profile_writer.hh"
#include "ftrace/proto.hh"

class CtxSwitchTracer : public Process {
public:
    explicit CtxSwitchTracer(JavaVM *_jvm, jvmtiEnv *_jvmti, ThreadMap& _thread_map, ProfileSerializingWriter& _serializer, std::uint32_t _max_stack_depth, ProbPct& _prob_pct, std::uint8_t _noctx_cov_pct, std::uint64_t _latency_threshold_ns, bool _track_wakeup_lag, bool _use_global_clock, bool _track_syscall, const char* listener_socket_path, const char* proc, std::function<void()>& _fail_work);

    ~CtxSwitchTracer();

    void run();

    void stop();

    void evt_mthd_return(JNIEnv* env);

    void handle_trace_events();

private:
    void connect_tracer(const char* listener_socket_path, const char* proc);

    void track_pid(pid_t tid);

    void untrack_pid(pid_t tid);

    void send_msg(iovec *iov, std::size_t iov_len, std::size_t expected_len);

    void handle_trace_events(std::uint8_t* buff,  ssize_t len);

    ThreadMap& thread_map;

    std::atomic<bool> do_stop;

    std::function<void()>& fail_work;

    int trace_conn;

    metrics::Ctr& s_c_peer_disconnected;

    metrics::Ctr& s_c_recv_errors;

    metrics::Ctr& s_c_send_errors;

    metrics::Timer& s_t_recv_wait;

    metrics::Timer& s_t_recv_total;

    metrics::Mtr& s_m_data_received;

    metrics::Mtr& s_m_events_received;

    ThdProcP thd_proc;

    struct {
        std::uint8_t bytes[ftrace::v_curr::max_pkt_sz];

        std::uint8_t len;

    } part_msg; //partial message

    DISALLOW_COPY_AND_ASSIGN(CtxSwitchTracer);
};

#endif
