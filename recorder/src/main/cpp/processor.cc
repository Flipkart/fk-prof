#include <thread>
#include <iostream>
#include "processor.hh"

#ifdef WINDOWS
#include <windows.h>
#else

#include <unistd.h>

#endif

const uint MICROS_IN_MILLI = 1000;

void sleep_for_millis(uint period) {
#ifdef WINDOWS
    Sleep(period);
#else
    usleep(period * MICROS_IN_MILLI);
#endif
}

Processor::Processor(jvmtiEnv* _jvmti, Processes&& _processes, std::uint32_t _interval)
    : jvmti(_jvmti), running(false), processes(_processes), interval(_interval) {}

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

        sleep_for_millis(interval);
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
