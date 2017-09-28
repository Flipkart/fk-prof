#include "ti_thd.hh"
#include <mutex>
#include <condition_variable>
#include <atomic>
#include "globals.hh"
#include "common.hh"

void quiesce_sigprof(const char* thd_name) {
    sigset_t mask;
    sigemptyset(&mask);
    sigaddset(&mask, SIGPROF);
    if (pthread_sigmask(SIG_BLOCK, &mask, NULL) < 0) {
        logger->error("Unable to set thread {} signal mask for quiescing sigprof", thd_name);
    }
}

enum class State { pre_start, started, stopped };
template <typename Formatter, typename Char>
void format_arg(Formatter& f, const Char* buff, const State& s) {
    switch (s) {
    case State::pre_start:
        buff = "pre_start";
        break;
    case State::started:
        buff = "started";
        break;
    case State::stopped:
        buff = "stopped";
        break;
    default:
        assert(false);
    }
}

struct ThreadTargetProc {
    void* arg;
    jvmtiStartFunction run_fn;
    std::string name;
    State state;
    std::mutex m;
    std::condition_variable v;

    metrics::Ctr& s_c_thds;

    ThreadTargetProc(void* _arg, jvmtiStartFunction _run_fn, const char* _name) :
        arg(_arg), run_fn(_run_fn), name(_name), state(State::pre_start),

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
            logger->trace("Will now wait for thread '{}' to be stopped, state as of now: {}", name, state);
            v.wait(l, [&] { return state == State::stopped; });
        }
    }

    void mark_stopped() {
        std::lock_guard<std::mutex> g(m);
        assert(state == State::started);
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

typedef std::shared_ptr<ThreadTargetProc> ThdProcP;

struct StartStopMarker {
    ThreadTargetProc& ttp;

    StartStopMarker(ThreadTargetProc& _ttp) : ttp(_ttp) {
        ttp.mark_started();
    }

    ~StartStopMarker() {
        ttp.mark_stopped();
    }
};

static void thread_target_proc_wrapper(jvmtiEnv *jvmti_env, JNIEnv *jni_env, void *arg) {
    auto ttp = static_cast<ThreadTargetProc*>(arg);
    quiesce_sigprof(ttp->name.c_str());
    StartStopMarker ssm(*ttp);
    ttp->run_fn(jvmti_env, jni_env, ttp->arg);
}

ThdProcP start_new_thd(JavaVM *jvm, jvmtiEnv *jvmti, const char* thd_name, jvmtiStartFunction run_fn, void *arg) {
    JNIEnv *env = getJNIEnv(jvm);
    return start_new_thd(env, jvmti, thd_name, run_fn, arg);
}

ThdProcP start_new_thd(JNIEnv *env, jvmtiEnv *jvmti, const char* thd_name, jvmtiStartFunction run_fn, void *arg) {
    jvmtiError result;

    if (env == NULL) {
        logger->error("Failed to obtain JNIEnv");
        return ThdProcP(nullptr);
    }

    jthread thread = newThread(env, thd_name);
    ThdProcP ttp = std::shared_ptr<ThreadTargetProc>(new ThreadTargetProc(arg, run_fn, thd_name));
    result = jvmti->RunAgentThread(thread, thread_target_proc_wrapper, ttp.get(), JVMTI_THREAD_NORM_PRIORITY);

    if (result == JVMTI_ERROR_NONE) {
        logger->info("Started thread named '{}'", thd_name);
    } else {
        logger->error("Failed to start thread named '{}' failed with: {}", thd_name, result);
    }
    return ttp;
}

void await_thd_death(ThdProcP ttp) {
    auto name = ttp->name.c_str();
    logger->info("Awaiting death of thread named '{}'", name);
    ttp->await_stop();
    logger->info("Thread named '{}' reaped", name);
}
