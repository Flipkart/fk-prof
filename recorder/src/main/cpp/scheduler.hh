#ifndef SCHEDULER_H
#define SCHEDULER_H

#include <functional>
#include <chrono>
#include <queue>
#include <exception>
#include <mutex>
#include <condition_variable>
#include "globals.hh"

class Scheduler {
public:
    /**
    * A POD struct to wrap a callback with a meaningful description.
    */
    struct Task {
        typedef std::function<void()> Cb;
        Cb callback;
        std::string description;
        void operator()() {
            callback();
        }
    };

    typedef std::pair<Time::Pt, Task> Ent;
    struct Cmp {
        bool operator()(const Ent &left, const Ent &right) {
            return left.first > right.first;
        }
    };
    typedef std::priority_queue<Ent, std::vector<Ent>, Cmp> Q;

    Scheduler();

    ~Scheduler();

    void schedule(Time::Pt time, Task task);
    void schedule(Time::Pt time, Task::Cb cb, std::string desc = "Not Described");

    bool poll();

private:
    Q q;
    std::mutex m;
    std::condition_variable nearest_entry_changed;

    metrics::Mtr& s_m_runout;
    metrics::Timer& s_t_wait;
    metrics::Timer& s_t_exec;
    metrics::Hist& s_h_exec_spree_len;
    metrics::Ctr& s_c_q_sz;
};

#endif
