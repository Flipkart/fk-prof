#include "perf_ctx_jni.hh"
#include "perf_ctx.hh"
#include "globals.hh"
#include <sstream>
#include "thread_map.hh"

PerfCtx::MergeSemantic PerfCtx::merge_semantic(PerfCtx::TracePt pt) {
    assert((pt & PerfCtx::TYPE_MASK) == PerfCtx::USER_CREATED_TYPE);
    return static_cast<MergeSemantic>((pt >> PerfCtx::MERGE_SEMANTIC_SHIFT) & MERGE_SEMANTIC_MASK);
}

metrics::Mtr* s_m_bad_pairing;
metrics::Mtr* s_m_nesting_overflow;
metrics::Mtr* s_m_null_tracker;

PerfCtx::ThreadTracker::ThreadTracker(Registry& _reg, ProbPct& _pct, int _tid) :
    reg(_reg), pct(_pct), ignore_count(0), effective_start(0), effective_end(0), record(false), tid(_tid) {
    effective.reserve((MAX_NESTING * (MAX_NESTING + 1)) / 2);
}

PerfCtx::ThreadTracker::~ThreadTracker() {}

void PerfCtx::ThreadTracker::enter(PerfCtx::TracePt pt) {
    assert((pt & PerfCtx::TYPE_MASK) == PerfCtx::USER_CREATED_TYPE);

    auto ms = PerfCtx::merge_semantic(pt);
    if ((ms != PerfCtx::MergeSemantic::scoped_strict) &&
        (actual_stack.size() > 0)) {
        auto& top = actual_stack.back();
        if (top.ctx == pt) {
            top.push_count++;
            return;
        }
    }

    if (actual_stack.size() == PerfCtx::MAX_NESTING) {
        if (ignore_count == 0) s_m_nesting_overflow->mark();
        ignore_count++;
        return;
    }
    assert(actual_stack.size() < PerfCtx::MAX_NESTING);
    bool start_recording = record;
    switch (ms) {
    case PerfCtx::MergeSemantic::to_parent:
    case PerfCtx::MergeSemantic::scoped:
    case PerfCtx::MergeSemantic::scoped_strict:
    case PerfCtx::MergeSemantic::duplicate:
        if (effective_end > 0) break;
    case PerfCtx::MergeSemantic::stack_up:
        auto e = prob_idxs.find(pt);
        if (e == prob_idxs.end()) {
            auto start_idx = (pt & USER_CREATED_CTX_ID_MASK) * tid;
            prob_idxs.insert({pt, start_idx});
        }
        std::uint8_t pt_coverage_pct = (pt >> COVERAGE_PCT_SHIFT) & COVERAGE_PCT_MASK;
        start_recording = pct.on(prob_idxs[pt]++, pt_coverage_pct);
        break;
    }

    actual_stack.emplace_back(pt, effective_start, effective_end, record);
    record = start_recording;
    switch (ms) {
    case PerfCtx::MergeSemantic::to_parent:
        if (effective_end == 0) {
            effective.push_back(pt);
            effective_end++;
        }
        break;
    case PerfCtx::MergeSemantic::scoped:
    case PerfCtx::MergeSemantic::scoped_strict:
        if (effective_end == 0) {
            effective.push_back(pt);
        } else {
            effective.push_back(reg.merge_bind(actual_stack));
            effective_start++;
        }
        effective_end++;
        break;
    case PerfCtx::MergeSemantic::stack_up:
        effective.push_back(pt);
        if (effective_end > 0) {
            effective_start++;
        }
        effective_end++;
        break;
    case PerfCtx::MergeSemantic::duplicate:
        effective.push_back(pt);
        effective_end++;
        break;
    }
}

