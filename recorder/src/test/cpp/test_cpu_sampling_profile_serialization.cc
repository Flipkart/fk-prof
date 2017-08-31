#include <thread>
#include <vector>
#include <iostream>
#include <cstdint>
#include "test.hh"
#include <profiler.hh>
#include "fixtures.hh"
#include <unordered_map>
#include <blocking_ring_buffer.hh>
#include <circular_queue.hh>
#include <memory>
#include <tuple>
#include <checksum.hh>
#include <google/protobuf/io/coded_stream.h>
#include <thread_map.hh>
#include <random>
#include "test_helpers.hh"
#include <algorithm>

namespace std {
    template <> struct hash<std::tuple<std::int64_t, jint>> {
        std::hash<std::int64_t> i64_h;
        std::hash<jint> jint_h;

    public:
        std::size_t operator()(const std::tuple<std::int64_t, jint>& v) const {
            return 13 * i64_h(std::get<0>(v)) + std::get<1>(v);
        }
    };
}

typedef std::unordered_map<std::int64_t, std::tuple<std::int64_t, const char*, const char*, const char*, const char*>> FnStub;
typedef std::unordered_map<std::tuple<std::int64_t, jint>, jint> LineNoStub;

FnStub method_lookup_stub;
LineNoStub line_no_lookup_stub;

bool test_mthd_info_resolver(const jmethodID method_id, jvmtiEnv* jvmti, SiteResolver::MethodListener& listener, std::int64_t& assigned_id) {
    auto t = method_lookup_stub.find(reinterpret_cast<std::int64_t>(method_id));
    if (t != method_lookup_stub.end()) {
        const auto& identity = t->second;
        assigned_id = listener.recordNewMethod(method_id, std::get<1>(identity), std::get<2>(identity), std::get<3>(identity), std::get<4>(identity));
        return true;
    }
    return false;
}

jint test_line_no_resolver(jint bci, jmethodID method_id, jvmtiEnv* jvmti) {
    auto t = line_no_lookup_stub.find(std::make_tuple(reinterpret_cast<int64_t>(method_id), bci));
    if (t != line_no_lookup_stub.end()) {
        return t->second;
    }
    return 0;
}

void stub(FnStub& method_lookup_stub, LineNoStub& line_no_lookup_stub, std::int64_t method_id, const char* file, const char* fqdn, const char* fn_name, const char* fn_sig) {
    method_lookup_stub.insert({method_id, std::make_tuple(method_id, file, fqdn, fn_name, fn_sig)});
    line_no_lookup_stub.insert({std::make_tuple(method_id, 10), 1});
    line_no_lookup_stub.insert({std::make_tuple(method_id, 20), 2});
    line_no_lookup_stub.insert({std::make_tuple(method_id, 30), 3});
    line_no_lookup_stub.insert({std::make_tuple(method_id, 40), 4});
}

struct AccumulatingRawWriter : public RawWriter {
    BlockingRingBuffer& buff;
    
    AccumulatingRawWriter(BlockingRingBuffer& _buff) : RawWriter(), buff(_buff) {}
    virtual ~AccumulatingRawWriter() {}
    
    void write_unbuffered(const std::uint8_t* data, std::uint32_t sz, std::uint32_t offset) {
        auto wrote = buff.write(data, offset, sz, false);
        if (wrote < sz) {
            throw std::runtime_error(Util::to_s("Wrote too little to byte-buff, wanted to write ", sz, " but wrote only ", wrote));
        }
    }
};

jmethodID mid(std::uint64_t id) {
    return reinterpret_cast<jmethodID>(id);
}


#define ASSERT_THREAD_INFO_IS(thd_info, thd_id, thd_name, thd_prio, thd_is_daemon, thd_tid) \
    {                                                                   \
        CHECK_EQUAL(thd_id, thd_info.thread_id());                      \
        CHECK_EQUAL(thd_name, thd_info.thread_name());                  \
        CHECK_EQUAL(thd_prio, thd_info.priority());                     \
        CHECK_EQUAL(thd_is_daemon, thd_info.is_daemon());               \
        CHECK_EQUAL(thd_tid, thd_info.tid());                           \
    }

#define Java_Sym recording::MethodInfo_CodeClass_cls_java
#define Native_Sym recording::MethodInfo_CodeClass_cls_native

#define ASSERT_METHOD_INFO_IS(mthd_info, mthd_id, kls_file, kls_fqdn, fn_name, fn_sig, code_cls) \
    {                                                                   \
        CHECK_EQUAL(mthd_id, mthd_info.method_id());                    \
        auto mthd_file = mthd_info.file_name();                         \
        CHECK_EQUAL(kls_file, (mthd_file[0] == '/') ? abs_path(mthd_file) : mthd_file); \
        CHECK_EQUAL(kls_fqdn, mthd_info.class_fqdn());                  \
        CHECK_EQUAL(fn_name, mthd_info.method_name());                  \
        CHECK_EQUAL(fn_sig, mthd_info.signature());                     \
        CHECK_EQUAL(code_cls, mthd_info.c_cls()); \
    }

#define ASSERT_TRACE_CTX_INFO_WITHOUT_CTXID_IS(trace_ctx, ctx_name, cov_pct, merge_semantics, is_gen) \
    {                                                                   \
        CHECK_EQUAL(ctx_name, trace_ctx.trace_name());                  \
        if (is_gen) {                                                   \
            CHECK_EQUAL(true, trace_ctx.is_generated());                \
            CHECK_EQUAL(false, trace_ctx.has_coverage_pct());           \
            CHECK_EQUAL(false, trace_ctx.has_merge());                  \
        } else {                                                        \
            CHECK_EQUAL(true, trace_ctx.has_coverage_pct());            \
            CHECK_EQUAL(true, trace_ctx.has_merge());                   \
            CHECK_EQUAL(cov_pct, trace_ctx.coverage_pct());             \
            CHECK_EQUAL(merge_semantics, trace_ctx.merge());            \
        }                                                               \
    }

#define ASSERT_TRACE_CTX_INFO_IS(trace_ctx, ctx_id, ctx_name, cov_pct, merge_semantics, is_gen) \
    {                                                                   \
        CHECK_EQUAL(ctx_id, trace_ctx.trace_id());                      \
        ASSERT_TRACE_CTX_INFO_WITHOUT_CTXID_IS(trace_ctx, ctx_name, cov_pct, merge_semantics, is_gen); \
    }

typedef std::int64_t F_mid;
typedef std::int32_t F_bci;
typedef std::int32_t F_line;
std::tuple<F_mid, F_bci, F_line> fr(F_mid mid, F_bci bci, F_line line) {
    return std::make_tuple(mid, bci, line);
}

#define NO_THD -1

#define ASSERT_STACK_SAMPLE_IS(ss, time_offset, thd_id, frames, ctx_ids, is_snipped) \
    {                                                                   \
        CHECK_EQUAL(time_offset, ss.start_offset_micros());             \
        if (thd_id == NO_THD) {                                         \
            CHECK_EQUAL(false, ss.has_thread_id());                     \
        } else {                                                        \
            CHECK_EQUAL(thd_id, ss.thread_id());                        \
        }                                                               \
        CHECK_EQUAL(frames.size(), ss.frame_size());                    \
        auto i = 0;                                                     \
        for (auto it = frames.begin(); it != frames.end(); it++, i++) { \
            auto f = ss.frame(i);                                       \
            CHECK_EQUAL(std::get<0>(*it), f.method_id());               \
            CHECK_EQUAL(std::get<1>(*it), f.bci());                     \
            CHECK_EQUAL(std::get<2>(*it), f.line_no());                 \
        }                                                               \
        CHECK_EQUAL(frames.size(), i);                                  \
        CHECK_EQUAL(ctx_ids.size(), ss.trace_id_size());                \
        i = 0;                                                          \
        for (auto it = ctx_ids.begin(); it != ctx_ids.end(); it++, i++) { \
            CHECK_EQUAL(*it, ss.trace_id(i));                           \
        }                                                               \
        CHECK_EQUAL(is_snipped, ss.snipped());                          \
    }

//[#]  calling convention will take a few instructions
//[##] just a guess, these functions are fairly small, so won't have more than 200 bytes worth of text
#define ASSERT_NATIVE_STACK_SAMPLE_IS(ss, time_offset, thd_id, frames, ctx_ids, is_snipped) \
    {                                                                   \
        CHECK_EQUAL(time_offset, ss.start_offset_micros());             \
        if (thd_id == NO_THD) {                                         \
            CHECK_EQUAL(false, ss.has_thread_id());                     \
        } else {                                                        \
            CHECK_EQUAL(thd_id, ss.thread_id());                        \
        }                                                               \
        CHECK_EQUAL(frames.size(), ss.frame_size());                    \
        auto i = 0;                                                     \
        for (auto it = frames.begin(); it != frames.end(); it++, i++) { \
            auto f = ss.frame(i);                                       \
            CHECK_EQUAL((*it), f.method_id());                          \
            CHECK(f.bci() > 0); /* [#] */                               \
            CHECK(f.bci() < 200); /* [##] */                            \
        }                                                               \
        CHECK_EQUAL(frames.size(), i);                                  \
        CHECK_EQUAL(ctx_ids.size(), ss.trace_id_size());                \
        i = 0;                                                          \
        for (auto it = ctx_ids.begin(); it != ctx_ids.end(); it++, i++) { \
            CHECK_EQUAL(*it, ss.trace_id(i));                           \
        }                                                               \
        CHECK_EQUAL(is_snipped, ss.snipped());                          \
    }


