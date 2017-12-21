#include "io_tracer.hh"

void IOTracerConfig::onVMinit(jvmtiEnv *jvmti, JNIEnv *jniEnv) {
    if (initialised) return;

    jclass local_ref = jniEnv->FindClass("fk/prof/trace/IOTrace");
    JNI_EXCEPTION_CHECK("fk.prof.trace.iotrace class not found. io tracing will be disabled");

    io_trace_class = reinterpret_cast<jclass> (jniEnv->NewGlobalRef(local_ref));
    jniEnv->DeleteLocalRef(local_ref);

    latency_threshold_setter = jniEnv->GetStaticMethodID(io_trace_class, "setLatencyThresholdNanos", "(J)V");
    JNI_EXCEPTION_CHECK("fk.prof.trace.iotrace.setLatencyThresholdNanos() not found");

    initialised = true;
}

void IOTracerConfig::onVMDeath(jvmtiEnv *jvmti_env, JNIEnv *jniEnv) {
    if (!initialised) return;

    jniEnv->DeleteGlobalRef(io_trace_class);
}

void IOTracerConfig::set_latency_threshold(JNIEnv* jniEnv, std::int64_t threshold) {
    if (!initialised) return;

    jniEnv->CallStaticVoidMethod(io_trace_class, latency_threshold_setter, (jlong) threshold);
}

const std::int64_t IOTracerConfig::default_latency_threshold = std::numeric_limits<std::int64_t>::max();

// global IOtraceJni
IOTracerConfig io_tracer_config;

IOTracerConfig& get_io_tracer_config() {
    return io_tracer_config;
}

IOTracer::IOTracer(JavaVM* _jvm, jvmtiEnv* _jvmtiEnv, ThreadMap& _thread_map, FdMap& _fd_map, iotrace::Queue::Listener& _serializer, std::int64_t _latency_threshold, std::uint32_t _max_stack_depth)
: jvm(_jvm), jvmtiEnv(_jvmtiEnv), thread_map(_thread_map), fd_map(_fd_map), latency_threshold(_latency_threshold), max_stack_depth(_max_stack_depth), evt_queue(_serializer, _max_stack_depth) {
}

IOTracer::~IOTracer() {
}

bool IOTracer::start() {
    if (running) {
        logger->warn("IOTracer.start when it is already running");
        return true;
    }

    JNIEnv* jniEnv = getJNIEnv(jvm);
    get_io_tracer_config().set_latency_threshold(jniEnv, latency_threshold);
    running = true;
    return true;
}

void IOTracer::stop() {
    JNIEnv* jniEnv = getJNIEnv(jvm);
    get_io_tracer_config().set_latency_threshold(jniEnv, IOTracerConfig::default_latency_threshold);
    running = false;
}

void IOTracer::run() {
    while(evt_queue.pop());
}

void IOTracer::record_file_read(JNIEnv* jniEnv, fd_type_t fd, std::uint64_t ts, std::uint64_t latency_ns, int count) {
    FdInfo* fd_info = fd_map.get(fd);

    blocking::FdReadEvt read_evt = {.fd = fd_info, count = count, .timeout = false};
    blocking::BlockingEvt evt = {.ts = ts, .latency_ns = latency_ns, .type = blocking::EvtType::file_read, .evt = read_evt};

    record(jniEnv, evt);
}

void IOTracer::record_file_write(JNIEnv* jniEnv, fd_type_t fd, std::uint64_t ts, std::uint64_t latency_ns, int count) {
    FdInfo* fd_info = fd_map.get(fd);

    blocking::FdWriteEvt write_evt = {.fd = fd_info, count = count};
    blocking::BlockingEvt evt = {.ts = ts, .latency_ns = latency_ns, .type = blocking::EvtType::file_write, .evt = write_evt};

    record(jniEnv, evt);
}

void IOTracer::record_socket_read(JNIEnv* jniEnv, fd_type_t fd, std::uint64_t ts, std::uint64_t latency_ns, int count, bool timeout) {
    FdInfo* fd_info = fd_map.get(fd);

    blocking::FdReadEvt read_evt = {.fd = fd_info, count = count, .timeout = timeout};
    blocking::BlockingEvt evt = {.ts = ts, .latency_ns = latency_ns, .type = blocking::EvtType::socket_read, .evt = read_evt};

    record(jniEnv, evt);
}

void IOTracer::record_socket_write(JNIEnv* jniEnv, fd_type_t fd, std::uint64_t ts, std::uint64_t latency_ns, int count) {
    FdInfo* fd_info = fd_map.get(fd);

    blocking::FdWriteEvt write_evt = {.fd = fd_info, count = count};
    blocking::BlockingEvt evt = {.ts = ts, .latency_ns = latency_ns, .type = blocking::EvtType::socket_write, .evt = write_evt};

    record(jniEnv, evt);
}

void IOTracer::record(JNIEnv* jniEnv, blocking::BlockingEvt& evt) {
    jvmtiFrameInfo frames[max_stack_depth];
    jint frame_count;

    ThreadBucket* thd_info = thread_map.get(jniEnv);
    bool default_context = !thd_info->data.ctx_tracker.in_ctx();

    auto err = jvmtiEnv->GetStackTrace(nullptr, 0, max_stack_depth, max_stack_depth, &frame_count);
    if (err != JVMTI_ERROR_NONE) {
        logger->debug("error while getting stacktrace: {}", err);

        evt_queue.push(iotrace::InMsg(evt, thd_info, default_context));
        return;
    }

    evt_queue.push(iotrace::InMsg(evt, thd_info, frames, frame_count, default_context));
}
