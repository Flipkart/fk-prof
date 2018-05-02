#ifndef TI_THD_H
#define TI_THD_H

#include <memory>
#include <mutex>
#include <condition_variable>
#include <atomic>

#include <jvmti.h>

#include "globals.hh"
#include "common.hh"

enum class State { pre_start, started, stopped };

void quiesce_sigprof(const char *thd_name);

template <typename Arg_t>
using jvmtiStartFunction_t = void(JNICALL *)(jvmtiEnv *, JNIEnv *, Arg_t);

static_assert(std::is_same<jvmtiStartFunction, jvmtiStartFunction_t<void *>>::value,
              "jvmtiStartFunction does not have the expected type");

/**
 * @brief   Wrapper for resources for the process that needs to run in a separate thread.
 *          Templatized by the arguments to the run_function.
 *          For multiple arguments use std::tuple.
 *
 *          note: This class can use variadic template to support multiple args without the need for
 *          std::tuple.
 */
template <typename Arg_t>
struct ThreadTargetProc {
    Arg_t arg;
    jvmtiStartFunction_t<Arg_t> run_fn;
    std::string name;
    State state;
    std::mutex m;
    std::condition_variable v;

    metrics::Ctr &s_c_thds;

    ThreadTargetProc(Arg_t _arg, jvmtiStartFunction_t<Arg_t> _run_fn, const char *_name)
        : arg(_arg), run_fn(_run_fn), name(_name), state(State::pre_start),

          s_c_thds(get_metrics_registry().new_counter({METRICS_DOMAIN, "threads", "running"})) {

        logger->trace("ThreadTargetProc for '{}' created", name);
    }

    ~ThreadTargetProc() {
        await_stop();
        assert(state == State::stopped);
        logger->trace("ThreadTargetProc for '{}' destroyed", name);
    }

    void await_stop() {
        std::unique_lock<std::mutex> l(m);
        if (state != State::stopped) {
            logger->trace("Will now wait for thread '{}' to be stopped, state as of now: {}", name,
                          static_cast<std::uint32_t>(state));
            v.wait(l, [&] { return state == State::stopped; });
        }
    }

    void mark_stopped() {
        std::lock_guard<std::mutex> g(m);
        state = State::stopped;
        v.notify_all();
        logger->trace("Thread '{}' stopped", name);
        s_c_thds.dec();
    }

    void mark_started() {
        std::lock_guard<std::mutex> g(m);
        assert(state == State::pre_start);
        state = State::started;
        logger->trace("Thread '{}' started", name);
        s_c_thds.inc();
    }
};

template <typename Arg_t>
using ThdProcP = std::shared_ptr<ThreadTargetProc<Arg_t>>;

template <typename Arg_t>
struct StartStopMarker {
    ThreadTargetProc<Arg_t> &ttp;

    StartStopMarker(ThreadTargetProc<Arg_t> &_ttp) : ttp(_ttp) {
        ttp.mark_started();
    }

    ~StartStopMarker() {
        ttp.mark_stopped();
    }
};

template <typename Arg_t>
static void thread_target_proc_wrapper(jvmtiEnv *jvmti_env, JNIEnv *jni_env, void *arg) {
    auto ttp = static_cast<ThreadTargetProc<Arg_t> *>(arg);
    quiesce_sigprof(ttp->name.c_str());
    StartStopMarker<Arg_t> ssm(*ttp);
    ttp->run_fn(jvmti_env, jni_env, ttp->arg);
}

template <typename Arg_t>
ThdProcP<Arg_t> start_new_thd(JNIEnv *env, jvmtiEnv *jvmti, const char *thd_name,
                              jvmtiStartFunction_t<Arg_t> run_fn, Arg_t &&arg) {
    jvmtiError result;

    if (env == NULL) {
        logger->error("Failed to obtain JNIEnv");
        return ThdProcP<Arg_t>(nullptr);
    }

    jthread thread = newThread(env, thd_name);
    auto ttp = std::make_shared<ThreadTargetProc<Arg_t>>(arg, run_fn, thd_name);
    result = jvmti->RunAgentThread(thread, thread_target_proc_wrapper<Arg_t>, ttp.get(),
                                   JVMTI_THREAD_NORM_PRIORITY);

    if (result == JVMTI_ERROR_NONE) {
        logger->info("Started thread named '{}'", thd_name);
    } else {
        logger->error("Failed to start thread named '{}' failed with: {}", thd_name, result);
        ttp->mark_stopped();
    }

    return ttp;
}

template <typename Arg_t>
bool is_started(ThdProcP<Arg_t> &thd) {
    return thd && (thd->state != State::stopped);
}

template <typename Arg_t>
ThdProcP<Arg_t> start_new_thd(JavaVM *jvm, jvmtiEnv *jvmti, const char *thd_name,
                              jvmtiStartFunction_t<Arg_t> run_fn, Arg_t &&arg) {
    JNIEnv *env = getJNIEnv(jvm);
    return start_new_thd<Arg_t>(env, jvmti, thd_name, run_fn, std::forward<Arg_t>(arg));
}

template <typename Arg_t>
void await_thd_death(ThdProcP<Arg_t> ttp) {
    if (ttp != nullptr) {
        auto name = ttp->name.c_str();
        logger->info("Awaiting death of thread named '{}'", name);
        ttp->await_stop();
        logger->info("Thread named '{}' reaped", name);
    } else {
        // possible during testing
        logger->warn("stop called on null ThdProc");
    }
}

extern template struct ThreadTargetProc<void *>;

#endif
