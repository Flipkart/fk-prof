#ifndef HONEST_PROFILER_CONTROLLER_H
#define HONEST_PROFILER_CONTROLLER_H

#include "globals.hh"
#include "common.hh"
#include "profiler.hh"
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <sys/types.h>
#include <sys/socket.h>
#include <netinet/in.h>
#include <arpa/inet.h>
#include <netdb.h>
#include <jvmti.h>
#include <atomic>
#include "profile_writer.hh"
#include "config.hh"
#include <functional>
#include <queue>
#include <memory>
#include "scheduler.hh"
#include "blocking_ring_buffer.hh"
#include "ti_thd.hh"

#define MAX_DATA_SIZE 100

class Controller {
public:
    explicit Controller(JavaVM *_jvm, jvmtiEnv *_jvmti, ThreadMap& _thread_map, ConfigurationOptions& _cfg);

    virtual ~Controller() {}

    void start();

    void stop();

    bool is_running() const;

    friend void controllerRunnable(jvmtiEnv *jvmti_env, JNIEnv *jni_env, void *arg);

private:
    JavaVM *jvm;
    jvmtiEnv *jvmti;
    ThreadMap& thread_map;
    ConfigurationOptions& cfg;
    std::atomic_bool keep_running;
    ThdProcP thd_proc;
    Buff buff;
    std::shared_ptr<ProfileWriter> writer;
    std::shared_ptr<ProfileSerializingWriter> serializer;
    std::shared_ptr<Processor> processor;
    std::function<void()> cancel_work;
    BlockingRingBuffer raw_writer_ring;

    Scheduler scheduler;
    
    std::recursive_mutex current_work_mtx;
    typedef recording::WorkAssignment W;
    typedef recording::WorkResponse::WorkState WSt;
    typedef recording::WorkResponse::WorkResult WRes;
    W current_work;
    WSt current_work_state;
    WRes current_work_result;
    Time::Pt work_start, work_end;

    SerializationFlushThresholds sft;
    TruncationThresholds tts;

    std::string vm_id;

    //[metrics......
    metrics::Timer& s_t_poll_rpc;
    metrics::Ctr& s_c_poll_rpc_failures;
    
    metrics::Timer& s_t_associate_rpc;
    metrics::Ctr& s_c_associate_rpc_failures;

    metrics::Value& s_v_work_cpu_sampling;

    metrics::Ctr& s_c_work_success;
    metrics::Ctr& s_c_work_failure;
    metrics::Ctr& s_c_work_retired;
    //......metrics]

    void run();
    
    void accept_work(Buff& poll_response_buff, const std::string& host, const std::uint32_t port);

    void with_current_work(std::function<void(W&, WSt&, WRes&, Time::Pt&, Time::Pt&)> proc);

    void issue_work(const std::string& host, const std::uint32_t port, std::uint32_t controller_id, std::uint32_t controller_version);
    void retire_work(const std::uint64_t work_id);

    bool capable(const W& w);

    bool capable(const recording::Work& w);
    void prep(const recording::Work& w);
    void issue(const recording::Work& w, Processes& processes, JNIEnv* env);
    void retire(const recording::Work& w);

    bool capable(const recording::CpuSampleWork& csw);
    void prep(const recording::CpuSampleWork& csw);
    void issue(const recording::CpuSampleWork& csw, Processes& processes, JNIEnv* env);
    void retire(const recording::CpuSampleWork& csw);
};

#endif
