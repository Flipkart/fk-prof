#include "profiler.hh"

ASGCTType Asgct::asgct_;
IsGCActiveType Asgct::is_gc_active_;

static void handle_profiling_signal(int signum, siginfo_t *info, void *context) {
    ReadsafePtr<Profiler> p(GlobalCtx::recording.cpu_profiler);
    if (p.available()) {
        p->handle(signum, info, context);
    }
}

void Profiler::handle(int signum, siginfo_t *info, void *context) {
    IMPLICITLY_USE(signum);//TODO: make this reenterant or implement try_lock+backoff
    IMPLICITLY_USE(info);//TODO: put a timer here after perf-tuning medida heavily, we'd dearly love a timer here, but the overhead makes it a no-go as of now.
    s_c_cpu_samp_total.inc();
    JNIEnv *jniEnv = getJNIEnv(jvm);
    ThreadBucket *thread_info = nullptr;
    PerfCtx::ThreadTracker* ctx_tracker = nullptr;
    auto current_sampling_attempt = sampling_attempts.fetch_add(1, std::memory_order_relaxed);
    if (jniEnv != nullptr) {
        thread_info = thread_map.get(jniEnv);
        bool do_record = true;
        if (thread_info != nullptr) {//TODO: increment a counter here to monitor freq of this, it could be GC thd or compiler-broker etc
            ctx_tracker = &(thread_info->ctx_tracker);
            do_record = ctx_tracker->in_ctx() ? ctx_tracker->should_record() : get_prob_pct().on(current_sampling_attempt, noctx_cov_pct);
        }
        if (! do_record) {
            return;
        }
    }

    STATIC_ARRAY(frames, JVMPI_CallFrame, capture_stack_depth(), MAX_FRAMES_TO_CAPTURE);

    JVMPI_CallTrace trace;
    trace.frames = frames;
    
    if (jniEnv == NULL) {
        IsGCActiveType is_gc_active = Asgct::GetIsGCActive();
        if ((is_gc_active != NULL) && ((*is_gc_active)() == 1)) {
            trace.num_frames = -2;
            s_c_cpu_samp_gc.inc();
        } else {
            trace.num_frames = -3;// ticks_unknown_not_Java or GC
            s_c_cpu_samp_err_no_jni.inc();
        }
    } else {
        trace.env_id = jniEnv;
        ASGCTType asgct = Asgct::GetAsgct();
        (*asgct)(&trace, capture_stack_depth(), context);
        if (trace.num_frames <= 0) {
            s_c_cpu_samp_err_unexpected.inc();
        }
    }
    // log all samples, failures included, let the post processing sift through the data
    buffer->push(trace, thread_info);
}

bool Profiler::start(JNIEnv *jniEnv) {
    if (running) {
        logger->warn("Start called but sampling is already running");
        return true;
    }

    // reference back to Profiler::handle on the singleton
    // instance of Profiler
    handler->SetAction(&handle_profiling_signal);
    bool res = handler->updateSigprofInterval();
    running = true;
    return res;
}

void Profiler::stop() {
    if (!running) {
        return;
    }

    handler->stopSigprof();
}

void Profiler::set_sampling_freq(std::uint32_t sampling_freq) {
    auto mean_sampling_itvl = 1000000 / sampling_freq;
    std::uint32_t itvl_10_pct = 0.1 * mean_sampling_itvl;
    itvl_max = mean_sampling_itvl + itvl_10_pct;
    itvl_min = mean_sampling_itvl - itvl_10_pct;
    itvl_min = itvl_min > 0 ? itvl_min : DEFAULT_SAMPLING_INTERVAL;
    itvl_max = itvl_max > 0 ? itvl_max : DEFAULT_SAMPLING_INTERVAL;
    logger->warn("Chose CPU sampling interval range [{0:06d}, {1:06d}) for requested sampling freq {2:d} Hz", itvl_min, itvl_max, sampling_freq);
}

std::uint32_t Profiler::calculate_max_stack_depth(std::uint32_t _max_stack_depth) {
    return (_max_stack_depth > 0 && _max_stack_depth < (MAX_FRAMES_TO_CAPTURE - 1)) ? _max_stack_depth : DEFAULT_MAX_FRAMES_TO_CAPTURE;
}

void Profiler::configure() {
    buffer = new CircularQueue(serializer, capture_stack_depth());

    handler = new SignalHandler(itvl_min, itvl_max);
    int processor_interval = Size * itvl_min / 1000 / 2;
    logger->debug("CpuSamplingProfiler is using processor-interval value: {}", processor_interval);
}

#define METRIC_TYPE "cpu_samples"

Profiler::Profiler(JavaVM *_jvm, jvmtiEnv *_jvmti, ThreadMap &_thread_map, ProfileSerializingWriter& _serializer, std::uint32_t _max_stack_depth, std::uint32_t _sampling_freq, ProbPct& _prob_pct, std::uint8_t _noctx_cov_pct)
    : jvm(_jvm), jvmti(_jvmti), thread_map(_thread_map), max_stack_depth(_max_stack_depth), serializer(_serializer),
      prob_pct(_prob_pct), sampling_attempts(0), noctx_cov_pct(_noctx_cov_pct), running(false), samples_handled(0),

      s_c_cpu_samp_total(get_metrics_registry().new_counter({METRICS_DOMAIN, METRIC_TYPE, "opportunities"})),

      s_c_cpu_samp_err_no_jni(get_metrics_registry().new_counter({METRICS_DOMAIN, METRIC_TYPE, "err_no_jni"})),
      s_c_cpu_samp_err_unexpected(get_metrics_registry().new_counter({METRICS_DOMAIN, METRIC_TYPE, "err_unexpected"})),
      s_c_cpu_samp_gc(get_metrics_registry().new_counter({METRICS_DOMAIN, METRIC_TYPE, "err_in_gc"})),

      s_h_pop_spree_len(get_metrics_registry().new_histogram({METRICS_DOMAIN, METRIC_TYPE, "pop_spree", "length"})),
      s_t_pop_spree_tm(get_metrics_registry().new_timer({METRICS_DOMAIN, METRIC_TYPE, "pop_spree", "time"})) {

    set_sampling_freq(_sampling_freq);
    configure();
}

Profiler::~Profiler() {
    if (running) stop();
    delete handler;
    delete buffer;
}

void Profiler::run() {
    {
        auto _ = s_t_pop_spree_tm.time_scope();

        int poppped_before = samples_handled;
        while (buffer->pop()) ++samples_handled;

        s_h_pop_spree_len.update(samples_handled - poppped_before);
    }

    if (samples_handled > 200) {
        if (! handler->updateSigprofInterval()) {
            logger->warn("Couldn't switch sigprof interval to the next random value");
        }
        samples_handled = 0;
    }
}
