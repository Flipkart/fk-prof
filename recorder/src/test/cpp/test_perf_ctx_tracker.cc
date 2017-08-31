#include <thread>
#include <vector>
#include <iostream>
#include <cstdint>
#include "../../main/cpp/perf_ctx.hh"
#include "fixtures.hh"
#include "test.hh"
#include "../../main/cpp/globals.hh"

using namespace PerfCtx;

TEST(PerfCtx__should_understand_ctx_merge_semantics) {
    TestEnv _;
    CHECK_EQUAL(MergeSemantic::to_parent, merge_semantic(0));
    CHECK_EQUAL(MergeSemantic::scoped, merge_semantic((std::uint64_t) 1 << 53));
    CHECK_EQUAL(MergeSemantic::scoped_strict, merge_semantic((std::uint64_t) 2 << 53));
    CHECK_EQUAL(MergeSemantic::stack_up, merge_semantic((std::uint64_t) 3 << 53));
    CHECK_EQUAL(MergeSemantic::duplicate, merge_semantic((std::uint64_t) 4 << 53));
}

PerfCtx::TracePt reg(Registry& r, const char* name, PerfCtx::MergeSemantic m = PerfCtx::MergeSemantic::scoped) {
    return r.find_or_bind(name, 0, static_cast<std::uint8_t>(m));
}

#define LOAD_AND_ASSERT_CURRENT_CTX_IS(expected_len, ctx, into)  \
    {                                                            \
        CHECK_EQUAL(expected_len, ctx.current(into));            \
        CHECK_EQUAL((expected_len > 0), ctx.in_ctx());           \
    }

TEST(ThreadPerfCtxTracker__should_understand_ctx__when_merging_to_parent) {
    TestEnv _;
    Registry r;
    ProbPct prob_pct;
    ThreadTracker t_ctx(r, prob_pct, 210);

    std::array<TracePt, MAX_NESTING> curr;

    LOAD_AND_ASSERT_CURRENT_CTX_IS(0, t_ctx, curr);
    t_ctx.enter(10);
    LOAD_AND_ASSERT_CURRENT_CTX_IS(1, t_ctx, curr);
    std::array<TracePt, 1> expected{{10}};
    CHECK_ARRAY_EQUAL(expected, curr, 1);
    t_ctx.enter(20);
    LOAD_AND_ASSERT_CURRENT_CTX_IS(1, t_ctx, curr);
    CHECK_ARRAY_EQUAL(expected, curr, 1);
    t_ctx.enter(30);
    LOAD_AND_ASSERT_CURRENT_CTX_IS(1, t_ctx, curr);
    CHECK_ARRAY_EQUAL(expected, curr, 1);
    t_ctx.exit(30);
    LOAD_AND_ASSERT_CURRENT_CTX_IS(1, t_ctx, curr);
    CHECK_ARRAY_EQUAL(expected, curr, 1);
    t_ctx.exit(20);
    LOAD_AND_ASSERT_CURRENT_CTX_IS(1, t_ctx, curr);
    CHECK_ARRAY_EQUAL(expected, curr, 1);
    t_ctx.exit(10);
    LOAD_AND_ASSERT_CURRENT_CTX_IS(0, t_ctx, curr);
}

TEST(ThreadPerfCtxTracker__should_not_allow_unpaired_pop) {
    TestEnv _;
    Registry r;
    ProbPct prob_pct;
    ThreadTracker t_ctx(r, prob_pct, 210);
    std::array<TracePt, MAX_NESTING> curr;

    reg(r, "ctx-2", PerfCtx::MergeSemantic::to_parent);
    reg(r, "ctx-3", PerfCtx::MergeSemantic::to_parent);

    LOAD_AND_ASSERT_CURRENT_CTX_IS(0, t_ctx, curr);
    try {
        t_ctx.exit(2);
        CHECK(false); //should never reach here
    } catch (const IncorrectEnterExitPairing& e) {
        CHECK_EQUAL("Unexpected exit for 'ctx-2'(2)", e.what());
    }
    LOAD_AND_ASSERT_CURRENT_CTX_IS(0, t_ctx, curr);
    t_ctx.enter(2);
    t_ctx.enter(3);
    try {
        t_ctx.exit(2);
        CHECK(false); //should never reach here
    } catch (const IncorrectEnterExitPairing& e) {
        CHECK_EQUAL("Expected exit for 'ctx-3'(3) got 'ctx-2'(2)", e.what());
    }
    std::array<TracePt, 1> expected{{2}};
    LOAD_AND_ASSERT_CURRENT_CTX_IS(1, t_ctx, curr);
    CHECK_ARRAY_EQUAL(expected, curr, 1);
    t_ctx.exit(3);
    CHECK_ARRAY_EQUAL(expected, curr, 1);
    t_ctx.exit(2);
    LOAD_AND_ASSERT_CURRENT_CTX_IS(0, t_ctx, curr);
}