CircularQueue* bt_q = nullptr;
ThreadBucket* bt_tinfo = nullptr;
BacktraceError bt_err;
std::uint32_t native_bt_max_depth = 6;
bool mark_default_ctx = false;

std::random_device rd;
std::uniform_int_distribution<int> dist(0, 10000);

void bt_pusher() {
    STATIC_ARRAY(frames, NativeFrame, native_bt_max_depth, native_bt_max_depth);
    auto len = Stacktraces::fill_backtrace(frames, native_bt_max_depth);
    bt_q->push(frames, len, bt_err, mark_default_ctx, bt_tinfo);
}

void push_native_backtrace(ThreadBucket* t, BacktraceError err, CircularQueue& q, bool default_ctx = false, int capture_bt_at = 4) {
    bt_q = &q;
    bt_err = err;
    bt_tinfo = t;
    native_bt_max_depth = capture_bt_at + 2;
    mark_default_ctx = default_ctx;

    fn_corge(dist(rd), dist(rd), capture_bt_at);

    native_bt_max_depth = 6;
    mark_default_ctx = false;
    bt_q = nullptr;
    bt_err = BacktraceError::Fkp_no_error;
    bt_tinfo = nullptr;
}

TEST(ProfileSerializer__should_write_cpu_samples_native_and_java) {
    TestEnv _;
    BlockingRingBuffer buff(1024 * 1024);
    std::shared_ptr<RawWriter> raw_w_ptr(new AccumulatingRawWriter(buff));
    Buff pw_buff;
    ProfileWriter pw(raw_w_ptr, pw_buff);

    method_lookup_stub.clear();
    line_no_lookup_stub.clear();
    
    std::int64_t d = 1, c = 2, y = 3, e = 10, f = 11;
    std::int64_t bt_pusher = 4, fn_c_foo = 5, fn_bar = 6, fn_baz = 7, fn_quux = 8, fn_corge = 9;
    stub(method_lookup_stub, line_no_lookup_stub, y, "x/Y.class", "x.Y", "fn_y", "(I)J");
    stub(method_lookup_stub, line_no_lookup_stub, c, "x/C.class", "x.C", "fn_c", "(F)I");
    stub(method_lookup_stub, line_no_lookup_stub, d, "x/D.class", "x.D", "fn_d", "(J)I");
    stub(method_lookup_stub, line_no_lookup_stub, e, "x/E.class", "x.E", "fn_e", "(J)I");
    stub(method_lookup_stub, line_no_lookup_stub, f, "x/F.class", "x.F", "fn_f", "(J)I");

    PerfCtx::Registry reg;
    auto to_parent_semantic = static_cast<std::uint8_t>(PerfCtx::MergeSemantic::to_parent);
    auto dup_semantic = static_cast<std::uint8_t>(PerfCtx::MergeSemantic::duplicate);
    auto ctx_foo = reg.find_or_bind("foo", 20, to_parent_semantic);
    auto ctx_bar = reg.find_or_bind("bar", 30, to_parent_semantic);
    auto ctx_baz = reg.find_or_bind("baz", 40, dup_semantic);
    
    ProbPct ppct;
    prob_pct = &ppct;
    ctx_reg = &reg;

    jvmtiEnv* ti = nullptr;

    SerializationFlushThresholds sft;
    TruncationThresholds tts(7);
    ProfileSerializingWriter ps(ti, pw, test_mthd_info_resolver, test_line_no_resolver, reg, sft, tts, 15);

    CircularQueue q(ps, 10);
    
    STATIC_ARRAY(frames, JVMPI_CallFrame, 7, 7);
    JVMPI_CallTrace ct;
    ct.frames = frames;

    frames[0].method_id = mid(d);
    frames[0].lineno = 10;
    frames[1].method_id = mid(c);
    frames[1].lineno = 10;
    frames[2].method_id = mid(d);
    frames[2].lineno = 20;
    frames[3].method_id = mid(c);
    frames[3].lineno = 20;
    frames[4].method_id = mid(y);
    frames[4].lineno = 30;
    ct.num_frames = 5;

    ThreadBucket t25(25, "Thread No. 25", 5, true);

    t25.ctx_tracker.enter(ctx_foo);
    t25.ctx_tracker.enter(ctx_bar);
    q.push(ct, BacktraceError::Fkp_no_error, false, ThreadBucket::acq_bucket(&t25));
    t25.ctx_tracker.exit(ctx_bar);
    push_native_backtrace(ThreadBucket::acq_bucket(&t25), BacktraceError::Fkp_no_error, q);
    t25.ctx_tracker.exit(ctx_foo);

    frames[0].method_id = mid(d);
    frames[0].lineno = 10;
    frames[1].method_id = mid(c);
    frames[1].lineno = 10;
    frames[2].method_id = mid(e);
    frames[2].lineno = 20;
    frames[3].method_id = mid(d);
    frames[3].lineno = 20;
    frames[4].method_id = mid(c);
    frames[4].lineno = 30;
    frames[5].method_id = mid(y);
    frames[5].lineno = 30;
    ct.num_frames = 6;

    ThreadBucket tmain(42, "main thread", 10, false);
    tmain.ctx_tracker.enter(ctx_bar);
    tmain.ctx_tracker.enter(ctx_baz);
    q.push(ct, BacktraceError::Fkp_no_error, false, ThreadBucket::acq_bucket(&tmain));
    tmain.ctx_tracker.exit(ctx_baz);

    frames[0].method_id = mid(c);
    frames[0].lineno = 10;
    frames[1].method_id = mid(f);
    frames[1].lineno = 10;
    frames[2].method_id = mid(e);
    frames[2].lineno = 20;
    frames[3].method_id = mid(d);
    frames[3].lineno = 20;
    frames[4].method_id = mid(c);
    frames[4].lineno = 30;
    frames[5].method_id = mid(y);
    frames[5].lineno = 30;
    ct.num_frames = 6;
    q.push(ct, BacktraceError::Fkp_no_error, false, ThreadBucket::acq_bucket(&tmain));
    
    tmain.ctx_tracker.exit(ctx_bar);

    frames[0].method_id = mid(c);
    frames[0].lineno = 40;
    q.push(ct, BacktraceError::Fkp_no_error, true, nullptr);
    push_native_backtrace(nullptr, BacktraceError::Fkp_no_jni_env, q, true, 2);

    CHECK(q.pop());
    CHECK(q.pop());
    CHECK(q.pop());
    CHECK(q.pop());
    CHECK(q.pop());
    CHECK(q.pop());
    CHECK(! q.pop());//because only 6 samples were pushed

    ps.flush();

    std::shared_ptr<std::uint8_t> tmp_buff(new std::uint8_t[1024 * 1024], std::default_delete<std::uint8_t[]>());
    auto bytes_sz = buff.read(tmp_buff.get(), 0, 1024 * 1024, false);
    CHECK(bytes_sz > 0);

    google::protobuf::io::CodedInputStream cis(tmp_buff.get(), bytes_sz);

    std::uint32_t len;
    CHECK(cis.ReadVarint32(&len));

    auto lim = cis.PushLimit(len);

    recording::Wse wse;
    CHECK(wse.ParseFromCodedStream(&cis));

    cis.PopLimit(lim);

    auto pos = cis.CurrentPosition();

    std::uint32_t csum;
    CHECK(cis.ReadVarint32(&csum));

    Checksum c_calc;
    auto computed_csum = c_calc.chksum(tmp_buff.get(), pos);

    CHECK_EQUAL(computed_csum, csum);

    CHECK_EQUAL(recording::WorkType::cpu_sample_work, wse.w_type());
    auto idx_data = wse.indexed_data();
    CHECK_EQUAL(0, idx_data.monitor_info_size());
    
    CHECK_EQUAL(2, idx_data.thread_info_size());
    ASSERT_THREAD_INFO_IS(idx_data.thread_info(0), 3, "Thread No. 25", 5, true, 25);
    ASSERT_THREAD_INFO_IS(idx_data.thread_info(1), 4, "main thread", 10, false, 42);

    CHECK_EQUAL(12, idx_data.method_info_size());
    ASSERT_METHOD_INFO_IS(idx_data.method_info(0), 0, "?", "?", "?", "?", Java_Sym);
    ASSERT_METHOD_INFO_IS(idx_data.method_info(1), d, "x/D.class", "x.D", "fn_d", "(J)I", Java_Sym);
    ASSERT_METHOD_INFO_IS(idx_data.method_info(2), c, "x/C.class", "x.C", "fn_c", "(F)I", Java_Sym);
    ASSERT_METHOD_INFO_IS(idx_data.method_info(3), y, "x/Y.class", "x.Y", "fn_y", "(I)J", Java_Sym);
    auto test_executable = my_executable();
    auto test_helper_lib = my_test_helper_lib();
    ASSERT_METHOD_INFO_IS(idx_data.method_info(4), bt_pusher, test_executable, "", "bt_pusher()", "", Native_Sym);
    ASSERT_METHOD_INFO_IS(idx_data.method_info(5), fn_c_foo, test_helper_lib, "", "fn_c_foo", "", Native_Sym);
    ASSERT_METHOD_INFO_IS(idx_data.method_info(6), fn_bar, test_helper_lib, "", "Bar::fn_bar(int)", "", Native_Sym);
    ASSERT_METHOD_INFO_IS(idx_data.method_info(7), fn_baz, test_helper_lib, "", "fn_baz(int)", "", Native_Sym);
    ASSERT_METHOD_INFO_IS(idx_data.method_info(8), fn_quux, test_helper_lib, "", "fn_quux(int, int)", "", Native_Sym);
    ASSERT_METHOD_INFO_IS(idx_data.method_info(9), fn_corge, test_helper_lib, "", "fn_corge(int, int, int)", "", Native_Sym);
    ASSERT_METHOD_INFO_IS(idx_data.method_info(10), e, "x/E.class", "x.E", "fn_e", "(J)I", Java_Sym);
    ASSERT_METHOD_INFO_IS(idx_data.method_info(11), f, "x/F.class", "x.F", "fn_f", "(J)I", Java_Sym);

    CHECK_EQUAL(5, idx_data.trace_ctx_size());
    ASSERT_TRACE_CTX_INFO_IS(idx_data.trace_ctx(0), 0, DEFAULT_CTX_NAME, 15, 0, false);
    ASSERT_TRACE_CTX_INFO_IS(idx_data.trace_ctx(1), 1, UNKNOWN_CTX_NAME, 0, 0, true);
    ASSERT_TRACE_CTX_INFO_IS(idx_data.trace_ctx(2), 5, "foo", 20, 0, false);
    ASSERT_TRACE_CTX_INFO_IS(idx_data.trace_ctx(3), 6, "bar", 30, 0, false);
    ASSERT_TRACE_CTX_INFO_IS(idx_data.trace_ctx(4), 7, "baz", 40, 4, false);

    auto cse = wse.cpu_sample_entry();

    CHECK_EQUAL(6, cse.stack_sample_size());
    auto s1 = {fr(d, 10, 1), fr(c, 10, 1), fr(d, 20, 2), fr(c, 20, 2), fr(y, 30, 3)};
    auto s1_ctxs = {5};
    ASSERT_STACK_SAMPLE_IS(cse.stack_sample(0), 0, 3, s1, s1_ctxs, false); //TODO: fix this to actually record time-offset, right now we are using zero
    auto sn1 = {bt_pusher, fn_c_foo, fn_bar, fn_baz, fn_quux, fn_corge};
    auto sn1_ctxs = {5};
    ASSERT_NATIVE_STACK_SAMPLE_IS(cse.stack_sample(1), 0, 3, sn1, sn1_ctxs, false);
    auto s2 = {fr(d, 10, 1), fr(c, 10, 1), fr(e, 20, 2), fr(d, 20, 2), fr(c, 30, 3), fr(y, 30, 3)};
    auto s2_ctxs = {6, 7};
    ASSERT_STACK_SAMPLE_IS(cse.stack_sample(2), 0, 4, s2, s2_ctxs, false);
    auto s3 = {fr(c, 10, 1), fr(f, 10, 1), fr(e, 20, 2), fr(d, 20, 2), fr(c, 30, 3), fr(y, 30, 3)};
    auto s3_ctxs = {6};
    ASSERT_STACK_SAMPLE_IS(cse.stack_sample(3), 0, 4, s3, s3_ctxs, false);
    auto s4 = {fr(c, 40, 4), fr(f, 10, 1), fr(e, 20, 2), fr(d, 20, 2), fr(c, 30, 3), fr(y, 30, 3)};
    auto s4_ctxs = {0};
    ASSERT_STACK_SAMPLE_IS(cse.stack_sample(4), 0, NO_THD, s4, s4_ctxs, false);
    auto sn2 = {bt_pusher, fn_baz, fn_quux, fn_corge};
    auto sn2_ctxs = {0};
    ASSERT_NATIVE_STACK_SAMPLE_IS(cse.stack_sample(5), 0, NO_THD, sn2, sn2_ctxs, false);
}

