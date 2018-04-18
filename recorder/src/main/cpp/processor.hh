#ifndef PROCESSOR_H
#define PROCESSOR_H

#include <jvmti.h>
#include "common.hh"
#include "signal_handler.hh"
#include "circular_queue.hh"
#include "ti_thd.hh"

/**
 * @brief Abstract class to send a notification
 */
class Notifiable {
public:
    virtual void notify() = 0;
    virtual ~Notifiable() {
    }
};

class Process {
public:
    static constexpr Time::msec run_itvl_ignore = Time::msec::max();

    Process() {
    }

    virtual ~Process() {
    }

    /* Do some work for this process and then return. */
    virtual void run() = 0;

    virtual void stop() = 0;

    /**
     * @brief  A processing thread can use the returned value as a hint for the
     *         rate at which it needs to call the run method of this process.
     * @return run interval in millis.
     */
    virtual Time::msec run_itvl() {
        return run_itvl_ignore;
    };
};

typedef std::shared_ptr<Process> ProcessPtr;

typedef std::vector<std::shared_ptr<Process>> Processes;

class Processor : public Notifiable {
public:
    explicit Processor(jvmtiEnv *_jvmti);

    virtual ~Processor();

    void start(JNIEnv *jniEnv, Processes _processes);

    void run();

    void stop();

    bool is_running() const;

    void notify() override;

private:
    jvmtiEnv *jvmti;

    std::atomic_bool running;

    Processes processes;

    ThdProcP<Processor *> thd_proc;

    /* Required for notifying the processor thd */
    std::condition_variable cv;

    std::mutex mutex;

    std::mutex processes_mutex;

    /* Flag for condition variable.
     * True means that some process thd wants the processing thd to call run().
     */
    std::atomic_bool processing_pending;
    
    void run_processes();

    DISALLOW_COPY_AND_ASSIGN(Processor);
};

#endif // PROCESSOR_H
