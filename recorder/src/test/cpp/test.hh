#ifndef HONEST_PROFILER_TEST_H
#define HONEST_PROFILER_TEST_H

#ifdef __APPLE__
#include <UnitTest++/UnitTest++/UnitTest++.h>
#else
#include <UnitTest++.h>
#endif

#include <iostream>
#include <globals.hh>
#include <perf_ctx.hh>
#include <stacktraces.hh>

extern PerfCtx::Registry* ctx_reg;
extern ProbPct* prob_pct;
extern medida::MetricsRegistry* metrics_registry;

std::ostream& operator<<(std::ostream& os, PerfCtx::MergeSemantic ms);
std::ostream& operator<<(std::ostream& os, BacktraceType type);

class TestEnv {
public:
    TestEnv();
    ~TestEnv();
};

#endif //HONEST_PROFILER_TEST_H
