/* 
 * File:   class_redef.hpp
 * Author: gaurav.ashok
 *
 * Created on 27 May, 2018, 3:37 PM
 */

#ifndef CLASS_REDEF_HPP
#define CLASS_REDEF_HPP

#include "jvmti.h"

void JNICALL OnClassFileLoadHook(jvmtiEnv *jvmti_env, JNIEnv *env, jclass class_being_redefined,
                                 jobject loader, const char *name, jobject protection_domain,
                                 jint class_data_len, const unsigned char *class_data,
                                 jint *new_class_data_len, unsigned char **new_class_data);

#endif /* CLASS_REDEF_HPP */
