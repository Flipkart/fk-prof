#include <thread>
#include <iostream>
#include "test.hh"
#include <boost/asio.hpp>
#include <ftrace/proto.hh>
#include <spdlog/fmt/ostr.h>

const static ftrace::v_curr::PayloadLen hdr_sz = sizeof(ftrace::v_curr::Header);
std::uint8_t part_msg_len(0);
const static size_t max_msg_sz = (1 << (sizeof(part_msg_len) * 8)) - 1;
std::uint8_t part_msg[max_msg_sz];//partial message
const static size_t io_buff_sz = 1 * 1024;
//Selecting the last logical cpu for binding
const static int cpu_to_bind = std::thread::hardware_concurrency() - 1;

struct FtraceEventTracker {
    unsigned int ss_in;
    unsigned int ss_out;
    unsigned int sw;
};

std::unordered_map<int32_t , FtraceEventTracker> thread_events;

void handle_pkt(ftrace::v_curr::PktType type, std::uint8_t* buff, size_t len) {
    ftrace::v_curr::payload::SchedSwitch* ss;
    ftrace::v_curr::payload::SchedWakeup* sw;
    ftrace::v_curr::payload::LostEvents * le;
    bool tracked_pid;

    switch (type) {
        case ftrace::v_curr::PktType::sched_switch:
            assert(len == sizeof(ftrace::v_curr::payload::SchedSwitch));
            ss = reinterpret_cast<ftrace::v_curr::payload::SchedSwitch*>(buff);
            logger->info("sched switch {}", *ss);
            assert(ss->cpu == cpu_to_bind);
            tracked_pid = false;
            if(ss->in_tid) {
                auto it = thread_events.find(ss->in_tid);
                if(it != std::end(thread_events)) {
                    it->second.ss_in += 1;
                    tracked_pid = true;
                }
            }
            if(ss->out_tid) {
                auto it = thread_events.find(ss->out_tid);
                if(it != std::end(thread_events)) {
                    it->second.ss_out += 1;
                    tracked_pid = true;
                }
            }
            assert(tracked_pid);
            break;
        case ftrace::v_curr::PktType::sched_wakeup:
            assert(len == sizeof(ftrace::v_curr::payload::SchedWakeup));
            sw = reinterpret_cast<ftrace::v_curr::payload::SchedWakeup*>(buff);
            logger->info("sched wakeup {}", *sw);
            assert(sw->target_cpu == cpu_to_bind);
            tracked_pid = false;
            if(sw->tid) {
                auto it = thread_events.find(sw->tid);
                if(it != std::end(thread_events)) {
                    it->second.sw += 1;
                    tracked_pid = true;
                }
            }
            assert(tracked_pid);
            break;
        case ftrace::v_curr::PktType::lost_events:
            assert(len == sizeof(ftrace::v_curr::payload::LostEvents));
            le = reinterpret_cast<ftrace::v_curr::payload::LostEvents *>(buff);
            logger->info("lost events {}", *le);
            break;
        default:
            logger->error("Received pkt with unexpected type ({}) of RPC", std::to_string(type));
            throw std::runtime_error("Unexpected rpc class: " + std::to_string(type));
    }
}

void handle_server_response(std::uint8_t* buff, size_t read_sz) {
    if (read_sz == 0) {
        logger->warn("closed connection probably");
        return;
    }

    logger->trace("bytes received={}, bytes leftover={}", read_sz, part_msg_len);
    while (read_sz > 0) {
        logger->trace("bytes remaining={}", read_sz + part_msg_len);
        if (part_msg_len == 0) {
            if (read_sz >= hdr_sz) {
                auto h = reinterpret_cast<ftrace::v_curr::Header*>(buff);
                auto pkt_len = h->len;
                if (read_sz >= pkt_len) {
                    handle_pkt(h->type, buff + hdr_sz, pkt_len - hdr_sz);
                    buff += pkt_len;
                    read_sz -= pkt_len;
                } else {
                    memcpy(part_msg, buff, read_sz);
                    part_msg_len = read_sz;
                    read_sz = 0;
                }
            } else {
                memcpy(part_msg, buff, read_sz);
                part_msg_len = read_sz;
                read_sz = 0;
            }
        } else {
            if (part_msg_len >= hdr_sz) {
                auto h = reinterpret_cast<ftrace::v_curr::Header*>(part_msg);
                auto pkt_len = h->len;
                auto payload_sz = pkt_len - hdr_sz;
                auto missing_payload_bytes = pkt_len - part_msg_len;
                assert(missing_payload_bytes > 0);
                if (read_sz >= missing_payload_bytes) {
                    memcpy(part_msg + part_msg_len, buff, missing_payload_bytes);
                    handle_pkt(h->type, part_msg + hdr_sz, payload_sz);
                    buff += missing_payload_bytes;
                    read_sz -= missing_payload_bytes;
                    part_msg_len = 0;
                } else {
                    memcpy(part_msg + part_msg_len, buff, read_sz);
                    part_msg_len += read_sz;
                    read_sz = 0;
                }
            } else {
                auto missing_header_bytes = (hdr_sz - part_msg_len);
                assert(missing_header_bytes > 0);
                if (read_sz >= missing_header_bytes) {
                    memcpy(part_msg + part_msg_len, buff, missing_header_bytes);
                    buff += missing_header_bytes;
                    read_sz -= missing_header_bytes;
                    part_msg_len += missing_header_bytes;
                    auto h = reinterpret_cast<ftrace::v_curr::Header*>(part_msg);
                    auto pkt_len = h->len;
                    auto payload_sz = pkt_len - hdr_sz;
                    if (read_sz >= payload_sz) {
                        handle_pkt(h->type, buff, payload_sz);
                        buff += payload_sz;
                        read_sz -= payload_sz;
                        part_msg_len = 0;
                    } else {
                        memcpy(part_msg + part_msg_len, buff, read_sz);
                        part_msg_len += read_sz;
                        read_sz = 0;
                    }
                } else {
                    memcpy(part_msg + part_msg_len, buff, read_sz);
                    part_msg_len += read_sz;
                    read_sz = 0;
                }
            }
        }
    }
}