void PerfCtx::ThreadTracker::exit(PerfCtx::TracePt pt) throw (IncorrectEnterExitPairing) {
    if (ignore_count > 0) {
        ignore_count--;
        return;
    }
    if (actual_stack.size() > 0) {
        auto& top = actual_stack.back();
        if ((top.ctx == pt) &&
            (top.push_count > 0)) {
            top.push_count--;
            return;
        }
    }

    if (actual_stack.size() == 0) {
        s_m_bad_pairing->mark();
        throw IncorrectEnterExitPairing(reg, pt);
    }

    auto top = actual_stack.back();
    if (top.ctx != pt) {
        s_m_bad_pairing->mark();
        throw IncorrectEnterExitPairing(reg, top.ctx, pt);
    }
    effective.resize(top.prev.end);
    effective_start = top.prev.start;
    effective_end = top.prev.end;
    record = top.prev.record;
    actual_stack.pop_back();
}

int PerfCtx::ThreadTracker::current(PerfCtx::ThreadTracker::EffectiveCtx& curr) {
    if (effective.size() == 0) return 0;
    auto len = effective_end - effective_start;
    for (auto i = 0; i < len; i++) {
        curr[i] = effective[i + effective_start];
    }
    SPDLOG_DEBUG(logger, "De-referencing thread's perf-ctx, found len: {}", len);
    return len;
}

bool PerfCtx::ThreadTracker::in_ctx() {
    return effective.size() > 0;
}

bool PerfCtx::ThreadTracker::should_record() {
    return record;
}

typedef std::uint32_t prime_t;

