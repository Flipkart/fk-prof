#include <thread>
#include <vector>
#include <iostream>
#include <cstdint>
#include <chrono>
#include "fixtures.hh"
#include "test.hh"
#include "../../main/cpp/blocking_ring_buffer.hh"

#define ASSERT_IS_SEQUENCE(buff, offset, len, init)            \
    {                                                          \
        int counter = init;                                    \
        for (int i = 0; i < len; i++) {                        \
            std::uint32_t val = buff[offset + i];              \
            CHECK_EQUAL(counter++, val);                       \
        }                                                      \
    }

TEST(BlockingRingBuffer_should_RW_____when_read_ptr_before_write_ptr) {
    TestEnv _;
    std::uint8_t write_buff[100], read_buff[100];
    BlockingRingBuffer ring(100);
    for (int i = 0; i < sizeof(write_buff); i++) {
        write_buff[i] = i % 256;
    }
    CHECK_EQUAL(20, ring.write(write_buff, 0, 20));
    CHECK_EQUAL(1, ring.read(read_buff, 0, 1));
    ASSERT_IS_SEQUENCE(read_buff, 0, 1, 0);
    CHECK_EQUAL(1, ring.read(read_buff, 0, 1));
    ASSERT_IS_SEQUENCE(read_buff, 0, 1, 1);
    CHECK_EQUAL(2, ring.read(read_buff, 0, 2));
    ASSERT_IS_SEQUENCE(read_buff, 0, 2, 2);
    CHECK_EQUAL(4, ring.read(read_buff, 0, 4));
    ASSERT_IS_SEQUENCE(read_buff, 0, 4, 4);
    CHECK_EQUAL(12, ring.read(read_buff, 0, 12));
    ASSERT_IS_SEQUENCE(read_buff, 0, 12, 8);
    CHECK_EQUAL(0, ring.read(read_buff, 0, 1, false));
}

TEST(BlockingRingBuffer_should_RW_____when_write_ptr_wraps_over____one_shot) {
    TestEnv _;
    std::uint8_t write_buff[100], read_buff[100];
    BlockingRingBuffer ring(100);
    for (int i = 0; i < sizeof(write_buff); i++) {
        write_buff[i] = i % 256;
    }
    CHECK_EQUAL(20, ring.write(write_buff, 0, 20));
    CHECK_EQUAL(10, ring.read(read_buff, 0, 10));

    CHECK_EQUAL(85, ring.write(write_buff, 0, 85));
    CHECK_EQUAL(10, ring.read(read_buff, 0, 10));
    ASSERT_IS_SEQUENCE(read_buff, 0, 10, 10);
    CHECK_EQUAL(75, ring.read(read_buff, 0, 75));
    ASSERT_IS_SEQUENCE(read_buff, 0, 75, 0);
}

TEST(BlockingRingBuffer_should_RW_____when_write_ptr_wraps_over____across_2_writes) {
    TestEnv _;
    std::uint8_t write_buff[100], read_buff[100];
    BlockingRingBuffer ring(100);
    for (int i = 0; i < sizeof(write_buff); i++) {
        write_buff[i] = i % 256;
    }
    CHECK_EQUAL(20, ring.write(write_buff, 0, 20));
    CHECK_EQUAL(ring.read(read_buff, 0, 10), 10);

    CHECK_EQUAL(80, ring.write(write_buff, 0, 80));
    CHECK_EQUAL(5, ring.write(write_buff, 80, 5));
    CHECK_EQUAL(10, ring.read(read_buff, 0, 10));
    ASSERT_IS_SEQUENCE(read_buff, 0, 10, 10);
    CHECK_EQUAL(75, ring.read(read_buff, 0, 75));
    ASSERT_IS_SEQUENCE(read_buff, 0, 75, 0);
}

