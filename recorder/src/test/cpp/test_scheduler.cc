#include <thread>
#include <vector>
#include <iostream>
#include <utility>
#include <cstdint>
#include "fixtures.hh"
#include "test.hh"
#include "../../main/cpp/globals.hh"
#include "../../main/cpp/scheduler.hh"

std::uint32_t elapsed_time(std::function<void()> proc) {
    auto start = Time::now();
    proc();
    auto end = Time::now();
    return std::chrono::duration_cast<std::chrono::milliseconds>(end - start).count();
}

std::uint32_t time_poll(Scheduler& s, bool expected_return = true) {
    return elapsed_time([&] { CHECK_EQUAL(expected_return, s.poll()); });
}

TEST(Scheduler___one_at_a_time___just_expired) {
    TestEnv _;
    int order_tracker = 0;
    Scheduler s;

    auto start = Time::now();
    
    s.schedule(start, [&] { order_tracker = 1; });
    CHECK_EQUAL(0, order_tracker);
    CHECK_CLOSE(1, time_poll(s), 1);
    CHECK_EQUAL(1, order_tracker);
    
    s.schedule(start, [&] { order_tracker = 2; });
    CHECK_EQUAL(1, order_tracker);
    CHECK_CLOSE(1, time_poll(s), 1);
    CHECK_EQUAL(2, order_tracker);
}

TEST(Scheduler___one_at_a_time___pre_expired) {
    TestEnv _;
    int order_tracker = 0;
    Scheduler s;

    s.schedule(Time::now() - Time::sec(10), [&] { order_tracker = 1; });
    CHECK_EQUAL(0, order_tracker);
    CHECK_CLOSE(1, time_poll(s), 1);
    CHECK_EQUAL(1, order_tracker);

    s.schedule(Time::now() - Time::sec(5), [&] { order_tracker = 2; });
    CHECK_EQUAL(1, order_tracker);
    CHECK_CLOSE(1, time_poll(s), 1);
    CHECK_EQUAL(2, order_tracker);
}

TEST(Scheduler___several___pre_expired) {
    TestEnv _;
    int order_tracker[] = {0, 0};
    Scheduler s;

    s.schedule(Time::now() - Time::sec(5), [&] { order_tracker[1] = 5; });
    s.schedule(Time::now() - Time::sec(10), [&] { order_tracker[0] = 10; });

    CHECK_EQUAL(0, order_tracker[0]);
    CHECK_EQUAL(0, order_tracker[1]);
    CHECK_CLOSE(1, time_poll(s), 1);
    CHECK_EQUAL(10, order_tracker[0]);
    CHECK_EQUAL(5, order_tracker[1]);
    auto elapsed = elapsed_time([&s]() {
            CHECK(! s.poll());
        });
    CHECK_CLOSE(1, elapsed, 1);
}

TEST(Scheduler___several___future) {
    TestEnv _;
    int order_tracker = 0;
    Scheduler s;

    auto now = Time::now();
    s.schedule(now + Time::sec(1), [&] { order_tracker = 1000; });
    s.schedule(now + std::chrono::milliseconds(200), [&] { order_tracker = 200; });
    s.schedule(now + std::chrono::milliseconds(70), [&] { order_tracker = 70; });
    s.schedule(now + std::chrono::milliseconds(900), [&] { order_tracker = 900; });
    s.schedule(now + std::chrono::milliseconds(500), [&] { order_tracker = 500; });

    CHECK_EQUAL(0, order_tracker);
    
    CHECK_CLOSE(70 - 0, time_poll(s), 50);
    CHECK_EQUAL(70, order_tracker);
    
    CHECK_CLOSE(200 - 70, time_poll(s), 50);
    CHECK_EQUAL(200, order_tracker);
    
    CHECK_CLOSE(500 - 200, time_poll(s), 50);
    CHECK_EQUAL(500, order_tracker);
    
    CHECK_CLOSE(900 - 500, time_poll(s), 50);
    CHECK_EQUAL(900, order_tracker);
    
    CHECK_CLOSE(1000 - 900, time_poll(s), 50);
    CHECK_EQUAL(1000, order_tracker);
}