const prime_t PRIMES[] = {2, 3, 5, 7, 11, 13, 17, 19, 23, 29, 31, 37, 41, 43, 47, 53, 59, 61, 67, 71, 73, 79, 83, 89, 97, 101, 103, 107, 109, 113, 127, 131, 137, 139, 149, 151, 157, 163, 167, 173, 179, 181, 191, 193, 197, 199, 211, 223, 227, 229, 233, 239, 241, 251, 257, 263, 269, 271, 277, 281, 283, 293, 307, 311, 313, 317, 331, 337, 347, 349, 353, 359, 367, 373, 379, 383, 389, 397, 401, 409, 419, 421, 431, 433, 439, 443, 449, 457, 461, 463, 467, 479, 487, 491, 499, 503, 509, 521, 523, 541, 547, 557, 563, 569, 571, 577, 587, 593, 599, 601, 607, 613, 617, 619, 631, 641, 643, 647, 653, 659, 661, 673, 677, 683, 691, 701, 709, 719, 727, 733, 739, 743, 751, 757, 761, 769, 773, 787, 797, 809, 811, 821, 823, 827, 829, 839, 853, 857, 859, 863, 877, 881, 883, 887, 907, 911, 919, 929, 937, 941, 947, 953, 967, 971, 977, 983, 991, 997, 1009, 1013, 1019, 1021, 1031, 1033, 1039, 1049, 1051, 1061, 1063, 1069, 1087, 1091, 1093, 1097, 1103, 1109, 1117, 1123, 1129, 1151, 1153, 1163, 1171, 1181, 1187, 1193, 1201, 1213, 1217, 1223, 1229, 1231, 1237, 1249, 1259, 1277, 1279, 1283, 1289, 1291, 1297, 1301, 1303, 1307, 1319, 1321, 1327, 1361, 1367, 1373, 1381, 1399, 1409, 1423, 1427, 1429, 1433, 1439, 1447, 1451, 1453, 1459, 1471, 1481, 1483, 1487, 1489, 1493, 1499, 1511, 1523, 1531, 1543, 1549, 1553, 1559, 1567, 1571, 1579, 1583, 1597, 1601, 1607, 1609, 1613, 1619, 1621, 1627, 1637, 1657, 1663, 1667, 1669, 1693, 1697, 1699, 1709, 1721, 1723, 1733, 1741, 1747, 1753, 1759, 1777, 1783, 1787, 1789, 1801, 1811, 1823, 1831, 1847, 1861, 1867, 1871, 1873, 1877, 1879, 1889, 1901, 1907, 1913, 1931, 1933, 1949, 1951, 1973, 1979, 1987, 1993, 1997, 1999, 2003, 2011, 2017, 2027, 2029, 2039, 2053, 2063, 2069, 2081, 2083, 2087, 2089, 2099, 2111, 2113, 2129, 2131, 2137, 2141, 2143, 2153, 2161, 2179, 2203, 2207, 2213, 2221, 2237, 2239, 2243, 2251, 2267, 2269, 2273, 2281, 2287, 2293, 2297, 2309, 2311, 2333, 2339, 2341, 2347, 2351, 2357, 2371, 2377, 2381, 2383, 2389, 2393, 2399, 2411, 2417, 2423, 2437, 2441, 2447, 2459, 2467, 2473, 2477, 2503, 2521, 2531, 2539, 2543, 2549, 2551, 2557, 2579, 2591, 2593, 2609, 2617, 2621, 2633, 2647, 2657, 2659, 2663, 2671, 2677, 2683, 2687, 2689, 2693, 2699, 2707, 2711, 2713, 2719, 2729, 2731, 2741, 2749, 2753, 2767, 2777, 2789, 2791, 2797, 2801, 2803, 2819, 2833, 2837, 2843, 2851, 2857, 2861, 2879, 2887, 2897, 2903, 2909, 2917, 2927, 2939, 2953, 2957, 2963, 2969, 2971, 2999, 3001, 3011, 3019, 3023, 3037, 3041, 3049, 3061, 3067, 3079, 3083, 3089, 3109, 3119, 3121, 3137, 3163, 3167, 3169, 3181, 3187, 3191, 3203, 3209, 3217, 3221, 3229, 3251, 3253, 3257, 3259, 3271, 3299, 3301, 3307, 3313, 3319, 3323, 3329, 3331, 3343, 3347, 3359, 3361, 3371, 3373, 3389, 3391, 3407, 3413, 3433, 3449, 3457, 3461, 3463, 3467, 3469, 3491, 3499, 3511, 3517, 3527, 3529, 3533, 3539, 3541, 3547, 3557, 3559, 3571, 3581, 3583, 3593, 3607, 3613, 3617, 3623, 3631, 3637, 3643, 3659, 3671, 3673, 3677, 3691, 3697, 3701, 3709, 3719, 3727, 3733, 3739, 3761, 3767, 3769, 3779, 3793, 3797, 3803, 3821, 3823, 3833, 3847, 3851, 3853, 3863, 3877, 3881, 3889, 3907, 3911, 3917, 3919, 3923, 3929, 3931, 3943, 3947, 3967, 3989, 4001, 4003, 4007, 4013, 4019, 4021, 4027, 4049, 4051, 4057, 4073, 4079, 4091, 4093, 4099, 4111, 4127, 4129, 4133, 4139, 4153, 4157, 4159, 4177, 4201, 4211, 4217, 4219, 4229, 4231, 4241, 4243, 4253, 4259, 4261, 4271, 4273, 4283, 4289, 4297, 4327, 4337, 4339, 4349, 4357, 4363, 4373, 4391, 4397, 4409, 4421, 4423, 4441, 4447, 4451, 4457, 4463, 4481, 4483, 4493, 4507, 4513, 4517, 4519, 4523, 4547, 4549, 4561, 4567, 4583, 4591, 4597, 4603, 4621, 4637, 4639, 4643, 4649, 4651, 4657, 4663, 4673, 4679, 4691, 4703, 4721, 4723, 4729, 4733, 4751, 4759, 4783, 4787, 4789, 4793, 4799, 4801, 4813, 4817, 4831, 4861, 4871, 4877, 4889, 4903, 4909, 4919, 4931, 4933, 4937, 4943, 4951, 4957, 4967, 4969, 4973, 4987, 4993, 4999, 5003, 5009, 5011, 5021, 5023, 5039, 5051, 5059, 5077, 5081, 5087, 5099, 5101, 5107, 5113, 5119, 5147, 5153, 5167, 5171, 5179, 5189, 5197, 5209, 5227, 5231, 5233, 5237, 5261, 5273, 5279, 5281, 5297, 5303, 5309, 5323, 5333, 5347, 5351, 5381, 5387, 5393, 5399, 5407, 5413, 5417, 5419, 5431, 5437, 5441, 5443, 5449, 5471, 5477, 5479, 5483, 5501, 5503, 5507, 5519, 5521, 5527, 5531, 5557, 5563, 5569, 5573, 5581, 5591, 5623, 5639, 5641, 5647, 5651, 5653, 5657, 5659, 5669, 5683, 5689, 5693, 5701, 5711, 5717, 5737, 5741, 5743, 5749, 5779, 5783, 5791, 5801, 5807, 5813, 5821, 5827, 5839, 5843, 5849, 5851, 5857, 5861, 5867, 5869, 5879, 5881, 5897, 5903, 5923, 5927, 5939, 5953, 5981, 5987, 6007, 6011, 6029, 6037, 6043, 6047, 6053, 6067, 6073, 6079, 6089, 6091, 6101, 6113, 6121, 6131, 6133, 6143, 6151, 6163, 6173, 6197, 6199, 6203, 6211, 6217, 6221, 6229, 6247, 6257, 6263, 6269, 6271, 6277, 6287, 6299, 6301, 6311, 6317, 6323, 6329, 6337, 6343, 6353, 6359, 6361, 6367, 6373, 6379, 6389, 6397, 6421, 6427, 6449, 6451, 6469, 6473, 6481, 6491, 6521, 6529, 6547, 6551, 6553, 6563, 6569, 6571, 6577, 6581, 6599, 6607, 6619, 6637, 6653, 6659, 6661, 6673, 6679, 6689, 6691, 6701, 6703, 6709, 6719, 6733, 6737, 6761, 6763, 6779, 6781, 6791, 6793, 6803, 6823, 6827, 6829, 6833, 6841, 6857, 6863, 6869, 6871, 6883, 6899, 6907, 6911, 6917, 6947, 6949, 6959, 6961, 6967, 6971, 6977, 6983, 6991, 6997, 7001, 7013, 7019, 7027, 7039, 7043, 7057, 7069, 7079, 7103, 7109, 7121, 7127, 7129, 7151, 7159, 7177, 7187, 7193, 7207, 7211, 7213, 7219, 7229, 7237, 7243, 7247, 7253, 7283, 7297, 7307, 7309, 7321, 7331, 7333, 7349, 7351, 7369, 7393, 7411, 7417, 7433, 7451, 7457, 7459, 7477, 7481, 7487, 7489, 7499, 7507, 7517, 7523, 7529, 7537, 7541, 7547, 7549, 7559, 7561, 7573, 7577, 7583, 7589, 7591, 7603, 7607, 7621, 7639, 7643, 7649, 7669, 7673, 7681, 7687, 7691, 7699, 7703, 7717, 7723, 7727, 7741, 7753, 7757, 7759, 7789, 7793, 7817, 7823, 7829, 7841, 7853, 7867, 7873, 7877, 7879, 7883, 7901, 7907, 7919, 7927, 7933, 7937, 7949, 7951, 7963, 7993, 8009, 8011, 8017, 8039, 8053, 8059, 8069, 8081, 8087, 8089, 8093, 8101, 8111, 8117, 8123, 8147, 8161, 8167, 8171, 8179, 8191, 8209, 8219, 8221, 8231, 8233, 8237, 8243, 8263, 8269, 8273, 8287, 8291, 8293, 8297, 8311, 8317, 8329, 8353, 8363, 8369, 8377, 8387, 8389, 8419, 8423, 8429, 8431, 8443, 8447, 8461, 8467, 8501, 8513, 8521, 8527, 8537, 8539, 8543, 8563, 8573, 8581, 8597, 8599, 8609, 8623, 8627, 8629, 8641, 8647, 8663, 8669, 8677, 8681, 8689, 8693, 8699, 8707, 8713, 8719, 8731, 8737, 8741, 8747, 8753, 8761, 8779, 8783, 8803, 8807, 8819, 8821, 8831, 8837, 8839, 8849, 8861, 8863, 8867, 8887, 8893, 8923, 8929, 8933, 8941, 8951, 8963, 8969, 8971, 8999, 9001, 9007, 9011, 9013, 9029, 9041, 9043, 9049, 9059, 9067, 9091, 9103, 9109, 9127, 9133, 9137, 9151, 9157, 9161, 9173, 9181, 9187, 9199, 9203, 9209, 9221, 9227, 9239, 9241, 9257, 9277, 9281, 9283, 9293, 9311, 9319, 9323, 9337, 9341, 9343, 9349, 9371, 9377, 9391, 9397, 9403, 9413, 9419, 9421, 9431, 9433, 9437, 9439, 9461, 9463, 9467, 9473, 9479, 9491, 9497, 9511, 9521, 9533, 9539, 9547, 9551, 9587, 9601, 9613, 9619, 9623, 9629, 9631, 9643, 9649, 9661, 9677, 9679, 9689, 9697, 9719, 9721, 9733, 9739, 9743, 9749, 9767, 9769, 9781, 9787, 9791, 9803, 9811, 9817, 9829, 9833, 9839, 9851, 9857, 9859, 9871, 9883, 9887, 9901, 9907, 9923, 9929, 9931, 9941, 9949, 9967, 9973};