TEST(ThreadPerfCtxTracker__should_understand_ctx__when_scoping_under_parent) {
    TestEnv _;
    Registry r;
    ProbPct prob_pct;
    ThreadTracker t_ctx(r, prob_pct, 210);

    constexpr auto SCOPED_MASK = static_cast<std::uint64_t>(MergeSemantic::scoped) << PerfCtx::MERGE_SEMANTIC_SHIFT;

    std::array<TracePt, MAX_NESTING> curr;

    reg(r, "2", PerfCtx::MergeSemantic::to_parent);
    reg(r, "3");
    reg(r, "5");
    reg(r, "7");
    LOAD_AND_ASSERT_CURRENT_CTX_IS(0, t_ctx, curr);
    t_ctx.enter(2);
    LOAD_AND_ASSERT_CURRENT_CTX_IS(1, t_ctx, curr);
    std::array<TracePt, 1> expected{{2}};
    CHECK_ARRAY_EQUAL(expected, curr, 1);

    t_ctx.enter(SCOPED_MASK | 7);
    LOAD_AND_ASSERT_CURRENT_CTX_IS(1, t_ctx, curr);
    expected[0] = MERGE_GENERATED_TYPE | ((2 * 7) << GENERATED_COMBINATION_SHIFT);
    CHECK_ARRAY_EQUAL(expected, curr, 1);

    t_ctx.enter(SCOPED_MASK | 5);
    LOAD_AND_ASSERT_CURRENT_CTX_IS(1, t_ctx, curr);
    expected[0] = MERGE_GENERATED_TYPE | ((2 * 7 * 5) << GENERATED_COMBINATION_SHIFT) | 0x1;
    CHECK_ARRAY_EQUAL(expected, curr, 1);

    t_ctx.enter(SCOPED_MASK | 3);
    expected[0] = MERGE_GENERATED_TYPE | ((2 * 7 * 5 * 3) << GENERATED_COMBINATION_SHIFT) | 0x5;
    LOAD_AND_ASSERT_CURRENT_CTX_IS(1, t_ctx, curr);
    CHECK_ARRAY_EQUAL(expected, curr, 1);

    //now we exit 2 nearest scopes and push them in opposite order
    
    t_ctx.exit(SCOPED_MASK | 3);
    expected[0] = MERGE_GENERATED_TYPE | ((2 * 7 * 5) << GENERATED_COMBINATION_SHIFT) | 0x1;
    LOAD_AND_ASSERT_CURRENT_CTX_IS(1, t_ctx, curr);
    CHECK_ARRAY_EQUAL(expected, curr, 1);

    t_ctx.exit(SCOPED_MASK | 5);
    expected[0] = MERGE_GENERATED_TYPE | ((2 * 7) << GENERATED_COMBINATION_SHIFT);
    LOAD_AND_ASSERT_CURRENT_CTX_IS(1, t_ctx, curr);
    CHECK_ARRAY_EQUAL(expected, curr, 1);

    t_ctx.enter(SCOPED_MASK | 3);
    expected[0] = MERGE_GENERATED_TYPE | ((2 * 7 * 3) << GENERATED_COMBINATION_SHIFT) | 0x1;
    LOAD_AND_ASSERT_CURRENT_CTX_IS(1, t_ctx, curr);
    CHECK_ARRAY_EQUAL(expected, curr, 1);

    t_ctx.enter(SCOPED_MASK | 5);
    expected[0] = MERGE_GENERATED_TYPE | ((2 * 7 * 3 * 5) << GENERATED_COMBINATION_SHIFT) | 0x4;
    LOAD_AND_ASSERT_CURRENT_CTX_IS(1, t_ctx, curr);
    CHECK_ARRAY_EQUAL(expected, curr, 1);

    //permutation id now is 4 and not 5, while combination-id is the same (this distinguishes 2>7>3>5 from 2>7>5>3

    t_ctx.exit(SCOPED_MASK | 5);
    expected[0] = MERGE_GENERATED_TYPE | ((2 * 7 * 3) << GENERATED_COMBINATION_SHIFT) | 0x1;
    LOAD_AND_ASSERT_CURRENT_CTX_IS(1, t_ctx, curr);
    CHECK_ARRAY_EQUAL(expected, curr, 1);

    t_ctx.exit(SCOPED_MASK | 3);
    expected[0] = MERGE_GENERATED_TYPE | ((2 * 7) << GENERATED_COMBINATION_SHIFT);
    LOAD_AND_ASSERT_CURRENT_CTX_IS(1, t_ctx, curr);
    CHECK_ARRAY_EQUAL(expected, curr, 1);

    t_ctx.exit(SCOPED_MASK | 7);
    expected[0] = 2;
    LOAD_AND_ASSERT_CURRENT_CTX_IS(1, t_ctx, curr);
    CHECK_ARRAY_EQUAL(expected, curr, 1);

    t_ctx.exit(2);
    LOAD_AND_ASSERT_CURRENT_CTX_IS(0, t_ctx, curr);
}

TEST(ThreadPerfCtxTracker__not_nest_beyond_max_depth__when_scoping_under_parent) {
    TestEnv _;
    Registry r;
    ProbPct prob_pct;
    ThreadTracker t_ctx(r, prob_pct, 210);

    constexpr auto SCOPED_MASK = static_cast<std::uint64_t>(MergeSemantic::scoped) << PerfCtx::MERGE_SEMANTIC_SHIFT;

    std::array<TracePt, MAX_NESTING> curr;

    reg(r, "2", PerfCtx::MergeSemantic::to_parent);
    reg(r, "3");
    reg(r, "5");
    reg(r, "7");
    reg(r, "11");
    reg(r, "13");

    LOAD_AND_ASSERT_CURRENT_CTX_IS(0, t_ctx, curr);
    t_ctx.enter(2);
    LOAD_AND_ASSERT_CURRENT_CTX_IS(1, t_ctx, curr);
    std::array<TracePt, 1> expected{{2}};
    CHECK_ARRAY_EQUAL(expected, curr, 1);
    
    t_ctx.enter(SCOPED_MASK | 7);
    LOAD_AND_ASSERT_CURRENT_CTX_IS(1, t_ctx, curr);
    expected[0] = MERGE_GENERATED_TYPE | ((2 * 7) << GENERATED_COMBINATION_SHIFT);
    CHECK_ARRAY_EQUAL(expected, curr, 1);

    t_ctx.enter(SCOPED_MASK | 5);
    LOAD_AND_ASSERT_CURRENT_CTX_IS(1, t_ctx, curr);
    expected[0] = MERGE_GENERATED_TYPE | ((2 * 7 * 5) << GENERATED_COMBINATION_SHIFT) | 0x1;
    CHECK_ARRAY_EQUAL(expected, curr, 1);

    t_ctx.enter(SCOPED_MASK | 3);
    expected[0] = MERGE_GENERATED_TYPE | ((2 * 7 * 5 * 3) << GENERATED_COMBINATION_SHIFT) | 0x5;
    LOAD_AND_ASSERT_CURRENT_CTX_IS(1, t_ctx, curr);
    CHECK_ARRAY_EQUAL(expected, curr, 1);

    t_ctx.enter(SCOPED_MASK | 11);
    expected[0] = MERGE_GENERATED_TYPE | ((2 * 7 * 5 * 3 * 11) << GENERATED_COMBINATION_SHIFT) | 0xE;
    LOAD_AND_ASSERT_CURRENT_CTX_IS(1, t_ctx, curr);
    CHECK_ARRAY_EQUAL(expected, curr, 1);

    t_ctx.enter(SCOPED_MASK | 13);
    expected[0] = MERGE_GENERATED_TYPE | ((2 * 7 * 5 * 3 * 11) << GENERATED_COMBINATION_SHIFT) | 0xE;
    LOAD_AND_ASSERT_CURRENT_CTX_IS(1, t_ctx, curr);
    CHECK_ARRAY_EQUAL(expected, curr, 1);

    t_ctx.enter(SCOPED_MASK | 17);
    expected[0] = MERGE_GENERATED_TYPE | ((2 * 7 * 5 * 3 * 11) << GENERATED_COMBINATION_SHIFT) | 0xE;
    LOAD_AND_ASSERT_CURRENT_CTX_IS(1, t_ctx, curr);
    CHECK_ARRAY_EQUAL(expected, curr, 1);


    t_ctx.exit(42); //this is gibberish, doesn't matter, because ctx-tracer is ignoring it (its beyond 5 levels)
    LOAD_AND_ASSERT_CURRENT_CTX_IS(1, t_ctx, curr);
    CHECK_ARRAY_EQUAL(expected, curr, 1);
    t_ctx.exit(42); //gibberish again, same as above
    LOAD_AND_ASSERT_CURRENT_CTX_IS(1, t_ctx, curr);
    CHECK_ARRAY_EQUAL(expected, curr, 1);

    try {
        t_ctx.exit(42); //now gibberish won't work, because ctx is tracking it
        CHECK(false); //should never reach here
    } catch (const IncorrectEnterExitPairing& e) {
        CHECK_EQUAL(Util::to_s("Expected exit for '11'(", SCOPED_MASK | 11, ") got '~ Un-registered ~'(42)"), e.what());
    }
    
    t_ctx.exit(SCOPED_MASK | 11);
    expected[0] = MERGE_GENERATED_TYPE | ((2 * 7 * 5 * 3) << GENERATED_COMBINATION_SHIFT) | 0x5;
    LOAD_AND_ASSERT_CURRENT_CTX_IS(1, t_ctx, curr);
    CHECK_ARRAY_EQUAL(expected, curr, 1);

    t_ctx.exit(SCOPED_MASK | 3);
    expected[0] = MERGE_GENERATED_TYPE | ((2 * 7 * 5) << GENERATED_COMBINATION_SHIFT) | 0x1;
    LOAD_AND_ASSERT_CURRENT_CTX_IS(1, t_ctx, curr);
    CHECK_ARRAY_EQUAL(expected, curr, 1);

    t_ctx.exit(SCOPED_MASK | 5);
    expected[0] = MERGE_GENERATED_TYPE | ((2 * 7) << GENERATED_COMBINATION_SHIFT);
    LOAD_AND_ASSERT_CURRENT_CTX_IS(1, t_ctx, curr);
    CHECK_ARRAY_EQUAL(expected, curr, 1);

    t_ctx.exit(SCOPED_MASK | 7);
    expected[0] = 2;
    LOAD_AND_ASSERT_CURRENT_CTX_IS(1, t_ctx, curr);
    CHECK_ARRAY_EQUAL(expected, curr, 1);

    t_ctx.exit(2);
    LOAD_AND_ASSERT_CURRENT_CTX_IS(0, t_ctx, curr);
}

