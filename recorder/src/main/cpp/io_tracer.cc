#include "io_tracer.hh"
#include "io_trace_jni.hh"
#include "bci_jni.hh"

void IOTracerJavaState::onVMInit(jvmtiEnv* jvmti, JNIEnv* jni_env) {
    if (initialised) return;

    jclass local_ref = jni_env->FindClass("fk/prof/trace/IOTrace");
    JNI_EXCEPTION_CHECK(jni_env, "fk.prof.trace.iotrace class not found. io tracing will be disabled");

    io_trace_class = reinterpret_cast<jclass> (jni_env->NewGlobalRef(local_ref));
    jni_env->DeleteLocalRef(local_ref);

    latency_threshold_setter = jni_env->GetStaticMethodID(io_trace_class, "setLatencyThresholdNanos", "(J)V");
    JNI_EXCEPTION_CHECK(jni_env, "fk.prof.trace.iotrace.setLatencyThresholdNanos() not found");

    initialised = true;
}

void IOTracerJavaState::onVMDeath(jvmtiEnv *jvmti_env, JNIEnv *jni_env) {
    if (!initialised) return;

    jni_env->DeleteGlobalRef(io_trace_class);
}

void IOTracerJavaState::setLatencyThreshold(JNIEnv* jni_env, std::int64_t threshold) {
    if (!initialised) return;

    jni_env->CallStaticVoidMethod(io_trace_class, latency_threshold_setter, (jlong) threshold);
}

void IOTracerJavaState::failBciFor(const char* class_name) {
    std::lock_guard<std::mutex> lock(mutex);
    bci_failed.emplace_back(std::string(class_name));
}

const std::int64_t IOTracerJavaState::default_latency_threshold = std::numeric_limits<std::int64_t>::max();

// global IOtrace java state
IOTracerJavaState io_tracer_js;

IOTracerJavaState& getIOTracerJavaState() {
    return io_tracer_js;
}

IOTracer::IOTracer(JavaVM* _jvm, jvmtiEnv* _jvmti_env, ThreadMap& _thread_map, FdMap& _fd_map, iotrace::Queue::Listener& _serializer, std::int64_t _latency_threshold_ns, std::uint32_t _max_stack_depth)
: jvm(_jvm), jvmti_env(_jvmti_env), thread_map(_thread_map), fd_map(_fd_map), latency_threshold_ns(_latency_threshold_ns), max_stack_depth(_max_stack_depth), evt_queue(_serializer, _max_stack_depth) {
}

IOTracer::~IOTracer() {
}

bool IOTracer::start(JNIEnv* jni_env) {
    if (running) {
        logger->warn("IOTracer.start when it is already running");
        return true;
    }
    
    getIOTracerJavaState().setLatencyThreshold(jni_env, latency_threshold_ns);
    running = true;
    return true;
}

void IOTracer::stop() {
    JNIEnv* jni_env = getJNIEnv(jvm);
    getIOTracerJavaState().setLatencyThreshold(jni_env, IOTracerJavaState::default_latency_threshold);
    running = false;
}

void IOTracer::run() {
    while (evt_queue.pop());
}

void IOTracer::recordFileRead(JNIEnv* jni_env, fd_type_t fd, std::uint64_t ts, std::uint64_t latency_ns, int count) {
    FdBucket* fd_info = fd_map.get(fd);

    blocking::FdReadEvt read_evt = {.fd = fd_info, count = count, .timeout = false};
    blocking::BlockingEvt evt = {
        .ts = ts,
        .latency_ns = latency_ns,
        .type = blocking::EvtType::file_read,
        .evt = { .fd_read_evt = read_evt }
    };

    record(jni_env, evt);
}

void IOTracer::recordFileWrite(JNIEnv* jni_env, fd_type_t fd, std::uint64_t ts, std::uint64_t latency_ns, int count) {
    FdBucket* fd_info = fd_map.get(fd);

    blocking::FdWriteEvt write_evt = {.fd = fd_info, count = count};
    blocking::BlockingEvt evt = {
        .ts = ts,
        .latency_ns = latency_ns,
        .type = blocking::EvtType::file_write,
        .evt = { .fd_write_evt = write_evt }
    };

    record(jni_env, evt);
}

void IOTracer::recordSocketRead(JNIEnv* jni_env, fd_type_t fd, std::uint64_t ts, std::uint64_t latency_ns, int count, bool timeout) {
    FdBucket* fd_info = fd_map.get(fd);

    blocking::FdReadEvt read_evt = {.fd = fd_info, count = count, .timeout = timeout};
    blocking::BlockingEvt evt = {
        .ts = ts,
        .latency_ns = latency_ns,
        .type = blocking::EvtType::socket_read,
        .evt = { .fd_read_evt = read_evt }
    };

    record(jni_env, evt);
}