TEST(BlockingRingBuffer_should_RW_____when_read_ptr_wraps_over____across_2_reads) { //one shot case is already tested
    TestEnv _;
    std::uint8_t write_buff[100], read_buff[100];
    BlockingRingBuffer ring(100);
    for (int i = 0; i < sizeof(write_buff); i++) {
        write_buff[i] = i % 256;
    }
    CHECK_EQUAL(20, ring.write(write_buff, 0, 20));
    CHECK_EQUAL(10, ring.read(read_buff, 0, 10));

    CHECK_EQUAL(85, ring.write(write_buff, 0, 85));
    CHECK_EQUAL(10, ring.read(read_buff, 0, 10));
    ASSERT_IS_SEQUENCE(read_buff, 0, 10, 10);
    CHECK_EQUAL(70, ring.read(read_buff, 0, 70));
    ASSERT_IS_SEQUENCE(read_buff, 0, 70, 0);
    CHECK_EQUAL(5, ring.read(read_buff, 0, 5));
    ASSERT_IS_SEQUENCE(read_buff, 0, 5, 70);
}

TEST(BlockingRingBuffer_should_not_block_when_can_write) { 
    TestEnv _;
    std::uint8_t write_buff[100];
    BlockingRingBuffer ring(100);
    for (int i = 0; i < sizeof(write_buff); i++) {
        write_buff[i] = i % 256;
    }
    auto start = std::chrono::steady_clock::now();
    CHECK_EQUAL(20, ring.write(write_buff, 0, 20));
    CHECK_CLOSE(std::chrono::duration_cast<std::chrono::microseconds>(std::chrono::steady_clock::now() - start).count(), 500, 500);
}

TEST(BlockingRingBuffer_should_not_block_when_can_write____but_needs_wrapping_over) {
    TestEnv _;
    std::uint8_t write_buff[100];
    BlockingRingBuffer ring(100);
    for (int i = 0; i < sizeof(write_buff); i++) {
        write_buff[i] = i % 256;
    }
    CHECK_EQUAL(90, ring.write(write_buff, 0, 90));
    CHECK_EQUAL(90, ring.read(write_buff, 0, 90));
    auto start = std::chrono::steady_clock::now();
    CHECK_EQUAL(20, ring.write(write_buff, 0, 20));
    CHECK_CLOSE(std::chrono::duration_cast<std::chrono::microseconds>(std::chrono::steady_clock::now() - start).count(), 500, 500);
}

TEST(BlockingRingBuffer_should_not_block_when_can_read) {
    TestEnv _;
    std::uint8_t write_buff[100];
    BlockingRingBuffer ring(100);
    for (int i = 0; i < sizeof(write_buff); i++) {
        write_buff[i] = i % 256;
    }
    CHECK_EQUAL(90, ring.write(write_buff, 0, 90));
    auto start = std::chrono::steady_clock::now();
    CHECK_EQUAL(90, ring.read(write_buff, 0, 90));
    CHECK_CLOSE(std::chrono::duration_cast<std::chrono::microseconds>(std::chrono::steady_clock::now() - start).count(), 500, 500);
}

TEST(BlockingRingBuffer_should_not_block_when_can_read____but_needs_wrapping_over) {
    TestEnv _;
    std::uint8_t write_buff[100];
    BlockingRingBuffer ring(100);
    for (int i = 0; i < sizeof(write_buff); i++) {
        write_buff[i] = i % 256;
    }
    CHECK_EQUAL(90, ring.write(write_buff, 0, 90));
    CHECK_EQUAL(90, ring.read(write_buff, 0, 90));
    CHECK_EQUAL(50, ring.write(write_buff, 0, 50));
    auto start = std::chrono::steady_clock::now();
    CHECK_EQUAL(50, ring.read(write_buff, 0, 50));
    CHECK_CLOSE(std::chrono::duration_cast<std::chrono::microseconds>(std::chrono::steady_clock::now() - start).count(), 500, 500);
}

TEST(BlockingRingBuffer_should_not_block_when_can_not_read____but_is_a_non_blocking_read_call) {
    TestEnv _;
    std::uint8_t buff[100];
    BlockingRingBuffer ring(100);
    auto start = std::chrono::steady_clock::now();
    CHECK_EQUAL(0, ring.read(buff, 0, 10, false));
    CHECK_CLOSE(std::chrono::duration_cast<std::chrono::microseconds>(std::chrono::steady_clock::now() - start).count(), 100, 100);
}

