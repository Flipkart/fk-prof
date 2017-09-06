#include "circular_queue.hh"
#include <iostream>
#include <unistd.h>

CircularQueue::CircularQueue(QueueListener& listener, std::uint32_t maxFrameSize) : listener_(listener), input(0), output(0) {
    memset(buffer, 0, sizeof(buffer));
    for (int i = 0; i < Capacity; ++i)
        frame_buffer_[i] = new StackFrame[maxFrameSize]();
}

CircularQueue::~CircularQueue() {
    for (int i = 0; i < Capacity; ++i)
        delete[] frame_buffer_[i];
}

bool CircularQueue::acquire_write_slot(size_t& slot) {
    size_t currentInput;
    size_t nextInput;
    do {
        currentInput = input.load(std::memory_order_seq_cst);
        nextInput = advance(currentInput);
        if (output.load(std::memory_order_seq_cst) == nextInput) {
            return false;
        }
        // TODO: have someone review the memory ordering constraints
    } while (!input.compare_exchange_strong(currentInput, nextInput, std::memory_order_relaxed));

    slot = currentInput;
    return true;
}

void CircularQueue::mark_committed(const size_t slot) {
    buffer[slot].is_committed.store(COMMITTED, std::memory_order_release);
}

bool CircularQueue::push(const JVMPI_CallTrace &item, const BacktraceError error, bool default_ctx, ThreadBucket* info) {
    size_t current_input;
    if (! acquire_write_slot(current_input)) return false;

    write(item, current_input, info, error, default_ctx);
    mark_committed(current_input);
    return true;
}

bool CircularQueue::push(const NativeFrame* item, const std::uint32_t num_frames, const BacktraceError error, bool default_ctx, ThreadBucket *info) {
    size_t current_input;
    if (! acquire_write_slot(current_input)) return false;

    write(item, num_frames, current_input, info, error, default_ctx);
    mark_committed(current_input);
    return true;
}

// Unable to use memcpy inside the push method because its not async-safe
void CircularQueue::write(const JVMPI_CallTrace &trace, const size_t slot, ThreadBucket* info, BacktraceError error, bool default_ctx) {
    StackFrame* fb = frame_buffer_[slot];
    for (int frame_num = 0; frame_num < trace.num_frames; ++frame_num) {
        // Padding already set to 0 by the consumer.

        fb[frame_num].jvmpi_frame.lineno = trace.frames[frame_num].lineno;
        fb[frame_num].jvmpi_frame.method_id = trace.frames[frame_num].method_id;
    }

    update_trace_info(fb, BacktraceType::Java, slot, trace.num_frames, info, error, default_ctx);
}

void CircularQueue::write(const NativeFrame* trace, const std::uint32_t num_frames, const size_t slot, ThreadBucket* info, const BacktraceError error, bool default_ctx) {
    StackFrame* fb = frame_buffer_[slot];
    for (int frame_num = 0; frame_num < num_frames; ++frame_num) {
        fb[frame_num].native_frame = trace[frame_num];
    }

    update_trace_info(fb, BacktraceType::Native, slot, num_frames, info, error, default_ctx);
}

void CircularQueue::update_trace_info(StackFrame* fb, const BacktraceType type, const size_t slot, const std::uint32_t num_frames, ThreadBucket* info, const BacktraceError error, bool default_ctx) {
    buffer[slot].trace.frames = fb;
    buffer[slot].trace.type = type;
    buffer[slot].trace.error = error;
    buffer[slot].trace.num_frames = num_frames;
    buffer[slot].info = info;
    buffer[slot].ctx_len = (info == nullptr) ? 0 : info->ctx_tracker.current(buffer[slot].ctx);
    buffer[slot].default_ctx = default_ctx;
}

bool CircularQueue::pop() {
    const auto current_output = output.load(std::memory_order_seq_cst);

    // queue is empty
    if (current_output == input.load(std::memory_order_seq_cst)) {
        return false;
    }

    // wait until we've finished writing to the buffer
    while (buffer[current_output].is_committed.load(std::memory_order_acquire) != COMMITTED) {
        usleep(1);
    }

    listener_.record(buffer[current_output].trace, buffer[current_output].info, buffer[current_output].ctx_len, &buffer[current_output].ctx, buffer[current_output].default_ctx);
    
    // ensure that the record is ready to be written to
    buffer[current_output].is_committed.store(UNCOMMITTED, std::memory_order_release);
    // Signal that you've finished reading the record
    output.store(advance(current_output), std::memory_order_seq_cst);

    return true;
}

size_t CircularQueue::advance(size_t index) const {
    return (index + 1) % Capacity;
}
