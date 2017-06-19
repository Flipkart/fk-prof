#include <thread>
#include <vector>
#include <iostream>
#include <cstdint>
#include <chrono>
#include "fixtures.hh"
#include "test.hh"
#include <mapping_parser.hh>
#include "test_helpers.hh"
#include <set>
#include <linux/limits.h>
#include <dlfcn.h>
#include <sys/types.h>
#include <sys/stat.h>
#include <unistd.h>
#include <sys/mman.h>
#include <fstream>

#ifdef HAS_VALGRIND
#include <valgrind/valgrind.h>
#endif

#include "mapping_helper.hh"

static std::unique_ptr<Backtracer> b_tracer {nullptr};

__attribute__ ((noinline)) int foo(NativeFrame* buff, std::uint32_t sz) {
    bool bt_unreadable;
    return b_tracer->fill_in(buff, sz, bt_unreadable);
}

__attribute__ ((noinline)) int caller_of_foo(NativeFrame* buff, std::uint32_t sz) {
    return foo(buff, sz);
}

TEST(SiteResolver__should_resolve_backtrace) {
    TestEnv _;
    auto map_file = MRegion::file();
    b_tracer.reset(new Backtracer(map_file));
    const std::uint32_t buff_sz = 100;
    NativeFrame buff[buff_sz];
    std::uint32_t bt_len;
    some_lambda_caller([&]() {
            bt_len = caller_of_foo(buff, buff_sz);
        });
    CHECK(bt_len > 4);

    std::string path = my_executable();

    SiteResolver::SymInfo s_info;
    std::string fn_name;
    std::string file_name;
    SiteResolver::Addr pc_offset;
    s_info.site_for(buff[0], file_name, fn_name, pc_offset);
    CHECK_EQUAL("foo(unsigned long*, unsigned int)", fn_name);
    CHECK_EQUAL(path, file_name);
    s_info.site_for(buff[1], file_name, fn_name, pc_offset);
    CHECK_EQUAL("caller_of_foo(unsigned long*, unsigned int)", fn_name);
    CHECK_EQUAL(path, file_name);

    std::map<std::string, std::string> fn_files;
    for (auto i = bt_len; i > 0; i--) {
        s_info.site_for(buff[i - 1], file_name, fn_name, pc_offset);
        fn_files[fn_name] = file_name;
    }

    auto it = fn_files.find("some_lambda_caller(std::function<void ()>)");
    CHECK(it != std::end(fn_files));//this symbol comes from a shared-lib (aim is to ensure it works well with relocatable symbols)

    CHECK_EQUAL(my_test_helper_lib(), abs_path(it->second));
}

TEST(SiteResolver__should_call_out_unknown_mapping) {
    TestEnv _;
    auto map_file = MRegion::file();
    b_tracer.reset(new Backtracer(map_file));
    const std::uint32_t buff_sz = 100;
    NativeFrame buff[buff_sz];
    std::uint32_t bt_len;
    some_lambda_caller([&]() {
            bt_len = caller_of_foo(buff, buff_sz);
        });
    CHECK(bt_len > 4);

    long pg_sz;
    void* mmap_region;
    auto path = my_executable();
    map_one_anon_executable_page_between_executable_and_testlib(&mmap_region, pg_sz, path);

    SiteResolver::SymInfo s_info;
    std::string fn_name;
    std::string file_name;
    SiteResolver::Addr pc_offset;

    s_info.site_for(buff[0], file_name, fn_name, pc_offset);
    CHECK_EQUAL("foo(unsigned long*, unsigned int)", fn_name);
    CHECK_EQUAL(path, file_name);

    buff[1] = reinterpret_cast<SiteResolver::Addr>(mmap_region) + (pg_sz / 2); //fake the return addr

    s_info.site_for(buff[1], file_name, fn_name, pc_offset);
    CHECK_EQUAL("*unknown symbol*", fn_name);
    CHECK_EQUAL("*anonymous mapping*", file_name);

    buff[2] = reinterpret_cast<SiteResolver::Addr>(mmap_region) + pg_sz + (pg_sz / 2); //addr outside any mapped region

    s_info.site_for(buff[2], file_name, fn_name, pc_offset);
    CHECK_EQUAL("*unknown symbol*", fn_name);
    CHECK_EQUAL("*unknown mapping*", file_name);

    if (mmap_region != nullptr) {
        CHECK_EQUAL(0, munmap(mmap_region, pg_sz));
    }
}

