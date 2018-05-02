// Somewhat originally dervied from:
// http://www.codeproject.com/Articles/43510/Lock-Free-Single-Producer-Single-Consumer-Circular

// Multiple Producer, Single Consumer Queue

#ifndef CIRCULAR_QUEUE_H
#define CIRCULAR_QUEUE_H

#include "thread_map.hh"
#include "stacktraces.hh"
#include "blocking_events.hh"
#include <string.h>
#include <cstddef>

const size_t Size = 1024;

// Capacity is 1 larger than size to make sure
// we can use input = output as our "can't read" invariant
// and advance(output) = input as our "can't write" invariant
// effective the gap acts as a sentinel
const size_t Capacity = Size + 1;

template <typename TraceType> 
class QueueListener {
public:
    virtual void record(const TraceType& entry) = 0;

    virtual ~QueueListener() { }
};

const int COMMITTED = 1;
const int UNCOMMITTED = 0;

template <typename TraceType, typename InMsg> 
class CircularQueue {
public:
    typedef QueueListener<TraceType> Listener;

    explicit CircularQueue(Listener &listener, std::uint32_t maxFrameSize);

    virtual ~CircularQueue();
    
    bool push(const InMsg& in_msg);

    bool pop();
    
    size_t size();

private:
    Listener& listener_;

    std::atomic<size_t> input;
    std::atomic<size_t> output;

    TraceType buffer[Capacity];
    StackFrame* frame_buffer_[Capacity];

    size_t advance(size_t index) const;

    bool acquire_write_slot(size_t& slot);

    void mark_committed(const size_t slot);

protected:
    virtual void write(TraceType& entry, StackFrame* fb, const InMsg& in_msg) = 0;
};

namespace cpu {

    struct Sample {
        std::atomic<int> is_committed;
        Backtrace trace;
        ThreadBucket* info;
        PerfCtx::ThreadTracker::EffectiveCtx ctx;
        std::uint8_t ctx_len;
        bool default_ctx;
    };

    struct InMsg {
        BacktraceType type;
        ThreadBucket* info;
        BacktraceError error;
        bool default_ctx;

        union {

            struct {
                const JVMPI_CallTrace* ct;
            } java;

            struct {
                const NativeFrame* ct;
                std::uint32_t num_frames;
            } native;
        } ct;

        InMsg(const JVMPI_CallTrace& item, ThreadBucket* info, const BacktraceError error, const bool default_ctx);

        InMsg(const NativeFrame* trace, const std::uint32_t num_frames, ThreadBucket* info, const BacktraceError error, bool default_ctx);

    private:
        InMsg(BacktraceType type, ThreadBucket* info, const BacktraceError error, bool default_ctx);
    };

    class Queue : public CircularQueue<Sample, InMsg> {
    public:
        explicit Queue(Listener& listener, std::uint32_t maxFrameSize);

        ~Queue();

    protected:
        void write(Sample& entry, StackFrame* fb, const InMsg& in_msg);
    };
}

extern template class ::CircularQueue<cpu::Sample, cpu::InMsg>;

namespace iotrace {

    struct Sample {
        std::atomic<int> is_committed;
        ThreadBucket* thd_info;
        blocking::BlockingEvt evt;
        Backtrace trace;
        PerfCtx::ThreadTracker::EffectiveCtx ctx;
        std::uint8_t ctx_len;
        bool default_ctx;
    };

    struct InMsg {
        blocking::BlockingEvt evt;
        ThreadBucket* info;
        jvmtiFrameInfo* frames;
        int frame_count;
        bool default_ctx;

        InMsg(const blocking::BlockingEvt& evt, ThreadBucket* info, jvmtiFrameInfo* frames, const int frame_count, const bool default_ctx);
        
        InMsg(const blocking::BlockingEvt& evt, ThreadBucket* info, const bool default_ctx);
    };

    class Queue : public CircularQueue<Sample, InMsg> {
    public:

        explicit Queue(Listener& listener, std::uint32_t maxFrameSize);

        ~Queue();

    protected:
        void write(Sample& entry, StackFrame* fb, const InMsg& in_msg);
    };
}

extern template class ::CircularQueue<iotrace::Sample, iotrace::InMsg>;

#endif /* CIRCULAR_QUEUE_H */