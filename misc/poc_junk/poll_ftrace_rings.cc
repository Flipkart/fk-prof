#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/types.h>
#include <sys/socket.h>
#include <netdb.h>
#include <unistd.h>
#include <fcntl.h>
#include <sys/epoll.h>
#include <errno.h>
#include <cstdint>
#include <unordered_map>
#include <sys/stat.h>
#include <fcntl.h>

#include <iostream>
#include <stdexcept>
#include <sys/types.h>
#include <sys/stat.h>
#include <fcntl.h>
#include <unistd.h>
#include <errno.h>
#include <cstdint>
#include <memory>
#include <thread>
#include <chrono>

struct PgHeader {
    std::uint64_t ts;
    std::uint64_t data_len;
};

struct __attribute__((packed)) EvtHdrPrefix {
    std::uint32_t type_len:5, time_delta:27;
};

struct __attribute__((packed)) SyscallEnter {
    std::int64_t nr;
    std::uint64_t args[6];
};

struct __attribute__((packed)) SyscallExit {
    std::int64_t nr;
    std::int64_t ret;
};

#define RINGBUF_TYPE_DATA_TYPE_LEN_MAX 28
#define RINGBUF_TYPE_PADDING 29
#define RINGBUF_TYPE_TIME_EXTEND 30

struct __attribute__((packed)) CommonFields {
    std::uint16_t common_type;
    std::uint8_t common_flags;
    std::uint8_t common_preempt_count;
    std::int32_t common_pid;
};

// field:char prev_comm[16];       offset:8;       size:16;        signed:1;
// field:pid_t prev_pid;   offset:24;      size:4; signed:1;
// field:int prev_prio;    offset:28;      size:4; signed:1;
// field:long prev_state;  offset:32;      size:8; signed:1;
// field:char next_comm[16];       offset:40;      size:16;        signed:1;
// field:pid_t next_pid;   offset:56;      size:4; signed:1;
// field:int next_prio;    offset:60;      size:4; signed:1;

struct __attribute__((packed)) SchedSwitch {
    char prev_comm[16];
    std::int32_t prev_pid;
    std::int32_t prev_prio;
    std::int64_t prev_state;
    char next_comm[16];
    std::int32_t next_pid;
    std::int32_t next_prio;
};

// field:char comm[16];    offset:8;       size:16;        signed:1;
// field:pid_t pid;        offset:24;      size:4; signed:1;
// field:int prio; offset:28;      size:4; signed:1;
// field:int success;      offset:32;      size:4; signed:1;
// field:int target_cpu;   offset:36;      size:4; signed:1;

struct __attribute__((packed)) SchedWakeup {
    char comm[16];
    std::int32_t pid;
    std::int32_t prio;
    std::int32_t success;
    std::int32_t target_cpu;
};

std::uint32_t r_u32(std::uint8_t** ev_buff) {
    std::uint32_t* val = reinterpret_cast<std::uint32_t*>(*ev_buff);
    *ev_buff += sizeof(std::uint32_t);
    return *val;
}

std::int64_t r_i64(std::uint8_t** ev_buff) {
    std::int64_t* val = reinterpret_cast<std::int64_t*>(*ev_buff);
    *ev_buff += sizeof(std::int64_t);
    return *val;
}

#define ID_SYS_EXIT 16
#define ID_SYS_ENTER 17
#define ID_SCHED_SWITCH 273
#define ID_SCHED_WAKEUP 275

template <typename T> T* read(std::uint8_t** ev_buff) {
    auto p = reinterpret_cast<T*>(*ev_buff);
    *ev_buff += sizeof(T);
    return p;
}