void IOTracer::recordSocketWrite(JNIEnv* jni_env, fd_type_t fd, std::uint64_t ts, std::uint64_t latency_ns, int count) {
    FdBucket* fd_info = fd_map.get(fd);

    blocking::FdWriteEvt write_evt = {.fd = fd_info, count = count};
    blocking::BlockingEvt evt = {
        .ts = ts,
        .latency_ns = latency_ns,
        .type = blocking::EvtType::socket_write,
        .evt = { .fd_write_evt = write_evt }
    };

    record(jni_env, evt);
}

void IOTracer::record(JNIEnv* jni_env, blocking::BlockingEvt& evt) {
    jvmtiFrameInfo frames[max_stack_depth];
    jint frame_count;

    ThreadBucket* thd_info = thread_map.get(jni_env);
    bool default_context = !thd_info->data.ctx_tracker.in_ctx();

    auto err = jvmti_env->GetStackTrace(nullptr, 0, max_stack_depth, frames, &frame_count);
    if (err != JVMTI_ERROR_NONE) {
        logger->debug("error while getting stacktrace: {}", err);

        evt_queue.push(iotrace::InMsg(evt, thd_info, default_context));
        return;
    }

    evt_queue.push(iotrace::InMsg(evt, thd_info, frames, frame_count, default_context));
}

/* JNI implementation */

JNIEXPORT void JNICALL Java_fk_prof_trace_IOTrace_00024File_open(JNIEnv* jni_env, jclass , jint fd, jstring path, jlong ts, jlong latency) {
    const char* path_str = jni_env->GetStringUTFChars(path, nullptr);
    getFdMap().putFileInfo(fd, path_str);
    jni_env->ReleaseStringUTFChars(path, path_str);
}

JNIEXPORT void JNICALL Java_fk_prof_trace_IOTrace_00024Socket_accept(JNIEnv* jni_env, jclass, jint fd, jstring remote_path, jlong ts, jlong latency) {
    const char* path_str = jni_env->GetStringUTFChars(remote_path, nullptr);
    getFdMap().putSocketInfo(fd, path_str, false);
    jni_env->ReleaseStringUTFChars(remote_path, path_str);
}

JNIEXPORT void JNICALL Java_fk_prof_trace_IOTrace_00024Socket_connect(JNIEnv* jni_env, jclass, jint fd, jstring remote_path, jlong ts, jlong latency) {
    const char* path_str = jni_env->GetStringUTFChars(remote_path, nullptr);
    getFdMap().putSocketInfo(fd, path_str, true);
    jni_env->ReleaseStringUTFChars(remote_path, path_str);
}

JNIEXPORT void JNICALL Java_fk_prof_trace_IOTrace_00024File_read(JNIEnv* jni_env, jclass, jint fd, jint count, jlong ts, jlong latency) {
    // get the process object
    IOTracer* tracer = nullptr;
    if(tracer != nullptr)
        tracer->recordFileRead(jni_env, fd, ts, latency, count);
}

JNIEXPORT void JNICALL Java_fk_prof_trace_IOTrace_00024File_write(JNIEnv* jni_env, jclass, jint fd, jint count, jlong ts, jlong latency) {
    // get the process object
    IOTracer* tracer = nullptr;
    if(tracer != nullptr)
        tracer->recordFileWrite(jni_env, fd, ts, latency, count);
}

JNIEXPORT void JNICALL Java_fk_prof_trace_IOTrace_00024Socket_read(JNIEnv* jni_env, jclass, jint fd, jint count, jlong ts, jlong latency, jboolean timeout) {
    // get the process object
    IOTracer* tracer = nullptr;
    if(tracer != nullptr)
        tracer->recordSocketRead(jni_env, fd, ts, latency, count, timeout);
}

JNIEXPORT void JNICALL Java_fk_prof_trace_IOTrace_00024Socket_write(JNIEnv* jni_env, jclass, jint fd, jint count, jlong ts, jlong latency) {
    // get the process object
    IOTracer* tracer = nullptr;
    if(tracer != nullptr)
        tracer->recordSocketWrite(jni_env, fd, ts, latency, count);
}

JNIEXPORT void JNICALL Java_fk_prof_bciagent_ProfileMethodTransformer_bciStarted(JNIEnv* jni_env, jclass ) {
    getIOTracerJavaState().setBciStarted();
}

JNIEXPORT void JNICALL Java_fk_prof_bciagent_ProfileMethodTransformer_bciFailed(JNIEnv* jni_env, jclass , jstring class_name) {
    const char* class_name_str = jni_env->GetStringUTFChars(class_name, nullptr);
    getIOTracerJavaState().failBciFor(class_name_str);
    jni_env->ReleaseStringUTFChars(class_name, class_name_str);
}