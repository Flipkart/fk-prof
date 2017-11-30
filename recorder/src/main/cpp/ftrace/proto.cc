#include "ftrace/proto.hh"
#include <stdexcept>
#include <ostream>
#include <cassert>
#include <cstring>

const std::uint8_t ftrace::v0::VERSION = 0;


std::string std::to_string(const ftrace::v0::PktType type) {
    switch(type) {
    case ftrace::v0::PktType::toggle_features:
        return "v0::Features";
    case ftrace::v0::PktType::add_tid:
        return "v0::AddTid";
    case ftrace::v0::PktType::del_tid:
        return "v0::DelTid";
    case ftrace::v0::PktType::lost_events:
        return "v0::LostEvents";
    case ftrace::v0::PktType::sched_switch:
        return "v0::SchedSwitch";
    case ftrace::v0::PktType::sched_wakeup:
        return "v0::SchedWakeup";
    default:
        std::runtime_error("Unknown v0 pkt-type: " + std::to_string(static_cast<std::uint32_t>(type)));
    }
}

bool operator==(const ftrace::v0::payload::SchedSwitch& sw1, const ftrace::v0::payload::SchedSwitch& sw2) {
    return (sw1.timestamp == sw2.timestamp) &&
        (sw1.in_tid == sw2.in_tid) &&
        (sw1.out_tid == sw2.out_tid) &&
        (sw1.syscall_nr == sw2.syscall_nr) &&
        (sw1.cpu == sw2.cpu) &&
        (sw1.voluntary == sw2.voluntary);
}

std::ostream& operator<<(std::ostream& os, const ftrace::v0::payload::SchedSwitch& sw) {
    os << '{';
    os << "ts: " << sw.timestamp << ", ";
    os << "in_tid: " << sw.in_tid << ", ";
    os << "out_tid: " << sw.out_tid << ", ";
    os << "syscall_nr: " << sw.syscall_nr << ", ";
    os << "cpu: " << sw.cpu << ", ";
    os << "voluntary: " << sw.voluntary;
    os << '}';
    return os;
}

bool operator==(const ftrace::v0::payload::SchedWakeup& w1, const ftrace::v0::payload::SchedWakeup& w2) {
    return (w1.timestamp == w2.timestamp) &&
        (w1.target_cpu == w2.target_cpu) &&
        (w1.tid == w2.tid) &&
        (w1.cpu == w2.cpu);
}

std::ostream& operator<<(std::ostream& os, const ftrace::v0::payload::SchedWakeup& w) {
    os << '{';
    os << "ts: " << w.timestamp << ", ";
    os << "target_cpu: " << w.target_cpu << ", ";
    os << "in_tid: " << w.tid << ", ";
    os << "cpu: " << w.cpu;
    os << '}';
    return os;
}

template <typename PMsg, typename Hdr, std::size_t MaxPktSz> void
ftrace::read_events(int fd, std::uint8_t* buff, ssize_t read_sz, PMsg& pmsg, std::function<void(int, const Hdr&, const std::uint8_t*, std::size_t sz)> pkt_hdlr) {
    const auto hdr_sz = sizeof(Hdr);
    assert(sizeof(pmsg.buff) == MaxPktSz);
    
    while (read_sz > 0) {
        if (pmsg.len == 0) {
            if (read_sz >= hdr_sz) {
                auto h = reinterpret_cast<Hdr*>(buff);
                auto pkt_len = h->len;
                if (read_sz >= pkt_len) {
                    pkt_hdlr(fd, *h, buff, pkt_len - hdr_sz);
                    buff += pkt_len;
                    read_sz -= pkt_len;
                } else {
                    memcpy(pmsg.buff, buff, read_sz);
                    pmsg.len = read_sz;
                    read_sz = 0;
                }
            } else {
                memcpy(pmsg.buff, buff, read_sz);
                pmsg.len = read_sz;
                read_sz = 0;
            }
        } else {
            if (pmsg.len >= hdr_sz) {
                auto h = reinterpret_cast<Hdr*>(pmsg.buff);
                auto pkt_len = h->len;
                auto payload_sz = pkt_len - hdr_sz;
                auto missing_payload_bytes = payload_sz - pmsg.len;
                assert(missing_payload_bytes > 0);
                if (read_sz >= missing_payload_bytes) {
                    memcpy(pmsg.buff + pmsg.len, buff, missing_payload_bytes);
                    pkt_hdlr(fd, *h, pmsg.buff + hdr_sz, payload_sz);
                    buff += missing_payload_bytes;
                    read_sz -= missing_payload_bytes;
                    pmsg.len = 0;
                } else {
                    memcpy(pmsg.buff + pmsg.len, buff, read_sz);
                    read_sz = 0;
                    pmsg.len += read_sz;
                }
            } else {
                auto missing_header_bytes = (hdr_sz - pmsg.len);
                assert(missing_header_bytes > 0);
                if (read_sz >= missing_header_bytes) {
                    memcpy(pmsg.buff + pmsg.len, buff, missing_header_bytes);
                    buff += missing_header_bytes;
                    read_sz -= missing_header_bytes;
                    auto h = reinterpret_cast<Hdr*>(pmsg.buff);
                    auto pkt_len = h->len;
                    if (read_sz >= pkt_len) {
                        auto payload_sz = pkt_len - hdr_sz;
                        pkt_hdlr(fd, *h, buff, payload_sz);
                        buff += payload_sz;
                        read_sz -= payload_sz;
                        pmsg.len = 0;
                    } else {
                        memcpy(pmsg.buff + pmsg.len, buff, read_sz);
                        pmsg.len += read_sz;
                        read_sz = 0;
                    }
                } else {
                    memcpy(pmsg.buff + pmsg.len, buff, read_sz);
                    pmsg.len += read_sz;
                    read_sz = 0;
                }
            }
        }
    }
}

template void
ftrace::read_events<ftrace::PartMsgBuff, ftrace::v_curr::Header, ftrace::v_curr::max_pkt_sz>(int fd, std::uint8_t* buff, ssize_t rd_sz, ftrace::PartMsgBuff& pmsg,
                                                                                             std::function<void(int, const ftrace::v_curr::Header&, const std::uint8_t*, std::size_t sz)> pkt_hdlr);
