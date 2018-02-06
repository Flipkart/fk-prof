#ifndef IO_TRACER_HH
#define IO_TRACER_HH

#include "processor.hh"
#include "profile_writer.hh"

class IOTracerJavaState {
public:

    static const std::int64_t default_latency_threshold;

    void onVMInit(jvmtiEnv *jvmti, JNIEnv *jni_env);

    void onVMDeath(jvmtiEnv *jvmti, JNIEnv *jni_env);

    void setLatencyThreshold(JNIEnv* jni_env, std::int64_t threshold);
    
    void setBciAgentLoaded() {
        bci_agent_loaded = true;
    }
    
    void failBciFor(const char* class_name);

    bool getBciFailedCount() {
        return bci_failed.size();
    }
    
    bool isBciAgentLoaded() {
        return bci_agent_loaded;
    }
    
private:

    bool initialised = false;

    jclass io_trace_class;

    jmethodID latency_threshold_setter;
    
    bool bci_agent_loaded;
    
    std::vector<std::string> bci_failed;
    
    // for modifications to bci_failed vector
    std::mutex mutex;
};

class IOTracer : public Process {
public:

    IOTracer(JavaVM* _jvm, jvmtiEnv* _jvmti_env, ThreadMap& _thread_map, FdMap& _fd_map, iotrace::Queue::Listener& _serializer, std::int64_t _latency_threshold, std::uint32_t _max_stack_depth);

    bool start(JNIEnv* env);

    void run() override;

    void stop() override;
    
    void recordSocketRead(JNIEnv* jni_env, fd_t fd, std::uint64_t ts, std::uint64_t latency_ns, int count, bool timeout);
    
    void recordSocketWrite(JNIEnv* jni_env, fd_t fd,std::uint64_t ts, std::uint64_t latency_ns, int count);
    
    void recordFileRead(JNIEnv* jni_env, fd_t fd, std::uint64_t ts, std::uint64_t latency_ns, int count);
    
    void recordFileWrite(JNIEnv* jni_env, fd_t fd, std::uint64_t ts, std::uint64_t latency_ns, int count);
    
    ~IOTracer();

private:
    
    void record(JNIEnv* jni_env, blocking::BlockingEvt& evt);
    
    JavaVM* jvm;
    
    jvmtiEnv* jvmti_env;

    ThreadMap& thread_map;

    FdMap& fd_map;
    
    std::int64_t latency_threshold_ns;
    
    std::uint32_t max_stack_depth;
    
    iotrace::Queue evt_queue;

    bool running;

    DISALLOW_COPY_AND_ASSIGN(IOTracer);
};

IOTracerJavaState& getIOTracerJavaState();

#endif /* IO_TRACER_HH */
