#include <iostream>
#include <cstring>
#include <list>
#include "jnif.hpp"
#include "class_redef.hh"

void JNICALL OnClassFileLoadHook(jvmtiEnv *jvmti_env, JNIEnv *env, jclass class_being_redefined,
                                 jobject loader, const char *name, jobject protection_domain,
                                 jint class_data_len, const unsigned char *class_data,
                                 jint *new_class_data_len, unsigned char **new_class_data) {

    if (name != nullptr) {
        if(strcmp(name, "java/lang/Thread") == 0) {
            jnif::parser::ClassFileParser cfp(class_data, class_data_len);

            // new fields
            cfp.addField("taskCtx", "Lfk/prof/AsyncTaskCtx;", jnif::Field::PUBLIC);

            unsigned char *class_data_new;

            jnif::u4 sz = cfp.computeSize();
            jvmti_env->Allocate(sz, &class_data_new);
            cfp.write(class_data_new, sz);

            *new_class_data_len = sz;
            *new_class_data = class_data_new;
        } 
        else if(strcmp(name, "org/eclipse/jetty/server/HttpChannelState") == 0) {
            jnif::parser::ClassFileParser cfp(class_data, class_data_len);

            // new fields
            cfp.addField("$$$_httpTask", "Lfk/prof/HttpTask;", jnif::Field::PUBLIC);
        
            unsigned char *class_data_new;

            jnif::u4 sz = cfp.computeSize();
            jvmti_env->Allocate(sz, &class_data_new);
            cfp.write(class_data_new, sz);

            *new_class_data_len = sz;
            *new_class_data = class_data_new;
        }
    }
}
