#ifndef BLOCKING_EVENTS_HH
#define BLOCKING_EVENTS_HH

#include <stdint.h>

namespace blocking {
    
    enum class EvtType {
        socket_read = 0,
        socket_write,
        wait,
        lock,
        file_read,
        file_write,
        select
    };
    
    // Events related to read/write events on file descriptors.
    struct FdReadEvt {
        int fd;
        // bytes read/write 
        int count;
        bool timeout;
    };
    
    struct FdWriteEvt {
        int fd;
        int count;
    };
    
    struct BlockingEvt {
        std::uint64_t ts;
        std::uint64_t latency_ns;
        EvtType type;
        
        union {
            FdReadEvt fd_read_evt;
            FdWriteEvt fd_write_evt;
        } evt;
    };
}

#endif /* IO_EVENTS_HH */