void handle_event(std::uint8_t** ev_buff, std::uint32_t len, std::uint64_t ts, int cpu) {
    auto start_addr = reinterpret_cast<std::uint64_t>(*ev_buff);
    auto cf = reinterpret_cast<CommonFields*>(*ev_buff);
    *ev_buff += sizeof(CommonFields);
    std::int64_t nr = 0;
    SyscallEnter* s_enter = nullptr;
    SyscallExit* s_exit = nullptr;
    SchedSwitch* ss = nullptr;
    SchedWakeup* sw = nullptr;
    auto t_us = ts / 1000;
    auto us_per_s = 1000 * 1000;
    auto t_s = t_us / us_per_s;
    t_us %= us_per_s;
    std::cout << '[' << cpu << ']' << "T: " << t_s << "." << t_us << " -> ";
    switch(cf->common_type) {
    case ID_SYS_EXIT:
        s_exit = read<SyscallExit>(ev_buff);
        std::cout << "SYS_Exit(pid: " << cf->common_pid << "): " << s_exit->nr << "\n";
        break;
    case ID_SYS_ENTER:
        s_enter = read<SyscallEnter>(ev_buff);
        std::cout << "SYS_Enter(pid: " << cf->common_pid << "): " << s_enter->nr << "\n";
        break;
    case ID_SCHED_SWITCH:
        ss = read<SchedSwitch>(ev_buff);
        std::cout << "Switch(pid: " << cf->common_pid << "): Prev(" << (ss->prev_state == 0) << ") (cmd: " << ss->prev_comm << ", pid: " << ss->prev_pid << ") => Next (cmd: " << ss->next_comm << ", pid: " << ss->next_pid << ")\n";
        break;
    case ID_SCHED_WAKEUP:
        sw = read<SchedWakeup>(ev_buff);
        std::cout << "Wakeup(pid: " << cf->common_pid << "): (cmd: " << sw->comm << ", pid: " << sw->pid << ", target-cpu: " << sw->target_cpu << ")\n";
        break;
    default:
        std::cerr << "Unknown common_type: " << cf->common_type << "\n";
    }
    auto end_addr = reinterpret_cast<std::uint64_t>(*ev_buff);
    auto total_read = end_addr - start_addr;
    if(total_read == len) {
        std::cerr << "Len matched (" << total_read << ")\n";
    } else {
        std::cerr << "Len didn't match, expected: " << len << " actual: " << total_read << "\n";
        auto remaining = (len - total_read);
        *ev_buff += remaining;
    }
}

void parse(std::uint8_t* ev_buff, const PgHeader& pg_hdr, int cpu) {
    std::uint64_t ts = pg_hdr.ts;
    auto end_pos = ev_buff + pg_hdr.data_len;
    std::cerr << "Data in page: " << pg_hdr.data_len << " end-pos: '" << static_cast<void*>(end_pos) << "'\n";
    while (ev_buff < end_pos) {
        std::cerr << "Curr Pos: '" << static_cast<void*>(ev_buff) << "'\n";
        auto fhp = reinterpret_cast<EvtHdrPrefix*>(ev_buff);
        ev_buff += sizeof(EvtHdrPrefix);
        std::cerr << "Type-len: " << fhp->type_len << "\n";
        if (fhp->type_len == 0) {
            auto arr_0 = r_u32(&ev_buff);
            ts += fhp->time_delta;
            handle_event(&ev_buff, arr_0 - sizeof(arr_0), ts, cpu);
        } else if (fhp->type_len <= RINGBUF_TYPE_DATA_TYPE_LEN_MAX) {
            std::uint32_t len = fhp->type_len;
            len <<= 2;
            ts += fhp->time_delta;
            handle_event(&ev_buff, len, ts, cpu);
        } else if (fhp->type_len == RINGBUF_TYPE_PADDING) {
            if (fhp->time_delta == 0) {
                std::cout << "Padding tail-record\n";
                return;
            }
            auto arr_0 = r_u32(&ev_buff);
            ev_buff += (arr_0 - sizeof(arr_0));
            ts += fhp->time_delta;
            std::cerr << "Padding ate: " << arr_0 << " bytes, curr pos: " << static_cast<void*>(ev_buff) << "\n";
        } else if (fhp->type_len == RINGBUF_TYPE_TIME_EXTEND) {
            auto arr_0 = r_u32(&ev_buff);
            std::uint64_t time_delta = arr_0;
            time_delta = (time_delta << 27);
            time_delta += fhp->time_delta;
            std::cerr << "Time δ: "  << time_delta << "\n";
            ts += time_delta;
        } else {
            std::cerr << "Got invalid type_len value " << fhp->type_len << "\n";
            throw std::runtime_error("Got invalid type_len value");
        }
    }
}


#define MAXEVENTS 64

static int
make_socket_non_blocking (int sfd)
{
    int flags, s;

    flags = fcntl (sfd, F_GETFL, 0);
    if (flags == -1)
    {
        perror ("fcntl");
        return -1;
    }

    flags |= O_NONBLOCK;
    s = fcntl (sfd, F_SETFL, flags);
    if (s == -1)
    {
        perror ("fcntl");
        return -1;
    }

    return 0;
}

