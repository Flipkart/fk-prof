#include "test.hh"
#include <cstring>
#include "TestReporterStdout.h"

using namespace UnitTest;

int main(int argc, char** argv ) {
    TestReporterStdout reporter;
    TestRunner runner(reporter);
    auto ret = runner.RunTestsIf(Test::GetTestList(), NULL, [argc, argv](Test* t) {
            if (argc == 1) return true;
            return strstr(t->m_details.testName, argv[1]) != nullptr;
        }, 0);
    logger.reset();
    return ret;
}
