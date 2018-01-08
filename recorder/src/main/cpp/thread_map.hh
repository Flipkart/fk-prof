#ifndef THREAD_MAP_H
#define THREAD_MAP_H

#include <jvmti.h>
#include <jni.h>
#include <string.h>
#include "concurrent_map.hh"
#include "linked_concurrent_map.hh"
#include "perf_ctx.hh"
#include "globals.hh"

int gettid();

template <typename PType>
struct PointerHasher {
    /* Numerical Recipes, 3rd Edition */
    static int64_t hash(void *p);
};

static const int kInitialMapSize = 256;

struct ThreadInfo {
    const int tid;
    char *name;
    std::uint32_t priority;
    bool is_daemon;
    map::GC::EpochType localEpoch;
    PerfCtx::ThreadTracker ctx_tracker;

    ThreadInfo(int id, const char *n, std::uint32_t _priority, bool _is_daemon);

    ~ThreadInfo();
};

class ThreadMapBase : public LinkedMapBase<map::ConcurrentMapProvider<PointerHasher<JNIEnv>, true>, ThreadInfo> {
public:
    ThreadMapBase(int capacity = kInitialMapSize);
    
    void put(JNIEnv* jni_env, const char *name, jint priority, jboolean is_daemon) {
        put(jni_env, name, gettid(), priority, is_daemon);
    }

    void put(JNIEnv* jni_env, const char *name, int tid, jint priority, jboolean is_daemon);

    Value* get(JNIEnv* jni_env);

    void remove(JNIEnv* jni_env);
};

typedef ThreadMapBase ThreadMap;
typedef ThreadMap::Value ThreadBucket;
ThreadMap& get_thread_map();

#endif