TEST(ThreadPerfCtxTracker__not_exceed_max_depth_due_to_recursion___when_scoping_under_parent) {
    TestEnv _;
    Registry r;
    ProbPct prob_pct;
    ThreadTracker t_ctx(r, prob_pct, 210);

    constexpr auto SCOPED_MASK = static_cast<std::uint64_t>(MergeSemantic::scoped) << PerfCtx::MERGE_SEMANTIC_SHIFT;

    std::array<TracePt, MAX_NESTING> curr;

    reg(r, "2", PerfCtx::MergeSemantic::to_parent);
    reg(r, "3");
    reg(r, "5");
    reg(r, "7");
    reg(r, "11");
    reg(r, "13");

    LOAD_AND_ASSERT_CURRENT_CTX_IS(0, t_ctx, curr);
    t_ctx.enter(2);
    LOAD_AND_ASSERT_CURRENT_CTX_IS(1, t_ctx, curr);
    std::array<TracePt, 1> expected{{2}};
    CHECK_ARRAY_EQUAL(expected, curr, 1);

    t_ctx.enter(SCOPED_MASK | 11);
    LOAD_AND_ASSERT_CURRENT_CTX_IS(1, t_ctx, curr);
    expected[0] = MERGE_GENERATED_TYPE | ((2 * 11) << GENERATED_COMBINATION_SHIFT);
    CHECK_ARRAY_EQUAL(expected, curr, 1);

    t_ctx.enter(SCOPED_MASK | 5);
    LOAD_AND_ASSERT_CURRENT_CTX_IS(1, t_ctx, curr);
    expected[0] = MERGE_GENERATED_TYPE | ((2 * 11 * 5) << GENERATED_COMBINATION_SHIFT) | 0x1;
    CHECK_ARRAY_EQUAL(expected, curr, 1);

    for (auto i = 0; i < 100; i++) {
        t_ctx.enter(SCOPED_MASK | 5);
        LOAD_AND_ASSERT_CURRENT_CTX_IS(1, t_ctx, curr);
        CHECK_ARRAY_EQUAL(expected, curr, 1);
    }

    t_ctx.enter(SCOPED_MASK | 3);
    expected[0] = MERGE_GENERATED_TYPE | ((2 * 11 * 5 * 3) << GENERATED_COMBINATION_SHIFT) | 0x5;
    LOAD_AND_ASSERT_CURRENT_CTX_IS(1, t_ctx, curr);
    CHECK_ARRAY_EQUAL(expected, curr, 1);

    t_ctx.enter(SCOPED_MASK | 7);
    expected[0] = MERGE_GENERATED_TYPE | ((2 * 11 * 5 * 3 * 7) << GENERATED_COMBINATION_SHIFT) | 0x14;
    LOAD_AND_ASSERT_CURRENT_CTX_IS(1, t_ctx, curr);
    CHECK_ARRAY_EQUAL(expected, curr, 1);

    t_ctx.enter(SCOPED_MASK | 13);
    LOAD_AND_ASSERT_CURRENT_CTX_IS(1, t_ctx, curr);
    CHECK_ARRAY_EQUAL(expected, curr, 1);

    t_ctx.exit(1729);//gibberish
    LOAD_AND_ASSERT_CURRENT_CTX_IS(1, t_ctx, curr);
    CHECK_ARRAY_EQUAL(expected, curr, 1);

    t_ctx.exit(SCOPED_MASK | 7);
    expected[0] = MERGE_GENERATED_TYPE | ((2 * 11 * 5 * 3) << GENERATED_COMBINATION_SHIFT) | 0x5;
    LOAD_AND_ASSERT_CURRENT_CTX_IS(1, t_ctx, curr);
    CHECK_ARRAY_EQUAL(expected, curr, 1);

    t_ctx.exit(SCOPED_MASK | 3);
    expected[0] = MERGE_GENERATED_TYPE | ((2 * 11 * 5) << GENERATED_COMBINATION_SHIFT) | 0x1;
    LOAD_AND_ASSERT_CURRENT_CTX_IS(1, t_ctx, curr);
    CHECK_ARRAY_EQUAL(expected, curr, 1);

    for (auto i = 0; i < 100; i++) {
        t_ctx.exit(SCOPED_MASK | 5);
        LOAD_AND_ASSERT_CURRENT_CTX_IS(1, t_ctx, curr);
        CHECK_ARRAY_EQUAL(expected, curr, 1);
    }

    t_ctx.exit(SCOPED_MASK | 5);
    expected[0] = MERGE_GENERATED_TYPE | ((2 * 11) << GENERATED_COMBINATION_SHIFT);
    LOAD_AND_ASSERT_CURRENT_CTX_IS(1, t_ctx, curr);
    CHECK_ARRAY_EQUAL(expected, curr, 1);

    t_ctx.exit(SCOPED_MASK | 11);
    expected[0] = 2;
    LOAD_AND_ASSERT_CURRENT_CTX_IS(1, t_ctx, curr);
    CHECK_ARRAY_EQUAL(expected, curr, 1);

    t_ctx.exit(2);
    LOAD_AND_ASSERT_CURRENT_CTX_IS(0, t_ctx, curr);
}