static int
create_and_bind (char *port)
{
    struct addrinfo hints;
    struct addrinfo *result, *rp;
    int s, sfd;

    memset (&hints, 0, sizeof (struct addrinfo));
    hints.ai_family = AF_UNSPEC;     /* Return IPv4 and IPv6 choices */
    hints.ai_socktype = SOCK_STREAM; /* We want a TCP socket */
    hints.ai_flags = AI_PASSIVE;     /* All interfaces */

    s = getaddrinfo (NULL, port, &hints, &result);
    if (s != 0)
    {
        fprintf (stderr, "getaddrinfo: %s\n", gai_strerror (s));
        return -1;
    }

    for (rp = result; rp != NULL; rp = rp->ai_next)
    {
        sfd = socket (rp->ai_family, rp->ai_socktype, rp->ai_protocol);
        if (sfd == -1)
            continue;

        s = bind (sfd, rp->ai_addr, rp->ai_addrlen);
        if (s == 0)
        {
            /* We managed to bind successfully! */
            break;
        }

        close (sfd);
    }

    if (rp == NULL)
    {
        fprintf (stderr, "Could not bind\n");
        return -1;
    }

    freeaddrinfo (result);

    return sfd;
}

struct FtraceRing {
    int cpu;
    std::uint64_t dropped;
    int stats_fd;
};

std::unordered_map<int, FtraceRing> ft_rings;

void add_cpu_rings(int efd, int cpu_count) {
    for (int i = 0; i < cpu_count; i++) {
        std::string p = "/sys/kernel/debug/tracing/instances/quux/per_cpu/cpu" + std::to_string(i) + "/trace_pipe_raw";
        struct stat info;
        if (stat(p.c_str(), &info) != 0) {
            std::runtime_error("Couldn't find trace_pipe_raw file at " + p);
        }
        auto fd = open(p.c_str(), O_RDONLY | O_NONBLOCK);
        struct epoll_event event;
        event.data.fd = fd;
        event.events = EPOLLIN;
        if (epoll_ctl(efd, EPOLL_CTL_ADD, fd, &event) != 0) {
            perror("Couldn't add trace-pipe to epoll ctx");
            std::runtime_error("Couldn't setup epoll ctx for per-cpu ftrace rings");
        }
        ft_rings[fd] = {i, 0, -1};
    }
}

void read_a_page_and_print(int fd, std::uint8_t* buff, ssize_t sz, int cpu) {
    while (true) {
        ssize_t rd = read(fd, buff, sz);
        if (rd == 0) return;
        std::cerr << "Read sz: " << rd << "\n";
        std::uint64_t pg_tm;
        if (rd > 0) {
            auto pg_hdr = reinterpret_cast<PgHeader*>(buff);
            auto pg_usec = pg_hdr->ts / 1000;
            auto pg_sec =  pg_usec / (1000 * 1000);
            pg_usec -= (pg_sec * 1000 * 1000);
            std::cerr << "Ts: " << pg_sec << "." << pg_usec << " len: " << pg_hdr->data_len << "\n";
            std::uint8_t* events = buff;
            events += sizeof(PgHeader);
            parse(events, *pg_hdr, cpu);
        } else if (rd == -1) {
            if (errno == EAGAIN) return;
            perror("Read of per-cpu ring failed");
            throw std::runtime_error("Error " + std::to_string(errno));
        }
    }
}

int main(int argc, char** argv) {
    if (argc < 2) throw std::runtime_error("too few args, need raw file name too");

    std::uint8_t buff[4096];
    
    int fd = open(argv[1], O_RDONLY);
    read_a_page_and_print(fd, buff, sizeof(buff), 1);
    close(fd);
}

// int
// main (int argc, char *argv[])
// {
//     int sfd, s;
//     int efd;
//     struct epoll_event event;
//     struct epoll_event events[MAXEVENTS];

//     size_t pg_sz = getpagesize();
//     std::unique_ptr<std::uint8_t[]> buff { new std::uint8_t[pg_sz] };

//     if (argc != 3)
//     {
//         fprintf (stderr, "Usage: %s [port] [cpu-count]\n", argv[0]);
//         exit (EXIT_FAILURE);
//     }

//     sfd = create_and_bind (argv[1]);
//     auto cpu_count = std::stoi(argv[2]);
//     if (sfd == -1)
//         abort ();

//     s = make_socket_non_blocking (sfd);
//     if (s == -1)
//         abort ();

//     s = listen (sfd, SOMAXCONN);
//     if (s == -1)
//     {
//         perror ("listen");
//         abort ();
//     }

