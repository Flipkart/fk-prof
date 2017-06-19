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

__attribute__ ((noinline)) std::uint32_t capture_bt(Backtracer& b_tracer, NativeFrame* bt, std::size_t capacity, bool& bt_unreadable);

__attribute__ ((noinline)) std::uint32_t bt_test_foo(Backtracer& b_tracer, NativeFrame* bt, std::size_t capacity, bool& bt_unreadable);

__attribute__ ((noinline)) std::uint32_t bt_test_bar(Backtracer& b_tracer, NativeFrame* bt, std::size_t capacity, bool& bt_unreadable, std::uint64_t unmapped_address);

__attribute__ ((noinline)) std::uint32_t bt_test_baz(Backtracer& b_tracer, NativeFrame* bt, std::size_t capacity, bool& bt_unreadable, std::uint64_t unmapped_address);

__attribute__ ((noinline)) std::uint32_t bt_test_quux(Backtracer& b_tracer, NativeFrame* bt, std::size_t capacity, bool& bt_unreadable, std::uint64_t unmapped_address);


#endif /* TEST_HELPERS_H */