TEST(ProfileSerializer__should_write_cpu_samples__with_scoped_ctx) {
    TestEnv _;
    BlockingRingBuffer buff(1024 * 1024);
    std::shared_ptr<RawWriter> raw_w_ptr(new AccumulatingRawWriter(buff));
    Buff pw_buff;
    ProfileWriter pw(raw_w_ptr, pw_buff);

    method_lookup_stub.clear();
    line_no_lookup_stub.clear();

    std::int64_t y = 4, c = 3;
    std::int64_t bt_pusher = 1, fn_corge = 2;
    stub(method_lookup_stub, line_no_lookup_stub, y, "x/Y.class", "x.Y", "fn_y", "(I)J");
    stub(method_lookup_stub, line_no_lookup_stub, c, "x/C.class", "x.C", "fn_c", "(F)I");

    PerfCtx::Registry reg;
    auto to_parent_semantic = static_cast<std::uint8_t>(PerfCtx::MergeSemantic::to_parent);
    auto scoped_semantic = static_cast<std::uint8_t>(PerfCtx::MergeSemantic::scoped);
    auto ctx_foo = reg.find_or_bind("foo", 20, to_parent_semantic);
    auto ctx_bar = reg.find_or_bind("bar", 30, scoped_semantic);
    
    ProbPct ppct;
    prob_pct = &ppct;
    ctx_reg = &reg;

    jvmtiEnv* ti = nullptr;

    SerializationFlushThresholds sft;
    TruncationThresholds tts(7);
    ProfileSerializingWriter ps(ti, pw, test_mthd_info_resolver, test_line_no_resolver, reg, sft, tts, 0);

    CircularQueue q(ps, 10);
    
    //const JVMPI_CallTrace item, ThreadBucket *info = nullptr, std::uint8_t ctx_len = 0, PerfCtx::ThreadTracker::EffectiveCtx* ctx = nullptr

    STATIC_ARRAY(frames, JVMPI_CallFrame, 7, 7);
    JVMPI_CallTrace ct;
    ct.frames = frames;

    frames[0].method_id = mid(c);
    frames[0].lineno = 10;
    frames[1].method_id = mid(y);
    frames[1].lineno = 20;
    ct.num_frames = 2;

    ThreadBucket t25(25, "some thread", 8, false);
    t25.ctx_tracker.enter(ctx_foo);
    t25.ctx_tracker.enter(ctx_bar);
    push_native_backtrace(ThreadBucket::acq_bucket(&t25), BacktraceError::Fkp_no_error, q, false, 0);
    q.push(ct, BacktraceError::Fkp_no_error, false, ThreadBucket::acq_bucket(&t25));
    t25.ctx_tracker.exit(ctx_bar);
    t25.ctx_tracker.exit(ctx_foo);

    frames[0].method_id = mid(y);
    frames[0].lineno = 10;
    frames[1].method_id = mid(c);
    frames[1].lineno = 20;
    ct.num_frames = 2;

    t25.ctx_tracker.enter(ctx_bar);
    t25.ctx_tracker.enter(ctx_foo);
    q.push(ct, BacktraceError::Fkp_no_error, false, ThreadBucket::acq_bucket(&t25));
    t25.ctx_tracker.exit(ctx_foo);
    t25.ctx_tracker.exit(ctx_bar);

    CHECK(q.pop());
    CHECK(q.pop());
    CHECK(q.pop());
    CHECK(! q.pop());//because only 3 samples were pushed

    ps.flush();

    std::shared_ptr<std::uint8_t> tmp_buff(new std::uint8_t[1024 * 1024], std::default_delete<std::uint8_t[]>());
    auto bytes_sz = buff.read(tmp_buff.get(), 0, 1024 * 1024, false);
    CHECK(bytes_sz > 0);

    google::protobuf::io::CodedInputStream cis(tmp_buff.get(), bytes_sz);

    std::uint32_t len;
    CHECK(cis.ReadVarint32(&len));

    auto lim = cis.PushLimit(len);

    recording::Wse wse;
    CHECK(wse.ParseFromCodedStream(&cis));

    cis.PopLimit(lim);

    CHECK_EQUAL(recording::WorkType::cpu_sample_work, wse.w_type());
    auto idx_data = wse.indexed_data();
    CHECK_EQUAL(0, idx_data.monitor_info_size());
    
    CHECK_EQUAL(1, idx_data.thread_info_size());
    ASSERT_THREAD_INFO_IS(idx_data.thread_info(0), 3, "some thread", 8, false, 25);

    CHECK_EQUAL(5, idx_data.method_info_size());
    ASSERT_METHOD_INFO_IS(idx_data.method_info(0), 0, "?", "?", "?", "?", Java_Sym);
    auto test_executable = my_executable();
    auto test_helper_lib = my_test_helper_lib();
    ASSERT_METHOD_INFO_IS(idx_data.method_info(1), bt_pusher, test_executable, "", "bt_pusher()", "", Native_Sym);
    ASSERT_METHOD_INFO_IS(idx_data.method_info(2), fn_corge, test_helper_lib, "", "fn_corge(int, int, int)", "", Native_Sym);
    ASSERT_METHOD_INFO_IS(idx_data.method_info(3), c, "x/C.class", "x.C", "fn_c", "(F)I", Java_Sym);
    ASSERT_METHOD_INFO_IS(idx_data.method_info(4), y, "x/Y.class", "x.Y", "fn_y", "(I)J", Java_Sym);

    CHECK_EQUAL(4, idx_data.trace_ctx_size());
    ASSERT_TRACE_CTX_INFO_IS(idx_data.trace_ctx(0), 0, DEFAULT_CTX_NAME, 0, 0, false);
    ASSERT_TRACE_CTX_INFO_IS(idx_data.trace_ctx(1), 1, UNKNOWN_CTX_NAME, 0, 0, true);
    ASSERT_TRACE_CTX_INFO_IS(idx_data.trace_ctx(2), 5, "foo > bar", 0, 0, true);
    ASSERT_TRACE_CTX_INFO_IS(idx_data.trace_ctx(3), 6, "bar", 30, 1, false);

    auto cse = wse.cpu_sample_entry();

    CHECK_EQUAL(3, cse.stack_sample_size());
    auto s1n = {bt_pusher, fn_corge};
    auto s1n_ctxs = {5};
    ASSERT_NATIVE_STACK_SAMPLE_IS(cse.stack_sample(0), 0, 3, s1n, s1n_ctxs, false); //TODO: fix this to actually record time-offset, right now we are using zero
    auto s1 = {fr(c, 10, 1), fr(y, 20, 2)};
    auto s1_ctxs = {5};
    ASSERT_STACK_SAMPLE_IS(cse.stack_sample(1), 0, 3, s1, s1_ctxs, false);
    auto s2 = {fr(y, 10, 1), fr(c, 20, 2)};
    auto s2_ctxs = {6};
    ASSERT_STACK_SAMPLE_IS(cse.stack_sample(2), 0, 3, s2, s2_ctxs, false);
}