TEST(BlockingRingBuffer_should_not_block_when_can_not_write____but_is_a_non_blocking_write_call) {
    TestEnv _;
    std::uint8_t buff[100];
    BlockingRingBuffer ring(100);
    CHECK_EQUAL(100, ring.write(buff, 0, 100));
    auto start = std::chrono::steady_clock::now();
    CHECK_EQUAL(0, ring.write(buff, 0, 10, false));
    CHECK_CLOSE(std::chrono::duration_cast<std::chrono::microseconds>(std::chrono::steady_clock::now() - start).count(), 100, 100);
}

TEST(BlockingRingBuffer_should_not_write____more_than_available_capacity) {
    TestEnv _;
    std::uint8_t buff[100];
    for (int i = 0; i < sizeof(buff); i++) {
        buff[i] = i % 256;
    }
    BlockingRingBuffer ring(100);
    CHECK_EQUAL(80, ring.write(buff, 0, 80));
    CHECK_EQUAL(20, ring.write(buff, 0, 30, false));
    CHECK_EQUAL(100, ring.read(buff, 0, 100, false));
    ASSERT_IS_SEQUENCE(buff, 0, 80, 0);
    ASSERT_IS_SEQUENCE(buff, 80, 20, 0);
}

TEST(BlockingRingBuffer_should_not_read____more_than_available_content) {
    TestEnv _;
    std::uint8_t write_buff[100], read_buff[100];
    for (int i = 0; i < sizeof(write_buff); i++) {
        write_buff[i] = i % 256;
    }
    read_buff[10] = 42;
    
    BlockingRingBuffer ring(100);
    CHECK_EQUAL(10, ring.write(write_buff, 0, 10));
    CHECK_EQUAL(10, ring.read(read_buff, 0, 20, false));
    ASSERT_IS_SEQUENCE(read_buff, 0, 10, 0);
    CHECK_EQUAL(42, read_buff[10]);
}

void read_after_sleep(std::uint32_t ms, BlockingRingBuffer& ring, std::uint8_t* buff, std::uint32_t offset, std::uint32_t sz, std::uint32_t& bytes_read) {
    std::this_thread::sleep_for(std::chrono::milliseconds(ms));
    bytes_read = ring.read(buff, offset, sz);
}

void write_after_sleep(std::uint32_t ms, BlockingRingBuffer& ring, std::uint8_t* buff, std::uint32_t offset, std::uint32_t sz, std::uint32_t& bytes_written) {
    std::this_thread::sleep_for(std::chrono::milliseconds(ms));
    bytes_written = ring.write(buff, offset, sz);
}

TEST(BlockingRingBuffer_should_block_write____if_it_became_full_midway_of_a_write) {
    TestEnv _;
    std::uint32_t bytes_read_after_sleep = 0;
    std::uint8_t write_buff[100], read_buff[100];
    for (int i = 0; i < sizeof(write_buff); i++) {
        write_buff[i] = i % 256;
    }
    BlockingRingBuffer ring(100);
    CHECK_EQUAL(80, ring.write(write_buff, 0, 80));

    std::thread t(read_after_sleep, static_cast<std::uint32_t>(100), std::ref(ring), read_buff, static_cast<std::uint32_t>(0), static_cast<std::uint32_t>(10), std::ref(bytes_read_after_sleep)); 

    auto start = std::chrono::steady_clock::now();
    CHECK_EQUAL(30, ring.write(write_buff, 0, 30));
    CHECK_CLOSE(100, std::chrono::duration_cast<std::chrono::milliseconds>(std::chrono::steady_clock::now() - start).count(), 5);
    t.join();

    ASSERT_IS_SEQUENCE(read_buff, 0, 10, 0);
    
    CHECK_EQUAL(100, ring.read(read_buff, 0, 100, false));
    
    ASSERT_IS_SEQUENCE(read_buff, 0, 70, 10);
    ASSERT_IS_SEQUENCE(read_buff, 70, 30, 0);
    CHECK_EQUAL(10, bytes_read_after_sleep);
}

