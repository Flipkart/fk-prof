#include "test.hh"
#include <circular_queue.hh>

#ifndef FIXTURES_H
#define FIXTURES_H

class ItemHolder : public QueueListener {
public:
  explicit ItemHolder() {}

    virtual void record(const Backtrace &trace, ThreadBucket *info, std::uint8_t ctx_len, PerfCtx::ThreadTracker::EffectiveCtx* ctx, bool default_ctx) {
        CHECK_EQUAL(2, trace.num_frames);
        CHECK_EQUAL(BacktraceType::Java, trace.type);

        JVMPI_CallFrame frame0 = trace.frames[0].jvmpi_frame;

        CHECK_EQUAL(52, frame0.lineno);
        CHECK_EQUAL((jmethodID)1, frame0.method_id);
  }

  long envId;
};

// Queue too big to stack allocate,
// So we use a fixture
struct GivenQueue {
  GivenQueue() {
    holder = new ItemHolder();
    queue = new CircularQueue(*holder, DEFAULT_MAX_FRAMES_TO_CAPTURE);
  }

  ~GivenQueue() {
    delete holder;
    delete queue;
  }

  ItemHolder *holder;

  CircularQueue *queue;

  // wrap an easy to test api around the queue
  bool pop(const long envId) {
    holder->envId = envId;
    return queue->pop();
  }
};

#endif /* FIXTURES_H */
