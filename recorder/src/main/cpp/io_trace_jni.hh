/* DO NOT EDIT THIS FILE - it is machine generated */
#include <jni.h>
/* Header for class fk_prof_trace_IOTrace */

#ifndef _Included_fk_prof_trace_IOTrace
#define _Included_fk_prof_trace_IOTrace
#ifdef __cplusplus
extern "C" {
#endif
#ifdef __cplusplus
}
#endif
#endif
/* Header for class fk_prof_trace_IOTrace_File */

#ifndef _Included_fk_prof_trace_IOTrace_File
#define _Included_fk_prof_trace_IOTrace_File
#ifdef __cplusplus
extern "C" {
#endif
/*
 * Class:     fk_prof_trace_IOTrace_File
 * Method:    open
 * Signature: (ILjava/lang/String;JJ)V
 */
JNIEXPORT void JNICALL Java_fk_prof_trace_IOTrace_00024File_open
  (JNIEnv *, jclass, jint, jstring, jlong, jlong);

/*
 * Class:     fk_prof_trace_IOTrace_File
 * Method:    read
 * Signature: (IIJJ)V
 */
JNIEXPORT void JNICALL Java_fk_prof_trace_IOTrace_00024File_read
  (JNIEnv *, jclass, jint, jint, jlong, jlong);

/*
 * Class:     fk_prof_trace_IOTrace_File
 * Method:    write
 * Signature: (IIJJ)V
 */
JNIEXPORT void JNICALL Java_fk_prof_trace_IOTrace_00024File_write
  (JNIEnv *, jclass, jint, jint, jlong, jlong);

#ifdef __cplusplus
}
#endif
#endif
/* Header for class fk_prof_trace_IOTrace_Socket */

#ifndef _Included_fk_prof_trace_IOTrace_Socket
#define _Included_fk_prof_trace_IOTrace_Socket
#ifdef __cplusplus
extern "C" {
#endif
/*
 * Class:     fk_prof_trace_IOTrace_Socket
 * Method:    accept
 * Signature: (ILjava/lang/String;JJ)V
 */
JNIEXPORT void JNICALL Java_fk_prof_trace_IOTrace_00024Socket_accept
  (JNIEnv *, jclass, jint, jstring, jlong, jlong);

/*
 * Class:     fk_prof_trace_IOTrace_Socket
 * Method:    connect
 * Signature: (ILjava/lang/String;JJ)V
 */
JNIEXPORT void JNICALL Java_fk_prof_trace_IOTrace_00024Socket_connect
  (JNIEnv *, jclass, jint, jstring, jlong, jlong);

/*
 * Class:     fk_prof_trace_IOTrace_Socket
 * Method:    read
 * Signature: (IIJJZ)V
 */
JNIEXPORT void JNICALL Java_fk_prof_trace_IOTrace_00024Socket_read
  (JNIEnv *, jclass, jint, jint, jlong, jlong, jboolean);

/*
 * Class:     fk_prof_trace_IOTrace_Socket
 * Method:    write
 * Signature: (IIJJ)V
 */
JNIEXPORT void JNICALL Java_fk_prof_trace_IOTrace_00024Socket_write
  (JNIEnv *, jclass, jint, jint, jlong, jlong);

#ifdef __cplusplus
}
#endif
#endif