TEST(BlockingRingBuffer_should_block_read____if_it_became_empty_midway_of_a_read) {
    TestEnv _;
    std::uint32_t bytes_written_after_sleep = 0;
    std::uint8_t write_buff[100], read_buff[100];
    for (int i = 0; i < sizeof(write_buff); i++) {
        write_buff[i] = i % 256;
    }
    BlockingRingBuffer ring(100);
    CHECK_EQUAL(95, ring.write(write_buff, 0, 95));
    CHECK_EQUAL(90, ring.read(read_buff, 0, 90));//just moving the pointer around so we can wrap around

    std::thread t(write_after_sleep, static_cast<std::uint32_t>(100), std::ref(ring), write_buff, static_cast<std::uint32_t>(0), static_cast<std::uint32_t>(30), std::ref(bytes_written_after_sleep)); 

    auto start = std::chrono::steady_clock::now();
    CHECK_EQUAL(20, ring.read(read_buff, 0, 20));
    CHECK_CLOSE(100, std::chrono::duration_cast<std::chrono::milliseconds>(std::chrono::steady_clock::now() - start).count(), 5);

    t.join();
    
    ASSERT_IS_SEQUENCE(read_buff, 0, 5, 90);
    ASSERT_IS_SEQUENCE(read_buff, 5, 15, 0);
    
    CHECK_EQUAL(15, ring.read(read_buff, 0, 15, false));

    ASSERT_IS_SEQUENCE(read_buff, 0, 15, 15);
    CHECK_EQUAL(30, bytes_written_after_sleep);
}

TEST(BlockingRingBuffer_should_block_read____if_no_data_has_ever_been_written_to_ring) {
    TestEnv _;
    std::uint32_t bytes_written_after_sleep = 0;
    std::uint8_t write_buff[100], read_buff[100];
    for (int i = 0; i < sizeof(write_buff); i++) {
        write_buff[i] = i % 256;
    }
    BlockingRingBuffer ring(100);
    std::thread t(write_after_sleep, static_cast<std::uint32_t>(100), std::ref(ring), write_buff, static_cast<std::uint32_t>(0), static_cast<std::uint32_t>(30), std::ref(bytes_written_after_sleep)); 

    auto start = std::chrono::steady_clock::now();
    CHECK_EQUAL(20, ring.read(read_buff, 0, 20));
    CHECK_CLOSE(100, std::chrono::duration_cast<std::chrono::milliseconds>(std::chrono::steady_clock::now() - start).count(), 5);

    t.join();
    
    ASSERT_IS_SEQUENCE(read_buff, 0, 20, 0);
    CHECK_EQUAL(10, ring.read(read_buff, 0, 10));
    ASSERT_IS_SEQUENCE(read_buff, 0, 10, 20);
}

TEST(BlockingRingBuffer_should_block_write____if_ring_is_full___and_no_data_has_ever_been_read) {
    TestEnv _;
    std::uint32_t bytes_written_after_sleep = 0;
    std::uint8_t write_buff[100], read_buff[100];
    for (int i = 0; i < sizeof(write_buff); i++) {
        write_buff[i] = i % 256;
    }
    BlockingRingBuffer ring(100);
    CHECK_EQUAL(100, ring.write(write_buff, 0, 100));

    std::thread t(read_after_sleep, static_cast<std::uint32_t>(100), std::ref(ring), read_buff, static_cast<std::uint32_t>(0), static_cast<std::uint32_t>(100), std::ref(bytes_written_after_sleep)); 

    auto start = std::chrono::steady_clock::now();
    CHECK_EQUAL(20, ring.write(read_buff, 0, 20));
    CHECK_CLOSE(100, std::chrono::duration_cast<std::chrono::milliseconds>(std::chrono::steady_clock::now() - start).count(), 5);

    t.join();
    
    ASSERT_IS_SEQUENCE(read_buff, 0, 100, 0);
    CHECK_EQUAL(20, ring.read(read_buff, 0, 20, false));
    ASSERT_IS_SEQUENCE(read_buff, 0, 20, 0);
}

