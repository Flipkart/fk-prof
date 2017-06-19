#include "test_helpers.hh"
#include <unordered_map>
#include <fstream>
#include <libgen.h>
#include <sys/types.h>
#include <sys/stat.h>
#include <unistd.h>
#include <sstream>
#include <list>

__attribute__ ((noinline)) void some_lambda_caller(std::function<void()> fn) {
    fn();
}

std::atomic<int> test_sink {0};

extern "C" __attribute__ ((noinline)) void fn_c_foo() {
    test_sink++;
    bt_pusher();
    test_sink++;
}

__attribute__ ((noinline)) void Bar::fn_bar(int bt_capture_depth) {
    test_sink += 10;
    if (bt_capture_depth == 0) {
        bt_pusher();
    } else {
        fn_c_foo();
    }
    test_sink += 10;
}

__attribute__ ((noinline)) void fn_baz(int bt_capture_depth) {
    test_sink += 100;
    if (bt_capture_depth == 0) {
        bt_pusher();
    } else {
        Bar::fn_bar(--bt_capture_depth);
    }
    test_sink += 100;
}

__attribute__ ((noinline)) void fn_quux(int r, int bt_capture_depth) {
    test_sink += r;
    if (bt_capture_depth == 0) {
        bt_pusher();
    } else {
        fn_baz(--bt_capture_depth);
    }
    test_sink += r;
}

__attribute__ ((noinline)) void fn_corge(int p, int q, int bt_capture_depth) {
    test_sink += (p - q);
    if (bt_capture_depth == 0) {
        bt_pusher();
    } else {
        fn_quux(q, --bt_capture_depth);
    }
    test_sink += (p - q);
}

std::string my_executable() {
    char link_path[PATH_MAX];
    auto path_len = readlink("/proc/self/exe", link_path, PATH_MAX);
    link_path[path_len] = '\0';
    return {link_path};
}

std::string my_test_helper_lib() {
    auto exec_path = my_executable();
    auto exec_path_cstr = strdup(exec_path.c_str());
    auto dir_name = dirname(exec_path_cstr);
    std::string dir_name_str {dir_name};
    dir_name_str += LIB_TEST_UTIL;
    return dir_name_str;
}

static bool is_symlink(const std::string& path) {
    struct stat s;
    assert(lstat(path.c_str(), &s) == 0);
    return S_ISLNK(s.st_mode);
}

static void fragment_path(const std::string& path, std::list<std::string>& frags) {
    auto j = path.length();
    for (int i = j; i > 0; i--) {
        if (path[i] == '/') {
            auto f = path.substr(i + 1, j - i);
            frags.push_front(f);
            j = i - 1;
        }
    }
    frags.push_front(path.substr(1, j));
}

static void dereference_symlink(std::stringstream& path) {
    char dest[PATH_MAX];
    auto curr_path = path.str();
    auto ret = readlink(curr_path.c_str(), dest, PATH_MAX);
    assert(ret > 0);
    dest[ret] = '\0';
    path.str(dest);
    path.clear();
}

std::string abs_path(const std::string& path) {
    std::list<std::string> frags;
    fragment_path(path, frags);
    std::stringstream curr_path;
    while (! frags.empty()) {
        auto f = frags.front();
        frags.pop_front();
        curr_path << '/' << f;
        if (is_symlink(curr_path.str())) {
            dereference_symlink(curr_path);
            fragment_path(curr_path.str(), frags);
            curr_path.str("");
            curr_path.clear();
        }
    }
    return curr_path.str();
}

__attribute__ ((noinline)) std::uint32_t capture_bt(Backtracer& b_tracer, NativeFrame* bt, std::size_t capacity, bool& bt_unreadable) {
    bt_unreadable = false;
    return b_tracer.fill_in(bt, capacity, bt_unreadable);
}

__attribute__ ((noinline)) std::uint32_t bt_test_foo(Backtracer& b_tracer, NativeFrame* bt, std::size_t capacity, bool& bt_unreadable) {
    return capture_bt(b_tracer, bt, capacity, bt_unreadable);
}

__attribute__ ((noinline)) std::uint32_t bt_test_bar(Backtracer& b_tracer, NativeFrame* bt, std::size_t capacity, bool& bt_unreadable, std::uint64_t unmapped_address) {
    std::uint64_t rbp, old_rbp;
    asm("movq %%rbp, %%rax;"
        "movq %%rax, %0;"
        : "=r"(rbp)
        :
        : "rax");

    std::uint64_t* rbp_ptr = reinterpret_cast<std::uint64_t*>(rbp);

    old_rbp = *rbp_ptr;

    *rbp_ptr = unmapped_address;

    auto len = bt_test_foo(b_tracer, bt, capacity, bt_unreadable);

    *rbp_ptr = old_rbp;

    return len;
}

__attribute__ ((noinline)) std::uint32_t bt_test_baz(Backtracer& b_tracer, NativeFrame* bt, std::size_t capacity, bool& bt_unreadable, std::uint64_t unmapped_address) {
    return bt_test_bar(b_tracer, bt, capacity, bt_unreadable, unmapped_address);
}

__attribute__ ((noinline)) std::uint32_t bt_test_quux(Backtracer& b_tracer, NativeFrame* bt, std::size_t capacity, bool& bt_unreadable, std::uint64_t unmapped_address) {
    return bt_test_baz(b_tracer, bt, capacity, bt_unreadable, unmapped_address);
}