TEST(ProfileSerializer__should_auto_flush__at_buffering_threshold) {
    TestEnv _;
    BlockingRingBuffer buff(1024 * 1024);
    std::shared_ptr<RawWriter> raw_w_ptr(new AccumulatingRawWriter(buff));
    Buff pw_buff;
    ProfileWriter pw(raw_w_ptr, pw_buff);

    method_lookup_stub.clear();
    line_no_lookup_stub.clear();

    std::int64_t y = 2, c = 1;
    std::int64_t bt_pusher = 3, fn_corge = 4;
    stub(method_lookup_stub, line_no_lookup_stub, y, "x/Y.class", "x.Y", "fn_y", "(I)J");
    stub(method_lookup_stub, line_no_lookup_stub, c, "x/C.class", "x.C", "fn_c", "(F)I");

    PerfCtx::Registry reg;
    auto to_parent_semantic = static_cast<std::uint8_t>(PerfCtx::MergeSemantic::to_parent);
    auto ctx_foo = reg.find_or_bind("foo", 20, to_parent_semantic);
    
    ProbPct ppct;
    prob_pct = &ppct;
    ctx_reg = &reg;

    jvmtiEnv* ti = nullptr;

    SerializationFlushThresholds sft;
    sft.cpu_samples = 10;
    TruncationThresholds tts(7);
    ProfileSerializingWriter ps(ti, pw, test_mthd_info_resolver, test_line_no_resolver, reg, sft, tts, 0);

    CircularQueue q(ps, 10);
    
    STATIC_ARRAY(frames, JVMPI_CallFrame, 7, 7);
    JVMPI_CallTrace ct;
    ct.frames = frames;

    frames[0].method_id = mid(c);
    frames[0].lineno = 10;
    frames[1].method_id = mid(y);
    frames[1].lineno = 20;
    ct.num_frames = 2;

    ThreadBucket t25(25, "some thread", 8, false);
    t25.ctx_tracker.enter(ctx_foo);
    for (auto i = 0; i < 10; i++) {
        if (i < 5) {
            q.push(ct, BacktraceError::Fkp_no_error, false, ThreadBucket::acq_bucket(&t25));
        } else {
            push_native_backtrace(ThreadBucket::acq_bucket(&t25), BacktraceError::Fkp_no_error, q, false, 0);
        }
        CHECK(q.pop());

        std::uint8_t tmp;
        CHECK_EQUAL(0, buff.read(&tmp, 0, 1, false));
    }
    q.push(ct, BacktraceError::Fkp_no_error, false, ThreadBucket::acq_bucket(&t25));
    t25.ctx_tracker.exit(ctx_foo);
    CHECK(q.pop());

    const std::size_t one_meg = 1024 * 1024;
    std::shared_ptr<std::uint8_t> tmp_buff(new std::uint8_t[one_meg], std::default_delete<std::uint8_t[]>());
    auto bytes_sz = buff.read(tmp_buff.get(), 0, one_meg, false);
    CHECK(bytes_sz > 0);
    CHECK(bytes_sz < one_meg);

    google::protobuf::io::CodedInputStream cis(tmp_buff.get(), bytes_sz);

    std::uint32_t len;
    CHECK(cis.ReadVarint32(&len));

    auto lim = cis.PushLimit(len);

    recording::Wse wse;
    CHECK(wse.ParseFromCodedStream(&cis));

    cis.PopLimit(lim);

    CHECK_EQUAL(recording::WorkType::cpu_sample_work, wse.w_type());
    auto idx_data = wse.indexed_data();
    CHECK_EQUAL(0, idx_data.monitor_info_size());
    
    CHECK_EQUAL(1, idx_data.thread_info_size());
    ASSERT_THREAD_INFO_IS(idx_data.thread_info(0), 3, "some thread", 8, false, 25);

    CHECK_EQUAL(5, idx_data.method_info_size());
    ASSERT_METHOD_INFO_IS(idx_data.method_info(0), 0, "?", "?", "?", "?", Java_Sym);
    ASSERT_METHOD_INFO_IS(idx_data.method_info(1), c, "x/C.class", "x.C", "fn_c", "(F)I", Java_Sym);
    ASSERT_METHOD_INFO_IS(idx_data.method_info(2), y, "x/Y.class", "x.Y", "fn_y", "(I)J", Java_Sym);
    auto test_executable = my_executable();
    auto test_helper_lib = my_test_helper_lib();
    ASSERT_METHOD_INFO_IS(idx_data.method_info(3), bt_pusher, test_executable, "", "bt_pusher()", "", Native_Sym);
    ASSERT_METHOD_INFO_IS(idx_data.method_info(4), fn_corge, test_helper_lib, "", "fn_corge(int, int, int)", "", Native_Sym);

    CHECK_EQUAL(3, idx_data.trace_ctx_size());
    ASSERT_TRACE_CTX_INFO_IS(idx_data.trace_ctx(0), 0, DEFAULT_CTX_NAME, 0, 0, false);
    ASSERT_TRACE_CTX_INFO_IS(idx_data.trace_ctx(1), 1, UNKNOWN_CTX_NAME, 0, 0, true);
    ASSERT_TRACE_CTX_INFO_IS(idx_data.trace_ctx(2), 5, "foo", 20, 0, false);

    auto cse = wse.cpu_sample_entry();
    CHECK_EQUAL(10, cse.stack_sample_size());

    auto s1 = {fr(c, 10, 1), fr(y, 20, 2)};
    auto ctxs = {5};
    auto s1n = {bt_pusher, fn_corge};
    for (auto j = 0; j < 10; j++) {
        auto& stack_sample = cse.stack_sample(j);
        if (j < 5) {
            ASSERT_STACK_SAMPLE_IS(stack_sample, 0, 3, s1, ctxs, false);
        } else {
            ASSERT_NATIVE_STACK_SAMPLE_IS(stack_sample, 0, 3, s1n, ctxs, false);
        }
    }
}