TEST(BlockingRingBuffer_should_block_read____when_ring_is_empty___but_has_seen_some_io_before_becoming_empty) {
    TestEnv _;
    std::uint32_t bytes_written_after_sleep = 0;
    std::uint8_t write_buff[100], read_buff[100];
    for (int i = 0; i < sizeof(write_buff); i++) {
        write_buff[i] = i % 256;
    }
    BlockingRingBuffer ring(100);
    CHECK_EQUAL(15, ring.write(write_buff, 0, 15));
    CHECK_EQUAL(15, ring.read(read_buff, 0, 15));

    std::thread t(write_after_sleep, static_cast<std::uint32_t>(100), std::ref(ring), write_buff, static_cast<std::uint32_t>(0), static_cast<std::uint32_t>(10), std::ref(bytes_written_after_sleep)); 

    auto start = std::chrono::steady_clock::now();
    CHECK_EQUAL(10, ring.read(read_buff, 0, 10));
    CHECK_CLOSE(100, std::chrono::duration_cast<std::chrono::milliseconds>(std::chrono::steady_clock::now() - start).count(), 5);

    t.join();
    
    ASSERT_IS_SEQUENCE(read_buff, 0, 10, 0);
    CHECK_EQUAL(10, bytes_written_after_sleep);
}

TEST(BlockingRingBuffer_should_block_write____when_ring_is_full___but_has_seen_reads_too_before_becoming_full) {
    TestEnv _;
    std::uint32_t bytes_written_after_sleep = 0;
    std::uint8_t write_buff[100], read_buff[100];
    for (int i = 0; i < sizeof(write_buff); i++) {
        write_buff[i] = i % 256;
    }
    BlockingRingBuffer ring(100);
    CHECK_EQUAL(15, ring.write(write_buff, 0, 15));
    CHECK_EQUAL(15, ring.read(read_buff, 0, 15));

    CHECK_EQUAL(100, ring.write(write_buff, 0, 100));

    std::thread t(read_after_sleep, static_cast<std::uint32_t>(100), std::ref(ring), read_buff, static_cast<std::uint32_t>(0), static_cast<std::uint32_t>(10), std::ref(bytes_written_after_sleep)); 

    auto start = std::chrono::steady_clock::now();
    CHECK_EQUAL(10, ring.write(write_buff, 0, 10));
    CHECK_CLOSE(100, std::chrono::duration_cast<std::chrono::milliseconds>(std::chrono::steady_clock::now() - start).count(), 5);

    t.join();
    
    ASSERT_IS_SEQUENCE(read_buff, 0, 10, 0);
    CHECK_EQUAL(10, bytes_written_after_sleep);

    CHECK_EQUAL(100, ring.read(read_buff, 0, 100, false));
    ASSERT_IS_SEQUENCE(read_buff, 0, 90, 10);
    ASSERT_IS_SEQUENCE(read_buff, 90, 10, 0);
}

TEST(BlockingRingBuffer_should_not_block_on_read____if_0_byte_read_is_requested) {
    TestEnv _;
    std::uint32_t bytes_written_after_sleep = 0;
    std::uint8_t write_buff[100], read_buff[100];
    for (int i = 0; i < sizeof(write_buff); i++) {
        write_buff[i] = i % 256;
    }
    BlockingRingBuffer ring(100);
    std::thread t(write_after_sleep, static_cast<std::uint32_t>(100), std::ref(ring), write_buff, static_cast<std::uint32_t>(0), static_cast<std::uint32_t>(30), std::ref(bytes_written_after_sleep)); 

    auto start = std::chrono::steady_clock::now();
    CHECK_EQUAL(0, ring.read(read_buff, 0, 0));
    CHECK_CLOSE(100, std::chrono::duration_cast<std::chrono::microseconds>(std::chrono::steady_clock::now() - start).count(), 100);

    t.join();
    
    CHECK_EQUAL(30, ring.reset());
}

