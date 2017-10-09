#ifndef INSTRUMENTATION_H
#define INSTRUMENTATION_H

#include <atomic>
#include <jvmti.h>
#include "config.hh"

// copied from java_crw_demo.h: https://github.com/twitter/jvmgcprof/blob/master/java_crw_demo/java_crw_demo.h
// docs: https://github.com/twitter/jvmgcprof/tree/master/java_crw_demo
namespace Crw {
    typedef void (*FatalErrorHandler)(const char*message, const char*file, int line);

    typedef void (*MethodNumberRegister)(unsigned, const char**, const char**, int);

    typedef void (*JavaCrwDemo)(unsigned class_number,
                                        const char *name,
                                        const unsigned char *file_image,
                                        long file_len,
                                        int system_class,
                                        char* tclass_name,
                                        char* tclass_sig,
                                        char* call_name,
                                        char* call_sig,
                                        char* return_name,
                                        char* return_sig,
                                        char* obj_init_name,
                                        char* obj_init_sig,
                                        char* newarray_name,
                                        char* newarray_sig,
                                        unsigned char **pnew_file_image,
                                        long *pnew_file_len,
                                        FatalErrorHandler fatal_error_handler,
                                        MethodNumberRegister mnum_callback);

    typedef char* (*JavaCrwDemoClassname)(const unsigned char *file_image,
                                          long file_len,
                                          FatalErrorHandler fatal_error_handler);

    typedef JavaCrwDemo instrument;

    typedef JavaCrwDemoClassname get_class_name;
}

class Instrumentation {

public:
    Instrumentation(JavaVM *_jvm, jvmtiEnv *_jvmti, ConfigurationOptions& _cfg);

    virtual ~Instrumentation();

    void engage_return_tracer(UniqueReadsafePtr<CtxSwitchTracer>& ctxsw_tracer, JNIEnv* env);

    void disengage_return_tracer(JNIEnv* env);

    void class_file_loaded(VmInitState init_state, JNIEnv* env, jclass class_being_redefined, jobject loader,
                           const char* name, jobject protection_domain, jint class_data_len,
                           const unsigned char* class_data, jint* new_class_data_len, unsigned char** new_class_data);

    void evt_mthd_return(JNIEnv* jni_env);

private:
    bool bci_allowed;

    jvmtiEnv* jvmti;

    void* crw_lib;

    Crw::instrument do_instr;

    Crw::get_class_name get_cn;

    std::atomic<UniqueReadsafePtr<CtxSwitchTracer>*> tracer_ursp;

    std::atomic<std::uint32_t> clsfile_load_count{0};
};

#endif
