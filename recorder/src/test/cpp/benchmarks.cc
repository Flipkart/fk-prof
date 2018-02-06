#include <benchmark/benchmark.h>
#include <cstring>
#include "globals.hh"
#include "concurrentqueue.h"
#include "circular_queue.hh"

// noop listener

template <typename T>
class NoopListener : public QueueListener<T> {
public:
    std::size_t counter;

    void record(const T& entry) override {
        ++counter;
    }
};

const auto max_ssize = 128u;

static auto listener = NoopListener<cpu::Sample>{};
static cpu::Queue q{listener, max_ssize};

static bool push_to_queue(cpu::Queue& q) {
    JVMPI_CallFrame frames[max_ssize];
    JVMPI_CallTrace ct{nullptr, max_ssize, frames};

    return q.push(cpu::InMsg(ct, nullptr, BacktraceError::Fkp_no_error, true));
}

static void BM_Queue_Event_Push(benchmark::State& state) {
    std::size_t push_skipped = 0, pop_skipped = 0;

    for (auto _ : state) {
        for (int i = 0; i < 128; ++i) {
            push_skipped += (push_to_queue(q) ? 0 : 1);
        }
        for (int i = 0; i < 128; ++i) {
            pop_skipped += (q.pop() ? 0 : 1);
        }
    }
    state.counters["push_skipped"] = push_skipped;
    state.counters["pop_skipped"] = pop_skipped;
}
// Register the function as a benchmark
BENCHMARK(BM_Queue_Event_Push)->Iterations(10000)->Repetitions(10)->ThreadRange(1, 4);

BENCHMARK_MAIN();