TEST(BlockingRingBuffer_should_not_block_on_write____if_0_byte_write_is_requested) {
    TestEnv _;
    std::uint32_t bytes_written_after_sleep = 0;
    std::uint8_t write_buff[100], read_buff[100];
    for (int i = 0; i < sizeof(write_buff); i++) {
        write_buff[i] = i % 256;
    }
    BlockingRingBuffer ring(100);
    CHECK_EQUAL(100, ring.write(write_buff, 0, 100));

    std::thread t(read_after_sleep, static_cast<std::uint32_t>(100), std::ref(ring), read_buff, static_cast<std::uint32_t>(0), static_cast<std::uint32_t>(30), std::ref(bytes_written_after_sleep)); 

    auto start = std::chrono::steady_clock::now();
    CHECK_EQUAL(0, ring.write(write_buff, 0, 0));
    CHECK_CLOSE(100, std::chrono::duration_cast<std::chrono::microseconds>(std::chrono::steady_clock::now() - start).count(), 100);

    t.join();
    
    CHECK_EQUAL(70, ring.reset());
}

TEST(BlockingRingBuffer_should_reset_and_drop_data____when_clered) {
    TestEnv _;
    std::uint8_t write_buff[100], read_buff[100];
    for (int i = 0; i < sizeof(write_buff); i++) {
        write_buff[i] = i % 256;
    }
    BlockingRingBuffer ring(100);
    
    CHECK_EQUAL(20, ring.write(write_buff, 0, 20));
    CHECK_EQUAL(20, ring.reset());

    CHECK_EQUAL(100, ring.write(write_buff, 0, 100, false));
    CHECK_EQUAL(100, ring.read(read_buff, 0, 100, false));
    
    ASSERT_IS_SEQUENCE(read_buff, 0, 100, 0);
}

void try_to_sum_up(BlockingRingBuffer& ring, std::atomic<std::uint32_t>& sum, std::atomic<bool>& do_stop) {
    std::uint8_t buff[70] __attribute__((aligned(0x40)));
    while (! do_stop.load(std::memory_order_relaxed)) {
        auto read = ring.read(buff, 59, 11);
        for (int i = 0; i < read; i++) {
            sum += buff[i + 59];
        }
    }

}

void write_to_ring_conditionally(BlockingRingBuffer& ring, std::uint8_t *buff, std::uint32_t buff_len, std::uint32_t batch_sz, std::uint32_t times, std::atomic<bool>& do_start, std::atomic<bool>& do_continue) {
    while (! do_start.load(std::memory_order_relaxed));
    for (auto i = 0; i < times; i++) {
        for (auto offset = 0; offset < buff_len; ) {
            auto len = Util::min(batch_sz, buff_len - offset);
            if (! do_continue.load(std::memory_order_relaxed)) return;
            ring.write(buff, offset, len);
            offset += len;
        }
    }
}

void write_to_ring(BlockingRingBuffer& ring, std::uint8_t *buff, std::uint32_t buff_len, std::uint32_t batch_sz, std::uint32_t times, std::atomic<bool>& do_start) {
    std::atomic<bool> continue_forever(true);
    write_to_ring_conditionally(ring, buff, buff_len, batch_sz, times, do_start, continue_forever);
}

TEST(BlockingRingBuffer_should_have_no_data_loss____in_the_face_of_concurrent_reads_and_writes) {
    TestEnv _;
    std::uint8_t write_buff[1024];
    for (int i = 0; i < sizeof(write_buff); i++) {
        write_buff[i] = i % 256;
    }
    
    std::atomic<std::uint32_t> sum(0);
    std::atomic<bool> do_start(false), do_stop(false), keep_pumping_zeros(true);

    std::vector<std::thread> summing_thds, writing_thds;

    BlockingRingBuffer ring(100);

    for (auto i = 0; i < 10; i++) {
        std::thread t(try_to_sum_up, std::ref(ring), std::ref(sum), std::ref(do_stop));
        summing_thds.emplace_back(std::move(t));
    }

    for (auto i = 0; i < 10; i++) {
        std::thread t(write_to_ring, std::ref(ring), write_buff, 1024, (i + 1) * 13, 5, std::ref(do_start));
        writing_thds.emplace_back(std::move(t));
    }

    std::this_thread::sleep_for(std::chrono::milliseconds(10));
    do_start.store(true, std::memory_order_relaxed);

    for (auto& t : writing_thds) t.join();

    std::this_thread::sleep_for(std::chrono::milliseconds(1000));

    do_stop.store(true, std::memory_order_relaxed);

    std::uint8_t zero_buff[1024];
    std::memset(zero_buff, 0, sizeof(zero_buff));

    std::thread t(write_to_ring_conditionally, std::ref(ring), zero_buff, sizeof(zero_buff), 1, 1, std::ref(do_start), std::ref(keep_pumping_zeros));
    for (auto& t : summing_thds) {
        t.join();
    }
    keep_pumping_zeros.store(false, std::memory_order_relaxed);
    std::uint8_t read_buff[1024];
    while (ring.read(read_buff, 0, sizeof(read_buff), false) > 0);
    t.join();
    
    CHECK_EQUAL(255*(256/2) * 4 * 5 * 10, sum.load());                    
}

