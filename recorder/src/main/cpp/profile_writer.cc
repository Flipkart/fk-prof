#include "profile_writer.hh"
#include "buff.hh"
#include "globals.hh"
#include "util.hh"

//for buff, no one uses read-end here, so it is inconsistent

void ProfileWriter::flush() {
    w->write_unbuffered(data.buff, data.write_end, 0); //TODO: err-check me!
    data.write_end = 0;
}

std::uint32_t ProfileWriter::ensure_freebuff(std::uint32_t min_reqired) {
    if ((data.write_end + min_reqired) > data.capacity) {
        flush();
        data.ensure_free(min_reqired);
    }
    return data.capacity - data.write_end;
}

template <class T> inline std::uint32_t ProfileWriter::ensure_freebuff(const T& value) {
    auto sz = value.ByteSize();
    return ensure_freebuff(sz + MIN_FREE_BUFF);
}
    
void ProfileWriter::write_unchecked(std::uint32_t value) {
    auto _data = google::protobuf::io::CodedOutputStream::WriteVarint32ToArray(value, data.buff + data.write_end);
    data.write_end = _data - data.buff;
}

template <class T> void ProfileWriter::write_unchecked_obj(const T& value) {
    auto sz = value.ByteSize();
    write_unchecked(sz);
    if (! value.SerializeToArray(data.buff + data.write_end, sz)) {
        //TODO: handle this error
    }
    data.write_end += sz;
}

void ProfileWriter::write_header(const recording::RecordingHeader& rh) {
    assert(! header_written);
    header_written = true;
    ensure_freebuff(rh);
    write_unchecked(DATA_ENCODING_VERSION);
    write_unchecked_obj(rh);
    auto csum = chksum.chksum(data.buff, data.write_end);
    write_unchecked(csum);
}

void ProfileWriter::append_wse(const recording::Wse& e) {
    ensure_freebuff(e);
    auto old_offset = data.write_end;
    write_unchecked_obj(e);
    chksum.reset();
    auto data_sz = data.write_end - old_offset;
    auto csum = chksum.chksum(data.buff + old_offset, data_sz);
    write_unchecked(csum);
}

#define EOF_VALUE 0

void ProfileWriter::mark_eof() {
    write_unchecked(EOF_VALUE);
}

ProfileWriter::ProfileWriter(std::shared_ptr<RawWriter> _w, Buff& _data) : w(_w), data(_data), header_written(false) {
    data.write_end = data.read_end = 0;
}

ProfileWriter::~ProfileWriter() {
    mark_eof();
    flush();
}

recording::StackSample::Error translate_backtrace_error(BacktraceError error) {
    return static_cast<recording::StackSample::Error>(error);
}

void ProfileSerializingWriter::report_new_ctx(const ProfileSerializingWriter::CtxId ctx_id, const std::string& name, const bool is_generated, const std::uint8_t coverage_pct, const PerfCtx::MergeSemantic m_sem) {
    auto idx_dat = cpu_sample_accumulator.mutable_indexed_data();
    auto new_ctx = idx_dat->add_trace_ctx();
    new_ctx->set_trace_id(ctx_id);
    new_ctx->set_is_generated(is_generated);
    if (! is_generated) {
        new_ctx->set_coverage_pct(coverage_pct);
        new_ctx->set_merge(static_cast<recording::TraceContext_MergeSemantics>(static_cast<int>(m_sem)));
    }
    new_ctx->set_trace_name(name);
    s_c_new_ctx_info.inc();
}

ProfileSerializingWriter::CtxId ProfileSerializingWriter::report_new_ctx(const std::string& name, const bool is_generated, const std::uint8_t coverage_pct, const PerfCtx::MergeSemantic m_sem) {
    CtxId ctx_id = next_ctx_id++;
    report_new_ctx(ctx_id, name, is_generated, coverage_pct, m_sem);
    return ctx_id;
}

ProfileSerializingWriter::CtxId ProfileSerializingWriter::report_ctx(PerfCtx::TracePt trace_pt) {
    auto known_ctx = known_ctxs.find(trace_pt);
    if (known_ctx == known_ctxs.end()) {
        std::string name;
        bool is_generated;
        std::uint8_t coverage_pct;
        PerfCtx::MergeSemantic m_sem;
        reg.resolve(trace_pt, name, is_generated, coverage_pct, m_sem);
        CtxId ctx_id = report_new_ctx(name, is_generated, coverage_pct, m_sem);
        known_ctxs.insert({trace_pt, ctx_id});
        SPDLOG_DEBUG(logger, "Reporting trace named '{}', cov {}% as ctx-id: {}", name, coverage_pct, ctx_id);
        return ctx_id;
    } else {
        return known_ctx->second;
    }
}

