#include "ti_thd.hh"

void quiesce_sigprof(const char* thd_name) {
    sigset_t mask;
    sigemptyset(&mask);
    sigaddset(&mask, SIGPROF);
    if (pthread_sigmask(SIG_BLOCK, &mask, NULL) < 0) {
        logger->error("Unable to set thread {} signal mask for quiescing sigprof", thd_name);
    }
}

template struct ThreadTargetProc<void *>;
