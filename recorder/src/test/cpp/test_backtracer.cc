#include <thread>
#include <vector>
#include <iostream>
#include <cstdint>
#include <chrono>
#include "fixtures.hh"
#include "test.hh"
#include <mapping_parser.hh>
#include <site_resolver.hh>
#include "test_helpers.hh"
#include <set>
#include <linux/limits.h>
#include <dlfcn.h>
#include <sys/types.h>
#include <sys/stat.h>
#include <unistd.h>

#include <fstream>
#include <sys/mman.h>

#ifdef HAS_VALGRIND
#include <valgrind/valgrind.h>
#endif

#include "mapping_helper.hh"

TEST(Backtracer__should_not_fill_anything_when_native_backtracing_is_disabled) {
    TestEnv _;
    auto maps_file = MRegion::file();
    Backtracer btracer(maps_file, false);
    
    const std::uint32_t buff_sz = 100;
    NativeFrame buff[buff_sz];
    buff[0] = 1;
    bool bt_unreadable = true;
    CHECK_EQUAL(0, btracer.fill_in(buff, buff_sz, bt_unreadable));
    CHECK_EQUAL(false, bt_unreadable);
    CHECK_EQUAL(1, buff[0]); //its unchanged
}