//     efd = epoll_create1 (0);
//     if (efd == -1)
//     {
//         perror ("epoll_create");
//         abort ();
//     }

//     event.data.fd = sfd;
//     event.events = EPOLLIN | EPOLLET;
//     s = epoll_ctl (efd, EPOLL_CTL_ADD, sfd, &event);

//     if (s == -1)
//     {
//         perror ("epoll_ctl");
//         abort ();
//     }

//     add_cpu_rings(efd, cpu_count);

//     /* The event loop */
//     while (1)
//     {
//         int n, i;

//         n = epoll_wait (efd, events, MAXEVENTS, -1);
//         for (i = 0; i < n; i++)
//         {
//             if ((events[i].events & EPOLLERR) ||
//                 (events[i].events & EPOLLHUP) ||
//                 (!(events[i].events & EPOLLIN)))
//             {
//                 /* An error has occured on this fd, or the socket is not
//                    ready for reading (why were we notified then?) */
//                 fprintf (stderr, "epoll error\n");
//                 close (events[i].data.fd);
//                 continue;
//             }

//             else if (sfd == events[i].data.fd)
//             {
//                 /* We have a notification on the listening socket, which
//                    means one or more incoming connections. */
//                 while (1)
//                 {
//                     struct sockaddr in_addr;
//                     socklen_t in_len;
//                     int infd;
//                     char hbuf[NI_MAXHOST], sbuf[NI_MAXSERV];

//                     in_len = sizeof in_addr;
//                     infd = accept (sfd, &in_addr, &in_len);
//                     if (infd == -1)
//                     {
//                         if ((errno == EAGAIN) ||
//                             (errno == EWOULDBLOCK))
//                         {
//                             /* We have processed all incoming
//                                connections. */
//                             break;
//                         }
//                         else
//                         {
//                             perror ("accept");
//                             break;
//                         }
//                     }

//                     s = getnameinfo (&in_addr, in_len,
//                                      hbuf, sizeof hbuf,
//                                      sbuf, sizeof sbuf,
//                                      NI_NUMERICHOST | NI_NUMERICSERV);
//                     if (s == 0)
//                     {
//                         printf("Accepted connection on descriptor %d "
//                                "(host=%s, port=%s)\n", infd, hbuf, sbuf);
//                     }

//                     /* Make the incoming socket non-blocking and add it to the
//                        list of fds to monitor. */
//                     s = make_socket_non_blocking (infd);
//                     if (s == -1)
//                         abort ();

//                     event.data.fd = infd;
//                     event.events = EPOLLIN | EPOLLET;
//                     s = epoll_ctl (efd, EPOLL_CTL_ADD, infd, &event);
//                     if (s == -1)
//                     {
//                         perror ("epoll_ctl");
//                         abort ();
//                     }
//                 }
//                 continue;
//             } else if (ft_rings.find(events[i].data.fd) != std::end(ft_rings))
//             {
//                 auto it = ft_rings.find(events[i].data.fd);
//                 read_a_page_and_print(events[i].data.fd, buff.get(), pg_sz, it->second.cpu);
//             }
//             else
//             {
//                 /* We have data on the fd waiting to be read. Read and
//                    display it. We must read whatever data is available
//                    completely, as we are running in edge-triggered mode
//                    and won't get a notification again for the same
//                    data. */
//                 int done = 0;

//                 while (1)
//                 {
//                     ssize_t count;
//                     char buf[512];

//                     count = read (events[i].data.fd, buf, sizeof buf);
//                     if (count == -1)
//                     {
//                         /* If errno == EAGAIN, that means we have read all
//                            data. So go back to the main loop. */
//                         if (errno != EAGAIN)
//                         {
//                             perror ("read");
//                             done = 1;
//                         }
//                         break;
//                     }
//                     else if (count == 0)
//                     {
//                         /* End of file. The remote has closed the
//                            connection. */
//                         done = 1;
//                         break;
//                     }

//                     /* Write the buffer to standard output */
//                     s = write (1, buf, count);
//                     if (s == -1)
//                     {
//                         perror ("write");
//                         abort ();
//                     }
//                 }

//                 if (done)
//                 {
//                     printf ("Closed connection on descriptor %d\n",
//                             events[i].data.fd);

//                     /* Closing the descriptor will make epoll remove it
//                        from the set of descriptors which are monitored. */
//                     close (events[i].data.fd);
//                 }
//             }
//         }
//     }

//     close (sfd);

//     return EXIT_SUCCESS;
// }
