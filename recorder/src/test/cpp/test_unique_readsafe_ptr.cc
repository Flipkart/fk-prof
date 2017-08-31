#include <thread>
#include <vector>
#include <iostream>
#include <cstdint>
#include <chrono>
#include "fixtures.hh"
#include "test.hh"
#include "../../main/cpp/unique_readsafe_ptr.hh"

struct DestructorTracker {
    std::atomic<bool>& destroyed;
    std::atomic<std::uint64_t>& destroyed_at_usec;
    std::atomic<std::uint64_t>& last_read_at_usec;
    std::chrono::time_point<std::chrono::system_clock> created_at;
    std::atomic<std::uint32_t>& destroyed_before_read;

    DestructorTracker(std::atomic<bool>& _destroyed, std::atomic<std::uint64_t>& _destroyed_at_usec, std::atomic<std::uint64_t>& _last_read_at_usec, std::chrono::time_point<std::chrono::system_clock> _created_at, std::atomic<std::uint32_t>& _destroyed_before_read) :
        destroyed(_destroyed), destroyed_at_usec(_destroyed_at_usec), last_read_at_usec(_last_read_at_usec), created_at(_created_at), destroyed_before_read(_destroyed_before_read) {
        last_read_at_usec.store(0, std::memory_order_seq_cst);
        destroyed.store(false, std::memory_order_seq_cst);
    }
    ~DestructorTracker() {
        destroyed.store(true, std::memory_order_seq_cst);
        destroyed_at_usec.store(std::chrono::duration_cast<std::chrono::microseconds>(std::chrono::system_clock::now() - created_at).count());
    }
    void read() {
        last_read_at_usec.store(std::chrono::duration_cast<std::chrono::microseconds>(std::chrono::system_clock::now() - created_at).count());
        if (destroyed.load(std::memory_order_seq_cst)) {
            destroyed_before_read.fetch_add(1, std::memory_order_seq_cst);
        }
    }
};

void readref_and_sleep_for(std::uint32_t ms, UniqueReadsafePtr<DestructorTracker>& urp, std::atomic<std::uint32_t>& started) {
    ReadsafePtr<DestructorTracker> rp(urp);
    logger->info("Flipped...");
    started.fetch_add(1, std::memory_order_seq_cst);
    logger->info("Started...");
    std::this_thread::sleep_for(std::chrono::milliseconds(ms));
    rp->read();
    logger->info("Read done...");
}

TEST(ReadsafePtr___should_not_destruct___until_all_readers_are_done) {
    TestEnv _;

    std::atomic<bool> destroyed;
    std::atomic<std::uint64_t> destroyed_at_usec;
    std::atomic<std::uint64_t> last_read_at_usec;
    std::atomic<std::uint32_t> destroyed_before_read {0};
    std::thread* t1;
    std::thread* t2;
    std::atomic<std::uint32_t> started {0};
    std::chrono::time_point<std::chrono::system_clock> scope_ended_at;
    std::chrono::time_point<std::chrono::system_clock> object_created_at;
    {
        UniqueReadsafePtr<DestructorTracker> p;
        object_created_at = std::chrono::system_clock::now();
        p.reset(new DestructorTracker(destroyed, destroyed_at_usec, last_read_at_usec, object_created_at, destroyed_before_read));
        t1 = new std::thread { readref_and_sleep_for, 10, std::ref(p), std::ref(started) };
        t2 = new std::thread { readref_and_sleep_for, 20, std::ref(p), std::ref(started) };
        while (started.load(std::memory_order_seq_cst) < 2);
        scope_ended_at = std::chrono::system_clock::now();
        logger->info("End of scope reached...");
    }
    CHECK_EQUAL(true, destroyed.load());
    logger->info("Came out of scope...");
    std::uint32_t destruct_time_lag = std::chrono::duration_cast<std::chrono::microseconds>(std::chrono::system_clock::now() - scope_ended_at).count();
    t1->join();
    delete t1;
    t2->join();
    delete t2;

    CHECK(destruct_time_lag >= (20 * 1000));//atleast 20 ms
    CHECK(last_read_at_usec.load() <= destroyed_at_usec.load());
    CHECK_EQUAL(0, destroyed_before_read.load(std::memory_order_seq_cst));
}

struct Foo {
    int x;
};

TEST(ReadsafePtr___should_be_available___only_after_writer_sets_it) {
    TestEnv _;
    auto f = new Foo();
    f->x = 10;
    UniqueReadsafePtr<Foo> p;
    {
        ReadsafePtr<Foo> rp(p);
        CHECK_EQUAL(false, rp.available());
        p.reset(f);
        CHECK_EQUAL(false, rp.available());
        ReadsafePtr<Foo> rp2(p);
        CHECK_EQUAL(true, rp2.available());
        CHECK_EQUAL(10, rp2->x);
    }
    auto g = new Foo();
    g->x = 20;
    p.reset(g);
    ReadsafePtr<Foo> rp3(p);
    CHECK_EQUAL(true, rp3.available());
    CHECK_EQUAL(20, rp3->x);
}

TEST(ReadsafePtr___should_not_be_available___after_writer_clears_it) {
    TestEnv _;

    std::atomic<bool> destroyed;
    std::atomic<std::uint64_t> destroyed_at_usec;
    std::atomic<std::uint64_t> last_read_at_usec;
    std::atomic<std::uint32_t> destroyed_before_read {0};
    std::chrono::time_point<std::chrono::system_clock> object_created_at;
    {
        UniqueReadsafePtr<DestructorTracker> p;
        p.reset(new DestructorTracker(destroyed, destroyed_at_usec, last_read_at_usec, object_created_at, destroyed_before_read));
        {
            {
                ReadsafePtr<DestructorTracker> rp_before(p);
                CHECK_EQUAL(true, rp_before.available());
            }
            p.reset();
            ReadsafePtr<DestructorTracker> rp_after(p);
            CHECK_EQUAL(false, rp_after.available());
        }
    }
}