void PerfCtx::Registry::load_unused_primes(std::uint32_t count) {
    auto i = 0;
    for ( ; i < sizeof(PRIMES)/sizeof(prime_t); i++) {
        if (! unused_prime_nos.try_enqueue(PRIMES[i])) break;
    }
    logger->warn("Loaded up {} prime numbers for ctx creation", i);
    assert(count <= i);
}

template <typename T> void dump_table_to_logs(T& tab) {
    auto l = tab.lock_table();
    for (auto it = l.cbegin(); it != l.cend(); it++) {
        logger->error("\tTABLE-DUMP {} -> 0x{:x}", it->first, it->second);
    }
}

static void assert_equal(const char* name, std::uint8_t cov_pct, std::uint8_t merge_sem, PerfCtx::TracePt pt, metrics::Mtr& conflict_meter) {
    auto old_cov_pct = (pt >> PerfCtx::COVERAGE_PCT_SHIFT) & PerfCtx::COVERAGE_PCT_MASK;
    auto old_merge_sem = (pt >> PerfCtx::MERGE_SEMANTIC_SHIFT) & PerfCtx::MERGE_SEMANTIC_MASK;
    if ((old_cov_pct != cov_pct) || (old_merge_sem != merge_sem)) {
        auto err_msg = Util::to_s("New value (cov: ", static_cast<std::uint32_t>(cov_pct), "%, merge: ", static_cast<std::uint32_t>(merge_sem), ") for ctx 'foo' conflicts with old value (cov: ", static_cast<std::uint32_t>(old_cov_pct), "%, merge: ", static_cast<std::uint32_t>(old_merge_sem), ")");
        logger->warn("App tried to plug conflicting definitions of a ctx: {}", err_msg);
        conflict_meter.mark();
        throw PerfCtx::CtxCreationFailure(err_msg);
    }
}

