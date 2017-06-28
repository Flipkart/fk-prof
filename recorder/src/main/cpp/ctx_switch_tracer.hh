#ifndef CTX_SWITCH_TRACER_H
#define CTX_SWITCH_TRACER_H

#include "globals.hh"
#include "processor.hh"
#include "thread_map.hh"
#include "profile_writer.hh"

class CtxSwitchTracer : public Process {
public:
    explicit CtxSwitchTracer(ThreadMap& _thread_map, ProfileSerializingWriter& _serializer, std::uint32_t _max_stack_depth, ProbPct& _prob_pct, std::uint8_t _noctx_cov_pct, std::uint64_t _latency_threshold_ns, bool _track_wakeup_lag, bool _use_global_clock, bool _track_syscall, const char* listener_socket_path, const char* proc);

    ~CtxSwitchTracer();

    void start(JNIEnv* env);

    void run();

    void stop();

    void evt_mthd_return(JNIEnv* env);

private:
    void connect_tracer(const char* listener_socket_path, const char* proc);

    int trace_conn;

    DISALLOW_COPY_AND_ASSIGN(CtxSwitchTracer);
};

#endif
