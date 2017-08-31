#include "blocking_ring_buffer.hh"
#include "globals.hh"

#define METRIC_RING "out_ring"

BlockingRingBuffer::BlockingRingBuffer(std::uint32_t _capacity) :
    read_idx(0), write_idx(0), capacity(_capacity), available(0), buff(new std::uint8_t[capacity]), allow_writes(true),
    
    s_t_write(get_metrics_registry().new_timer({METRICS_DOMAIN, METRICS_TYPE_OP, METRIC_RING, "write"})),
    s_t_write_wait(get_metrics_registry().new_timer({METRICS_DOMAIN, METRICS_TYPE_OP, METRIC_RING, "write_wait"})),
    s_h_write_sz(get_metrics_registry().new_histogram({METRICS_DOMAIN, METRICS_TYPE_OP, METRIC_RING, "write_sz"})),

    s_t_read(get_metrics_registry().new_timer({METRICS_DOMAIN, METRICS_TYPE_OP, METRIC_RING, "read"})),
    s_t_read_wait(get_metrics_registry().new_timer({METRICS_DOMAIN, METRICS_TYPE_OP, METRIC_RING, "read_wait"})),
    s_h_read_sz(get_metrics_registry().new_histogram({METRICS_DOMAIN, METRICS_TYPE_OP, METRIC_RING, "read_sz"})) {
    
    logger->trace("Created a ring of capacity: {}, available: {}", capacity, available);
}

BlockingRingBuffer::~BlockingRingBuffer() {
    delete[] buff;
}

std::uint32_t BlockingRingBuffer::write(const std::uint8_t *from, std::uint32_t offset, std::uint32_t sz, bool do_block) {
    auto _ = s_t_write.time_scope();
    std::unique_lock<std::mutex> lock(m);

    std::uint32_t total_written = 0;
    while (allow_writes) {
        auto written_now = write_noblock(from, offset, sz);
        s_h_write_sz.update(written_now);
        total_written += written_now;
        logger->trace("Ring wrote {} bytes (hence a total of {} bytes)", written_now, total_written);
        if (written_now > 0) {
            logger->trace("Notifying readers");
            readable.notify_all();
        }
        if (do_block && (sz > 0)) {
            logger->trace("Waiting on ring being writable (available: {}, capacity: {})", available, capacity);
            auto __ = s_t_write_wait.time_scope();
            writable.wait(lock, [&] { return (available < capacity) || (! allow_writes); });
        } else break;
    }
    return total_written;
}

std::uint32_t BlockingRingBuffer::read(std::uint8_t *to, std::uint32_t offset, std::uint32_t sz, bool do_block) {
    auto _ = s_t_read.time_scope();
    std::unique_lock<std::mutex> lock(m);

    std::uint32_t total_read = 0;
    while (true) {
        auto read_now = read_noblock(to, offset, sz);
        s_h_read_sz.update(read_now);
        total_read += read_now;
        if (! allow_writes) break;
        logger->trace("Ring read {} bytes (hence a total of {} bytes)", read_now, total_read);
        if (read_now > 0) {
            logger->trace("Notifying writers");
            writable.notify_all();
        }
        if (do_block && (sz > 0)) {
            logger->trace("Waiting on ring being readable (available: {}, capacity: {})", available, capacity);
            auto __ = s_t_read_wait.time_scope();
            readable.wait(lock, [&] { return (available > 0) || (! allow_writes); });
        } else break;
    }
    return total_read;
}

std::uint32_t BlockingRingBuffer::write_noblock(const std::uint8_t *from, std::uint32_t& offset, std::uint32_t& sz) {
    if (available == capacity) return 0;
    
    auto available_before = available;

    if ((read_idx < write_idx) ||
        (read_idx == write_idx &&
         available == 0)) {
        auto copy_bytes = Util::min(sz, capacity - write_idx);
        std::memcpy(buff + write_idx, from + offset, copy_bytes);
        available += copy_bytes;
        logger->trace("Ring added {} available bytes", copy_bytes);
        offset += copy_bytes;
        sz -= copy_bytes;
        write_idx += copy_bytes;
        if (write_idx == capacity) write_idx = 0;
        if (sz == 0) {
            return copy_bytes;
        }
    }

    auto bytes_copied = available - available_before;
    auto copy_bytes = Util::min(sz, read_idx - write_idx);
    if (copy_bytes > 0) {
        std::memcpy(buff + write_idx, from + offset, copy_bytes);
        write_idx += copy_bytes;
        if (write_idx == capacity) write_idx = 0;
        bytes_copied += copy_bytes;
        available += copy_bytes;
        logger->trace("Ring added {} available bytes", copy_bytes);
        offset += copy_bytes;
        sz -= copy_bytes;
    }

    return bytes_copied;
}

std::uint32_t BlockingRingBuffer::read_noblock(std::uint8_t *to, std::uint32_t& offset, std::uint32_t& sz) {
    if (available == 0) return 0;

    auto available_before = available;

    if ((write_idx < read_idx) ||
        (read_idx == write_idx &&
         available == capacity)) {
        auto copy_bytes = Util::min(sz, capacity - read_idx);
        std::memcpy(to + offset, buff + read_idx, copy_bytes);
        available -= copy_bytes;
        offset += copy_bytes;
        sz -= copy_bytes;
        read_idx += copy_bytes;
        if (read_idx == capacity) read_idx = 0;
        if (sz == 0) {
            return copy_bytes;
        }
    }

    auto bytes_copied = available_before - available;
    auto copy_bytes = Util::min(sz, write_idx - read_idx);
    if (copy_bytes > 0) {
        std::memcpy(to + offset, buff + read_idx, copy_bytes);
        read_idx += copy_bytes;
        if (read_idx == capacity) read_idx = 0;
        bytes_copied += copy_bytes;
        available -= copy_bytes;
        offset += copy_bytes;
        sz -= copy_bytes;
    }

    return bytes_copied;
}

std::uint32_t BlockingRingBuffer::reset() {
    std::unique_lock<std::mutex> lock(m);
    
    auto old_available = available;
    read_idx = write_idx = available = 0;
    allow_writes = true;
    writable.notify_all();
    
    return old_available;
}

void BlockingRingBuffer::readonly() {
    std::unique_lock<std::mutex> lock(m);
    allow_writes = false;
    readable.notify_all();
    writable.notify_all();
}
