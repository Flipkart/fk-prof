#include "globals.hh"

#ifndef STACKTRACES_H
#define STACKTRACES_H

// To implement the profiler, we rely on an undocumented function called
// AsyncGetCallTrace in the Java virtual machine, which is used by Sun
// Studio Analyzer, and designed to get stack traces asynchronously.
// It uses the old JVMPI interface, so we must reconstruct the
// neccesary bits of that here.

// For a Java frame, the lineno is the bci of the method, and the
// method_id is the jmethodID.  For a JNI method, the lineno is -3,
// and the method_id is the jmethodID.
typedef struct {
    jint lineno;
    jmethodID method_id;
} JVMPI_CallFrame;

typedef std::uint64_t NativeFrame;

typedef union {
    JVMPI_CallFrame jvmpi_frame;
    NativeFrame native_frame;
} StackFrame;

typedef struct {
    // JNIEnv of the thread from which we grabbed the trace
    JNIEnv* env_id;
    // < 0 if the frame isn't walkable
    jint num_frames;
    // The frames, callee first.
    JVMPI_CallFrame *frames;
} JVMPI_CallTrace;

enum class BacktraceError {
    /** copied form forte.cpp, this is error-table we are trying to translate
        enum {
        ticks_no_Java_frame         =  0,
        ticks_no_class_load         = -1,
        ticks_GC_active             = -2,
        ticks_unknown_not_Java      = -3,
        ticks_not_walkable_not_Java = -4,
        ticks_unknown_Java          = -5,
        ticks_not_walkable_Java     = -6,
        ticks_unknown_state         = -7,
        ticks_thread_exit           = -8,
        ticks_deopt                 = -9,
        ticks_safepoint             = -10
        };
        We may eventually have more errors, but because we do multiply with -1 translation, we need to keep room for more.
    **/
    Forte_no_Java_frame         = 0,
    Forte_no_class_load         = 1,
    Forte_GC_active             = 2,
    Forte_unknown_not_Java      = 3,
    Forte_not_walkable_not_Java = 4,
    Forte_unknown_Java          = 5,
    Forte_not_walkable_Java     = 6,
    Forte_unknown_state         = 7,
    Forte_thread_exit           = 8,
    Forte_deopt                 = 9,
    Forte_safepoint             = 10,  //range [0, 100) is reserved for Forte
    Fkp_no_error                = 100,
    Fkp_no_jni_env              = 101
};

enum class BacktraceType {
    Java = 0,
    Native = 1
    // jruby, clojure, scala etc should go here
};

typedef struct {
    BacktraceType type;
    BacktraceError error;

    uint16_t num_frames;
    StackFrame* frames;     // The frames, callee first
    // Frame may be one of:
    // - The frames, callee first: JVMPI_CallFrame *frames;
    // - native frames (PC):  std::uint64_t *frames;
} Backtrace;

typedef void (*ASGCTType)(JVMPI_CallTrace *, jint, void *);
typedef int (*IsGCActiveType)();

const int kNumCallTraceErrors = 10;

enum CallTraceErrors {
    // 0 is reserved for native stack traces.  This includes JIT and GC threads.
            kNativeStackTrace = 0,
    // The JVMTI class load event is disabled (a prereq for AsyncGetCallTrace)
            kNoClassLoad = -1,
    // For traces in GC
            kGcTraceError = -2,
    // We can't figure out what the top (non-Java) frame is
            kUnknownNotJava = -3,
    // The frame is not Java and not walkable
            kNotWalkableFrameNotJava = -4,
    // We can't figure out what the top Java frame is
            kUnknownJava = -5,
    // The frame is Java and not walkable
            kNotWalkableFrameJava = -6,
    // Unknown thread state (not in Java or native or the VM)
            kUnknownState = -7,
    // The JNIEnv is bad - this likely means the thread has exited
            kTicksThreadExit = -8,
    // The thread is being deoptimized, so the stack is borked
            kDeoptHandler = -9,
    // We're in a safepoint, and can't do reporting
            kSafepoint = -10,
};

// Wrapper to hold reference to AsyncGetCallTrace function
class Asgct {
public:
    static void SetAsgct(ASGCTType asgct) {
        asgct_ = asgct;
    }

    static void SetIsGCActive(IsGCActiveType is_gc_active) {
        is_gc_active_ = is_gc_active;
    }

    // AsyncGetCallTrace function, to be dlsym'd.
    static ASGCTType GetAsgct() {
        return asgct_;
    }

    static IsGCActiveType GetIsGCActive() {
        return is_gc_active_;
    }

private:
    static ASGCTType asgct_;
    static IsGCActiveType is_gc_active_;

    DISALLOW_IMPLICIT_CONSTRUCTORS(Asgct);
};

namespace Stacktraces {
    std::uint32_t fill_backtrace(NativeFrame* buff, std::uint32_t capacity);
}

#endif // STACKTRACES_H