TEST(ThreadPerfCtxTracker__should_track_recursion__and_handle_scoping_well__when_strict_scoping_under_parent__and_starting_out_with_scoped_ctx) {
    TestEnv _;
    Registry r;
    ProbPct prob_pct;
    ThreadTracker t_ctx(r, prob_pct, 210);

    constexpr auto SCOPED_STRICT_MASK = static_cast<std::uint64_t>(MergeSemantic::scoped_strict) << PerfCtx::MERGE_SEMANTIC_SHIFT;
    constexpr auto SCOPED_MASK = static_cast<std::uint64_t>(MergeSemantic::scoped) << PerfCtx::MERGE_SEMANTIC_SHIFT;

    std::array<TracePt, MAX_NESTING> curr;

    reg(r, "2");
    reg(r, "3");
    reg(r, "5", PerfCtx::MergeSemantic::scoped_strict);
    reg(r, "7");

    LOAD_AND_ASSERT_CURRENT_CTX_IS(0, t_ctx, curr);
    t_ctx.enter(SCOPED_MASK | 2);
    LOAD_AND_ASSERT_CURRENT_CTX_IS(1, t_ctx, curr);
    std::array<TracePt, 1> expected{{SCOPED_MASK | 2}};
    CHECK_ARRAY_EQUAL(expected, curr, 1);

    t_ctx.enter(SCOPED_MASK | 7);
    LOAD_AND_ASSERT_CURRENT_CTX_IS(1, t_ctx, curr);
    expected[0] = MERGE_GENERATED_TYPE | ((2 * 7) << GENERATED_COMBINATION_SHIFT);
    CHECK_ARRAY_EQUAL(expected, curr, 1);

    t_ctx.enter(SCOPED_STRICT_MASK | 5);
    LOAD_AND_ASSERT_CURRENT_CTX_IS(1, t_ctx, curr);
    expected[0] = MERGE_GENERATED_TYPE | ((2 * 7 * 5) << GENERATED_COMBINATION_SHIFT) | 0x1;
    CHECK_ARRAY_EQUAL(expected, curr, 1);

    t_ctx.enter(SCOPED_STRICT_MASK | 5);
    LOAD_AND_ASSERT_CURRENT_CTX_IS(1, t_ctx, curr);
    expected[0] = MERGE_GENERATED_TYPE | ((2 * 7 * 5 * 5) << GENERATED_COMBINATION_SHIFT) | 0x4;
    CHECK_ARRAY_EQUAL(expected, curr, 1);
    
    t_ctx.enter(SCOPED_STRICT_MASK | 5);
    LOAD_AND_ASSERT_CURRENT_CTX_IS(1, t_ctx, curr);
    expected[0] = MERGE_GENERATED_TYPE | ((2 * 7 * 5 * 5 * 5) << GENERATED_COMBINATION_SHIFT) | 0x12;
    CHECK_ARRAY_EQUAL(expected, curr, 1);

    for (auto i = 0; i < 100; i++) {
        t_ctx.enter(100 - 1);//gibberish
        LOAD_AND_ASSERT_CURRENT_CTX_IS(1, t_ctx, curr);
        CHECK_ARRAY_EQUAL(expected, curr, 1);
    }

    for (auto i = 0; i < 100; i++) {
        t_ctx.exit(i);//gibberish
        LOAD_AND_ASSERT_CURRENT_CTX_IS(1, t_ctx, curr);
        CHECK_ARRAY_EQUAL(expected, curr, 1);
    }

    t_ctx.exit(SCOPED_STRICT_MASK | 5);
    expected[0] = MERGE_GENERATED_TYPE | ((2 * 7 * 5 * 5) << GENERATED_COMBINATION_SHIFT) | 0x4;
    LOAD_AND_ASSERT_CURRENT_CTX_IS(1, t_ctx, curr);
    CHECK_ARRAY_EQUAL(expected, curr, 1);

    t_ctx.exit(SCOPED_STRICT_MASK | 5);
    expected[0] = MERGE_GENERATED_TYPE | ((2 * 7 * 5) << GENERATED_COMBINATION_SHIFT) | 0x1;
    LOAD_AND_ASSERT_CURRENT_CTX_IS(1, t_ctx, curr);
    CHECK_ARRAY_EQUAL(expected, curr, 1);

    t_ctx.exit(SCOPED_STRICT_MASK | 5);
    expected[0] = MERGE_GENERATED_TYPE | ((2 * 7) << GENERATED_COMBINATION_SHIFT);
    LOAD_AND_ASSERT_CURRENT_CTX_IS(1, t_ctx, curr);
    CHECK_ARRAY_EQUAL(expected, curr, 1);

    t_ctx.exit(SCOPED_MASK | 7);
    expected[0] = SCOPED_MASK | 2;
    LOAD_AND_ASSERT_CURRENT_CTX_IS(1, t_ctx, curr);
    CHECK_ARRAY_EQUAL(expected, curr, 1);

    t_ctx.exit(SCOPED_MASK | 2);
    LOAD_AND_ASSERT_CURRENT_CTX_IS(0, t_ctx, curr);
}

