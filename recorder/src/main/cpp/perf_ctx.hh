/* DO NOT EDIT THIS FILE - it is machine generated */
#include <jni.h>
/* Header for class fk_prof_PerfCtx */

#ifndef _Included_fk_prof_PerfCtx
#define _Included_fk_prof_PerfCtx
#ifdef __cplusplus
extern "C" {
#endif
/*
 * Class:     fk_prof_PerfCtx
 * Method:    registerCtx
 * Signature: (Ljava/lang/String;I)I
 */
    JNIEXPORT jint JNICALL Java_fk_prof_PerfCtx_registerCtx
    (JNIEnv *, jobject, jstring, jint);

/*
 * Class:     fk_prof_PerfCtx
 * Method:    end
 * Signature: (I)V
 */
    JNIEXPORT void JNICALL Java_fk_prof_PerfCtx_end
    (JNIEnv *, jobject, jint);

/*
 * Class:     fk_prof_PerfCtx
 * Method:    start
 * Signature: (I)V
 */
    JNIEXPORT void JNICALL Java_fk_prof_PerfCtx_start
    (JNIEnv *, jobject, jint);

#ifdef __cplusplus
}
#endif
#endif
