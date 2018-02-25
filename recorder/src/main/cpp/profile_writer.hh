#ifndef PROFILE_WRITER_H
#define PROFILE_WRITER_H

#include <memory>
#include <unordered_map>
#include <unordered_set>
#include <google/protobuf/io/coded_stream.h>
#include "checksum.hh"
#include "recorder.pb.h"
#include "recording.pb.h"
#include "circular_queue.hh"
#include "buff.hh"
#include "site_resolver.hh"

namespace recording = fk::prof::idl;

class RawWriter {
public:
    RawWriter() {}
    virtual ~RawWriter() {}
    virtual void write_unbuffered(const std::uint8_t* data, std::uint32_t sz, std::uint32_t offset) = 0;
};

// Writes individual protobuf messages to a byte array
class ProfileWriter {
private:
    //MIN_FREE_BUFF should accomodate atleast 4 varint32 values
    static const std::uint32_t MIN_FREE_BUFF = 64;

    // raw bytes writer. can be a http / file writer
    std::shared_ptr<RawWriter> w;

    Checksum chksum;
    Buff &data;
    bool header_written;

    std::uint32_t ensure_freebuff(std::uint32_t min_reqired);

    template <class T> std::uint32_t ensure_freebuff(const T& value);
    
    void write_unchecked(std::uint32_t value);

    template <class T> void write_unchecked_obj(const T& value);

    void mark_eof();

public:
    ProfileWriter(std::shared_ptr<RawWriter> _w, Buff& _data);

    ~ProfileWriter();

    void write_header(const recording::RecordingHeader& rh);

    void write_recording(const recording::RecordingChunk& recording);

    void flush();
};

typedef std::uint32_t FlushCtr;

struct TruncationThresholds {

    typedef std::uint32_t TruncationCap;

    TruncationCap cpu_samples_max_stack_sz;
    TruncationCap io_trace_max_stack_sz;

    TruncationThresholds(
            TruncationCap _cpu_samples_max_stack_sz = DEFAULT_MAX_FRAMES_TO_CAPTURE,
            TruncationCap _io_trace_max_stack_sz = DEFAULT_MAX_FRAMES_TO_CAPTURE)
    : cpu_samples_max_stack_sz(_cpu_samples_max_stack_sz), io_trace_max_stack_sz(_io_trace_max_stack_sz) {
    }
    ~TruncationThresholds() {}
};

// Serializes profiling data by listening to events.
class ProfileSerializingWriter : public cpu::Queue::Listener,
                                 public iotrace::Queue::Listener,
                                 public SiteResolver::MethodListener {
private:
    jvmtiEnv* jvmti;
    
    typedef std::int64_t MthId;
    typedef std::int64_t ThdId;
    typedef std::uint32_t CtxId;
    typedef std::uint32_t FdId;
    typedef SiteResolver::Addr Pc;
    typedef std::int32_t PcOffset;
    
    ProfileWriter& w;
    SiteResolver::MethodInfoResolver fir;
    SiteResolver::LineNoResolver lnr;
    PerfCtx::Registry& reg;

    recording::RecordingChunk recording;
    recording::Wse* cpu_samples_accumulator;
    recording::Wse* io_trace_accumulator;
    
    std::unordered_map<MthId, MthId> known_methods;
    std::unordered_map<Pc, std::pair<MthId, PcOffset>> known_symbols;
    MthId next_mthd_id;
    std::unordered_map<uintptr_t, ThdId> known_threads;
    ThdId next_thd_id;
    std::unordered_map<PerfCtx::TracePt, CtxId> known_ctxs;
    CtxId next_ctx_id;
    std::unordered_map<uintptr_t, FdId> known_fds;
    FdId next_fd_id;

    const FlushCtr sft;
    FlushCtr ser_flush_ctr;

    const TruncationThresholds& trunc_thresholds;

    metrics::Ctr& s_c_new_thd_info;
    metrics::Ctr& s_c_new_ctx_info;
    metrics::Ctr& s_c_total_mthd_info;
    metrics::Ctr& s_c_new_mthd_info;
    metrics::Ctr& s_c_new_pc;

    metrics::Ctr& s_c_bad_lineno;

    metrics::Ctr& s_c_frame_snipped;

    metrics::Mtr& s_m_stack_sample_err;
    metrics::Mtr& s_m_cpu_sample_add;

    SiteResolver::SymInfo syms;

    CtxId report_ctx(PerfCtx::TracePt trace_pt);

    ThdId report_thd_info(const ThreadBucket* const thd_bucket);

    FdId report_fd_info(const FdBucket* const fd_bucket);

    void fill_frame(const JVMPI_CallFrame& jvmpi_cf, recording::Frame* f);

    void fill_frame(const NativeFrame& pc, recording::Frame* f);

    MthId report_new_mthd_info(const char *file_name, const char *class_name, const char *method_name, const char *method_signature, const BacktraceType bt_type);

    CtxId report_new_ctx(const std::string& name, const bool is_generated, const std::uint8_t coverage_pct, const PerfCtx::MergeSemantic m_sem);

    void report_new_ctx(const CtxId ctx_id, const std::string& name, const bool is_generated, const std::uint8_t coverage_pct, const PerfCtx::MergeSemantic m_sem);

    /* helper methods to populate proto structures */
    void populate_ctx_in_ss(recording::StackSample* const ss, const PerfCtx::ThreadTracker::EffectiveCtx& ctx, std::uint8_t ctx_len, bool default_ctx);

    void populate_bt_in_ss(recording::StackSample* const ss, const Backtrace& bt, std::uint32_t frames_to_write, bool snipped);

    void populate_fdread(recording::IOTrace* const evt, const blocking::FdReadEvt& read_evt, blocking::EvtType type);

    void populate_fdwrite(recording::IOTrace* const evt, const blocking::FdWriteEvt& write_evt, blocking::EvtType type);

    void clear_proto();

public:
    ProfileSerializingWriter(jvmtiEnv* _jvmti, ProfileWriter& _w, SiteResolver::MethodInfoResolver _fir, SiteResolver::LineNoResolver _lnr,
                             PerfCtx::Registry& _reg, const FlushCtr _sft, const TruncationThresholds& _trunc_thresholds,
                             std::uint8_t _noctx_cov_pct);

    ~ProfileSerializingWriter();

    virtual void record(const cpu::Sample& entry) override;

    virtual void record(const iotrace::Sample& entry) override;

    virtual MthId recordNewMethod(const jmethodID method_id, const char *file_name, const char *class_name, const char *method_name, const char *method_signature) override;

    void flush();
};

#endif
