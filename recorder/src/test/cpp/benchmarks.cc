#include <benchmark/benchmark.h>
#include <cstring>
#include "globals.hh"
#include "concurrentqueue.h"
#include "circular_queue.hh"
#include <atomic>

// noop listener

template <typename T>
class NoopListener : public QueueListener<T> {
public:
    std::size_t counter;

    void record(const T &entry) override {
        ++counter;
    }
};

const auto max_ssize = 128u;

static auto listener = NoopListener<cpu::Sample>{};
static cpu::Queue q{listener, max_ssize};

static bool push_to_queue(cpu::Queue &q) {
    JVMPI_CallFrame frames[max_ssize];
    JVMPI_CallTrace ct{nullptr, max_ssize, frames};

    return q.push(cpu::InMsg(ct, nullptr, BacktraceError::Fkp_no_error, true));
}

std::string to_str(const char *name, int idx) {
    return std::string(name) + std::to_string(idx);
}

#include <iostream>

static void BM_Queue_Event_Push(benchmark::State &state) {
    std::size_t iteration;
    for (auto _ : state) {
        if (state.thread_index == 0) {
            // pop
            int c = state.threads - 1;
            while (c > 0) {
                c--;
                iteration = 100000;
                while (iteration > 0) {
                    while (!q.pop()) {
                    };
                    iteration--;
                }
            }
        } else {
            // push
            iteration = 100000;
            while (iteration > 0) {
                while (!push_to_queue(q))
                    ;
                iteration--;
            }
        }
    }
}
// Register the function as a benchmark
BENCHMARK(BM_Queue_Event_Push)
    ->Iterations(1)
    ->Repetitions(10)
    ->ReportAggregatesOnly()
    ->ThreadRange(2, 8);

BENCHMARK_MAIN();
