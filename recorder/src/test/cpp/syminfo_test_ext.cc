#include <atomic>

std::atomic<int> x {10};

extern std::atomic<int> x;

extern "C" int foo_bar_baz() {
    x++;
    return x.load();
}
