// Somewhat originally dervied from:
// http://www.codeproject.com/Articles/43510/Lock-Free-Single-Producer-Single-Consumer-Circular

// Multiple Producer, Single Consumer Queue

#ifndef CIRCULAR_QUEUE_H
#define CIRCULAR_QUEUE_H

#include "thread_map.hh"
#include "stacktraces.hh"
#include <string.h>
#include <cstddef>

const size_t Size = 1024;

// Capacity is 1 larger than size to make sure
// we can use input = output as our "can't read" invariant
// and advance(output) = input as our "can't write" invariant
// effective the gap acts as a sentinel
const size_t Capacity = Size + 1;

class QueueListener {
public:
    virtual void record(const Backtrace& item, ThreadBucket* info = nullptr, std::uint8_t ctx_len = 0, PerfCtx::ThreadTracker::EffectiveCtx* ctx = nullptr, bool default_ctx = false) = 0;

    virtual ~QueueListener() { }
};

const int COMMITTED = 1;
const int UNCOMMITTED = 0;

struct TraceHolder {
    std::atomic<int> is_committed;
    Backtrace trace;
    ThreadBucket *info;
    PerfCtx::ThreadTracker::EffectiveCtx ctx;
    std::uint8_t ctx_len;
    bool default_ctx;
};

class CircularQueue {
public:
    explicit CircularQueue(QueueListener &listener, std::uint32_t maxFrameSize);

    ~CircularQueue();

    // We tolerate following obnoxious push overloads (and write overloads) for performance reasons
    //      (this is already a 1-copy impl, the last thing we want is make it 2-copy, just to make it pretty).
    // Yuck! I know...
    bool push(const JVMPI_CallTrace &item, const BacktraceError error, bool default_ctx, ThreadBucket *info = nullptr);

    bool push(const NativeFrame* item, const std::uint32_t num_frames, const BacktraceError error, bool default_ctx, ThreadBucket *info = nullptr);

    bool pop();

private:

    QueueListener &listener_;

    std::atomic<size_t> input;
    std::atomic<size_t> output;

    TraceHolder buffer[Capacity];
    StackFrame *frame_buffer_[Capacity];

    size_t advance(size_t index) const;

    bool acquire_write_slot(size_t& slot);

    void update_trace_info(StackFrame* fb, const BacktraceType type, const size_t slot, const std::uint32_t num_frames, ThreadBucket* info, const BacktraceError error, bool default_ctx);

    void write(const JVMPI_CallTrace& item, const size_t slot, ThreadBucket* info, const BacktraceError error, bool default_ctx);

    void write(const NativeFrame* trace, const std::uint32_t num_frames, const size_t slot, ThreadBucket* info, const BacktraceError error, bool default_ctx);

    void mark_committed(const size_t slot);
};

#endif /* CIRCULAR_QUEUE_H */
