#include <benchmark/benchmark.h>
#include <cstring>
#include <atomic>
#include "globals.hh"
#include "concurrentqueue.h"
#include "circular_queue.hh"
#include "circular_queue_1.hh"
#include "lstack.hh"

// noop listener

constexpr auto max_ssize = 128u;
LFStack<JVMPI_CallFrame> ss_stack(max_ssize, 1024);

std::atomic<std::size_t> stack_sz(10240);

template <typename T>
class NoopListener : public QueueListener<T> {
public:
    std::size_t counter;

    void record(const T &entry) override {
        ++counter;
    }
};

class NoopListener_1 : public fk_bench::QueueListener<fk_bench::cpu::Sample> {
public:
    std::size_t counter;

    void record(const fk_bench::cpu::Sample &entry) override {
        ++counter;
        ss_stack.push(entry.trace.java_trace);
        stack_sz++;
    }
};

static auto listener_1 = NoopListener_1{};
static fk_bench::cpu::Queue q_1{listener_1, max_ssize};

static auto listener = NoopListener<cpu::Sample>{};
static cpu::Queue q{listener, max_ssize};

static bool push_to_queue(cpu::Queue &q) {
    JVMPI_CallFrame frames[max_ssize];
    JVMPI_CallTrace ct{nullptr, max_ssize, frames};

    return q.push(cpu::InMsg(ct, nullptr, BacktraceError::Fkp_no_error, true));
}

static bool push_to_queue(fk_bench::cpu::Queue &q) {
    JVMPI_CallFrame *frames = nullptr;

    do {
        frames = ss_stack.pop();
    } while (frames == nullptr);

    stack_sz--;
    auto msg = fk_bench::cpu::InMsg(frames, max_ssize, nullptr, BacktraceError::Fkp_no_error, true);
    return q.push(msg);
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
                iteration = 1000000;
                while (iteration > 0) {
                    while (!q.pop()) {
                    };
                    iteration--;
                }
            }
        } else {
            // push
            iteration = 1000000;
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
    ->ThreadRange(2, 2);

static void BM_Queue_1_Event_Push(benchmark::State &state) {
    std::size_t iteration;
    for (auto _ : state) {
        if (state.thread_index == 0) {
            // pop
            int c = state.threads - 1;
            while (c > 0) {
                c--;
                iteration = 1000000;
                while (iteration > 0) {
                    while (!q_1.pop()) {
                    };
                    iteration--;
                }
            }
        } else {
            // push
            iteration = 1000000;
            while (iteration > 0) {
                while (!push_to_queue(q_1))
                    ;
                iteration--;
            }
        }
    }
}
// Register the function as a benchmark
BENCHMARK(BM_Queue_1_Event_Push)
    ->Iterations(1)
    ->Repetitions(10)
    ->ReportAggregatesOnly()
    ->ThreadRange(2, 2);

BENCHMARK_MAIN();
