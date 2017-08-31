#include "scheduler.hh"
#include <thread>
#include <iostream>

void Scheduler::schedule(Time::Pt time, Scheduler::Cb task) {
    auto sec_in_future = std::chrono::duration_cast<Time::sec>(time - Time::now());
    logger->debug("Scheduling {} {}s in future", typeid(task).name(), sec_in_future.count());//TODO: handle me better
    std::lock_guard<std::mutex> g(m);
    q.push({time, task});
    s_c_q_sz.inc();
    auto top_expiry = q.top().first;
    if (time < top_expiry) {
        nearest_entry_changed.notify_one();
    }
}

Time::usec usec_to_expiry(const Time::Pt& tm) {
    return std::chrono::duration_cast<Time::usec>(tm - Time::now());
}

bool is_expired(const Time::Pt& tm) {
    return usec_to_expiry(tm).count() <= 0;
}

typedef std::unique_lock<std::mutex> Lock;

void wait_for_expiry(const Scheduler::Q& q, Lock& l, std::condition_variable& expiry_plan, metrics::Timer& s_t_wait) {
    auto _ = s_t_wait.time_scope();
    while (true) {
        auto top_expiry = q.top().first;
        if (is_expired(top_expiry)) break;
        expiry_plan.wait_until(l, top_expiry);
    }
}

struct Unlocker {
    Lock& l;

    Unlocker(Lock& _l) : l(_l) {
        l.unlock();
    }

    ~Unlocker() {
        l.lock();
    }
};

void execute_top(Scheduler::Q& q, Lock& l, metrics::Timer& s_t_exec, metrics::Ctr& s_c_q_sz) {
    Scheduler::Ent sched_for = std::move(q.top());
    q.pop();
    s_c_q_sz.dec();
    logger->debug("Scheduler is now triggering {}", typeid(sched_for).name());
    {
        Unlocker ul(l);
        auto _ = s_t_exec.time_scope();
        sched_for.second();
    }
}

bool Scheduler::poll() {
    Lock l(m);
    if (q.empty()) {
        s_m_runout.mark();
        return false;
    }

    wait_for_expiry(q, l, nearest_entry_changed, s_t_wait);

    execute_top(q, l, s_t_exec, s_c_q_sz);
    auto i = 1;

    while ((! q.empty()) && is_expired(q.top().first)) {
        execute_top(q, l, s_t_exec, s_c_q_sz);
        i++;
    }
    s_h_exec_spree_len.update(i);

    return true;
}

#define METRIC_TYPE "scheduler"

Scheduler::Scheduler() :
    s_m_runout(get_metrics_registry().new_meter({METRICS_DOMAIN, METRIC_TYPE, "queue", "runout"}, "rate")),
    s_t_wait(get_metrics_registry().new_timer({METRICS_DOMAIN, METRIC_TYPE, "sched", "wait"})),
    s_t_exec(get_metrics_registry().new_timer({METRICS_DOMAIN, METRIC_TYPE, "sched", "exec"})),
    s_h_exec_spree_len(get_metrics_registry().new_histogram({METRICS_DOMAIN, METRIC_TYPE, "sched", "exec_spree"})),
    s_c_q_sz(get_metrics_registry().new_counter({METRICS_DOMAIN, METRIC_TYPE, "queue", "sz"})) {}

Scheduler::~Scheduler() {}
