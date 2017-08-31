#include "error_backtrace_different_compilation_unit_helper.hh"
#include <atomic>
#include <thread>

std::atomic<bool> x;

void Foo::quux() {
    std::this_thread::sleep_for(std::chrono::seconds(5));
    x.store(true, std::memory_order_relaxed);
    print_bt();
}

int foo(int x) {
    auto b = bar();
    return x - b;
}

int foo() {
    return foo(10);
}
