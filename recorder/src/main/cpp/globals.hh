#ifndef GLOBALS_H
#define GLOBALS_H

#include <assert.h>
#include <dlfcn.h>
#include <jvmti.h>
#include <jni.h>
#include <stdint.h>
#include <signal.h>
#include <chrono>
#include "metrics.hh"
#include "unique_readsafe_ptr.hh"

#define SPDLOG_ENABLE_SYSLOG
#include <spdlog/spdlog.h>

#include "perf_ctx.hh"

#include "defs.hh"

extern const char* fkprec_commit;
extern const char* fkprec_branch;
extern const char* fkprec_version;
extern const char* fkprec_version_verbose;
extern const char* fkprec_build_env;

typedef std::shared_ptr<spdlog::logger> LoggerP;

namespace Time {
    typedef std::chrono::steady_clock Clk;
    typedef std::chrono::time_point<Clk> Pt;
    typedef std::chrono::seconds sec;
    typedef std::chrono::milliseconds msec;
    typedef std::chrono::microseconds usec;

    Pt now();

    std::uint32_t elapsed_seconds(const Pt& later, const Pt& earlier);
};

extern LoggerP logger;//TODO: stick me in GlobalCtx???

class Profiler;
class IOTracer;

namespace GlobalCtx {
    typedef struct {
        UniqueReadsafePtr<Profiler> cpu_profiler;
        UniqueReadsafePtr<IOTracer> io_tracer;
    } Rec;

    extern GlobalCtx::Rec recording;
}

const int DEFAULT_FLUSH_BATCH_SIZE = 100;
const int DEFAULT_SAMPLING_INTERVAL = 1;
const int DEFAULT_MAX_FRAMES_TO_CAPTURE = 128;
const int MAX_FRAMES_TO_CAPTURE = 2048;

const std::int64_t NANOS_IN_MILLIS = 1000000;

// Short version: reinterpret_cast produces undefined behavior in many
// cases where memcpy doesn't.
template<class Dest, class Source>
inline Dest bit_cast(const Source &source) {
    // Compile time assertion: sizeof(Dest) == sizeof(Source)
    static_assert(sizeof(Dest) == sizeof(Source), "Dest and Source have different sizes");

    Dest dest;
    memcpy(&dest, &source, sizeof(dest));
    return dest;
}

template<class T>
class JvmtiScopedPtr {
public:
    explicit JvmtiScopedPtr(jvmtiEnv *jvmti) : jvmti_(jvmti), ref_(NULL) {
    }

    JvmtiScopedPtr(jvmtiEnv *jvmti, T *ref) : jvmti_(jvmti), ref_(ref) {
    }

    ~JvmtiScopedPtr() {
        if (NULL != ref_) {
            JVMTI_ERROR(jvmti_->Deallocate((unsigned char *) ref_));
        }
    }

    T **GetRef() {
        assert(ref_ == NULL);
        return &ref_;
    }

    T *Get() {
        return ref_;
    }

    void AbandonBecauseOfError() {
        ref_ = NULL;
    }

private:
    jvmtiEnv *jvmti_;
    T *ref_;

    DISALLOW_IMPLICIT_CONSTRUCTORS(JvmtiScopedPtr);
};

// Accessors for getting the Jvm function for AsyncGetCallTrace.
class Accessors {
public:
    template<class FunctionType>
    static inline FunctionType GetJvmFunction(const char *function_name) {
        // get address of function, return null if not found
        return bit_cast<FunctionType>(dlsym(RTLD_DEFAULT, function_name));
    }
};

void bootstrapHandle(int signum, siginfo_t *info, void *context);

#endif // GLOBALS_H
