#ifndef PROFILER_H
#define PROFILER_H

#include <signal.h>
#include <unistd.h>
#include <chrono>
#include <sstream>
#include <string>

#include "globals.hh"
#include "profile_writer.hh"
#include "thread_map.hh"
#include "signal_handler.hh"
#include "stacktraces.hh"
#include "processor.hh"
#include "perf_ctx.hh"


using namespace std::chrono;
using std::ofstream;
using std::ostringstream;
using std::string;

class Profiler : public Process {
public:
    static std::uint32_t calculate_max_stack_depth(std::uint32_t hinted_max_stack_depth);

    explicit Profiler(JavaVM *_jvm, jvmtiEnv *_jvmti, ThreadMap &_thread_map, ProfileSerializingWriter& _serializer, std::uint32_t _max_stack_depth, std::uint32_t _sampling_freq, ProbPct& _prob_pct, std::uint8_t _noctx_cov_pct, bool _capture_native_bt, bool _capture_unknown_thd_bt);

    bool start(JNIEnv *jniEnv);

    void stop() override;

    void run() override;

    void handle(int signum, siginfo_t *info, void *context);
    
    Time::msec run_itvl() override;

    ~Profiler();

private:
    JavaVM *jvm;

    jvmtiEnv *jvmti;

    ThreadMap &thread_map;

    std::uint32_t max_stack_depth;

    std::uint32_t itvl_min, itvl_max;

    std::shared_ptr<ProfileWriter> writer;

    cpu::Queue *buffer;

    SignalHandler* handler;

    ProfileSerializingWriter& serializer;

    ProbPct& prob_pct;
    std::atomic<std::uint32_t> sampling_attempts;
    const std::uint8_t noctx_cov_pct;
    const bool capture_native_bt;
    const bool capture_unknown_thd_bt;

    bool running;

    std::uint32_t samples_handled;

    metrics::Ctr& s_c_cpu_samp_total;
    metrics::Ctr& s_c_cpu_samp_err_no_jni;
    metrics::Ctr& s_c_cpu_samp_err_unexpected;
    metrics::Hist& s_h_pop_spree_len;
    metrics::Timer& s_t_pop_spree_tm;

    SiteResolver::SymInfo si;

    void set_sampling_freq(std::uint32_t sampling_freq);

    void configure();

    inline std::uint32_t capture_stack_depth() {
        return max_stack_depth + 1;
    }

    DISALLOW_COPY_AND_ASSIGN(Profiler);
};

#endif // PROFILER_H