#define METRIC_TYPE "perf_ctx"

PerfCtx::Registry::Registry() :
    unused_prime_nos(MAX_USER_CTX_COUNT),
    exhausted(false),

    s_c_ctx(get_metrics_registry().new_counter({METRICS_DOMAIN, METRIC_TYPE, "count"})),
    s_m_create_rebind(get_metrics_registry().new_meter({METRICS_DOMAIN, METRIC_TYPE, "create", "rebind"}, "rate")),
    s_m_create_conflict(get_metrics_registry().new_meter({METRICS_DOMAIN, METRIC_TYPE, "create", "conflict"}, "rate")),
    s_m_create_runout(get_metrics_registry().new_meter({METRICS_DOMAIN, METRIC_TYPE, "create", "runout"}, "rate")),

    s_m_merge_reuse(get_metrics_registry().new_meter({METRICS_DOMAIN, METRIC_TYPE, "merge", "reuse"}, "rate")),
    s_c_merge_new(get_metrics_registry().new_counter({METRICS_DOMAIN, METRIC_TYPE, "merge", "new"})) {

    s_m_bad_pairing = &get_metrics_registry().new_meter({METRICS_DOMAIN, METRIC_TYPE, "entry", "bad_pairing"}, "rate");
    s_m_nesting_overflow = &get_metrics_registry().new_meter({METRICS_DOMAIN, METRIC_TYPE, "entry", "nesting_overflow"}, "rate");
    s_m_null_tracker = &get_metrics_registry().new_meter({METRICS_DOMAIN, METRIC_TYPE, "trace", "null_tracker"}, "rate");
    load_unused_primes(MAX_USER_CTX_COUNT);
}

