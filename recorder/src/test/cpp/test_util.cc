#include <thread>
#include <regex>
#include <iostream>
#include "test.hh"
#include <util.hh>

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