void make_readonly_after_sleep(std::uint32_t ms, BlockingRingBuffer& ring) {
    std::this_thread::sleep_for(std::chrono::milliseconds(ms));
    ring.readonly();
}

TEST(BlockingRingBuffer_should_not_block_reads____or_allow_writes____when_readonly) {
    TestEnv _;
    std::uint8_t buff[100];
    BlockingRingBuffer ring(100);
    
    std::thread t(make_readonly_after_sleep, static_cast<std::uint32_t>(100), std::ref(ring)); 

    auto start = std::chrono::steady_clock::now();
    CHECK_EQUAL(0, ring.read(buff, 0, 20));
    CHECK_CLOSE(100, std::chrono::duration_cast<std::chrono::milliseconds>(std::chrono::steady_clock::now() - start).count(), 5);

    t.join();

    start = std::chrono::steady_clock::now();
    CHECK_EQUAL(0, ring.read(buff, 0, 20));
    CHECK_CLOSE(1, std::chrono::duration_cast<std::chrono::microseconds>(std::chrono::steady_clock::now() - start).count(), 1);

    start = std::chrono::steady_clock::now();
    CHECK_EQUAL(0, ring.write(buff, 0, 20));
    CHECK_CLOSE(1, std::chrono::duration_cast<std::chrono::microseconds>(std::chrono::steady_clock::now() - start).count(), 1);
}

TEST(BlockingRingBuffer_should_not_block_writes____when_readonly____but_should_allow_completion_of_read) {
    TestEnv _;
    std::uint8_t write_buff[100], read_buff[200];
    for (int i = 0; i < sizeof(write_buff); i++) {
        write_buff[i] = i;
    }

    BlockingRingBuffer ring(100);
    CHECK_EQUAL(100, ring.write(write_buff, 0, 100));
    
    std::thread t(make_readonly_after_sleep, static_cast<std::uint32_t>(100), std::ref(ring)); 

    auto start = std::chrono::steady_clock::now();
    CHECK_EQUAL(0, ring.write(write_buff, 0, 1));
    CHECK_CLOSE(100, std::chrono::duration_cast<std::chrono::milliseconds>(std::chrono::steady_clock::now() - start).count(), 5);

    t.join();

    start = std::chrono::steady_clock::now();
    CHECK_EQUAL(100, ring.read(read_buff, 0, 200));
    CHECK_CLOSE(100, std::chrono::duration_cast<std::chrono::microseconds>(std::chrono::steady_clock::now() - start).count(), 100);
    ASSERT_IS_SEQUENCE(read_buff, 0, 100, 0);

    start = std::chrono::steady_clock::now();
    CHECK_EQUAL(0, ring.read(read_buff, 0, 20));
    CHECK_CLOSE(2, std::chrono::duration_cast<std::chrono::microseconds>(std::chrono::steady_clock::now() - start).count(), 2);
}