TEST(ThreadPerfCtxTracker__should_understand_ctx__when_stacking_over_parent_____and_handle_overflow_well) {
    TestEnv _;
    Registry r;
    ProbPct prob_pct;
    ThreadTracker t_ctx(r, prob_pct, 210);

    constexpr auto STACK_MASK = static_cast<std::uint64_t>(MergeSemantic::stack_up) << PerfCtx::MERGE_SEMANTIC_SHIFT;
    constexpr auto PARENT_MASK = 0;


    std::array<TracePt, MAX_NESTING> curr;

    LOAD_AND_ASSERT_CURRENT_CTX_IS(0, t_ctx, curr);
    t_ctx.enter(PARENT_MASK | 2);
    LOAD_AND_ASSERT_CURRENT_CTX_IS(1, t_ctx, curr);
    std::array<TracePt, 1> expected{{PARENT_MASK | 2}};
    CHECK_ARRAY_EQUAL(expected, curr, 1);

    t_ctx.enter(STACK_MASK | 7);
    LOAD_AND_ASSERT_CURRENT_CTX_IS(1, t_ctx, curr);
    expected[0] = STACK_MASK | 7;
    CHECK_ARRAY_EQUAL(expected, curr, 1);

    t_ctx.enter(PARENT_MASK | 4);
    LOAD_AND_ASSERT_CURRENT_CTX_IS(1, t_ctx, curr);
    expected[0] = STACK_MASK | 7;
    CHECK_ARRAY_EQUAL(expected, curr, 1);

    t_ctx.enter(STACK_MASK | 9);
    LOAD_AND_ASSERT_CURRENT_CTX_IS(1, t_ctx, curr);
    expected[0] = STACK_MASK | 9;
    CHECK_ARRAY_EQUAL(expected, curr, 1);
    
    t_ctx.enter(STACK_MASK | 5);
    LOAD_AND_ASSERT_CURRENT_CTX_IS(1, t_ctx, curr);
    expected[0] = STACK_MASK | 5;
    CHECK_ARRAY_EQUAL(expected, curr, 1);

    for (auto i = 0; i < 100; i++) {
        t_ctx.enter(100 - 1);//gibberish
        LOAD_AND_ASSERT_CURRENT_CTX_IS(1, t_ctx, curr);
        CHECK_ARRAY_EQUAL(expected, curr, 1);
    }

    for (auto i = 0; i < 100; i++) {
        t_ctx.exit(i);//gibberish
        LOAD_AND_ASSERT_CURRENT_CTX_IS(1, t_ctx, curr);
        CHECK_ARRAY_EQUAL(expected, curr, 1);
    }

    t_ctx.exit(STACK_MASK | 5);
    expected[0] = STACK_MASK | 9;
    LOAD_AND_ASSERT_CURRENT_CTX_IS(1, t_ctx, curr);
    CHECK_ARRAY_EQUAL(expected, curr, 1);

    t_ctx.exit(STACK_MASK | 9);
    expected[0] = STACK_MASK | 7;
    LOAD_AND_ASSERT_CURRENT_CTX_IS(1, t_ctx, curr);
    CHECK_ARRAY_EQUAL(expected, curr, 1);

    t_ctx.exit(PARENT_MASK | 4);
    expected[0] = STACK_MASK | 7;
    LOAD_AND_ASSERT_CURRENT_CTX_IS(1, t_ctx, curr);
    CHECK_ARRAY_EQUAL(expected, curr, 1);

    t_ctx.exit(STACK_MASK | 7);
    expected[0] = PARENT_MASK | 2;
    LOAD_AND_ASSERT_CURRENT_CTX_IS(1, t_ctx, curr);
    CHECK_ARRAY_EQUAL(expected, curr, 1);

    t_ctx.exit(PARENT_MASK | 2);
    LOAD_AND_ASSERT_CURRENT_CTX_IS(0, t_ctx, curr);
}

TEST(ThreadPerfCtxTracker__should_handle_stacking_merge_semantic_for_first_ctx) {
    TestEnv _;
    Registry r;
    ProbPct prob_pct;
    ThreadTracker t_ctx(r, prob_pct, 210);

    constexpr auto STACK_MASK = static_cast<std::uint64_t>(MergeSemantic::stack_up) << PerfCtx::MERGE_SEMANTIC_SHIFT;
    constexpr auto PARENT_MASK = 0;


    std::array<TracePt, MAX_NESTING> curr;

    LOAD_AND_ASSERT_CURRENT_CTX_IS(0, t_ctx, curr);
    t_ctx.enter(STACK_MASK | 2);
    LOAD_AND_ASSERT_CURRENT_CTX_IS(1, t_ctx, curr);
    std::array<TracePt, 1> expected{{STACK_MASK | 2}};
    CHECK_ARRAY_EQUAL(expected, curr, 1);

    t_ctx.enter(STACK_MASK | 7);
    LOAD_AND_ASSERT_CURRENT_CTX_IS(1, t_ctx, curr);
    expected[0] = STACK_MASK | 7;
    CHECK_ARRAY_EQUAL(expected, curr, 1);

    t_ctx.enter(PARENT_MASK | 4);
    LOAD_AND_ASSERT_CURRENT_CTX_IS(1, t_ctx, curr);
    expected[0] = STACK_MASK | 7;
    CHECK_ARRAY_EQUAL(expected, curr, 1);

    t_ctx.exit(PARENT_MASK | 4);
    expected[0] = STACK_MASK | 7;
    LOAD_AND_ASSERT_CURRENT_CTX_IS(1, t_ctx, curr);
    CHECK_ARRAY_EQUAL(expected, curr, 1);

    t_ctx.exit(STACK_MASK | 7);
    expected[0] = STACK_MASK | 2;
    LOAD_AND_ASSERT_CURRENT_CTX_IS(1, t_ctx, curr);
    CHECK_ARRAY_EQUAL(expected, curr, 1);

    t_ctx.exit(STACK_MASK | 2);
    LOAD_AND_ASSERT_CURRENT_CTX_IS(0, t_ctx, curr);
}

TEST(ThreadPerfCtxTracker__should_understand_ctx__when_duplicating_over_parent_____and_handle_overflow_well) {
    TestEnv _;
    Registry r;
    ProbPct prob_pct;
    ThreadTracker t_ctx(r, prob_pct, 210);

    constexpr auto DUP_MASK = static_cast<std::uint64_t>(MergeSemantic::duplicate) << PerfCtx::MERGE_SEMANTIC_SHIFT;
    constexpr auto PARENT_MASK = 0;

    std::array<TracePt, MAX_NESTING> curr;

    LOAD_AND_ASSERT_CURRENT_CTX_IS(0, t_ctx, curr);
    t_ctx.enter(PARENT_MASK | 2);
    LOAD_AND_ASSERT_CURRENT_CTX_IS(1, t_ctx, curr);
    std::array<TracePt, MAX_NESTING> expected{{PARENT_MASK | 2}};
    CHECK_ARRAY_EQUAL(expected, curr, 1);

    t_ctx.enter(DUP_MASK | 7);
    LOAD_AND_ASSERT_CURRENT_CTX_IS(2, t_ctx, curr);
    expected[0] = PARENT_MASK | 2;
    expected[1] = DUP_MASK | 7;
    CHECK_ARRAY_EQUAL(expected, curr, 2);

    t_ctx.enter(PARENT_MASK | 4);
    LOAD_AND_ASSERT_CURRENT_CTX_IS(2, t_ctx, curr);
    expected[0] = PARENT_MASK | 2;
    expected[1] = DUP_MASK | 7;
    CHECK_ARRAY_EQUAL(expected, curr, 2);

    t_ctx.enter(DUP_MASK | 9);
    LOAD_AND_ASSERT_CURRENT_CTX_IS(3, t_ctx, curr);
    expected[0] = PARENT_MASK | 2;
    expected[1] = DUP_MASK | 7;
    expected[2] = DUP_MASK | 9;
    CHECK_ARRAY_EQUAL(expected, curr, 3);
    
    t_ctx.enter(DUP_MASK | 5);
    LOAD_AND_ASSERT_CURRENT_CTX_IS(4, t_ctx, curr);
    expected[0] = PARENT_MASK | 2;
    expected[1] = DUP_MASK | 7;
    expected[2] = DUP_MASK | 9;
    expected[3] = DUP_MASK | 5;
    CHECK_ARRAY_EQUAL(expected, curr, 4);

    for (auto i = 0; i < 100; i++) {
        t_ctx.enter(100 - 1);//gibberish
        LOAD_AND_ASSERT_CURRENT_CTX_IS(4, t_ctx, curr);
        CHECK_ARRAY_EQUAL(expected, curr, 4);
    }

    for (auto i = 0; i < 100; i++) {
        t_ctx.exit(i);//gibberish
        LOAD_AND_ASSERT_CURRENT_CTX_IS(4, t_ctx, curr);
        CHECK_ARRAY_EQUAL(expected, curr, 4);
    }

    t_ctx.exit(DUP_MASK | 5);
    expected[0] = PARENT_MASK | 2;
    expected[1] = DUP_MASK | 7;
    expected[2] = DUP_MASK | 9;
    LOAD_AND_ASSERT_CURRENT_CTX_IS(3, t_ctx, curr);
    CHECK_ARRAY_EQUAL(expected, curr, 3);

    t_ctx.exit(DUP_MASK | 9);
    expected[0] = PARENT_MASK | 2;
    expected[1] = DUP_MASK | 7;
    LOAD_AND_ASSERT_CURRENT_CTX_IS(2, t_ctx, curr);
    CHECK_ARRAY_EQUAL(expected, curr, 2);

    t_ctx.exit(PARENT_MASK | 4);
    expected[0] = PARENT_MASK | 2;
    expected[1] = DUP_MASK | 7;
    LOAD_AND_ASSERT_CURRENT_CTX_IS(2, t_ctx, curr);
    CHECK_ARRAY_EQUAL(expected, curr, 2);

    t_ctx.exit(DUP_MASK | 7);
    expected[0] = PARENT_MASK | 2;
    LOAD_AND_ASSERT_CURRENT_CTX_IS(1, t_ctx, curr);
    CHECK_ARRAY_EQUAL(expected, curr, 1);

    t_ctx.exit(PARENT_MASK | 2);
    LOAD_AND_ASSERT_CURRENT_CTX_IS(0, t_ctx, curr);
}