TEST(Scheduler___too_many_pops) {
    TestEnv _;
    int order_tracker = 0;
    Scheduler s;

    s.schedule(Time::now() - Time::sec(5), [&] { order_tracker = 1; });
    CHECK(s.poll());
    CHECK(! s.poll());
}

void schedule_after_sleep(std::uint32_t ms, Scheduler& s, std::uint32_t sched_delay_ms, std::function<void()>&& proc) {
    std::this_thread::sleep_for(std::chrono::milliseconds(ms));
    s.schedule(Time::now() + std::chrono::milliseconds(sched_delay_ms), proc);
}

TEST(Scheduler___schedules_in_desired_order___when_another_thd_enqueues___during_poll_wait) {
    TestEnv _;
    int order_tracker = 0;
    int co_trigger_tracker = 0;
    Scheduler s;

    auto now = Time::now();
    s.schedule(now + std::chrono::milliseconds(200), [&] { order_tracker = 200; });
    s.schedule(now + std::chrono::milliseconds(300), [&] { order_tracker = 300; });


    CHECK_EQUAL(0, order_tracker);

    std::thread t100_0(schedule_after_sleep, static_cast<std::uint32_t>(10), std::ref(s), static_cast<std::uint32_t>(90), std::forward<std::function<void()>>([&] { order_tracker = 100; co_trigger_tracker++; }));
    std::thread t100_1(schedule_after_sleep, static_cast<std::uint32_t>(10), std::ref(s), static_cast<std::uint32_t>(90), std::forward<std::function<void()>>([&] { co_trigger_tracker++; }));
    std::thread t100_2(schedule_after_sleep, static_cast<std::uint32_t>(10), std::ref(s), static_cast<std::uint32_t>(90), std::forward<std::function<void()>>([&] { co_trigger_tracker++; }));
    std::thread t50(schedule_after_sleep, static_cast<std::uint32_t>(10), std::ref(s), static_cast<std::uint32_t>(40), std::forward<std::function<void()>>([&] { order_tracker = 50; }));
    std::thread t250(schedule_after_sleep, static_cast<std::uint32_t>(10), std::ref(s), static_cast<std::uint32_t>(240), std::forward<std::function<void()>>([&] { order_tracker = 250; }));

    t100_0.join();
    t100_1.join();
    t100_2.join();
    t50.join();
    t250.join();

    CHECK_CLOSE(50 - 10, time_poll(s), 5); //10 for sleep
    CHECK_EQUAL(50, order_tracker);


    CHECK_EQUAL(0, co_trigger_tracker);
    CHECK_CLOSE(100 - 50, time_poll(s), 5);
    CHECK_EQUAL(100, order_tracker);
    CHECK_EQUAL(3, co_trigger_tracker);

    CHECK_CLOSE(200 - 100, time_poll(s), 5);
    CHECK_EQUAL(200, order_tracker);

    CHECK_CLOSE(250 - 200, time_poll(s), 5);
    CHECK_EQUAL(250, order_tracker);

    CHECK_CLOSE(300 - 250, time_poll(s), 5);
    CHECK_EQUAL(300, order_tracker);

    CHECK_CLOSE(1, time_poll(s, false), 1);
}

TEST(Scheduler___schedules_correctly___when_one_tasks_enqueues_another) {
    TestEnv _;
    int order_tracker = 1;
    Scheduler s;

    auto start = Time::now();
    
    s.schedule(start, [&] {
            order_tracker *= 2;
            s.schedule(start, [&] {
                    order_tracker *= 3;
                    s.schedule(start, [&] {
                            order_tracker *= 5;
                        });
                });
        });
    CHECK_EQUAL(1, order_tracker);
    CHECK_CLOSE(1, time_poll(s), 1);
    CHECK_EQUAL(30, order_tracker);
}
