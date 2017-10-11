#include <thread>
#include <regex>
#include <iostream>
#include "test.hh"
#include <util.hh>
#include <boost/asio.hpp>
#include <ftrace/proto.hh>
#include <fstream>
#include "test_helpers.hh"
#include <spdlog/fmt/ostr.h>

TEST(Util_content_upto_when_line_is_found) {
    std::regex r("^int main.+");
    auto content = Util::content("src/test/cpp/main.cc", nullptr, &r);
    std::string expected = "#include \"test.hh\"\n#include <cstring>\n#include \"TestReporterStdout.h\"\n\nusing namespace UnitTest;\n\n";
    CHECK_EQUAL(expected, content);
}

TEST(Util_content_between_when_both_lines_are_found) {
    std::regex before("^int main.+");
    std::regex after("#include <cstring>");
    auto content = Util::content("src/test/cpp/main.cc", &after, &before);
    std::string expected = "#include \"TestReporterStdout.h\"\n\nusing namespace UnitTest;\n\n";
    CHECK_EQUAL(expected, content);
}

TEST(Util_content_failure_when_end_line_is_not_found) {
    std::regex r("foo bar baz quux");
    try {
        auto content = Util::content("src/test/cpp/main.cc", nullptr, &r);
        CHECK(false); // should have thrown an exception
    } catch (const std::exception& e) {
        CHECK_EQUAL("Didn't find the marker-pattern in file: src/test/cpp/main.cc", e.what());
    }
}

TEST(Util_content_after_when_line_is_found) {
    std::regex after(".+logger\\.reset.+");
    auto content = Util::content("src/test/cpp/main.cc", &after, nullptr);
    std::string expected = "    return ret;\n}\n";
    CHECK_EQUAL(expected, content);
}

TEST(Util_all_content) {
    auto content = Util::content("src/test/cpp/main.cc", nullptr, nullptr);
    std::string expected = "#include \"test.hh\"\n#include <cstring>\n#include \"TestReporterStdout.h\"\n\nusing namespace UnitTest;\n\nint main(int argc, char** argv) {\n"
        "    TestReporterStdout reporter;\n    TestRunner runner(reporter);\n    auto ret = runner.RunTestsIf(Test::GetTestList(), NULL, [argc, argv](Test* t) {\n"
        "            if (argc == 1) return true;\n            return strstr(t->m_details.testName, argv[1]) != nullptr;\n        }, 0);\n"
        "    logger.reset();\n    return ret;\n}\n";
    CHECK_EQUAL(expected, content);
}

TEST(Util_first_content_line_matching__when_nothing_matches) {
    std::regex r("foo bar baz quux");
    try {
        auto content = Util::first_content_line_matching("src/test/cpp/main.cc", r);
        CHECK(false); // should have thrown an exception
    } catch (const std::exception& e) {
        CHECK_EQUAL("No matching line found in file: src/test/cpp/main.cc", e.what());
    }
}

TEST(Util_first_content_line_matching__when_multiple_matches_exist) {
    std::regex r(".+\"Test.+");
    auto content = Util::first_content_line_matching("src/test/cpp/main.cc", r);
    auto expectd = "#include \"TestReporterStdout.h\"";
    CHECK_EQUAL(expectd, content);
}







const static ftrace::v_curr::PayloadLen hdr_sz = sizeof(ftrace::v_curr::Header);
std::uint8_t part_msg_len(0);
const static size_t max_msg_sz = (1 << (sizeof(part_msg_len) * 8)) - 1;
std::uint8_t part_msg[max_msg_sz];//partial message
const static size_t io_buff_sz = 4 * 1024;

void handle_pkt(ftrace::v_curr::PktType type, std::uint8_t* buff, size_t len) {
    ftrace::v_curr::payload::SchedSwitch* ss;
    ftrace::v_curr::payload::SchedWakeup* sw;
    ftrace::v_curr::payload::LostEvents * le;

    switch (type) {
        case ftrace::v_curr::PktType::sched_switch:
            assert(len == sizeof(ftrace::v_curr::payload::SchedSwitch));
            ss = reinterpret_cast<ftrace::v_curr::payload::SchedSwitch*>(buff);
            logger->info("sched switch {}", *ss);
            break;
        case ftrace::v_curr::PktType::sched_wakeup:
            assert(len == sizeof(ftrace::v_curr::payload::SchedWakeup));
            sw = reinterpret_cast<ftrace::v_curr::payload::SchedWakeup*>(buff);
            logger->info("sched wakeup {}", *sw);
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

    logger->trace("received={}, leftover={}", read_sz, part_msg_len);
    while (read_sz > 0) {
        logger->trace("remaining={}", read_sz + part_msg_len);
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

TEST(ftrace_client) {
    TestEnv _;
    logger->info("Starting ftrace client test");
    std::ifstream ctrlfile ("/tmp/fclient_test");
    if (!ctrlfile.is_open())
    {
        throw "Error opening ftrace client test config file: /tmp/fclient_test";
    }
    uint32_t tid;
    ctrlfile >> tid;

    using boost::asio::local::stream_protocol;
    try {
        boost::asio::io_service io_service;
        stream_protocol::socket s(io_service);
        s.connect(stream_protocol::endpoint("/var/tmp/fkp-tracer.sock"));

        ftrace::v_curr::Header h = { .v = ftrace::v_curr::VERSION, .type = ftrace::v_curr::add_tid };
        ftrace::v_curr::payload::AddTid p = tid;
        logger->info("Add tid {}", tid);
        h.len = sizeof(h) + sizeof(p);

        std::vector<boost::asio::const_buffer> buffers;
        buffers.push_back(boost::asio::buffer(&h, sizeof(h)));
        buffers.push_back(boost::asio::buffer(&p, sizeof(p)));
        boost::asio::write(s, buffers);

        std::unique_ptr<uint8_t[]> io_buff(new uint8_t[io_buff_sz]);
        while(true) {
            auto buff = io_buff.get();
            auto read_sz = boost::asio::read(s, boost::asio::buffer(buff, io_buff_sz));
            handle_server_response(buff, read_sz);
        }
    } catch (std::exception& e) {
        std::cerr << "Exception: " << e.what() << "\n";
    }
}

