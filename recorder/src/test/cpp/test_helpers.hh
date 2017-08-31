#ifndef TEST_HELPERS_H
#define TEST_HELPERS_H

#include <string>
#include <stacktraces.hh>

__attribute__ ((noinline)) void some_lambda_caller(std::function<void()> fn);

void bt_pusher(); //defined in serializer test

extern "C" __attribute__ ((noinline)) void fn_c_foo();

namespace Bar {
    __attribute__ ((noinline)) void fn_bar(int bt_capture_depth);
}

__attribute__ ((noinline)) void fn_baz(int bt_capture_depth);

__attribute__ ((noinline)) void fn_quux(int r, int bt_capture_depth);

__attribute__ ((noinline)) void fn_corge(int p, int q, int bt_capture_depth);

std::string my_executable();

#define LIB_TEST_UTIL "/libtestutil.so"

std::string my_test_helper_lib();

std::string abs_path(const std::string& path);

#endif /* TEST_HELPERS_H */