void add_tid(boost::asio::local::stream_protocol::socket &s, pid_t tid) {
    ftrace::v_curr::Header h = { .v = ftrace::v_curr::VERSION, .type = ftrace::v_curr::add_tid };
    ftrace::v_curr::payload::AddTid p = tid;
    h.len = sizeof(h) + sizeof(p);

    std::vector<boost::asio::const_buffer> buffers;
    buffers.push_back(boost::asio::buffer(&h, sizeof(h)));
    buffers.push_back(boost::asio::buffer(&p, sizeof(p)));
    boost::asio::write(s, buffers);

    logger->info("Added tracking for tid {}", tid);
}

void del_tid(boost::asio::local::stream_protocol::socket &s, pid_t tid) {
    ftrace::v_curr::Header h = { .v = ftrace::v_curr::VERSION, .type = ftrace::v_curr::del_tid };
    ftrace::v_curr::payload::DelTid p = tid;
    h.len = sizeof(h) + sizeof(p);

    std::vector<boost::asio::const_buffer> buffers;
    buffers.push_back(boost::asio::buffer(&h, sizeof(h)));
    buffers.push_back(boost::asio::buffer(&p, sizeof(p)));
    boost::asio::write(s, buffers);

    logger->info("Removed tracking for tid {}", tid);
}

void receive_loop(boost::asio::local::stream_protocol::socket &s, int seconds) {
    std::atomic_bool keep_receiving(true);
    std::thread terminator = std::thread([&keep_receiving, seconds] {
        std::this_thread::sleep_for(std::chrono::seconds(seconds)); //this wait ensures meaningful events from ftrace are captured
        keep_receiving.store(false, std::memory_order_relaxed);
    });

    std::unique_ptr<uint8_t[]> io_buff(new uint8_t[io_buff_sz]);
    while(keep_receiving.load(std::memory_order_relaxed)) {
        auto buff = io_buff.get();
        auto read_sz = boost::asio::read(s, boost::asio::buffer(buff, io_buff_sz));
        handle_server_response(buff, read_sz);
    }
    logger->trace("Exited receive loop");
    terminator.join();
}

void validate_events(pid_t tid) {
    auto fet = thread_events[tid];
    CHECK(fet.ss_in > 0);
    CHECK(fet.ss_out > 0);
    CHECK(fet.sw > 0);
    logger->info("Validated events for thread={}: switch_in={}, switch_out={}, wakeup={}",
                 tid, fet.ss_in, fet.ss_out, fet.sw);
}

TEST(ftrace_client) {
    TestEnv _;
    logger->info("Starting ftrace client test");
    try {
        const int num_threads = 2;
        std::atomic_bool keep_processing(true);

        std::thread threads[num_threads];
        pid_t tids[num_threads];
        for (unsigned i = 0; i < num_threads; ++i) {
            threads[i] = std::thread([i, &keep_processing, &tids, &logger] {
                std::this_thread::sleep_for(std::chrono::milliseconds(100 * (i*2 + 1)));
                tids[i] = syscall(SYS_gettid);
                thread_events[tids[i]] = {0, 0, 0};
                logger->trace("Starting processing thread {}", tids[i]);
                while (keep_processing.load(std::memory_order_relaxed)) {
                    std::this_thread::sleep_for(std::chrono::milliseconds(100));
                }
                logger->trace("Exiting processing thread {}", tids[i]);
            });

            cpu_set_t cpuset;
            CPU_ZERO(&cpuset);
            CPU_SET(cpu_to_bind, &cpuset);
            int rc = pthread_setaffinity_np(threads[i].native_handle(),
                                            sizeof(cpu_set_t), &cpuset);
            if (rc != 0) {
                std::cerr << "Error calling pthread_setaffinity_np: " << rc << "\n";
            } else {
                logger->trace("Pinned processing thread {} to cpu {}", i, cpu_to_bind);
            }
        }

        //this wait ensures processing threads are setup
        std::this_thread::sleep_for(std::chrono::seconds(2));

        using boost::asio::local::stream_protocol;
        boost::asio::io_service io_service;
        stream_protocol::socket s(io_service);
        s.connect(stream_protocol::endpoint("/var/tmp/fkp-tracer.sock"));

        add_tid(s, tids[0]);
        add_tid(s, tids[1]);
        receive_loop(s, 5);

        validate_events(tids[0]);
        validate_events(tids[1]);

        keep_processing.store(false, std::memory_order_relaxed);
        for (auto& t : threads) {
            t.join();
        }
    } catch (std::exception& e) {
        logger->error(e.what());
        std::cerr << "Exception: " << e.what() << "\n";
    }
    logger->trace("Exiting test");
}

