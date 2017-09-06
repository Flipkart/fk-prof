#include <cstdint>
#include <stack>
#include <stdexcept>
#include <array>
#include <vector>
#include "util.hh"
#include <mutex>
#include <cuckoohash_map.hh>
#include <concurrentqueue.h>
#include "prob_pct.hh"
#include <unordered_map>
#include <functional>
#include "metrics.hh"

#ifndef PERF_CTX_H
#define PERF_CTX_H

// special context IDs
#define DEFAULT_CTX_ID 0
#define DEFAULT_CTX_NAME "~ OTHERS ~"
#define UNKNOWN_CTX_ID 1
#define UNKNOWN_CTX_NAME "~ UNKNOWN ~"

namespace PerfCtx {
    typedef std::uint64_t TracePt;
    /*
      Ctx bit layout: it is composed of 3 parts, coverage %, merge-semantic and ctx-id and merge-semantic is not relevant for merge-generated ctx
      because they can never get pushed over some other context (other contexts may get pushed over them, but then the parent's merge-semantic
      doesn't matter).
          
      | bits [0 - 63] = 64 | [63 - 63] = 1 | [62 - 56] = 7 | [55 - 53] = 3  | [52 - 13] = 40 | [12 - 0] = 13                   |
      |--------------------+---------------+---------------+----------------+----------------+---------------------------------|
      | user-created       |             0 | coverage %    | merge-semantic | **unused**     | ctx-id (always a prime number)  |
      |--------------------+---------------+---------------+----------------+----------------+---------------------------------|

      13 bits can house 100th prime number (7919, which is less than 8192), so definitely sufficient for forseeable future.

      | bits [0 - 63] = 64 | [63 - 63] = 1 | [62 - 7] = 56               | [6 - 0] = 7                 |
      |--------------------+---------------+-----------------------------+-----------------------------|
      | merge-generated    |             1 | ctx-id part-0 (combination) | ctx-id part-1 (permutation) |
      |--------------------+---------------+-----------------------------+-----------------------------|

      This scheme allows us to pack all ctx-ids in jlong, hence allowing 0-lookup impl on perf-data processing path (sigprof handler, allocator-callback etc) while keeping things simple and fast. Obviously, it is possible to bump this limit by using 2 or 3 or more jlongs, but because this the final aggregated data cardinality needs to be such that its easy for humans to consume, such enhancements shoudldn't be necessary.

      This allows us to offer upto 349 user defined ctx, because 2351 is the 349th prime number and value of 2357^5 (next prime, 350th) is greater than 2^56. Since we support 5-depth nesting (as 2351^5 < 2^56) we need 7 bits for storing permutation-id (5! = 120, the next power of 2 is 128, ie 2^7).
          
    */
    const std::uint16_t MAX_USER_CTX_COUNT = 200; //this can actually be made as high as 349
    const std::uint8_t MAX_NESTING = 5;

    constexpr std::uint64_t TYPE_MASK = static_cast<std::uint64_t>(0x80) << (7 * 8);
    constexpr std::uint64_t USER_CREATED_TYPE = 0x0;
    constexpr std::uint64_t MERGE_GENERATED_TYPE = static_cast<std::uint64_t>(0x80) << (7 * 8);

    constexpr std::uint64_t USER_CREATED_CTX_ID_MASK = 0x1FFF;

    constexpr std::uint8_t MERGE_SEMANTIC_SHIFT = 53;
    constexpr std::uint8_t MERGE_SEMANTIC_MASK = 0x7;
    constexpr std::uint8_t COVERAGE_PCT_SHIFT = 56;
    constexpr std::uint8_t COVERAGE_PCT_MASK = 0x7F;

    
    constexpr std::uint8_t GENERATED_COMBINATION_SHIFT = 7;
    constexpr std::uint64_t GENERATED_COMBINATION_MAX_VALUE = 0xFFFFFFFFFFFFFF;
    constexpr std::uint8_t GENERATED_PERMUTATION_MAX_VALUE = 120;
    

