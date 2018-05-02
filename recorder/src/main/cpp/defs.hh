#ifndef DEFS_HH
#define DEFS_HH

#define RECORDER_VERION 1
#define DATA_ENCODING_VERSION 1

#if defined(STATIC_ALLOCATION_ALLOCA)
#define STATIC_ARRAY(NAME, TYPE, SIZE, MAXSZ) TYPE *NAME = (TYPE *)alloca((SIZE) * sizeof(TYPE))
#elif defined(STATIC_ALLOCATION_PEDANTIC)
#define STATIC_ARRAY(NAME, TYPE, SIZE, MAXSZ) TYPE NAME[MAXSZ]
#else
#define STATIC_ARRAY(NAME, TYPE, SIZE, MAXSZ) TYPE NAME[SIZE]
#endif

#define AGENTEXPORT __attribute__((visibility("default"))) JNIEXPORT

// Gets us around -Wunused-parameter
#define IMPLICITLY_USE(x) (void)x;

// Wrap JVMTI functions in this in functions that expect a return
// value and require cleanup but no error message
#define JVMTI_ERROR_CLEANUP_RET_NO_MESSAGE(error, retval, cleanup)                                 \
    {                                                                                              \
        int err;                                                                                   \
        if ((err = (error)) != JVMTI_ERROR_NONE) {                                                 \
            cleanup;                                                                               \
            return (retval);                                                                       \
        }                                                                                          \
    }
// Wrap JVMTI functions in this in functions that expect a return
// value and require cleanup.
#define JVMTI_ERROR_MESSAGE_CLEANUP_RET(error, message, retval, cleanup)                           \
    {                                                                                              \
        int err;                                                                                   \
        if ((err = (error)) != JVMTI_ERROR_NONE) {                                                 \
            logger->critical(message, err);                                                        \
            cleanup;                                                                               \
            return (retval);                                                                       \
        }                                                                                          \
    }

#define JVMTI_ERROR_CLEANUP_RET(error, retval, cleanup)                                            \
    JVMTI_ERROR_MESSAGE_CLEANUP_RET(error, "JVMTI error {}", retval, cleanup)

// Wrap JVMTI functions in this in functions that expect a return value.
#define JVMTI_ERROR_RET(error, retval) JVMTI_ERROR_CLEANUP_RET(error, retval, /* nothing */)

// Wrap JVMTI functions in this in void functions.
#define JVMTI_ERROR(error) JVMTI_ERROR_CLEANUP(error, /* nothing */)

// Wrap JVMTI functions in this in void functions that require cleanup.
#define JVMTI_ERROR_CLEANUP(error, cleanup)                                                        \
    {                                                                                              \
        int err;                                                                                   \
        if ((err = (error)) != JVMTI_ERROR_NONE) {                                                 \
            logger->critical("JVMTI error {}", err);                                               \
            cleanup;                                                                               \
            return;                                                                                \
        }                                                                                          \
    }

#define JNI_EXCEPTION_CHECK(jni_env, errmsg)                                                       \
    {                                                                                              \
        if (jni_env->ExceptionCheck()) {                                                           \
            jni_env->ExceptionClear();                                                             \
            logger->error(errmsg);                                                                 \
            return;                                                                                \
        }                                                                                          \
    }

#define DISALLOW_COPY_AND_ASSIGN(TypeName)                                                         \
    TypeName(const TypeName &) = delete;                                                           \
    void operator=(const TypeName &) = delete

#define DISALLOW_IMPLICIT_CONSTRUCTORS(TypeName)                                                   \
    TypeName() = delete;                                                                           \
    DISALLOW_COPY_AND_ASSIGN(TypeName)

#endif /* DEFS_HH */
