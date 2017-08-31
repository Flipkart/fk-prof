#include <thread>
#include <iostream>
#include "processor.hh"

#ifdef WINDOWS
#include <windows.h>
#else

#include <unistd.h>

#endif

const uint MILLIS_IN_MICRO = 1000;

void sleep_for_millis(uint period) {
#ifdef WINDOWS
    Sleep(period);
#else
    usleep(period * MILLIS_IN_MICRO);
#endif
}

Processor::Processor(jvmtiEnv* _jvmti, Processes&& _processes)
    : jvmti(_jvmti), running(false), processes(_processes),

      s_t_yield_tm(get_metrics_registry().new_timer({METRICS_DOMAIN, "processor", "sched_yield", "time"})) {}

Processor::~Processor() {
    for (auto& p : processes) {
        delete p;
    }
}

void Processor::run() {
    while (true) {
        for (auto& p : processes) {
            p->run();
        }

        if (!running.load(std::memory_order_relaxed)) {
            break;
        }

        {
            auto _ = s_t_yield_tm.time_scope();
            sched_yield();
        }
    }

    for (auto& p : processes) {
        p->stop();
    }
}

void callback_to_run_processor(jvmtiEnv *jvmti_env, JNIEnv *jni_env, void *arg) {
    Processor* processor = static_cast<Processor*>(arg);
    processor->run();
}

void Processor::start(JNIEnv *jniEnv) {
    running.store(true, std::memory_order_relaxed);
    thd_proc = start_new_thd(jniEnv, jvmti, "Fk-Prof Processing Thread", callback_to_run_processor, this);
}

void Processor::stop() {
    running.store(false, std::memory_order_relaxed);
    await_thd_death(thd_proc);
    thd_proc.reset();
}

bool Processor::is_running() const {
    return running.load(std::memory_order_relaxed);
}