TEST(ProfileSerializer__should_auto_flush_correctly__after_first_flush___and_should_incrementally_push___index_data_mapping) {
    TestEnv _;
    BlockingRingBuffer buff(1024 * 1024);
    std::shared_ptr<RawWriter> raw_w_ptr(new AccumulatingRawWriter(buff));
    Buff pw_buff;
    ProfileWriter pw(raw_w_ptr, pw_buff);

    method_lookup_stub.clear();
    line_no_lookup_stub.clear();

    std::int64_t y = 2, c = 1, d = 3;
    stub(method_lookup_stub, line_no_lookup_stub, y, "x/Y.class", "x.Y", "fn_y", "(I)J");
    stub(method_lookup_stub, line_no_lookup_stub, c, "x/C.class", "x.C", "fn_c", "(F)I");
    stub(method_lookup_stub, line_no_lookup_stub, d, "x/D.class", "x.D", "fn_d", "(J)I");

    PerfCtx::Registry reg;
    auto to_parent_semantic = static_cast<std::uint8_t>(PerfCtx::MergeSemantic::to_parent);
    auto ctx_foo = reg.find_or_bind("foo", 20, to_parent_semantic);
    auto ctx_bar = reg.find_or_bind("bar", 30, to_parent_semantic);
    
    ProbPct ppct;
    prob_pct = &ppct;
    ctx_reg = &reg;

    jvmtiEnv* ti = nullptr;

    SerializationFlushThresholds sft;
    sft.cpu_samples = 10;
    TruncationThresholds tts(7);
    ProfileSerializingWriter ps(ti, pw, test_mthd_info_resolver, test_line_no_resolver, reg, sft, tts, 0);

    CircularQueue q(ps, 10);
    
    STATIC_ARRAY(frames0, JVMPI_CallFrame, 7, 7);
    JVMPI_CallTrace ct0;

    frames0[0].method_id = mid(c);
    frames0[0].lineno = 10;
    frames0[1].method_id = mid(y);
    frames0[1].lineno = 20;
    ct0.frames = frames0;
    ct0.num_frames = 2;

    STATIC_ARRAY(frames1, JVMPI_CallFrame, 7, 7);
    JVMPI_CallTrace ct1;
    frames1[0].method_id = mid(d);
    frames1[0].lineno = 10;
    frames1[1].method_id = mid(y);
    frames1[1].lineno = 20;
    ct1.frames = frames1;
    ct1.num_frames = 2;

    ThreadBucket t25(25, "some thread", 8, false);
    ThreadBucket t10(10, "some other thread", 6, true);
    t25.ctx_tracker.enter(ctx_foo);
    t10.ctx_tracker.enter(ctx_bar);
    for (auto i = 0; i < 26; i++) {
        if (i == 15) {
            ps.flush();//check manual flush interleving
        }
        if (i < 15) {
            q.push(ct0, BacktraceError::Fkp_no_error, false, ThreadBucket::acq_bucket(&t25));
        } else {
            q.push(ct1, BacktraceError::Fkp_no_error, false, ThreadBucket::acq_bucket(&t10));
        }
        CHECK(q.pop());
    }
    t25.ctx_tracker.exit(ctx_foo);
    t10.ctx_tracker.exit(ctx_bar);

    const std::size_t one_meg = 1024 * 1024;
    std::shared_ptr<std::uint8_t> tmp_buff(new std::uint8_t[one_meg], std::default_delete<std::uint8_t[]>());
    auto bytes_sz = buff.read(tmp_buff.get(), 0, one_meg, false);
    CHECK(bytes_sz > 0);
    CHECK(bytes_sz < one_meg);

    google::protobuf::io::CodedInputStream cis(tmp_buff.get(), bytes_sz);

    std::uint32_t len;
    std::uint32_t csum;
    Checksum c_calc;
    recording::Wse wse0, wse1, wse2;

    CHECK(cis.ReadVarint32(&len));
    auto lim = cis.PushLimit(len);
    CHECK(wse0.ParseFromCodedStream(&cis));
    cis.PopLimit(lim);
    auto pos = cis.CurrentPosition();
    CHECK(cis.ReadVarint32(&csum));
    auto computed_csum = c_calc.chksum(tmp_buff.get(), pos);
    CHECK_EQUAL(computed_csum, csum);
    auto next_record_start = cis.CurrentPosition();

    CHECK(cis.ReadVarint32(&len));
    lim = cis.PushLimit(len);
    CHECK(wse1.ParseFromCodedStream(&cis));
    cis.PopLimit(lim);
    pos = cis.CurrentPosition();
    CHECK(cis.ReadVarint32(&csum));
    c_calc.reset();
    computed_csum = c_calc.chksum(tmp_buff.get() + next_record_start, pos - next_record_start);
    CHECK_EQUAL(computed_csum, csum);
    next_record_start = cis.CurrentPosition();

    CHECK(cis.ReadVarint32(&len));
    lim = cis.PushLimit(len);
    CHECK(wse2.ParseFromCodedStream(&cis));
    cis.PopLimit(lim);
    pos = cis.CurrentPosition();
    CHECK(cis.ReadVarint32(&csum));
    c_calc.reset();
    computed_csum = c_calc.chksum(tmp_buff.get() + next_record_start, pos - next_record_start);
    CHECK_EQUAL(computed_csum, csum);

    CHECK_EQUAL(recording::WorkType::cpu_sample_work, wse0.w_type());
    CHECK_EQUAL(recording::WorkType::cpu_sample_work, wse1.w_type());
    CHECK_EQUAL(recording::WorkType::cpu_sample_work, wse2.w_type());

    auto idx_data0 = wse0.indexed_data();
    CHECK_EQUAL(0, idx_data0.monitor_info_size());
    
    CHECK_EQUAL(1, idx_data0.thread_info_size());
    ASSERT_THREAD_INFO_IS(idx_data0.thread_info(0), 3, "some thread", 8, false, 25);

    CHECK_EQUAL(3, idx_data0.method_info_size());
    ASSERT_METHOD_INFO_IS(idx_data0.method_info(0), 0, "?", "?", "?", "?", Java_Sym);
    ASSERT_METHOD_INFO_IS(idx_data0.method_info(1), c, "x/C.class", "x.C", "fn_c", "(F)I", Java_Sym);
    ASSERT_METHOD_INFO_IS(idx_data0.method_info(2), y, "x/Y.class", "x.Y", "fn_y", "(I)J", Java_Sym);

    CHECK_EQUAL(3, idx_data0.trace_ctx_size());
    ASSERT_TRACE_CTX_INFO_IS(idx_data0.trace_ctx(0), 0, DEFAULT_CTX_NAME, 0, 0, false);
    ASSERT_TRACE_CTX_INFO_IS(idx_data0.trace_ctx(1), 1, UNKNOWN_CTX_NAME, 0, 0, true);
    ASSERT_TRACE_CTX_INFO_IS(idx_data0.trace_ctx(2), 5, "foo", 20, 0, false);

    auto cse0 = wse0.cpu_sample_entry();
    CHECK_EQUAL(10, cse0.stack_sample_size());

    auto s0 = {fr(c, 10, 1), fr(y, 20, 2)};
    auto s0_ctxs = {5};
    for (auto i = 0; i < 10; i++) {
        ASSERT_STACK_SAMPLE_IS(cse0.stack_sample(i), 0, 3, s0, s0_ctxs, false);
    }

    auto idx_data1 = wse1.indexed_data();
    CHECK_EQUAL(0, idx_data1.monitor_info_size());
    CHECK_EQUAL(0, idx_data1.thread_info_size());
    CHECK_EQUAL(0, idx_data1.method_info_size());
    CHECK_EQUAL(0, idx_data1.trace_ctx_size());
    auto cse1 = wse1.cpu_sample_entry();

    CHECK_EQUAL(5, cse1.stack_sample_size());
    for (auto i = 0; i < 5; i++) {
        ASSERT_STACK_SAMPLE_IS(cse1.stack_sample(i), 0, 3, s0, s0_ctxs, false);
    }

    auto idx_data2 = wse2.indexed_data();
    CHECK_EQUAL(0, idx_data2.monitor_info_size());
    
    CHECK_EQUAL(1, idx_data2.thread_info_size());
    ASSERT_THREAD_INFO_IS(idx_data2.thread_info(0), 4, "some other thread", 6, true, 10);

    CHECK_EQUAL(1, idx_data2.method_info_size());
    ASSERT_METHOD_INFO_IS(idx_data2.method_info(0), d, "x/D.class", "x.D", "fn_d", "(J)I", Java_Sym);

    CHECK_EQUAL(1, idx_data2.trace_ctx_size());
    ASSERT_TRACE_CTX_INFO_IS(idx_data2.trace_ctx(0), 6, "bar", 30, 0, false);

    auto cse2 = wse2.cpu_sample_entry();
    CHECK_EQUAL(10, cse2.stack_sample_size());

    auto s1 = {fr(d, 10, 1), fr(y, 20, 2)};
    auto s1_ctxs = {6};
    for (auto i = 0; i < 10; i++) {
        ASSERT_STACK_SAMPLE_IS(cse2.stack_sample(i), 0, 4, s1, s1_ctxs, false);
    }
}

