#include <thread>
#include <iostream>
#include "globals.hh"
#include "processor.hh"

#ifdef WINDOWS
#include <windows.h>
#else

#include <unistd.h>
#include <complex>

#endif

const uint MICROS_IN_MILLI = 1000;

void sleep_for_millis(uint period) {
#ifdef WINDOWS
    Sleep(period);
#else
    usleep(period * MICROS_IN_MILLI);
#endif
}

struct Processor_Thd_Args {
    Processor *processor;
    Processes processes;
};

Processor::Processor(jvmtiEnv *_jvmti) : jvmti(_jvmti), running(false), processing_pending(false) {
}

constexpr Time::msec Process::run_itvl_ignore;

Processor::~Processor() {
}

void Processor::notify() {
    if (processing_pending)
        return;

    {
        std::lock_guard<decltype(mutex)> lock(mutex);
        if (processing_pending)
            return;
        processing_pending = true;
    }

    cv.notify_one();
}

void Processor::run(Processes &processes) {
    SPDLOG_TRACE(logger, "processor run started. processes count: {}", processes.size());
    // Find the process with the least positive run_itvl. This will be our wait
    // period for the condition variable.
    auto sleep_itvl = std::accumulate(std::begin(processes), std::end(processes), Time::msec::max(),
                                      [](Time::msec min, ProcessPtr p) {
                                          auto itvl = p->run_itvl();
                                          if (itvl.count() > 0) {
                                              return std::min(min, itvl);
                                          }
                                          return min;
                                      });

    logger->info("chosen run itvl_time: {}", sleep_itvl.count());

    while (true) {
        auto before = Time::now();
        for (auto &p : processes) {
            p->run();
        }
        processing_pending.store(false);
        auto after = Time::now();
        auto run_duration = std::chrono::duration_cast<Time::msec>(after - before);

        SPDLOG_DEBUG(logger, "run_duration: {}ms", run_duration.count());

        if (!running.load(std::memory_order_relaxed)) {
            break;
        }

        {
            std::unique_lock<decltype(mutex)> lock(mutex);
            if (sleep_itvl == Process::run_itvl_ignore) {
                cv.wait(lock, [this]() { return processing_pending.load(); });
            } else {
                auto new_sleep_itvl = sleep_itvl - run_duration;
                if (new_sleep_itvl.count() > 0) {
                    cv.wait_for(lock, new_sleep_itvl,
                                [this]() { return processing_pending.load(); });
                }
            }

            SPDLOG_TRACE(logger, "processing thd woke up");
        }
    }

    SPDLOG_TRACE(logger, "stopping all process");
    for (auto &p : processes) {
        p->stop();
    }
}

void callback_to_run_processor(jvmtiEnv *jvmti_env, JNIEnv *jni_env, Processor_Thd_Args arg) {
    arg.processor->run(arg.processes);
}

void Processor::start(JNIEnv *jniEnv, Processes processes) {
    running.store(true, std::memory_order_relaxed);
    thd_proc = start_new_thd<Processor_Thd_Args>(
        jniEnv, jvmti, "Fk-Prof Processing Thread", callback_to_run_processor,
        Processor_Thd_Args{.processor = this, .processes = processes});
}

void Processor::stop() {
    running.store(false, std::memory_order_relaxed);
    // wake up the processing thd. It might be sleeping.
    notify();
    await_thd_death(thd_proc);
    // release the processes
    thd_proc.reset();
}

bool Processor::is_running() const {
    return running.load(std::memory_order_relaxed);
}