TEST(SiteResolver__should_handle_unknown_mapping_at__head__and__tail__gracefully) {
    TestEnv _;
    auto map_file = MRegion::file();
    b_tracer.reset(new Backtracer(map_file));

    SiteResolver::Addr lowest_exe = std::numeric_limits<SiteResolver::Addr>::max(), highest_exe = std::numeric_limits<SiteResolver::Addr>::min();
    iterate_mapping([&](SiteResolver::Addr start, SiteResolver::Addr end, const MRegion::Event& e) {
            if (e.perms.find('x') == std::string::npos) return;
            if (lowest_exe > start) lowest_exe = start;
            if (highest_exe < end) highest_exe = end;
        });

    SiteResolver::SymInfo s_info;
    std::string fn_name;
    std::string file_name;
    SiteResolver::Addr pc_offset;

    CHECK(lowest_exe > 0);
    CHECK(highest_exe < std::numeric_limits<SiteResolver::Addr>::max());//otherwise this test won't be able to test what it wants anyway

    s_info.site_for(lowest_exe - 1, file_name, fn_name, pc_offset);
    CHECK_EQUAL("*unknown symbol*", fn_name);
    CHECK_EQUAL("*unknown mapping*", file_name);

    s_info.site_for(highest_exe + 1, file_name, fn_name, pc_offset);
    CHECK_EQUAL("*unknown symbol*", fn_name);
    CHECK_EQUAL("*unknown mapping*", file_name);
}

TEST(SiteResolver__should_handle_vdso_and_vsyscall_addresses) {
    TestEnv _;
    auto map_file = MRegion::file();
    b_tracer.reset(new Backtracer(map_file));

    std::pair<SiteResolver::Addr, SiteResolver::Addr> vdso{0, 0}, vsyscall{0, 0};

    iterate_mapping([&](SiteResolver::Addr start, SiteResolver::Addr end, const MRegion::Event& e) {
            if (e.perms.find('x') == std::string::npos) return;
            if (e.path == "[vsyscall]") vsyscall = {start, end};
            if (e.path == "[vdso]") vdso = {start, end};
        });

    if (! RUNNING_ON_VALGRIND) { //valgrind does it own thing for vDSO, so don't worry about this
        CHECK(vdso.first + 1< vdso.second);
    }
    CHECK(vsyscall.first + 1 < vsyscall.second);//something is wrong with the way we identify the 2 special mappings if this goes wrong

    SiteResolver::SymInfo s_info;
    std::string fn_name;
    std::string file_name;
    SiteResolver::Addr pc_offset;

    s_info.site_for(vsyscall.first , file_name, fn_name, pc_offset);
    CHECK_EQUAL("*unknown symbol*", fn_name);
    CHECK_EQUAL("[vsyscall]", file_name);
    CHECK_EQUAL(0, pc_offset);

    s_info.site_for(vsyscall.first + 1, file_name, fn_name, pc_offset);
    CHECK_EQUAL("*unknown symbol*", fn_name);
    CHECK_EQUAL("[vsyscall]", file_name);
    CHECK_EQUAL(1, pc_offset);

    if (! RUNNING_ON_VALGRIND) { //valgrind does it own thing for vDSO, so don't worry about this
        s_info.site_for(vdso.first, file_name, fn_name, pc_offset);
        CHECK_EQUAL("*unknown symbol*", fn_name);
        CHECK_EQUAL("[vdso]", file_name);
        CHECK_EQUAL(0, pc_offset);

        s_info.site_for(vdso.first + 1, file_name, fn_name, pc_offset);
        CHECK_EQUAL("*unknown symbol*", fn_name);
        CHECK_EQUAL("[vdso]", file_name);
        CHECK_EQUAL(1, pc_offset);
    }
}