TEST(ProfileSerializer__should_auto_flush_correctly__after_first_flush___and_should_incrementally_push___index_data_mapping_for_native_frames_too) {
    TestEnv _;
    BlockingRingBuffer buff(1024 * 1024);
    std::shared_ptr<RawWriter> raw_w_ptr(new AccumulatingRawWriter(buff));
    Buff pw_buff;
    ProfileWriter pw(raw_w_ptr, pw_buff);

    method_lookup_stub.clear();
    line_no_lookup_stub.clear();

    std::int64_t bt_pusher = 1, fn_quux = 3, fn_corge = 2;

    PerfCtx::Registry reg;
    auto to_parent_semantic = static_cast<std::uint8_t>(PerfCtx::MergeSemantic::to_parent);
    auto ctx_foo = reg.find_or_bind("foo", 20, to_parent_semantic);
    auto ctx_bar = reg.find_or_bind("bar", 30, to_parent_semantic);

    ProbPct ppct;
    prob_pct = &ppct;
    ctx_reg = &reg;

    jvmtiEnv* ti = nullptr;

    SerializationFlushThresholds sft;
    sft.cpu_samples = 10;
    TruncationThresholds tts(7);
    ProfileSerializingWriter ps(ti, pw, test_mthd_info_resolver, test_line_no_resolver, reg, sft, tts, 0);

    CircularQueue q(ps, 10);

    ThreadBucket t25(25, "some thread", 8, false);
    ThreadBucket t10(10, "some other thread", 6, true);
    t25.ctx_tracker.enter(ctx_foo);
    t10.ctx_tracker.enter(ctx_bar);
    for (auto i = 0; i < 26; i++) {
        if (i == 15) {
            ps.flush();//check manual flush interleving
        }
        if (i < 15) {
            push_native_backtrace(ThreadBucket::acq_bucket(&t25), BacktraceError::Fkp_no_error, q, false, 0);
        } else {
            push_native_backtrace(ThreadBucket::acq_bucket(&t10), BacktraceError::Fkp_no_error, q, false, 1);
        }
        CHECK(q.pop());
    }
    t25.ctx_tracker.exit(ctx_foo);
    t10.ctx_tracker.exit(ctx_bar);

    const std::size_t one_meg = 1024 * 1024;
    std::shared_ptr<std::uint8_t> tmp_buff(new std::uint8_t[one_meg], std::default_delete<std::uint8_t[]>());
    auto bytes_sz = buff.read(tmp_buff.get(), 0, one_meg, false);
    CHECK(bytes_sz > 0);
    CHECK(bytes_sz < one_meg);

    google::protobuf::io::CodedInputStream cis(tmp_buff.get(), bytes_sz);

    std::uint32_t len;
    std::uint32_t csum;
    Checksum c_calc;
    recording::Wse wse0, wse1, wse2;

    CHECK(cis.ReadVarint32(&len));
    auto lim = cis.PushLimit(len);
    CHECK(wse0.ParseFromCodedStream(&cis));
    cis.PopLimit(lim);
    auto pos = cis.CurrentPosition();
    CHECK(cis.ReadVarint32(&csum));
    auto computed_csum = c_calc.chksum(tmp_buff.get(), pos);
    CHECK_EQUAL(computed_csum, csum);
    auto next_record_start = cis.CurrentPosition();

    CHECK(cis.ReadVarint32(&len));
    lim = cis.PushLimit(len);
    CHECK(wse1.ParseFromCodedStream(&cis));
    cis.PopLimit(lim);
    pos = cis.CurrentPosition();
    CHECK(cis.ReadVarint32(&csum));
    c_calc.reset();
    computed_csum = c_calc.chksum(tmp_buff.get() + next_record_start, pos - next_record_start);
    CHECK_EQUAL(computed_csum, csum);
    next_record_start = cis.CurrentPosition();

    CHECK(cis.ReadVarint32(&len));
    lim = cis.PushLimit(len);
    CHECK(wse2.ParseFromCodedStream(&cis));
    cis.PopLimit(lim);
    pos = cis.CurrentPosition();
    CHECK(cis.ReadVarint32(&csum));
    c_calc.reset();
    computed_csum = c_calc.chksum(tmp_buff.get() + next_record_start, pos - next_record_start);
    CHECK_EQUAL(computed_csum, csum);

    CHECK_EQUAL(recording::WorkType::cpu_sample_work, wse0.w_type());
    CHECK_EQUAL(recording::WorkType::cpu_sample_work, wse1.w_type());
    CHECK_EQUAL(recording::WorkType::cpu_sample_work, wse2.w_type());

    auto idx_data0 = wse0.indexed_data();
    CHECK_EQUAL(0, idx_data0.monitor_info_size());

    CHECK_EQUAL(1, idx_data0.thread_info_size());
    ASSERT_THREAD_INFO_IS(idx_data0.thread_info(0), 3, "some thread", 8, false, 25);

    CHECK_EQUAL(3, idx_data0.method_info_size());
    ASSERT_METHOD_INFO_IS(idx_data0.method_info(0), 0, "?", "?", "?", "?", Java_Sym);
    auto test_executable = my_executable();
    auto test_helper_lib = my_test_helper_lib();
    ASSERT_METHOD_INFO_IS(idx_data0.method_info(1), bt_pusher, test_executable, "", "bt_pusher()", "", Native_Sym);
    ASSERT_METHOD_INFO_IS(idx_data0.method_info(2), fn_corge, test_helper_lib, "", "fn_corge(int, int, int)", "", Native_Sym);

    CHECK_EQUAL(3, idx_data0.trace_ctx_size());
    ASSERT_TRACE_CTX_INFO_IS(idx_data0.trace_ctx(0), 0, DEFAULT_CTX_NAME, 0, 0, false);
    ASSERT_TRACE_CTX_INFO_IS(idx_data0.trace_ctx(1), 1, UNKNOWN_CTX_NAME, 0, 0, true);
    ASSERT_TRACE_CTX_INFO_IS(idx_data0.trace_ctx(2), 5, "foo", 20, 0, false);

    auto cse0 = wse0.cpu_sample_entry();
    CHECK_EQUAL(10, cse0.stack_sample_size());

    auto s0 = {bt_pusher, fn_corge};
    auto s0_ctxs = {5};
    for (auto j = 0; j < 10; j++) {
        ASSERT_NATIVE_STACK_SAMPLE_IS(cse0.stack_sample(j), 0, 3, s0, s0_ctxs, false);
    }

    auto idx_data1 = wse1.indexed_data();
    CHECK_EQUAL(0, idx_data1.monitor_info_size());
    CHECK_EQUAL(0, idx_data1.thread_info_size());
    CHECK_EQUAL(0, idx_data1.method_info_size());
    CHECK_EQUAL(0, idx_data1.trace_ctx_size());
    auto cse1 = wse1.cpu_sample_entry();

    CHECK_EQUAL(5, cse1.stack_sample_size());
    for (auto j = 0; j < 5; j++) {
        ASSERT_NATIVE_STACK_SAMPLE_IS(cse0.stack_sample(j), 0, 3, s0, s0_ctxs, false);
    }

    auto idx_data2 = wse2.indexed_data();
    CHECK_EQUAL(0, idx_data2.monitor_info_size());

    CHECK_EQUAL(1, idx_data2.thread_info_size());
    ASSERT_THREAD_INFO_IS(idx_data2.thread_info(0), 4, "some other thread", 6, true, 10);

    CHECK_EQUAL(1, idx_data2.method_info_size());
    ASSERT_METHOD_INFO_IS(idx_data2.method_info(0), fn_quux, test_helper_lib, "", "fn_quux(int, int)", "", Native_Sym);

    CHECK_EQUAL(1, idx_data2.trace_ctx_size());
    ASSERT_TRACE_CTX_INFO_IS(idx_data2.trace_ctx(0), 6, "bar", 30, 0, false);

    auto cse2 = wse2.cpu_sample_entry();
    CHECK_EQUAL(10, cse2.stack_sample_size());

    auto s1 = {bt_pusher, fn_quux, fn_corge};
    auto s1_ctxs = {6};
    for (auto j = 0; j < 10; j++) {
        ASSERT_NATIVE_STACK_SAMPLE_IS(cse2.stack_sample(j), 0, 4, s1, s1_ctxs, false);
    }
}

TEST(ProfileSerializer__should_write_cpu_samples__with_forte_error) {
    TestEnv _;
    BlockingRingBuffer buff(1024 * 1024);
    std::shared_ptr<RawWriter> raw_w_ptr(new AccumulatingRawWriter(buff));
    Buff pw_buff;
    ProfileWriter pw(raw_w_ptr, pw_buff);

    method_lookup_stub.clear();
    line_no_lookup_stub.clear();

    PerfCtx::Registry reg;
    
    ProbPct ppct;
    prob_pct = &ppct;
    ctx_reg = &reg;

    jvmtiEnv* ti = nullptr;

    SerializationFlushThresholds sft;
    TruncationThresholds tts(7);
    ProfileSerializingWriter ps(ti, pw, test_mthd_info_resolver, test_line_no_resolver, reg, sft, tts, 0);

    CircularQueue q(ps, 10);
    
    //const JVMPI_CallTrace item, ThreadBucket *info = nullptr, std::uint8_t ctx_len = 0, PerfCtx::ThreadTracker::EffectiveCtx* ctx = nullptr

    std::int64_t bt_pusher = 1, fn_corge = 2;

    STATIC_ARRAY(frames, JVMPI_CallFrame, 7, 7);
    JVMPI_CallTrace ct;
    ct.frames = frames;

    for (auto i = 0; i < 11; i++) {
        ct.num_frames = 0;
        bool default_ctx = ((i % 2) == 0);
        if (i < 5) {
            q.push(ct, static_cast<BacktraceError>(i), default_ctx, nullptr);
        } else {
            push_native_backtrace(nullptr, static_cast<BacktraceError>(i), q, default_ctx, 0);
        }
        CHECK(q.pop());
    }

    ps.flush();

    std::shared_ptr<std::uint8_t> tmp_buff(new std::uint8_t[1024 * 1024], std::default_delete<std::uint8_t[]>());
    auto bytes_sz = buff.read(tmp_buff.get(), 0, 1024 * 1024, false);
    CHECK(bytes_sz > 0);

    google::protobuf::io::CodedInputStream cis(tmp_buff.get(), bytes_sz);

    std::uint32_t len;
    CHECK(cis.ReadVarint32(&len));

    auto lim = cis.PushLimit(len);

    recording::Wse wse;
    CHECK(wse.ParseFromCodedStream(&cis));

    cis.PopLimit(lim);

    CHECK_EQUAL(recording::WorkType::cpu_sample_work, wse.w_type());
    auto idx_data = wse.indexed_data();
    CHECK_EQUAL(0, idx_data.monitor_info_size());
    CHECK_EQUAL(0, idx_data.thread_info_size());
    CHECK_EQUAL(3, idx_data.method_info_size());
    ASSERT_METHOD_INFO_IS(idx_data.method_info(0), 0, "?", "?", "?", "?", Java_Sym);
    auto test_executable = my_executable();
    auto test_helper_lib = my_test_helper_lib();
    ASSERT_METHOD_INFO_IS(idx_data.method_info(1), bt_pusher, test_executable, "", "bt_pusher()", "", Native_Sym);
    ASSERT_METHOD_INFO_IS(idx_data.method_info(2), fn_corge, test_helper_lib, "", "fn_corge(int, int, int)", "", Native_Sym);

    CHECK_EQUAL(2, idx_data.trace_ctx_size());
    ASSERT_TRACE_CTX_INFO_IS(idx_data.trace_ctx(0), 0, DEFAULT_CTX_NAME, 0, 0, false);
    ASSERT_TRACE_CTX_INFO_IS(idx_data.trace_ctx(1), 1, UNKNOWN_CTX_NAME, 0, 0, true);

    auto cse = wse.cpu_sample_entry();

    CHECK_EQUAL(11, cse.stack_sample_size());
    for (auto j = 0; j < 11; j++) {
        auto ss = cse.stack_sample(j);
        CHECK_EQUAL(false, ss.has_thread_id());
        if (j < 5) {
            CHECK_EQUAL(0, ss.frame_size());
        } else {
            CHECK_EQUAL(2, ss.frame_size());
            CHECK_EQUAL(bt_pusher, ss.frame(0).method_id());
            CHECK_EQUAL(fn_corge, ss.frame(1).method_id());
        }
        CHECK_EQUAL(1, ss.trace_id_size());
        CHECK_EQUAL((j % 2 == 0) ? DEFAULT_CTX_ID : UNKNOWN_CTX_ID, ss.trace_id(0));
        CHECK_EQUAL(static_cast<recording::StackSample::Error>(j), ss.error());
    }
}

