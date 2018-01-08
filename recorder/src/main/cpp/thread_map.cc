#include "thread_map.hh"

#include <sys/syscall.h>
#include <unistd.h>

#ifdef __APPLE__
#include <mach/mach.h>
#endif

// taken from Wine's get_unix_tid
int gettid() {
  int ret = -1;
#if defined(__linux__)
  ret = syscall(SYS_gettid);
#elif defined(__APPLE__)
  //ret = pthread_getthreadid_np();
  ret = mach_thread_self();
  mach_port_deallocate(mach_task_self(), ret);
#elif defined(__NetBSD__)
  ret = _lwp_self();
#elif defined(__FreeBSD__)
  long lwpid;
  thr_self(&lwpid);
  ret = lwpid;
#elif defined(__DragonFly__)
  ret = lwp_gettid();
#else
  ret = pthread_self();
#endif
  return ret;
}

template<typename PType>
int64_t PointerHasher<PType>::hash(void* p) {
    int64_t v = (int64_t) p / sizeof (PType);
    v = v * 3935559000370003845 + 2691343689449507681;

    v ^= v >> 21;
    v ^= v << 37;
    v ^= v >> 4;

    v *= 4768777513237032717;

    v ^= v << 20;
    v ^= v >> 41;
    v ^= v << 5;

    return v;
}

ThreadInfo::ThreadInfo(int id, const char* n, std::uint32_t _priority, bool _is_daemon) : tid(id), priority(_priority), is_daemon(_is_daemon), ctx_tracker(get_ctx_reg(), get_prob_pct(), id) {
    int len = strlen(n) + 1;
    name = new char[len];
    std::copy(n, n + len, name);
}

ThreadInfo::~ThreadInfo() {
    delete[] name;
}

ThreadMapBase::ThreadMapBase(int capacity) : LinkedMapBase(capacity) {
}

void ThreadMapBase::put(JNIEnv* jni_env, const char* name, int tid, jint priority, jboolean is_daemon) {
    // constructor == call to acquire
    logger->info("Thread started - '{}' (jniEnv: {}, tid: {}, priority: {}, is_daemon: {})", name, reinterpret_cast<std::uint64_t> (jni_env), tid, priority, is_daemon);
    Value* info = new Value(tid, name, static_cast<std::uint32_t> (priority), static_cast<bool> (is_daemon));
    add_to_values(info);
    info->data.localEpoch = map::GCHelper::attach();
    Value *old = (Value*) map.put((map::KeyType)jni_env, (map::ValueType)info);
    if (old != nullptr)
        remove_and_release(old);
    map::GCHelper::safepoint(info->data.localEpoch); // each thread inserts once
}

ThreadMapBase::Value* ThreadMapBase::get(JNIEnv* jni_env) {
    Value *info = (Value*) map.get((map::KeyType)jni_env);
    if (info != nullptr && (info = info->acquire()) != nullptr)
        map::GCHelper::signalSafepoint(info->data.localEpoch);
    return info;
}

void ThreadMapBase::remove(JNIEnv* jni_env) {
    Value* info = (Value*) map.remove((map::KeyType)jni_env);
    if (info != nullptr) {
        logger->info("Thread stopped - '{}' (jniEnv: {}, tid: {}, priority: {}, is_daemon: {})", info->data.name, reinterpret_cast<std::uint64_t> (jni_env), info->data.tid, info->data.priority, info->data.is_daemon);
        map::GCHelper::detach(info->data.localEpoch);
        remove_and_release(info);
    }
}