TEST(SiteResolver__should_handle_mapping_changes_between___mmap_parse___and___dl_iterate_phdr___gracefully) {
    TestEnv _;
    auto map_file = MRegion::file();
    b_tracer.reset(new Backtracer(map_file));
    //scenarios
    // - map [x,z] > parse > unmap [x,z] > dl_itr > test
    //   * test x => unknown sym but correct mapping
    //   * test z => unknown sym but correct mapping
    //   * DON'T worry about z + 1, because it has already been tested
    // - parse > map [x,z] > dl_itr > test
    //   * test x => correct sym and mapping
    //   * test z => correct sym and mapping
    //   * test z + 1 => last sym but warning in mapping

    auto pg_sz = sysconf(_SC_PAGESIZE);
    auto pg_count = 2;

    std::unique_ptr<char, std::function<void(char*)>> dir{get_current_dir_name(), free};

    auto mt1 = "mt1.tmp";
    {
        std::fstream map_target_1(mt1, std::ios_base::out | std::ios_base::trunc);
        for (int i = 0; i <= (pg_sz * pg_count); i++) {
            map_target_1 << '0';
        }
    }
    auto mt1_path = abs_path(std::string(dir.get()) + "/" + mt1);

    auto mt1_fd = open(mt1_path.c_str(), O_RDONLY);
    CHECK(fchmod(mt1_fd, S_IRUSR | S_IXUSR) == 0);

    auto mt1_sz = pg_sz * pg_count;

    auto mt1_map = mmap(0, mt1_sz, PROT_EXEC, MAP_PRIVATE, mt1_fd, 0);
    auto mt1_mapped = (mt1_map != MAP_FAILED);
    CHECK(mt1_mapped);

    SiteResolver::SymInfo s_info_1{[&]() {
            if (mt1_mapped) munmap(mt1_map, mt1_sz);
        }};

    void* handle = nullptr;
    auto path = my_executable();
    auto parent_path = path.substr(0, path.rfind("/"));
    auto lib_path = parent_path + "/libsyminfo_test_ext.so";
    struct stat lib_stat;
    CHECK(stat(lib_path.c_str(), &lib_stat) == 0);
    SiteResolver::SymInfo s_info_2{[&]() {
            handle = dlopen(lib_path.c_str(), RTLD_NOW);
        }};
    CHECK(handle != nullptr);

    void* sym_ptr = dlsym(handle, "foo_bar_baz");
    CHECK(sym_ptr != nullptr);
    auto sym = reinterpret_cast<SiteResolver::Addr>(sym_ptr);

    CurrMappings mapping_with_m2;
    SiteResolver::Addr post_m2_start = std::numeric_limits<SiteResolver::Addr>::max(), post_m2_end;
    iterate_mapping([&](SiteResolver::Addr start, SiteResolver::Addr end, const MRegion::Event& e) {
            if (e.perms.find('x') == std::string::npos) return;
            mapping_with_m2.push_back({start, end});
            if ((start > sym) && start < post_m2_start) {
                post_m2_start = start;
                post_m2_end = end;
            }
        });
    CHECK(post_m2_start > sym);
    CHECK(post_m2_end > post_m2_start);

    MappableRanges mappable;
    find_mappable_ranges_between(mapping_with_m2, sym, (post_m2_start - 1), mappable);
    CHECK(mappable.size() > 0);

    dlclose(handle); //This can happen in real world, where an app maps something, we start profiling, but it unmaps it later (while profile is still being recorded).
    // In such a scenario, it is possible for the app to then map something else to the same address and end up with wrong symbols being recorded.
    // This is detectable and preventable, but it comes at a performance overhead of checking mapping when we resolve symbols.
    // A faster impl that reduces the window of error (down to a second or lower, by polling /proc/<pid>/maps at regular interval) and such an impl can be written,
    //       but it is unlikely to be worth it (not many applications do this kind of thing with libs, so why check for it?).
    // So keeping it simple (racy, error-prone, you name it!) for now, because practical value in making it perfect just doesn't seem worth wrt perf hit and dev-time.
    // Its not unstable, but it is functionally incorrect (it will show you were in function foo in libfoo, when you were actually in bar in libbar,
    //       because you unmapped libfoo in favor of libbar and mapped it at the same virtual address as libfoo.
    // -jj


    std::string fn_name;
    std::string file_name;
    SiteResolver::Addr pc_offset;

    s_info_1.site_for(reinterpret_cast<SiteResolver::Addr>(mt1_map), file_name, fn_name, pc_offset);
    CHECK_EQUAL("*unknown symbol*", fn_name);
    CHECK_EQUAL(mt1_path, file_name);
    CHECK_EQUAL(0, pc_offset);

    s_info_1.site_for(reinterpret_cast<SiteResolver::Addr>(mt1_map) + pg_sz / 2, file_name, fn_name, pc_offset);
    CHECK_EQUAL("*unknown symbol*", fn_name);
    CHECK_EQUAL(mt1_path, file_name);
    CHECK_EQUAL(pg_sz / 2, pc_offset);

    s_info_1.site_for(reinterpret_cast<SiteResolver::Addr>(mt1_map) + pg_sz * pg_count, file_name, fn_name, pc_offset);
    CHECK_EQUAL("*unknown symbol*", fn_name);
    CHECK_EQUAL(mt1_path, file_name);
    CHECK_EQUAL(pg_sz * pg_count, pc_offset);

    s_info_2.site_for(sym, file_name, fn_name, pc_offset);
    CHECK_EQUAL("foo_bar_baz", fn_name);
    CHECK_EQUAL(lib_path, file_name);
    CHECK_EQUAL(0, pc_offset);

    s_info_2.site_for(sym + 1, file_name, fn_name, pc_offset);
    CHECK_EQUAL("foo_bar_baz", fn_name);
    CHECK_EQUAL(lib_path, file_name);
    CHECK_EQUAL(1, pc_offset);

    s_info_2.site_for(mappable.begin()->second - 1, file_name, fn_name, pc_offset);
    CHECK(fn_name.find("[last symbol, end unknown]") != std::string::npos);
    CHECK_EQUAL(lib_path, file_name);
}

