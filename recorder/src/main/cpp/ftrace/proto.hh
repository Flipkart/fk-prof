#ifndef FTRACE_PROTO_H
#define FTRACE_PROTO_H

#include <cstdint>
#include <sys/types.h>
#include <string>

/*
  This is designed to facilitate talk between 2 processes on the same host.
  So it is oblivious to endian-ness issues.

  Pkt:
  0    4    8   12   16   20   24   28   32
  |----|----|----|----|----|----|----|----|
  v    v    v    v    v    v    v    v    v
  
  +---+-----------------------------------+
  |v:3|              ....                 |
  +---+              ....                 |
  |                  ....                 |
  +---------------------------------------+

  (v0, supported, current)
  +---+-----+---------+-------------------+
  |0x0|typ:5|len:8    |                   |
  +---+-----+---------+                   |
  .                ....                   .
  .            data (0..254B)             .
  |                ....                   |
  +---------------------------------------+

  'len' is the length of _entire packet_ (not just the payload)
*/

namespace ftrace {
    namespace v0 {
        extern const std::uint8_t VERSION;

        const std::uint32_t max_pkt_sz = 256;

        typedef std::uint8_t PayloadLen;

        enum PktType  {
            toggle_features   = 0,
            add_tid           = 1,
            del_tid           = 2,
            lost_events       = 3,
            sched_switch      = 4,
            sched_wakeup      = 5
        };

        struct __attribute__((packed)) Header {
            std::uint8_t    v : 3;
            PktType      type : 5;
            std::uint8_t  len : 8;
        };

        namespace payload {
            struct __attribute__((packed)) Features {
                bool show_wakeups;
                bool show_syscalls;
            };

            typedef pid_t AddTid;
            
            typedef pid_t DelTid;
            
            typedef std::uint64_t LostEvents;
            
            struct __attribute__((packed)) SchedSwitch {
                std::uint64_t timestamp;
                std::int32_t out_tid;
                std::int32_t in_tid;
                std::int64_t syscall_nr; //-1 => no syscall
                std::int32_t cpu;
                bool voluntary; // true => out_tid was not running
            };

            struct __attribute__((packed)) SchedWakeup {
                std::uint64_t timestamp;
                std::int32_t target_cpu;
                std::int32_t tid;
                std::int32_t cpu;
            };
        }
    }

    namespace v_curr = v0;
}

namespace std {
    string to_string(const ftrace::v0::PktType type);
}

//for testing
bool operator==(const ftrace::v0::payload::SchedSwitch& sw1, const ftrace::v0::payload::SchedSwitch& sw2);

std::ostream& operator<<(std::ostream& os, const ftrace::v0::payload::SchedSwitch& sw);

bool operator==(const ftrace::v0::payload::SchedWakeup& w1, const ftrace::v0::payload::SchedWakeup& w2);

std::ostream& operator<<(std::ostream& os, const ftrace::v0::payload::SchedWakeup& w);

#endif