PerfCtx::Registry::~Registry() {}

void PerfCtx::Registry::user_ctxs(std::vector<PerfCtx::TracePt>& trace_pts) {
    trace_pts.reserve(name_to_pt.size());
    auto l = name_to_pt.lock_table();
    for (auto it = l.cbegin(); it != l.cend(); it++) {
        trace_pts.push_back(it->second);
    }
}

PerfCtx::TracePt PerfCtx::Registry::find_or_bind(const char* name, std::uint8_t coverage_pct, std::uint8_t merge_type) throw (PerfCtx::CtxCreationFailure) {
    TracePt pt;
    if (name_to_pt.find(name, pt)) {
        assert_equal(name, coverage_pct, merge_type, pt, s_m_create_conflict);
        s_m_create_rebind.mark();
        return pt;
    }
    std::uint32_t new_prime;
    if (! unused_prime_nos.try_dequeue(new_prime)) {
        auto sz = name_to_pt.size();
        logger->error("Ran out of context-space after creating ~ {} contexts, dumping the table.", sz);
        if (! exhausted.load()) {
            dump_table_to_logs(name_to_pt);
            exhausted.store(true);
        }
        s_m_create_runout.mark();
        throw CtxCreationFailure(Util::to_s("Too many (~ ", sz, ") ctxs have been created."));
    }
    assert(coverage_pct <= 100);
    assert(merge_type >= static_cast<std::uint8_t>(PerfCtx::MergeSemantic::to_parent));
    assert(merge_type <= static_cast<std::uint8_t>(PerfCtx::MergeSemantic::duplicate));
    assert(new_prime < USER_CREATED_CTX_ID_MASK);
    pt = (static_cast<std::uint64_t>(coverage_pct) << COVERAGE_PCT_SHIFT) | (static_cast<std::uint64_t>(merge_type) << MERGE_SEMANTIC_SHIFT) | new_prime;
    if (name_to_pt.insert(name, pt)) {
        auto reverse_insert = pt_to_name.insert(pt, name);
        assert(reverse_insert);
        s_c_ctx.inc();
        return pt;
    }
    unused_prime_nos.enqueue(new_prime);
    auto found = name_to_pt.find(name, pt);
    assert(found);
    assert_equal(name, coverage_pct, merge_type, pt, s_m_create_conflict);
    s_m_create_rebind.mark();
    return pt;
}

typedef std::vector<PerfCtx::ThreadCtx>::const_reverse_iterator It;

static std::uint8_t scoped_parent_chain_beginning_idx(const std::vector<PerfCtx::ThreadCtx>& ctx_stack) {
    for (std::uint8_t i = 0; i < ctx_stack.size(); i++) {
        auto ms = PerfCtx::merge_semantic(ctx_stack[i].ctx);
        if (ms != PerfCtx::MergeSemantic::scoped &&
            ms != PerfCtx::MergeSemantic::scoped_strict) {
            return i;
        }
    }
    return 0;
}