TEST(ThreadPerfCtxTracker__should_understand_ctx__with_duplicate_merge_for_first_ctx) {
    TestEnv _;
    Registry r;
    ProbPct prob_pct;
    ThreadTracker t_ctx(r, prob_pct, 210);

    constexpr auto DUP_MASK = static_cast<std::uint64_t>(MergeSemantic::duplicate) << PerfCtx::MERGE_SEMANTIC_SHIFT;
    constexpr auto PARENT_MASK = 0;

    std::array<TracePt, MAX_NESTING> curr;

    LOAD_AND_ASSERT_CURRENT_CTX_IS(0, t_ctx, curr);
    t_ctx.enter(DUP_MASK | 2);
    LOAD_AND_ASSERT_CURRENT_CTX_IS(1, t_ctx, curr);
    std::array<TracePt, MAX_NESTING> expected{{DUP_MASK | 2}};
    CHECK_ARRAY_EQUAL(expected, curr, 1);

    t_ctx.enter(DUP_MASK | 7);
    LOAD_AND_ASSERT_CURRENT_CTX_IS(2, t_ctx, curr);
    expected[0] = DUP_MASK | 2;
    expected[1] = DUP_MASK | 7;
    CHECK_ARRAY_EQUAL(expected, curr, 2);

    t_ctx.enter(PARENT_MASK | 4);
    LOAD_AND_ASSERT_CURRENT_CTX_IS(2, t_ctx, curr);
    expected[0] = DUP_MASK | 2;
    expected[1] = DUP_MASK | 7;
    CHECK_ARRAY_EQUAL(expected, curr, 2);

    t_ctx.exit(PARENT_MASK | 4);
    expected[0] = DUP_MASK | 2;
    expected[1] = DUP_MASK | 7;
    LOAD_AND_ASSERT_CURRENT_CTX_IS(2, t_ctx, curr);
    CHECK_ARRAY_EQUAL(expected, curr, 2);

    t_ctx.exit(DUP_MASK | 7);
    expected[0] = DUP_MASK | 2;
    LOAD_AND_ASSERT_CURRENT_CTX_IS(1, t_ctx, curr);
    CHECK_ARRAY_EQUAL(expected, curr, 1);

    t_ctx.exit(DUP_MASK | 2);
    LOAD_AND_ASSERT_CURRENT_CTX_IS(0, t_ctx, curr);
}

TEST(ThreadPerfCtxTracker__should_understand_ctx__with_duplicate_chain_broken_by_scoping_elements) {
    TestEnv _;
    Registry r;
    ProbPct prob_pct;
    ThreadTracker t_ctx(r, prob_pct, 210);

    constexpr auto DUP_MASK = static_cast<std::uint64_t>(MergeSemantic::duplicate) << PerfCtx::MERGE_SEMANTIC_SHIFT;
    constexpr auto SCOPED_MASK = static_cast<std::uint64_t>(MergeSemantic::scoped) << PerfCtx::MERGE_SEMANTIC_SHIFT;

    std::array<TracePt, MAX_NESTING> curr;

    reg(r, "2", PerfCtx::MergeSemantic::duplicate);
    reg(r, "3");
    reg(r, "5", PerfCtx::MergeSemantic::duplicate);
    reg(r, "7");
    reg(r, "11", PerfCtx::MergeSemantic::duplicate);
    reg(r, "13");


    LOAD_AND_ASSERT_CURRENT_CTX_IS(0, t_ctx, curr);
    t_ctx.enter(DUP_MASK | 2);
    LOAD_AND_ASSERT_CURRENT_CTX_IS(1, t_ctx, curr);
    std::array<TracePt, MAX_NESTING> expected{{DUP_MASK | 2}};
    CHECK_ARRAY_EQUAL(expected, curr, 1);

    t_ctx.enter(SCOPED_MASK | 7);
    LOAD_AND_ASSERT_CURRENT_CTX_IS(1, t_ctx, curr);
    expected[0] = MERGE_GENERATED_TYPE | ((2 * 7) << GENERATED_COMBINATION_SHIFT);
    CHECK_ARRAY_EQUAL(expected, curr, 1);

    t_ctx.enter(DUP_MASK | 5);
    LOAD_AND_ASSERT_CURRENT_CTX_IS(2, t_ctx, curr);
    expected[0] = MERGE_GENERATED_TYPE | ((2 * 7) << GENERATED_COMBINATION_SHIFT);
    expected[1] = DUP_MASK | 5;
    CHECK_ARRAY_EQUAL(expected, curr, 2);

    t_ctx.enter(DUP_MASK | 11);
    LOAD_AND_ASSERT_CURRENT_CTX_IS(3, t_ctx, curr);
    expected[0] = MERGE_GENERATED_TYPE | ((2 * 7) << GENERATED_COMBINATION_SHIFT);
    expected[1] = DUP_MASK | 5;
    expected[2] = DUP_MASK | 11;
    CHECK_ARRAY_EQUAL(expected, curr, 3);


    t_ctx.exit(DUP_MASK | 11);
    expected[0] = MERGE_GENERATED_TYPE | ((2 * 7) << GENERATED_COMBINATION_SHIFT);
    expected[1] = DUP_MASK | 5;
    LOAD_AND_ASSERT_CURRENT_CTX_IS(2, t_ctx, curr);
    CHECK_ARRAY_EQUAL(expected, curr, 2);

    t_ctx.exit(DUP_MASK | 5);
    expected[0] = MERGE_GENERATED_TYPE | ((2 * 7) << GENERATED_COMBINATION_SHIFT);
    LOAD_AND_ASSERT_CURRENT_CTX_IS(1, t_ctx, curr);
    CHECK_ARRAY_EQUAL(expected, curr, 1);

    t_ctx.exit(SCOPED_MASK | 7);
    expected[0] = DUP_MASK | 2;
    LOAD_AND_ASSERT_CURRENT_CTX_IS(1, t_ctx, curr);
    CHECK_ARRAY_EQUAL(expected, curr, 1);

    t_ctx.exit(DUP_MASK | 2);
    LOAD_AND_ASSERT_CURRENT_CTX_IS(0, t_ctx, curr);
}

