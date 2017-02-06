#include <jvmti.h>
#include <unordered_set>
#include <iostream>
#include <string.h>

#include "thread_map.hh"
#include "circular_queue.hh"
#include "stacktraces.hh"
#include "site_resolver.hh"

#ifndef LOG_WRITER_H
#define LOG_WRITER_H

using std::ostream;
using std::unordered_set;

typedef unsigned char byte;
typedef int64_t method_id;

const size_t FIFO_SIZE = 10;
const byte TRACE_START = 1;
const byte FRAME_BCI_ONLY = 2;// maintain backward compatibility
const byte FRAME_FULL = 21;
const byte NEW_METHOD = 3;
// Error values for line number. If BCI is an error value we report the BCI error value.
const jint ERR_NO_LINE_INFO = -100;
const jint ERR_NO_LINE_FOUND= -101;
// For the record, known BCI error values


// LogWriter should be independently testable without spinning up a JVM
class LogWriter : public QueueListener, public SiteResolver::MethodListener {

public:
    explicit LogWriter(ostream &output, SiteResolver::MethodInfoResolver frameLookup,
            jvmtiEnv *jvmti)
            : output_(output), frameLookup_(frameLookup), jvmti_(jvmti) {
    }

    virtual void record(const JVMPI_CallTrace &trace, ThreadBucket *info = nullptr, std::uint8_t ctx_len = 0, PerfCtx::ThreadTracker::EffectiveCtx* ctx = nullptr);

    void recordTraceStart(const jint num_frames, const map::HashType threadId);

    // method are unique pointers, use a long to standardise
    // between 32 and 64 bits
    void recordFrame(const jint bci, const jint lineNumber, method_id methodId);

    void recordFrame(const jint bci, method_id methodId);

    virtual void recordNewMethod(const jmethodID methodId, const char *file_name,
                                 const char *class_name, const char *method_name, const char* method_signature);

private:
    ostream &output_;

    SiteResolver::MethodInfoResolver frameLookup_;

    jvmtiEnv *jvmti_;

    unordered_set<method_id> knownMethods;

    template<typename T>
    void writeValue(const T &value);

    void writeWithSize(const char *value);

    void inspectMethod(const method_id methodId, const JVMPI_CallFrame &frame);

    jint getLineNo(jint bci, jmethodID methodId);

    DISALLOW_COPY_AND_ASSIGN(LogWriter);
};

#endif // LOG_WRITER_H
