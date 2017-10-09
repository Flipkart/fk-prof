#include <thread>
#include <regex>
#include <iostream>
#include "test.hh"
#include <util.hh>
#include <boost/asio.hpp>
#include <ftrace/proto.hh>
#include <fstream>

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

TEST(ftrace_client) {
    TestEnv _;
    logger->info("Starting ftrace client test");
    std::ifstream ctrlfile ("/tmp/fclient_test");
    if (!ctrlfile.is_open())
    {
        throw "Error opening ftrace client test config file: /tmp/fclient_test";
    }
    int tid;
    ctrlfile >> tid;

    using boost::asio::local::stream_protocol;
    try {
        boost::asio::io_service io_service;
        stream_protocol::socket s(io_service);
        s.connect(stream_protocol::endpoint("/var/tmp/fkp-tracer.sock"));

        ftrace::v_curr::Header h = { .v = ftrace::v_curr::VERSION, .type = ftrace::v_curr::add_tid };
        ftrace::v_curr::payload::AddTid p = tid;
        logger->info("Add tid");
        h.len = sizeof(h) + sizeof(p);

        std::vector<boost::asio::const_buffer> buffers;
        buffers.push_back(boost::asio::buffer(&h, sizeof(h)));
        buffers.push_back(boost::asio::buffer(&p, sizeof(p)));
        boost::asio::write(s, buffers);

        while(true) {
            char reply[4096];
            size_t reply_length = boost::asio::read(s, boost::asio::buffer(reply, h.len));
            if(reply_length > 0) {
                logger->info("Reply: {}", reply);
            } else if (reply_length == 0) {
                logger->info("closed connection probably");
            } else {
                logger->info("not sure what to do now");
            }
        }

    } catch (std::exception& e) {
        std::cerr << "Exception: " << e.what() << "\n";
    }
}