TEST(ThreadPerfCtxTracker__should_understand_duplicating_ctx__with_duplicate_chain_broken_by_stacking_elements) {
    TestEnv _;
    Registry r;
    ProbPct prob_pct;
    ThreadTracker t_ctx(r, prob_pct, 210);

    constexpr auto DUP_MASK = static_cast<std::uint64_t>(MergeSemantic::duplicate) << PerfCtx::MERGE_SEMANTIC_SHIFT;
    constexpr auto STACKED_MASK = static_cast<std::uint64_t>(MergeSemantic::stack_up) << PerfCtx::MERGE_SEMANTIC_SHIFT;

    std::array<TracePt, MAX_NESTING> curr;

    LOAD_AND_ASSERT_CURRENT_CTX_IS(0, t_ctx, curr);
    t_ctx.enter(DUP_MASK | 2);
    LOAD_AND_ASSERT_CURRENT_CTX_IS(1, t_ctx, curr);
    std::array<TracePt, MAX_NESTING> expected{{DUP_MASK | 2}};
    CHECK_ARRAY_EQUAL(expected, curr, 1);

    t_ctx.enter(STACKED_MASK | 7);
    LOAD_AND_ASSERT_CURRENT_CTX_IS(1, t_ctx, curr);
    expected[0] = STACKED_MASK | 7;
    CHECK_ARRAY_EQUAL(expected, curr, 1);

    t_ctx.enter(DUP_MASK | 4);
    LOAD_AND_ASSERT_CURRENT_CTX_IS(2, t_ctx, curr);
    expected[0] = STACKED_MASK | 7;
    expected[1] = DUP_MASK | 4;
    CHECK_ARRAY_EQUAL(expected, curr, 2);

    t_ctx.enter(DUP_MASK | 9);
    LOAD_AND_ASSERT_CURRENT_CTX_IS(3, t_ctx, curr);
    expected[0] = STACKED_MASK | 7;
    expected[1] = DUP_MASK | 4;
    expected[2] = DUP_MASK | 9;
    CHECK_ARRAY_EQUAL(expected, curr, 3);

    t_ctx.exit(DUP_MASK | 9);
    expected[0] = STACKED_MASK | 7;
    expected[1] = DUP_MASK | 4;
    LOAD_AND_ASSERT_CURRENT_CTX_IS(2, t_ctx, curr);
    CHECK_ARRAY_EQUAL(expected, curr, 2);

    t_ctx.exit(DUP_MASK | 4);
    expected[0] = STACKED_MASK | 7;
    LOAD_AND_ASSERT_CURRENT_CTX_IS(1, t_ctx, curr);
    CHECK_ARRAY_EQUAL(expected, curr, 1);

    t_ctx.exit(STACKED_MASK | 7);
    expected[0] = DUP_MASK | 2;
    LOAD_AND_ASSERT_CURRENT_CTX_IS(1, t_ctx, curr);
    CHECK_ARRAY_EQUAL(expected, curr, 1);

    t_ctx.exit(DUP_MASK | 2);
    LOAD_AND_ASSERT_CURRENT_CTX_IS(0, t_ctx, curr);
}

TEST(ThreadPerfCtxTracker__should_duplicating__when_chain_has_several_parent_merge_elements_between_2_duplicate) {
    TestEnv _;
    Registry r;
    ProbPct prob_pct;
    ThreadTracker t_ctx(r, prob_pct, 210);

    constexpr auto DUP_MASK = static_cast<std::uint64_t>(MergeSemantic::duplicate) << PerfCtx::MERGE_SEMANTIC_SHIFT;
    constexpr auto PARENT_MASK = static_cast<std::uint64_t>(MergeSemantic::to_parent) << PerfCtx::MERGE_SEMANTIC_SHIFT;

    std::array<TracePt, MAX_NESTING> curr;

    LOAD_AND_ASSERT_CURRENT_CTX_IS(0, t_ctx, curr);
    t_ctx.enter(DUP_MASK | 2);
    LOAD_AND_ASSERT_CURRENT_CTX_IS(1, t_ctx, curr);
    std::array<TracePt, MAX_NESTING> expected{{DUP_MASK | 2}};
    CHECK_ARRAY_EQUAL(expected, curr, 1);

    t_ctx.enter(PARENT_MASK | 7);
    LOAD_AND_ASSERT_CURRENT_CTX_IS(1, t_ctx, curr);
    expected[0] = DUP_MASK | 2;
    CHECK_ARRAY_EQUAL(expected, curr, 1);

    t_ctx.enter(PARENT_MASK | 4);
    LOAD_AND_ASSERT_CURRENT_CTX_IS(1, t_ctx, curr);
    expected[0] = DUP_MASK | 2;
    CHECK_ARRAY_EQUAL(expected, curr, 1);

    t_ctx.enter(DUP_MASK | 9);
    LOAD_AND_ASSERT_CURRENT_CTX_IS(2, t_ctx, curr);
    expected[0] = DUP_MASK | 2;
    expected[1] = DUP_MASK | 9;
    CHECK_ARRAY_EQUAL(expected, curr, 2);

    t_ctx.exit(DUP_MASK | 9);
    expected[0] = DUP_MASK | 2;
    LOAD_AND_ASSERT_CURRENT_CTX_IS(1, t_ctx, curr);
    CHECK_ARRAY_EQUAL(expected, curr, 1);

    t_ctx.exit(PARENT_MASK | 4);
    expected[0] = DUP_MASK | 2;
    LOAD_AND_ASSERT_CURRENT_CTX_IS(1, t_ctx, curr);
    CHECK_ARRAY_EQUAL(expected, curr, 1);

    t_ctx.exit(PARENT_MASK | 7);
    expected[0] = DUP_MASK | 2;
    LOAD_AND_ASSERT_CURRENT_CTX_IS(1, t_ctx, curr);
    CHECK_ARRAY_EQUAL(expected, curr, 1);

    t_ctx.exit(DUP_MASK | 2);
    LOAD_AND_ASSERT_CURRENT_CTX_IS(0, t_ctx, curr);
}