    enum class MergeSemantic { to_parent = 0, scoped = 1, scoped_strict = 2, stack_up = 3, duplicate = 4 };

    class CtxCreationFailure : public std::runtime_error {
    public:
        CtxCreationFailure(const std::string& msg) : runtime_error("PerfCtx creation failed: " + msg) {}
        virtual ~CtxCreationFailure() {}
    };

    class UnknownCtx : public std::runtime_error {
    public:
        UnknownCtx(TracePt pt) : runtime_error(Util::to_s("Name dereference failed for unkonwn ctx: 0x", std::hex, pt)) {}
        virtual ~UnknownCtx() {}
    };

    struct ThreadCtx {
        TracePt ctx;
        std::uint32_t push_count;
        struct {
            std::uint8_t start, end;
            bool record;
        } prev;

        ThreadCtx(TracePt _ctx, std::uint8_t _prev_start, std::uint8_t _prev_end, bool _prev_record) : ctx(_ctx), push_count(0) {
            prev.start = _prev_start;
            prev.end = _prev_end;
            prev.record = _prev_record;
        }
        ~ThreadCtx() {}
    };
    
    class Registry {
        typedef cuckoohash_map<std::string, TracePt, std::hash<std::string> > NameToPt;
        typedef cuckoohash_map<TracePt, std::string, std::hash<TracePt> > PtToName;

        moodycamel::ConcurrentQueue<std::uint32_t> unused_prime_nos;
        NameToPt name_to_pt;    //contains only user-created trace-pts
        PtToName pt_to_name;    //contains both user-created and generated trace-pts

        std::atomic<bool> exhausted;

        metrics::Ctr& s_c_ctx;
        metrics::Mtr& s_m_create_rebind;
        metrics::Mtr& s_m_create_conflict;
        metrics::Mtr& s_m_create_runout;

        metrics::Mtr& s_m_merge_reuse;
        metrics::Ctr& s_c_merge_new;

        void load_unused_primes(std::uint32_t count);

    public:
        Registry();
        ~Registry();
        
        TracePt find_or_bind(const char* name, std::uint8_t coverage_pct, std::uint8_t merge_type) throw (CtxCreationFailure);
        TracePt merge_bind(const std::vector<ThreadCtx>& parent, bool strict = false);
        void name_for(TracePt pt, std::string& name) throw (UnknownCtx);
        void resolve(TracePt pt, std::string& name, bool& is_generated, std::uint8_t& coverage_pct, MergeSemantic& m_sem) throw (UnknownCtx);
        void user_ctxs(std::vector<TracePt>& ctxs);
    };

    class IncorrectEnterExitPairing {
        std::string msg;

    public:
        IncorrectEnterExitPairing(Registry& reg, const TracePt expected, const TracePt got);

        IncorrectEnterExitPairing(Registry& reg, const TracePt got);

        ~IncorrectEnterExitPairing() {}

        const char* what() const;
    };

    MergeSemantic merge_semantic(TracePt pt);
    
    class ThreadTracker {
        Registry& reg;
        ProbPct& pct;
        
        std::vector<ThreadCtx> actual_stack;
        std::vector<TracePt> effective;

        std::uint32_t ignore_count;
        
        std::uint8_t effective_start, effective_end;

        bool record;

        std::unordered_map<TracePt, std::uint32_t> prob_idxs;

        const int tid;

    public:
        typedef std::array<TracePt, MAX_NESTING> EffectiveCtx;
        
        ThreadTracker(Registry& _reg, ProbPct& _pct, int _tid);
        ~ThreadTracker();

        void enter(TracePt pt);
        void exit(TracePt pt) throw (IncorrectEnterExitPairing);
        int current(EffectiveCtx& curr);
        bool should_record();
        bool in_ctx();
    };
};

std::ostream& operator<<(std::ostream& os, PerfCtx::MergeSemantic ms);

PerfCtx::Registry& get_ctx_reg();

#endif
