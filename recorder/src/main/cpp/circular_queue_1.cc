#include "circular_queue_1.hh"
#include <iostream>
#include <unistd.h>

using namespace fk_bench;

template <typename TraceType, typename InMsg>
CircularQueue<TraceType, InMsg>::CircularQueue(QueueListener<TraceType> &listener,
                                               std::uint32_t maxFrameSize)
    : listener_(listener), input(0), output(0) {
    memset(buffer, 0, sizeof(buffer));
}

template <typename TraceType, typename InMsg>
CircularQueue<TraceType, InMsg>::~CircularQueue() {
}

template <typename TraceType, typename InMsg>
bool CircularQueue<TraceType, InMsg>::acquire_write_slot(size_t &slot) {
    size_t currentInput;
    size_t nextInput;
    do {
        currentInput = input.load(std::memory_order_seq_cst);
        nextInput = advance(currentInput);
        if (output.load(std::memory_order_seq_cst) == nextInput) {
            return false;
        }
        // TODO: have someone review the memory ordering constraints
    } while (!input.compare_exchange_weak(currentInput, nextInput, std::memory_order_relaxed));

    slot = currentInput;
    return true;
}

template <typename TraceType, typename InMsg>
void CircularQueue<TraceType, InMsg>::mark_committed(const size_t slot) {
    buffer[slot].is_committed.store(COMMITTED, std::memory_order_release);
}

template <typename TraceType, typename InMsg>
bool CircularQueue<TraceType, InMsg>::push(InMsg &in_msg) {
    size_t slot;
    if (!acquire_write_slot(slot))
        return false;
    
    TraceType &entry = buffer[slot];
    write(entry, in_msg);

    mark_committed(slot);
    return true;
}

template <typename TraceType, typename InMsg>
bool CircularQueue<TraceType, InMsg>::pop() {
    const auto current_output = output.load(std::memory_order_seq_cst);

    // queue is empty
    if (current_output == input.load(std::memory_order_seq_cst)) {
        return false;
    }

    // wait until we've finished writing to the buffer
    if (buffer[current_output].is_committed.load(std::memory_order_acquire) != COMMITTED)
        return false;

    listener_.record(buffer[current_output]);

    // ensure that the record is ready to be written to
    buffer[current_output].is_committed.store(UNCOMMITTED, std::memory_order_release);
    // Signal that you've finished reading the record
    output.store(advance(current_output), std::memory_order_seq_cst);

    return true;
}

template <typename TraceType, typename InMsg>
size_t CircularQueue<TraceType, InMsg>::advance(size_t index) const {
    return (index + 1) % Capacity;
}

template <typename TraceType, typename InMsg>
size_t CircularQueue<TraceType, InMsg>::size() {
    auto current_input = input.load(std::memory_order_relaxed);
    auto current_output = output.load(std::memory_order_relaxed);
    return current_input < current_output ? (current_input + Capacity - current_output)
                                          : current_input - current_output;
}

// cpu sampling

cpu::Queue::Queue(QueueListener<cpu::Sample> &listener, std::uint32_t maxFrameSize)
    : CircularQueue<cpu::Sample, cpu::InMsg>(listener, maxFrameSize) {
}

cpu::Queue::~Queue() {
}

void cpu::Queue::write(cpu::Sample &entry, cpu::InMsg &in_msg) {
    // Unable to use memcpy inside the push method because its not async-safe
    
    entry.trace.java_trace = in_msg.java_trace;
    entry.trace.native_frame = in_msg.native_frame;
    entry.trace.frame_count = in_msg.frame_count;
    entry.trace.type = in_msg.type;
    entry.trace.error = in_msg.error;
    
    entry.info = in_msg.info;
    entry.ctx_len = (entry.info == nullptr) ? 0 : entry.info->data.ctx_tracker.current(entry.ctx);
    entry.default_ctx = in_msg.default_ctx;
}

cpu::InMsg::InMsg(JVMPI_CallFrame* item, std::size_t frame_count, ThreadBucket *info, const BacktraceError error,
                  const bool default_ctx)
    : cpu::InMsg(BacktraceType::Java, info, error, default_ctx) {
    java_trace = item;
    native_frame = nullptr;
    this->frame_count = frame_count;
}

cpu::InMsg::InMsg(BacktraceType _type, ThreadBucket *_info, const BacktraceError _error,
                  bool _default_ctx)
    : type(_type), info(_info), error(_error), default_ctx(_default_ctx) {
}

template class CircularQueue<cpu::Sample, cpu::InMsg>;