TEST(ThreadPerfCtxTracker__should_turn_on_recording_enough_times_to_meet_desired_coverage) {
    TestEnv _;
    Registry r;
    ProbPct prob_pct;
    ThreadTracker t_ctx(r, prob_pct, 210);

    PerfCtx::TracePt v2 = (static_cast<std::uint64_t>(10) << COVERAGE_PCT_SHIFT)
        | (static_cast<std::uint64_t>(PerfCtx::MergeSemantic::scoped) << MERGE_SEMANTIC_SHIFT)
        | 2;


    auto fire_count = 0;
    for (int i = 0; i < 10000; i++) {
        t_ctx.enter(v2);
        if (t_ctx.should_record()) fire_count++;
        t_ctx.exit(v2);
    }
    
    CHECK_CLOSE(1000, fire_count, 500);
}

TEST(ThreadPerfCtxTracker__should_track_stacking_merges___as_independent_ctxs___and_handles_exit_from_child_ctx_cleanly) {
    TestEnv _;
    Registry r;
    ProbPct prob_pct;
    ThreadTracker t_ctx(r, prob_pct, 210);

    PerfCtx::TracePt v2 = (static_cast<std::uint64_t>(10) << COVERAGE_PCT_SHIFT)
        | (static_cast<std::uint64_t>(PerfCtx::MergeSemantic::to_parent) << MERGE_SEMANTIC_SHIFT)
        | 2;

    PerfCtx::TracePt v3 = (static_cast<std::uint64_t>(50) << COVERAGE_PCT_SHIFT)
        | (static_cast<std::uint64_t>(PerfCtx::MergeSemantic::stack_up) << MERGE_SEMANTIC_SHIFT)
        | 3;

    PerfCtx::TracePt v5 = (static_cast<std::uint64_t>(25) << COVERAGE_PCT_SHIFT)
        | (static_cast<std::uint64_t>(PerfCtx::MergeSemantic::stack_up) << MERGE_SEMANTIC_SHIFT)
        | 5;


    auto no_v_fire_count = 0,
        v2_fire_count = 0,
        v3_fire_count = 0,
        v5_fire_count = 0,
        
        v2_ret_fire_count = 0,
        v3_ret_fire_count = 0,
        no_v_ret_fire_count = 0;
    
    for (int i = 0; i < 20000; i++) {
        if (t_ctx.should_record()) no_v_fire_count++;
        t_ctx.enter(v2);
        if (t_ctx.should_record()) v2_fire_count++;
        t_ctx.enter(v3);
        if (t_ctx.should_record()) v3_fire_count++;
        t_ctx.enter(v5);
        if (t_ctx.should_record()) v5_fire_count++;
        t_ctx.exit(v5);
        if (t_ctx.should_record()) v3_ret_fire_count++;
        t_ctx.exit(v3);
        if (t_ctx.should_record()) v2_ret_fire_count++;
        t_ctx.exit(v2);
        if (t_ctx.should_record()) no_v_ret_fire_count++;
    }

    CHECK_CLOSE(v2_fire_count,  2000, 1000);
    
    CHECK_CLOSE(v2_fire_count * 5,  v3_fire_count, 3000);
    CHECK_CLOSE(v3_fire_count,  v5_fire_count * 2, 1400);

    CHECK_EQUAL(v2_fire_count, v2_ret_fire_count);
    CHECK_EQUAL(v3_fire_count, v3_ret_fire_count);

    CHECK_EQUAL(no_v_ret_fire_count, no_v_fire_count);
    CHECK_EQUAL(no_v_fire_count, 0);
}

TEST(ThreadPerfCtxTracker__should_have_fair_sampling_in_duplicate_contexts) {
    TestEnv _;
    Registry r;
    ProbPct prob_pct;
    ThreadTracker t_ctx(r, prob_pct, 210);

    PerfCtx::TracePt v2 = (static_cast<std::uint64_t>(20) << COVERAGE_PCT_SHIFT)
        | (static_cast<std::uint64_t>(PerfCtx::MergeSemantic::to_parent) << MERGE_SEMANTIC_SHIFT)
        | 2;

    PerfCtx::TracePt v3 = (static_cast<std::uint64_t>(50) << COVERAGE_PCT_SHIFT)
        | (static_cast<std::uint64_t>(PerfCtx::MergeSemantic::duplicate) << MERGE_SEMANTIC_SHIFT)
        | 3;

    PerfCtx::TracePt v5 = (static_cast<std::uint64_t>(10) << COVERAGE_PCT_SHIFT)
        | (static_cast<std::uint64_t>(PerfCtx::MergeSemantic::duplicate) << MERGE_SEMANTIC_SHIFT)
        | 5;


    auto no_v_fire_count = 0,
        v2_fire_count = 0,
        v3_fire_count = 0,
        v5_fire_count = 0,
        
        v2_ret_fire_count = 0,
        v3_ret_fire_count = 0,
        no_v_ret_fire_count = 0;
    
    for (int i = 0; i < 10000; i++) {
        if (t_ctx.should_record()) no_v_fire_count++;
        t_ctx.enter(v2);
        if (t_ctx.should_record()) v2_fire_count++;
        t_ctx.enter(v3);
        if (t_ctx.should_record()) v3_fire_count++;
        t_ctx.enter(v5);
        if (t_ctx.should_record()) v5_fire_count++;
        t_ctx.exit(v5);
        if (t_ctx.should_record()) v3_ret_fire_count++;
        t_ctx.exit(v3);
        if (t_ctx.should_record()) v2_ret_fire_count++;
        t_ctx.exit(v2);
        if (t_ctx.should_record()) no_v_ret_fire_count++;
    }

    CHECK_CLOSE(v2_fire_count,  2000, 500);
    
    CHECK_EQUAL(v2_fire_count,  v3_fire_count);
    CHECK_EQUAL(v3_fire_count,  v5_fire_count);

    CHECK_EQUAL(v2_fire_count, v2_ret_fire_count);
    CHECK_EQUAL(v3_fire_count, v3_ret_fire_count);

    CHECK_EQUAL(no_v_ret_fire_count, no_v_fire_count);
    CHECK_EQUAL(no_v_fire_count, 0);
}