void ProfileSerializingWriter::record(const Backtrace &trace, ThreadBucket *info, std::uint8_t ctx_len, PerfCtx::ThreadTracker::EffectiveCtx* ctx, bool default_ctx) {
    if (cpu_samples_flush_ctr >= sft.cpu_samples) flush();
    cpu_samples_flush_ctr++;
    
    auto idx_dat = cpu_sample_accumulator.mutable_indexed_data();
    auto cse = cpu_sample_accumulator.mutable_cpu_sample_entry();
    auto ss = cse->add_stack_sample();

    ss->set_start_offset_micros(0);

    if (info != nullptr) {
        auto local_thd_id = reinterpret_cast<ThdId>(info);
        auto known_thd = known_threads.find(local_thd_id);
        if (known_thd == known_threads.end()) {
            ThdId thd_id = next_thd_id++;
            known_threads.insert({local_thd_id, thd_id});
            auto ti = idx_dat->add_thread_info();
            ti->set_thread_id(thd_id);
            ti->set_thread_name(info->name);
            ti->set_priority(info->priority);
            ti->set_is_daemon(info->is_daemon);
            ti->set_tid(info->tid);
            ss->set_thread_id(thd_id);
            s_c_new_thd_info.inc();
        } else {
            ss->set_thread_id(known_thd->second);
        }
        info->release();
    }
    if (trace.error != BacktraceError::Fkp_no_error) {//TODO: move me down
        ss->set_error(translate_backtrace_error(trace.error));
    }

    SPDLOG_DEBUG(logger, "Ctx-len count: {}", ctx_len);

    for (auto i = 0; i < ctx_len; i++) {
        auto trace_pt = ctx->at(i);
        auto known_ctx = report_ctx(trace_pt);
        ss->add_trace_id(known_ctx);
    }

    auto snipped = trace.num_frames > trunc_thresholds.cpu_samples_max_stack_sz;
    if (snipped) s_c_frame_snipped.inc();
    ss->set_snipped(snipped);

    if (trace.error != BacktraceError::Fkp_no_error) {
        s_m_stack_sample_err.mark();
    }
    
    if (default_ctx) {
        assert(ctx_len == 0);
        ss->add_trace_id(DEFAULT_CTX_ID);
    } else if (ctx_len == 0) {
        ss->add_trace_id(UNKNOWN_CTX_ID);
    }

    auto bt_type = trace.type;
    for (auto i = 0; i < Util::min(static_cast<TruncationCap>(trace.num_frames), trunc_thresholds.cpu_samples_max_stack_sz); i++) {
        auto f = ss->add_frame();
        switch (bt_type) {
        case BacktraceType::Java:
            fill_frame(trace.frames[i].jvmpi_frame, f);
            break;
        case BacktraceType::Native:
            fill_frame(trace.frames[i].native_frame, f);
            break;
        default:
            assert(false);
        }
    }

    s_m_cpu_sample_add.mark();
}

void ProfileSerializingWriter::fill_frame(const JVMPI_CallFrame& jvmpi_cf, recording::Frame* f) {
    auto mth_id = jvmpi_cf.method_id;
    MthId my_id;
    auto it = known_methods.find(reinterpret_cast<MthId>(mth_id));
    if (it == std::end(known_methods)) {
        if (fir(mth_id, jvmti, *this, my_id)) {
            s_c_new_mthd_info.inc();
        } else {
            my_id = 0;
        }
    } else {
        my_id = it->second;
    }
    f->set_method_id(my_id);
    f->set_bci(jvmpi_cf.lineno);//turns out its actually BCI
    auto line_no = lnr(jvmpi_cf.lineno, mth_id, jvmti);
    if (line_no < 0) s_c_bad_lineno.inc();
    f->set_line_no(line_no);
}

void ProfileSerializingWriter::fill_frame(const NativeFrame& pc, recording::Frame* f) {
    MthId my_id;
    PcOffset pc_offset;
    auto it = known_symbols.find(pc);
    if (it == std::end(known_symbols)) {
        std::string fn_name, file_name;
        SiteResolver::Addr offset;
        syms.site_for(pc, file_name, fn_name, offset);
        auto sym_start = pc - offset;
        it = known_symbols.find(sym_start);
        if (it == std::end(known_symbols)) {
            my_id = report_new_mthd_info(file_name.c_str(), "", fn_name.c_str(), "", BacktraceType::Native);
            known_symbols[sym_start] = {my_id, 0};
        } else {
            my_id = it->second.first;
        }
        pc_offset = static_cast<std::int32_t>(offset);
        known_symbols[pc] = {my_id, pc_offset};
        s_c_new_pc.inc();
    } else {
        const auto& fn_offset = it->second;
        my_id = fn_offset.first;
        pc_offset = fn_offset.second;
    }
    f->set_method_id(my_id);
    f->set_bci(pc_offset);
    f->set_line_no(0);
}

