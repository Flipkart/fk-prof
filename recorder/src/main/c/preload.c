#define _GNU_SOURCE
#include <pthread.h>
#include <signal.h>
#include <assert.h>
#include <stdio.h>
#include <jni.h>
#include <dlfcn.h>

int JLI_Launch(int argc, char ** argv,
           int jargc, const char** jargv,
           int appclassc, const char** appclassv,
           const char* fullversion,
           const char* dotversion,
           const char* pname,
           const char* lname,
           jboolean javaargs,
           jboolean cpwildcard,
           jboolean javaw,
           jint ergo
    ) {

    sigset_t set;
    int err = sigemptyset(&set);
    assert(err == 0);
    err = sigaddset(&set, SIGPROF);
    assert(err == 0);
    err = pthread_sigmask(SIG_BLOCK, &set, NULL);
    assert(err == 0);

    typedef int (*jli_launch_t)(int argc, char ** argv,
                        int jargc, const char** jargv,
                        int appclassc, const char** appclassv,
                        const char* fullversion,
                        const char* dotversion,
                        const char* pname,
                        const char* lname,
                        jboolean javaargs,
                        jboolean cpwildcard,
                        jboolean javaw,
                        jint ergo);
    jli_launch_t jli_launch = (jli_launch_t) dlsym(RTLD_NEXT, "JLI_Launch");
    jli_launch(argc, argv, jargc, jargv, appclassc, appclassv, fullversion, dotversion, pname, lname, javaargs, cpwildcard, javaw, ergo);

    return 0;
}