TEST(BlockingRingBuffer_should_block____if_reset____after_making_readonly) {
    TestEnv _;
    std::uint8_t write_buff[200], read_buff[200];
    std::uint32_t bytes_written_after_sleep = 0, bytes_read_after_sleep = 0;
    for (int i = 0; i < sizeof(write_buff); i++) {
        write_buff[i] = i;
    }

    BlockingRingBuffer ring(100);
    CHECK_EQUAL(10, ring.write(write_buff, 0, 10));
    ring.readonly();
    CHECK_EQUAL(10, ring.reset());
    
    std::thread t_write(write_after_sleep, static_cast<std::uint32_t>(100), std::ref(ring), write_buff, static_cast<std::uint32_t>(0), static_cast<std::uint32_t>(10), std::ref(bytes_written_after_sleep));

    auto start = std::chrono::steady_clock::now();
    CHECK_EQUAL(10, ring.read(read_buff, 0, 10));
    CHECK_CLOSE(100, std::chrono::duration_cast<std::chrono::milliseconds>(std::chrono::steady_clock::now() - start).count(), 5);

    t_write.join();

    std::thread t_read(read_after_sleep, static_cast<std::uint32_t>(100), std::ref(ring), read_buff, static_cast<std::uint32_t>(0), static_cast<std::uint32_t>(10), std::ref(bytes_read_after_sleep));

    start = std::chrono::steady_clock::now();
    CHECK_EQUAL(110, ring.write(write_buff, 0, 110));
    CHECK_CLOSE(100, std::chrono::duration_cast<std::chrono::milliseconds>(std::chrono::steady_clock::now() - start).count(), 5);

    t_read.join();
    ASSERT_IS_SEQUENCE(read_buff, 0, 10, 0);
    CHECK_EQUAL(10, bytes_read_after_sleep);

    CHECK_EQUAL(100, ring.read(read_buff, 0, 100));
    ASSERT_IS_SEQUENCE(read_buff, 0, 100, 10);
}

TEST(BlockingRingBuffer_should_cancel_inflight_writes____when_readonly) {
    TestEnv _;
    std::uint8_t write_buff[200], read_buff[100];
    for (int i = 0; i < sizeof(write_buff); i++) {
        write_buff[i] = i;
    }

    BlockingRingBuffer ring(100);
    
    std::thread t(make_readonly_after_sleep, static_cast<std::uint32_t>(100), std::ref(ring)); 

    auto start = std::chrono::steady_clock::now();
    CHECK_EQUAL(100, ring.write(write_buff, 0, 110));
    CHECK_CLOSE(100, std::chrono::duration_cast<std::chrono::milliseconds>(std::chrono::steady_clock::now() - start).count(), 5);

    t.join();

    CHECK_EQUAL(100, ring.read(read_buff, 0, 100));
    ASSERT_IS_SEQUENCE(read_buff, 0, 100, 0);

    CHECK_EQUAL(0, ring.read(read_buff, 0, 100));
}

TEST(BlockingRingBuffer_should_return_inflight_reads____when_readonly____without_waiting_for_buff_to_fill) {
    TestEnv _;
    std::uint8_t write_buff[100], read_buff[100];
    for (int i = 0; i < sizeof(write_buff); i++) {
        write_buff[i] = i;
    }

    BlockingRingBuffer ring(100);

    CHECK_EQUAL(50, ring.write(write_buff, 0, 50));

    std::thread t(make_readonly_after_sleep, static_cast<std::uint32_t>(100), std::ref(ring)); 

    auto start = std::chrono::steady_clock::now();
    CHECK_EQUAL(50, ring.read(read_buff, 0, 60));
    CHECK_CLOSE(100, std::chrono::duration_cast<std::chrono::milliseconds>(std::chrono::steady_clock::now() - start).count(), 5);
    ASSERT_IS_SEQUENCE(read_buff, 0, 50, 0);

    t.join();
}

TEST(BlockingRingBuffer_should_allow_reads_and_writes____larger_than_ring_sz) {
    TestEnv _;
    std::uint8_t write_buff[200], read_buff[150];
    std::uint32_t bytes_written_after_sleep = 0;
    for (int i = 0; i < sizeof(write_buff); i++) {
        write_buff[i] = i;
    }

    BlockingRingBuffer ring(100);

    std::thread t(write_after_sleep, static_cast<std::uint32_t>(100), std::ref(ring), write_buff, static_cast<std::uint32_t>(0), static_cast<std::uint32_t>(200), std::ref(bytes_written_after_sleep));

    CHECK_EQUAL(150, ring.read(read_buff, 0, 150));

    t.join();

    CHECK_EQUAL(200, bytes_written_after_sleep);

    ASSERT_IS_SEQUENCE(read_buff, 0, 150, 0);

}