ProfileSerializingWriter::MthId ProfileSerializingWriter::report_new_mthd_info(const char *file_name, const char *class_name, const char *method_name, const char *method_signature, const BacktraceType bt_type) {
    MthId my_id = next_mthd_id++;

    auto idx_dat = cpu_sample_accumulator.mutable_indexed_data();
    auto mi = idx_dat->add_method_info();

    mi->set_method_id(my_id);

    assert(file_name != nullptr);
    mi->set_file_name(file_name);

    assert(class_name != nullptr);
    mi->set_class_fqdn(class_name);

    assert(method_name != nullptr);
    mi->set_method_name(method_name);

    assert(method_signature != nullptr);
    mi->set_signature(method_signature);

    switch (bt_type) {
    case BacktraceType::Java:
        mi->set_c_cls(recording::MethodInfo_CodeClass_cls_java);
        break;
    case BacktraceType::Native:
        mi->set_c_cls(recording::MethodInfo_CodeClass_cls_native);
        break;
    default:
        assert(false);
    }

    s_c_total_mthd_info.inc();

    return my_id;
}

ProfileSerializingWriter::MthId ProfileSerializingWriter::recordNewMethod(const jmethodID method_id, const char *file_name, const char *class_name, const char *method_name, const char *method_signature) {
    auto my_id = report_new_mthd_info(file_name, class_name, method_name, method_signature, BacktraceType::Java);

    known_methods[reinterpret_cast<MthId>(method_id)] = my_id;

    return my_id;
}

void ProfileSerializingWriter::flush() {
    cpu_sample_accumulator.set_w_type(recording::WorkType::cpu_sample_work);
    w.append_wse(cpu_sample_accumulator);
    cpu_sample_accumulator.Clear();
    cpu_samples_flush_ctr = 0;
    w.flush();
}

#define METRIC_TYPE "profile_serializer"

ProfileSerializingWriter::ProfileSerializingWriter(jvmtiEnv* _jvmti, ProfileWriter& _w, SiteResolver::MethodInfoResolver _fir, SiteResolver::LineNoResolver _lnr,
                                                   PerfCtx::Registry& _reg, const SerializationFlushThresholds& _sft, const TruncationThresholds& _trunc_thresholds,
                                                   std::uint8_t _noctx_cov_pct) :
    jvmti(_jvmti), w(_w), fir(_fir), lnr(_lnr), reg(_reg), next_mthd_id(0), next_thd_id(3), next_ctx_id(5), sft(_sft), cpu_samples_flush_ctr(0),
    trunc_thresholds(_trunc_thresholds),

    s_c_new_thd_info(get_metrics_registry().new_counter({METRICS_DOMAIN, METRIC_TYPE, "thd_rpt", "new"})),
    s_c_new_ctx_info(get_metrics_registry().new_counter({METRICS_DOMAIN, METRIC_TYPE, "ctx_rpt", "new"})),
    s_c_total_mthd_info(get_metrics_registry().new_counter({METRICS_DOMAIN, METRIC_TYPE, "mthd_rpt", "total"})),
    s_c_new_mthd_info(get_metrics_registry().new_counter({METRICS_DOMAIN, METRIC_TYPE, "mthd_rpt", "new"})),
    s_c_new_pc(get_metrics_registry().new_counter({METRICS_DOMAIN, METRIC_TYPE, "pc_rpt", "new"})),


    s_c_bad_lineno(get_metrics_registry().new_counter({METRICS_DOMAIN, METRIC_TYPE, "line_rpt", "bad"})),

    s_c_frame_snipped(get_metrics_registry().new_counter({METRICS_DOMAIN, METRIC_TYPE, "backtrace_snipped"})),

    s_m_stack_sample_err(get_metrics_registry().new_meter({METRICS_DOMAIN, METRIC_TYPE, "cpu_sample", "err"}, "rate")),
    s_m_cpu_sample_add(get_metrics_registry().new_meter({METRICS_DOMAIN, METRIC_TYPE, "cpu_sample", "rpt"}, "rate")) {

    s_c_new_thd_info.clear();
    s_c_new_ctx_info.clear();
    s_c_total_mthd_info.clear();
    s_c_new_mthd_info.clear();
    s_c_new_pc.clear();

    s_c_bad_lineno.clear();

    s_c_frame_snipped.clear();

    report_new_ctx(DEFAULT_CTX_ID, DEFAULT_CTX_NAME, false, _noctx_cov_pct, PerfCtx::MergeSemantic::to_parent);
    report_new_ctx(UNKNOWN_CTX_ID, UNKNOWN_CTX_NAME, true, 0, PerfCtx::MergeSemantic::to_parent);

    report_new_mthd_info("?", "?", "?", "?", BacktraceType::Java);
}

ProfileSerializingWriter::~ProfileSerializingWriter() {
    std::vector<PerfCtx::TracePt> user_ctxs;
    reg.user_ctxs(user_ctxs);
    auto next_ctx_to_be_reported = next_ctx_id;
    for (auto pt : user_ctxs) report_ctx(pt);

    if ((cpu_samples_flush_ctr != 0) ||
        (next_ctx_to_be_reported != next_ctx_id)) flush();
    assert(cpu_samples_flush_ctr == 0);
}