#define BT_SZ 64

#define ASSERT_GOT_LIMITED_LENGTH_BACKTRACE(bt, bt_len)                 \
    {                                                                   \
        CHECK_EQUAL(4, bt_len);                                         \
                                                                        \
        SiteResolver::SymInfo s_info;                                   \
        std::string fn_name;                                            \
        std::string file_name;                                          \
        SiteResolver::Addr pc_offset;                                   \
        auto path = my_test_helper_lib();                               \
                                                                        \
        s_info.site_for(bt[0] , file_name, fn_name, pc_offset);         \
        CHECK_EQUAL("capture_bt(Backtracer&, unsigned long*, unsigned long, bool&)", fn_name); \
        CHECK_EQUAL(path, abs_path(file_name));                         \
                                                                        \
        s_info.site_for(bt[1] , file_name, fn_name, pc_offset);         \
        CHECK_EQUAL("bt_test_foo(Backtracer&, unsigned long*, unsigned long, bool&)", fn_name); \
        CHECK_EQUAL(path, abs_path(file_name));                         \
                                                                        \
        s_info.site_for(bt[2] , file_name, fn_name, pc_offset);         \
        CHECK_EQUAL("bt_test_bar(Backtracer&, unsigned long*, unsigned long, bool&, unsigned long)", fn_name); \
        CHECK_EQUAL(path, abs_path(file_name));                         \
                                                                        \
        s_info.site_for(bt[3] , file_name, fn_name, pc_offset);         \
        CHECK_EQUAL("bt_test_baz(Backtracer&, unsigned long*, unsigned long, bool&, unsigned long)", fn_name); \
        CHECK_EQUAL(path, abs_path(file_name));                         \
    }
//observe there is no bt_test_quux above, hence limited length.

TEST(Backtracer__should_not_dereference__when_rbp_is_entirely_an_unmapped_quad_word) {
    TestEnv _;
    auto maps_file = MRegion::file();
    b_tracer.reset(new Backtracer(maps_file));
    std::uint32_t bt_len = 0;
    NativeFrame bt[BT_SZ];
    bool bt_unreadable;

    std::uint64_t start, end;
    find_atleast_16_bytes_wide_unmapped_range(start, end);

    bt_len = bt_test_quux(*b_tracer, bt, BT_SZ, bt_unreadable, start);
    
    ASSERT_GOT_LIMITED_LENGTH_BACKTRACE(bt, bt_len);
}

TEST(Backtracer__should_not_dereference__when_return_address_is_entirely_not_mapped) {
    TestEnv _;
    auto maps_file = MRegion::file();
    b_tracer.reset(new Backtracer(maps_file));
    std::uint32_t bt_len = 0;
    NativeFrame bt[BT_SZ];
    bool bt_unreadable;
    
    std::uint64_t start, end;
    find_atleast_16_bytes_wide_unmapped_range(start, end);

    bt_len = bt_test_quux(*b_tracer, bt, BT_SZ, bt_unreadable, start - 8);
    
    ASSERT_GOT_LIMITED_LENGTH_BACKTRACE(bt, bt_len);
}

TEST(Backtracer__should_not_dereference__when_rbp_is_a_partly_unmapped_quad_word) {
    TestEnv _;
    auto maps_file = MRegion::file();
    b_tracer.reset(new Backtracer(maps_file));
    std::uint32_t bt_len = 0;
    NativeFrame bt[BT_SZ];
    bool bt_unreadable;
    
    std::uint64_t start, end;
    find_atleast_16_bytes_wide_unmapped_range(start, end);

    bt_len = bt_test_quux(*b_tracer, bt, BT_SZ, bt_unreadable, start - 7);
    
    ASSERT_GOT_LIMITED_LENGTH_BACKTRACE(bt, bt_len);
}

TEST(Backtracer__should_not_dereference__when_return_address_is_partly_unmapped) {
    TestEnv _;
    auto maps_file = MRegion::file();
    b_tracer.reset(new Backtracer(maps_file));
    std::uint32_t bt_len = 0;
    NativeFrame bt[BT_SZ];
    bool bt_unreadable;
    
    std::uint64_t start, end;
    find_atleast_16_bytes_wide_unmapped_range(start, end);

    bt_len = bt_test_quux(*b_tracer, bt, BT_SZ, bt_unreadable, start - 15);
    
    ASSERT_GOT_LIMITED_LENGTH_BACKTRACE(bt, bt_len);
}
