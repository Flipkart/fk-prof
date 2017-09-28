#ifndef PROCESSOR_H
#define PROCESSOR_H

#include <jvmti.h>
#include "common.hh"
#include "signal_handler.hh"
#include "circular_queue.hh"
#include "ti_thd.hh"

class Process {
public:
    Process() {}
    virtual ~Process() {}
    virtual void run() = 0;
    virtual void stop() = 0;
};

typedef std::vector<Process*> Processes;

class Processor {

public:
    explicit Processor(jvmtiEnv* _jvmti, Processes&& _tasks);

    ~Processor();

    void start(JNIEnv *jniEnv);

    void run();

    void stop();

    bool is_running() const;

private:
    jvmtiEnv* jvmti;

    std::atomic_bool running;

    Processes processes;

    ThdProcP thd_proc;

    metrics::Timer& s_t_yield_tm;

    void startCallback(jvmtiEnv *jvmti_env, JNIEnv *jni_env, void *arg);

    DISALLOW_COPY_AND_ASSIGN(Processor);
};

#endif // PROCESSOR_H