TEST(ProfileSerializer__should_snip_short__very_long_cpu_sample_backtraces) {
    TestEnv _;
    BlockingRingBuffer buff(1024 * 1024);
    std::shared_ptr<RawWriter> raw_w_ptr(new AccumulatingRawWriter(buff));
    Buff pw_buff;
    ProfileWriter pw(raw_w_ptr, pw_buff);

    method_lookup_stub.clear();
    line_no_lookup_stub.clear();
    
    std::int64_t y = 7, c = 2, d = 1;
    std::int64_t bt_pusher = 3, fn_c_foo = 4, fn_bar = 5, fn_baz = 6;
    stub(method_lookup_stub, line_no_lookup_stub, y, "x/Y.class", "x.Y", "fn_y", "(I)J");
    stub(method_lookup_stub, line_no_lookup_stub, c, "x/C.class", "x.C", "fn_c", "(F)I");
    stub(method_lookup_stub, line_no_lookup_stub, d, "x/D.class", "x.D", "fn_d", "(J)I");

    PerfCtx::Registry reg;
    auto to_parent_semantic = static_cast<std::uint8_t>(PerfCtx::MergeSemantic::to_parent);
    auto ctx_foo = reg.find_or_bind("foo", 20, to_parent_semantic);
    
    ProbPct ppct;
    prob_pct = &ppct;
    ctx_reg = &reg;

    jvmtiEnv* ti = nullptr;

    SerializationFlushThresholds sft;
    TruncationThresholds tts(4);
    ProfileSerializingWriter ps(ti, pw, test_mthd_info_resolver, test_line_no_resolver, reg, sft, tts, 0);

    CircularQueue q(ps, 10);
    
    STATIC_ARRAY(frames, JVMPI_CallFrame, 7, 7);
    JVMPI_CallTrace ct;
    ct.frames = frames;

    frames[0].method_id = mid(d);
    frames[0].lineno = 10;
    frames[1].method_id = mid(c);
    frames[1].lineno = 10;
    frames[2].method_id = mid(d);
    frames[2].lineno = 20;
    frames[3].method_id = mid(c);
    frames[3].lineno = 20;
    frames[4].method_id = mid(y);
    frames[4].lineno = 30;
    ct.num_frames = 5;

    ThreadBucket t25(25, "Thread No. 25", 5, true);
    t25.ctx_tracker.enter(ctx_foo);
    q.push(ct, BacktraceError::Fkp_no_error, false, ThreadBucket::acq_bucket(&t25));
    push_native_backtrace(ThreadBucket::acq_bucket(&t25), BacktraceError::Fkp_no_error, q);//default is 6 frames
    t25.ctx_tracker.exit(ctx_foo);

    CHECK(q.pop());
    CHECK(q.pop());
    CHECK(! q.pop());//because only 1 sample was pushed

    ps.flush();

    std::shared_ptr<std::uint8_t> tmp_buff(new std::uint8_t[1024 * 1024], std::default_delete<std::uint8_t[]>());
    auto bytes_sz = buff.read(tmp_buff.get(), 0, 1024 * 1024, false);
    CHECK(bytes_sz > 0);

    google::protobuf::io::CodedInputStream cis(tmp_buff.get(), bytes_sz);

    std::uint32_t len;
    CHECK(cis.ReadVarint32(&len));

    auto lim = cis.PushLimit(len);

    recording::Wse wse;
    CHECK(wse.ParseFromCodedStream(&cis));

    cis.PopLimit(lim);

    auto pos = cis.CurrentPosition();

    std::uint32_t csum;
    CHECK(cis.ReadVarint32(&csum));

    Checksum c_calc;
    auto computed_csum = c_calc.chksum(tmp_buff.get(), pos);

    CHECK_EQUAL(computed_csum, csum);

    CHECK_EQUAL(recording::WorkType::cpu_sample_work, wse.w_type());
    auto idx_data = wse.indexed_data();
    CHECK_EQUAL(0, idx_data.monitor_info_size());
    
    CHECK_EQUAL(1, idx_data.thread_info_size());
    ASSERT_THREAD_INFO_IS(idx_data.thread_info(0), 3, "Thread No. 25", 5, true, 25);

    CHECK_EQUAL(7, idx_data.method_info_size());
    ASSERT_METHOD_INFO_IS(idx_data.method_info(0), 0, "?", "?", "?", "?", Java_Sym);
    ASSERT_METHOD_INFO_IS(idx_data.method_info(1), d, "x/D.class", "x.D", "fn_d", "(J)I", Java_Sym);
    ASSERT_METHOD_INFO_IS(idx_data.method_info(2), c, "x/C.class", "x.C", "fn_c", "(F)I", Java_Sym);
    auto test_executable = my_executable();
    auto test_helper_lib = my_test_helper_lib();
    ASSERT_METHOD_INFO_IS(idx_data.method_info(3), bt_pusher, test_executable, "", "bt_pusher()", "", Native_Sym);
    ASSERT_METHOD_INFO_IS(idx_data.method_info(4), fn_c_foo, test_helper_lib, "", "fn_c_foo", "", Native_Sym);
    ASSERT_METHOD_INFO_IS(idx_data.method_info(5), fn_bar, test_helper_lib, "", "Bar::fn_bar(int)", "", Native_Sym);
    ASSERT_METHOD_INFO_IS(idx_data.method_info(6), fn_baz, test_helper_lib, "", "fn_baz(int)", "", Native_Sym);

    CHECK_EQUAL(3, idx_data.trace_ctx_size());
    ASSERT_TRACE_CTX_INFO_IS(idx_data.trace_ctx(0), 0, DEFAULT_CTX_NAME, 0, 0, false);
    ASSERT_TRACE_CTX_INFO_IS(idx_data.trace_ctx(1), 1, UNKNOWN_CTX_NAME, 0, 0, true);
    ASSERT_TRACE_CTX_INFO_IS(idx_data.trace_ctx(2), 5, "foo", 20, 0, false);

    auto cse = wse.cpu_sample_entry();

    CHECK_EQUAL(2, cse.stack_sample_size());
    auto s1 = {fr(d, 10, 1), fr(c, 10, 1), fr(d, 20, 2), fr(c, 20, 2)};
    auto s1_ctxs = {5};
    ASSERT_STACK_SAMPLE_IS(cse.stack_sample(0), 0, 3, s1, s1_ctxs, true); //TODO: fix this to actually record time-offset, right now we are using zero
    auto s1n = {bt_pusher, fn_c_foo, fn_bar, fn_baz};
    ASSERT_NATIVE_STACK_SAMPLE_IS(cse.stack_sample(1), 0, 3, s1n, s1_ctxs, true);
}