PerfCtx::TracePt merge(const std::array<std::uint64_t, PerfCtx::MAX_NESTING>& s, std::uint8_t end_idx) {
    auto fac = 1, permutation_id = 0;
    std::uint64_t combination_id = 1;
    for (auto i = 0; i < end_idx; i++) {
        auto rank = 1;
        for (auto j = 0; j < i; j++) {
            if (s[i] > s[j]) {
                rank++;
            }
        }
        permutation_id += fac * (rank - 1);
        fac *= (i + 1);
        combination_id *= s[i];
    }

    assert(permutation_id <= 120); //depends on MAX_NESTING, 5! = 120
    assert(combination_id < PerfCtx::GENERATED_COMBINATION_MAX_VALUE);

    return PerfCtx::MERGE_GENERATED_TYPE | (combination_id << PerfCtx::GENERATED_COMBINATION_SHIFT) | permutation_id;
}

PerfCtx::TracePt PerfCtx::Registry::merge_bind(const std::vector<ThreadCtx>& ctx_stack, bool strict) {//TODO: make me const
    assert(ctx_stack.size() > 0);
    assert(ctx_stack.size() <= PerfCtx::MAX_NESTING);
    auto parent_chain_start_idx = scoped_parent_chain_beginning_idx(ctx_stack);

    std::array<std::uint64_t, PerfCtx::MAX_NESTING> ctx_ids;

    std::uint8_t i;
    std::uint8_t len = ctx_stack.size();
    for (i = 0; i < (len - parent_chain_start_idx); i++) {
        assert(i < PerfCtx::MAX_NESTING);
        ctx_ids[i] = ctx_stack[len - i - 1].ctx & USER_CREATED_CTX_ID_MASK;
    }

    auto trace_pt = merge(ctx_ids, i);

    if (pt_to_name.contains(trace_pt)) {
        s_m_merge_reuse.mark();
        return trace_pt;
    }

    std::stringstream buff;
    std::string component_name;
    for (i = 0; i < (len - parent_chain_start_idx); i++) {
        name_for(ctx_stack[i + parent_chain_start_idx].ctx, component_name);
        if (i > 0) {
            buff << " > ";
        }
        buff << component_name;
    }
    auto name = buff.str();
    if (pt_to_name.insert(trace_pt, name)) {
        s_c_merge_new.inc();
    } else {
        logger->warn("Couldn't insert merge-generated ctx '{}' (0x{:x}), perhaps it was inserted concurrently", name, trace_pt);
    }
    
    return trace_pt;
}

void PerfCtx::Registry::name_for(TracePt pt, std::string& name) throw (PerfCtx::UnknownCtx) {
    if (! pt_to_name.find(pt, name)) throw UnknownCtx(pt);
}

void PerfCtx::Registry::resolve(TracePt pt, std::string& name, bool& is_generated, std::uint8_t& coverage_pct, MergeSemantic& m_sem) throw (PerfCtx::UnknownCtx) {
    name_for(pt, name);
    is_generated = ((PerfCtx::MERGE_GENERATED_TYPE & pt) != 0);
    if (! is_generated) {
        m_sem = static_cast<MergeSemantic>((pt >> PerfCtx::MERGE_SEMANTIC_SHIFT) & PerfCtx::MERGE_SEMANTIC_MASK);
        coverage_pct = (pt >> PerfCtx::COVERAGE_PCT_SHIFT) & PerfCtx::COVERAGE_PCT_MASK;
    }
}

std::ostream& operator<<(std::ostream& os, PerfCtx::MergeSemantic ms) {
    switch (ms) {
    case PerfCtx::MergeSemantic::to_parent:
        os << "Merge_To_Parent";
        break;
    case PerfCtx::MergeSemantic::scoped:
        os << "Parent_Scoped";
        break;
    case PerfCtx::MergeSemantic::scoped_strict:
        os << "Parent_Scoped (Strict)";
        break;
    case PerfCtx::MergeSemantic::stack_up:
        os << "Stack_up";
        break;
    case PerfCtx::MergeSemantic::duplicate:
        os << "Duplicate";
        break;
    default:
        os << "!!Unknown!!";
    }
    return os;
}

