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

#ifndef GLOBALS_H
#define GLOBALS_H

#define RECORDER_VERION 1
#define DATA_ENCODING_VERSION 1

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

namespace GlobalCtx {
    typedef struct {
        UniqueReadsafePtr<Profiler> cpu_profiler;
    } Rec;

    extern GlobalCtx::Rec recording;
}

Profiler *getProfiler();
void setProfiler(Profiler *p);

const int DEFAULT_SAMPLING_INTERVAL = 1;
const int DEFAULT_MAX_FRAMES_TO_CAPTURE = 128;
const int MAX_FRAMES_TO_CAPTURE = 2048;

#if defined(STATIC_ALLOCATION_ALLOCA)
  #define STATIC_ARRAY(NAME, TYPE, SIZE, MAXSZ) TYPE *NAME = (TYPE*)alloca((SIZE) * sizeof(TYPE))
#elif defined(STATIC_ALLOCATION_PEDANTIC)
  #define STATIC_ARRAY(NAME, TYPE, SIZE, MAXSZ) TYPE NAME[MAXSZ]
#else
  #define STATIC_ARRAY(NAME, TYPE, SIZE, MAXSZ) TYPE NAME[SIZE]
#endif

#define AGENTEXPORT __attribute__((visibility("default"))) JNIEXPORT

// Gets us around -Wunused-parameter
#define IMPLICITLY_USE(x) (void) x;

// Wrap JVMTI functions in this in functions that expect a return
// value and require cleanup but no error message
#define JVMTI_ERROR_CLEANUP_RET_NO_MESSAGE(error, retval, cleanup)             \
  {                                                                            \
    int err;                                                                   \
    if ((err = (error)) != JVMTI_ERROR_NONE) {                                 \
      cleanup;                                                                 \
      return (retval);                                                         \
    }                                                                          \
  }
// Wrap JVMTI functions in this in functions that expect a return
// value and require cleanup.
#define JVMTI_ERROR_MESSAGE_CLEANUP_RET(error, message, retval, cleanup)       \
  {                                                                            \
    int err;                                                                   \
    if ((err = (error)) != JVMTI_ERROR_NONE) {                                 \
        logger->critical(message, err);                                        \
      cleanup;                                                                 \
      return (retval);                                                         \
    }                                                                          \
  }

#define JVMTI_ERROR_CLEANUP_RET(error, retval, cleanup)                        \
    JVMTI_ERROR_MESSAGE_CLEANUP_RET(error, "JVMTI error {}", retval, cleanup)

// Wrap JVMTI functions in this in functions that expect a return value.
#define JVMTI_ERROR_RET(error, retval)                                         \
  JVMTI_ERROR_CLEANUP_RET(error, retval, /* nothing */)

// Wrap JVMTI functions in this in void functions.
#define JVMTI_ERROR(error) JVMTI_ERROR_CLEANUP(error, /* nothing */)

// Wrap JVMTI functions in this in void functions that require cleanup.
#define JVMTI_ERROR_CLEANUP(error, cleanup)                                    \
  {                                                                            \
    int err;                                                                   \
    if ((err = (error)) != JVMTI_ERROR_NONE) {                                 \
        logger->critical("JVMTI error {}", err);                               \
      cleanup;                                                                 \
      return;                                                                  \
    }                                                                          \
  }

#define DISALLOW_COPY_AND_ASSIGN(TypeName)                                     \
  TypeName(const TypeName &);                                                  \
  void operator=(const TypeName &)

#define DISALLOW_IMPLICIT_CONSTRUCTORS(TypeName)                               \
  TypeName();                                                                  \
  DISALLOW_COPY_AND_ASSIGN(TypeName)

// Short version: reinterpret_cast produces undefined behavior in many
// cases where memcpy doesn't.
template<class Dest, class Source>
inline Dest bit_cast(const Source &source) {
    // Compile time assertion: sizeof(Dest) == sizeof(Source)
    // A compile error here means your Dest and Source have different sizes.
    typedef char VerifySizesAreEqual[sizeof(Dest) == sizeof(Source) ? 1 : -1]
            __attribute__((unused));

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