void play_last_flush_scenario(recording::Wse& wse1, int additional_traces) {
    BlockingRingBuffer buff(1024 * 1024);
    std::shared_ptr<RawWriter> raw_w_ptr(new AccumulatingRawWriter(buff));
    Buff pw_buff;

    method_lookup_stub.clear();
    line_no_lookup_stub.clear();

    std::int64_t y = 2, c = 1, d = 3;
    stub(method_lookup_stub, line_no_lookup_stub, y, "x/Y.class", "x.Y", "fn_y", "(I)J");
    stub(method_lookup_stub, line_no_lookup_stub, c, "x/C.class", "x.C", "fn_c", "(F)I");
    stub(method_lookup_stub, line_no_lookup_stub, d, "x/D.class", "x.D", "fn_d", "(J)I");

    PerfCtx::Registry reg;
    auto to_parent_semantic = static_cast<std::uint8_t>(PerfCtx::MergeSemantic::to_parent);
    auto ctx_foo = reg.find_or_bind("foo", 20, to_parent_semantic);

    //we create some unused ones, to test they indeed get flushed
    auto duplicate_semantic = static_cast<std::uint8_t>(PerfCtx::MergeSemantic::duplicate);
    auto stack_semantic = static_cast<std::uint8_t>(PerfCtx::MergeSemantic::stack_up);
    auto scoped_semantic = static_cast<std::uint8_t>(PerfCtx::MergeSemantic::scoped);
    auto strict_scoped_semantic = static_cast<std::uint8_t>(PerfCtx::MergeSemantic::scoped_strict);
    reg.find_or_bind("bar", 40, to_parent_semantic);
    reg.find_or_bind("baz", 50, duplicate_semantic);
    reg.find_or_bind("quux", 60, stack_semantic);
    reg.find_or_bind("corge", 70, scoped_semantic);
    reg.find_or_bind("grault", 80, strict_scoped_semantic);

    ProbPct ppct;
    prob_pct = &ppct;
    ctx_reg = &reg;

    jvmtiEnv* ti = nullptr;

    SerializationFlushThresholds sft;
    sft.cpu_samples = 10;
    TruncationThresholds tts(7);

    STATIC_ARRAY(frames0, JVMPI_CallFrame, 7, 7);
    JVMPI_CallTrace ct0;

    frames0[0].method_id = mid(c);
    frames0[0].lineno = 10;
    frames0[1].method_id = mid(y);
    frames0[1].lineno = 20;
    ct0.frames = frames0;
    ct0.num_frames = 2;

    ThreadBucket t25(25, "some thread", 8, false);
    t25.ctx_tracker.enter(ctx_foo);
    {
        //destructor is the cue for EoF
        ProfileWriter pw(raw_w_ptr, pw_buff);

        ProfileSerializingWriter ps(ti, pw, test_mthd_info_resolver, test_line_no_resolver, reg, sft, tts, 0);

        CircularQueue q(ps, 10);

        for (auto i = 0; i < 10 + additional_traces; i++) {
            q.push(ct0, BacktraceError::Fkp_no_error, false, ThreadBucket::acq_bucket(&t25));
            CHECK(q.pop());
        }
        if (additional_traces == 0) {
            ps.flush();
        }
    }
    t25.ctx_tracker.exit(ctx_foo);

    buff.readonly();

    const std::size_t one_meg = 1024 * 1024;
    std::shared_ptr<std::uint8_t> tmp_buff(new std::uint8_t[one_meg], std::default_delete<std::uint8_t[]>());
    auto bytes_sz = buff.read(tmp_buff.get(), 0, one_meg);
    CHECK(bytes_sz > 0);
    CHECK(bytes_sz < one_meg);

    google::protobuf::io::CodedInputStream cis(tmp_buff.get(), bytes_sz);

    std::uint32_t len;
    std::uint32_t csum;
    Checksum c_calc;
    recording::Wse wse0;

    CHECK(cis.ReadVarint32(&len));
    auto lim = cis.PushLimit(len);
    CHECK(wse0.ParseFromCodedStream(&cis));
    cis.PopLimit(lim);
    auto pos = cis.CurrentPosition();
    CHECK(cis.ReadVarint32(&csum));
    auto computed_csum = c_calc.chksum(tmp_buff.get(), pos);
    CHECK_EQUAL(computed_csum, csum);
    auto next_record_start = cis.CurrentPosition();

    CHECK(cis.ReadVarint32(&len));
    lim = cis.PushLimit(len);
    CHECK(wse1.ParseFromCodedStream(&cis));
    cis.PopLimit(lim);
    pos = cis.CurrentPosition();
    CHECK(cis.ReadVarint32(&csum));
    c_calc.reset();
    computed_csum = c_calc.chksum(tmp_buff.get() + next_record_start, pos - next_record_start);
    CHECK_EQUAL(computed_csum, csum);
    //next_record_start = cis.CurrentPosition();// last one anyway, not required

    CHECK(cis.ReadVarint32(&len));
    CHECK_EQUAL(cis.CurrentPosition(), bytes_sz);
    CHECK_EQUAL(0, len);//EOF marker

    CHECK_EQUAL(recording::WorkType::cpu_sample_work, wse0.w_type());
    CHECK_EQUAL(recording::WorkType::cpu_sample_work, wse1.w_type());

    auto idx_data0 = wse0.indexed_data();
    CHECK_EQUAL(0, idx_data0.monitor_info_size());

    CHECK_EQUAL(1, idx_data0.thread_info_size());
    ASSERT_THREAD_INFO_IS(idx_data0.thread_info(0), 3, "some thread", 8, false, 25);

    CHECK_EQUAL(3, idx_data0.method_info_size());
    ASSERT_METHOD_INFO_IS(idx_data0.method_info(0), 0, "?", "?", "?", "?", Java_Sym);
    ASSERT_METHOD_INFO_IS(idx_data0.method_info(1), c, "x/C.class", "x.C", "fn_c", "(F)I", Java_Sym);
    ASSERT_METHOD_INFO_IS(idx_data0.method_info(2), y, "x/Y.class", "x.Y", "fn_y", "(I)J", Java_Sym);

    CHECK_EQUAL(3, idx_data0.trace_ctx_size());
    ASSERT_TRACE_CTX_INFO_IS(idx_data0.trace_ctx(0), 0, DEFAULT_CTX_NAME, 0, 0, false);
    ASSERT_TRACE_CTX_INFO_IS(idx_data0.trace_ctx(1), 1, UNKNOWN_CTX_NAME, 0, 0, true);
    ASSERT_TRACE_CTX_INFO_IS(idx_data0.trace_ctx(2), 5, "foo", 20, 0, false);

    auto cse0 = wse0.cpu_sample_entry();
    CHECK_EQUAL(10, cse0.stack_sample_size());

    auto s0 = {fr(c, 10, 1), fr(y, 20, 2)};
    auto s0_ctxs = {5};
    for (auto i = 0; i < 10; i++) {
        ASSERT_STACK_SAMPLE_IS(cse0.stack_sample(i), 0, 3, s0, s0_ctxs, false);
    }
}

TEST(ProfileSerializer__should_report_unflushed_trace__and_EOF_after_last_flush) {
    TestEnv _;
    recording::Wse last;
    play_last_flush_scenario(last, 1);

    //There is a little bit of duplication here, but its for readability reasons
    //duplicating entire test is no more readable, and returning these variables or
    //passing them in for re-use hurts readability too.
    //Anyway, we are better off with tests that are more readable than DRY.
    std::int64_t y = 2, c = 1;
    auto s0 = {fr(c, 10, 1), fr(y, 20, 2)};
    auto s0_ctxs = {5};

    auto last_data = last.indexed_data();
    CHECK_EQUAL(0, last_data.monitor_info_size());
    CHECK_EQUAL(0, last_data.thread_info_size());
    CHECK_EQUAL(0, last_data.method_info_size());

    CHECK_EQUAL(5, last_data.trace_ctx_size());
    typedef std::pair<std::string, int> TraceCtxEntry;
    std::vector<TraceCtxEntry> tce;
    for (auto i = 0; i < 5; i ++) {
        tce.emplace_back(last_data.trace_ctx(i).trace_name(), i);
    }
    std::sort(tce.begin(), tce.end());

    ASSERT_TRACE_CTX_INFO_WITHOUT_CTXID_IS(last_data.trace_ctx(tce[0].second), "bar", 40, 0, false);
    ASSERT_TRACE_CTX_INFO_WITHOUT_CTXID_IS(last_data.trace_ctx(tce[1].second), "baz", 50, 4, false);
    ASSERT_TRACE_CTX_INFO_WITHOUT_CTXID_IS(last_data.trace_ctx(tce[2].second), "corge", 70, 1, false);
    ASSERT_TRACE_CTX_INFO_WITHOUT_CTXID_IS(last_data.trace_ctx(tce[3].second), "grault", 80, 2, false);
    ASSERT_TRACE_CTX_INFO_WITHOUT_CTXID_IS(last_data.trace_ctx(tce[4].second), "quux", 60, 3, false);

    auto cse1 = last.cpu_sample_entry();
    CHECK_EQUAL(1, cse1.stack_sample_size());
    ASSERT_STACK_SAMPLE_IS(cse1.stack_sample(0), 0, 3, s0, s0_ctxs, false);
}


TEST(ProfileSerializer__should_report_all_user_tracepoints_that_were_never_reported_before__and_EOF_after_last_flush) {
    TestEnv _;
    recording::Wse last;
    play_last_flush_scenario(last, 0);

    //There is a little bit of duplication here, but its for readability reasons
    //duplicating entire test is no more readable, and returning these variables or
    //passing them in for re-use hurts readability too.
    //Anyway, we are better off with tests that are more readable than DRY.

    auto last_data = last.indexed_data();
    CHECK_EQUAL(0, last_data.monitor_info_size());
    CHECK_EQUAL(0, last_data.thread_info_size());
    CHECK_EQUAL(0, last_data.method_info_size());

    CHECK_EQUAL(5, last_data.trace_ctx_size());
    typedef std::pair<std::string, int> TraceCtxEntry;
    std::vector<TraceCtxEntry> tce;
    for (auto i = 0; i < 5; i ++) {
        tce.emplace_back(last_data.trace_ctx(i).trace_name(), i);
    }
    std::sort(tce.begin(), tce.end());

    ASSERT_TRACE_CTX_INFO_WITHOUT_CTXID_IS(last_data.trace_ctx(tce[0].second), "bar", 40, 0, false);
    ASSERT_TRACE_CTX_INFO_WITHOUT_CTXID_IS(last_data.trace_ctx(tce[1].second), "baz", 50, 4, false);
    ASSERT_TRACE_CTX_INFO_WITHOUT_CTXID_IS(last_data.trace_ctx(tce[2].second), "corge", 70, 1, false);
    ASSERT_TRACE_CTX_INFO_WITHOUT_CTXID_IS(last_data.trace_ctx(tce[3].second), "grault", 80, 2, false);
    ASSERT_TRACE_CTX_INFO_WITHOUT_CTXID_IS(last_data.trace_ctx(tce[4].second), "quux", 60, 3, false);

    auto cse1 = last.cpu_sample_entry();
    CHECK_EQUAL(0, cse1.stack_sample_size());
}