static void name_for(PerfCtx::Registry& reg, const PerfCtx::TracePt pt, std::string& name) {
    try {
        reg.name_for(pt, name);
    } catch (const PerfCtx::UnknownCtx& e) {
        name = "~ Un-registered ~";
    }
}

PerfCtx::IncorrectEnterExitPairing::IncorrectEnterExitPairing(Registry& reg, const TracePt expected, const TracePt got) {
    std::string expected_name, got_name;
    name_for(reg, expected, expected_name);
    name_for(reg, got, got_name);
    msg = Util::to_s("Expected exit for '", expected_name, "'(", expected, ") got '", got_name, "'(", got, ")");
}

PerfCtx::IncorrectEnterExitPairing::IncorrectEnterExitPairing(Registry& reg, const TracePt got) {
    std::string got_name;
    name_for(reg, got, got_name);
    msg = Util::to_s("Unexpected exit for '", got_name , "'(", got, ")");
}

const char* PerfCtx::IncorrectEnterExitPairing::what() const {
    return msg.c_str();
}

JNIEXPORT jlong JNICALL Java_fk_prof_PerfCtx_registerCtx(JNIEnv* env, jobject self, jstring name, jint coverage_pct, jint merge_type) {
    try {
        std::unique_ptr<const char, std::function<void(const char*)>>
            name_str(env->GetStringUTFChars(name, nullptr),
                     [&](const char* str) { if (str != nullptr) env->ReleaseStringUTFChars(name, str); });
        SPDLOG_DEBUG(logger, "Attempting registration of perf-ctx {} (cov: {}, merge: {})", name_str.get(), coverage_pct, merge_type);
        auto id = get_ctx_reg().find_or_bind(name_str.get(), static_cast<std::uint8_t>(coverage_pct), static_cast<std::uint8_t>(merge_type));
        SPDLOG_DEBUG(logger, "Registered perf-ctx {} as {}", name_str.get(), id);
        return static_cast<jlong>(id);
    } catch (PerfCtx::CtxCreationFailure& e) {
        if (env->ThrowNew(env->FindClass("fk/prof/PerfCtxInitException"), e.what()) == 0) return -1;
        logger->warn("Conflicting definition of perf-ctx ignored, details: {}", e.what());
        return -1;
    }
}

JNIEXPORT void JNICALL Java_fk_prof_PerfCtx_end(JNIEnv* env, jobject self, jlong ctx_id) {
    auto thd_info = get_thread_map().get(env);
    if (thd_info == nullptr) {
        SPDLOG_TRACE(logger, "Couldn't discover thd_info for jniEnv: {} to exit ctx_id: {}", reinterpret_cast<std::uint64_t>(env), ctx_id);
        s_m_null_tracker->mark();
        return;
    }
    try {
        SPDLOG_TRACE(logger, "Ending perf-ctx {} for jniEnv: {}", ctx_id, reinterpret_cast<std::uint64_t>(env));
        thd_info->ctx_tracker.exit(static_cast<PerfCtx::TracePt>(ctx_id));
    } catch (const PerfCtx::IncorrectEnterExitPairing& e) {
        env->ThrowNew(env->FindClass("fk/prof/IncorrectContextException"), e.what());
    }
    thd_info->release();
}

JNIEXPORT void JNICALL Java_fk_prof_PerfCtx_begin(JNIEnv* env, jobject self, jlong ctx_id) {
    
    auto thd_info = get_thread_map().get(env);
    if (thd_info == nullptr) {
        SPDLOG_TRACE(logger, "Couldn't discover thd_info for jniEnv: {} to enter ctx_id: {}", reinterpret_cast<std::uint64_t>(env), ctx_id);
        s_m_null_tracker->mark();
        return;
    }
    SPDLOG_TRACE(logger, "Begining perf-ctx {} for jniEnv: {}", ctx_id, reinterpret_cast<std::uint64_t>(env));
    thd_info->ctx_tracker.enter(static_cast<PerfCtx::TracePt>(ctx_id));
    thd_info->release();
}


