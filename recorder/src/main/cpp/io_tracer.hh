#ifndef IO_TRACER_HH
#define IO_TRACER_HH

#include "processor.hh"
#include "profile_writer.hh"

class IOTracerConfig {
public:

    static const std::int64_t default_latency_threshold;
    
    bool isInitialised() {
        return initialised;
    }

    void onVMinit(jvmtiEnv *jvmti, JNIEnv *jniEnv);

    void onVMDeath(jvmtiEnv *jvmti, JNIEnv *jniEnv);

    void set_latency_threshold(JNIEnv* jniEnv, std::int64_t threshold);

private:

    bool initialised = false;

    jclass io_trace_class;

    jmethodID latency_threshold_setter;
};

class IOTracer : public Process {
public:

    IOTracer(JavaVM* _jvm, jvmtiEnv* _jvmtiEnv, ThreadMap& _thread_map, FdMap& _fd_map, iotrace::Queue::Listener& _serializer, std::int64_t _latency_threshold, std::uint32_t _max_stack_depth);

    bool start();

    void run() override;

    void stop() override;
    
    void record_socket_read(JNIEnv* jniEnv, fd_type_t fd, std::uint64_t ts, std::uint64_t latency_ns, int count, bool timeout);
    
    void record_socket_write(JNIEnv* jniEnv, fd_type_t fd,std::uint64_t ts, std::uint64_t latency_ns, int count);
    
    void record_file_read(JNIEnv* jniEnv, fd_type_t fd, std::uint64_t ts, std::uint64_t latency_ns, int count);
    
    void record_file_write(JNIEnv* jniEnv, fd_type_t fd, std::uint64_t ts, std::uint64_t latency_ns, int count);
    
    ~IOTracer();

private:
    
    void record(JNIEnv* jniEnv, blocking::BlockingEvt& evt);
    
    JavaVM* jvm;
    
    jvmtiEnv* jvmtiEnv;

    ThreadMap& thread_map;

    FdMap& fd_map;
    
    std::int64_t latency_threshold;
    
    std::uint32_t max_stack_depth;
    
    iotrace::Queue evt_queue;

    bool running;

    DISALLOW_COPY_AND_ASSIGN(IOTracer);
};

IOTracerConfig& get_io_tracer_config();

#endif /* IO_TRACER_HH */
