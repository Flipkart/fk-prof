#ifndef BLOCKING_EVENTS_HH
#define BLOCKING_EVENTS_HH

#include <stdint.h>
#include "fd_map.hh"

namespace blocking {
    
    enum class EvtType {
        socket_read = 0,
        socket_write,
        file_read,
        file_write,
        select
    };
    
    // Events related to read/write events on file descriptors.
    struct FdReadEvt {
        FdBucket* fd;
        // bytes read/write 
        int count;
        bool timeout;
    };
    
    struct FdWriteEvt {
        FdBucket* fd;
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

#endif /* BLOCKING_EVENTS_HH */
