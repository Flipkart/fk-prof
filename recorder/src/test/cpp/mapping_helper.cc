#include "mapping_helper.hh"
#include "test.hh"
#include "test_helpers.hh"
#include <fstream>
#include <sys/mman.h>
#include <unistd.h>
#include <sys/types.h>

void find_mappable_ranges_between(const CurrMappings& curr_mappings, SiteResolver::Addr desired_start, SiteResolver::Addr desired_end, MappableRanges& mappable) {
    mappable[desired_start] = desired_end;
    for (auto& cm : curr_mappings) {
        //std::cout << "C: [" << cm.first << ", " << cm.second  << "]\n";
        auto it = mappable.lower_bound(cm.first);
        if (it == std::begin(mappable)) {
            if (mappable.lower_bound(cm.second) == it) continue;
        }
        it--;
        while (it != std::end(mappable) &&
               it->first < desired_end) {
            auto start = it->first;
            auto end = it->second;
            //std::cout << "x [" << start << ", " << end << "]\n";
            if (start <= cm.second &&
                cm.first <= end) { //overlap
                it = mappable.erase(it);
                //std::cout << "- [" << start << ", " << end << "]\n";
                if (start < cm.first) {
                    mappable[start] = cm.first - 1;
                    //std::cout << "+ [" << start << ", " << cm.first - 1 << "]\n";
                }
                if (end > cm.second) {
                    mappable[cm.second + 1] = end;
                    //std::cout << "+ [" << cm.second + 1 << ", " << end << "]\n";
                }
            } else {
                it++;
            }
        }
    }
}

TEST(MappingHelper__mappable_range_finder) {
    CurrMappings cms;
    cms.emplace_back(100, 200);
    cms.emplace_back(300, 400);
    cms.emplace_back(500, 600);
    MappableRanges mappable;
    find_mappable_ranges_between(cms, 150, 550, mappable);

    CHECK_EQUAL(2, mappable.size());
    CHECK_EQUAL(299, mappable[201]);
    CHECK_EQUAL(499, mappable[401]);

    mappable.clear();
    find_mappable_ranges_between(cms, 100, 550, mappable);

    CHECK_EQUAL(2, mappable.size());
    CHECK_EQUAL(299, mappable[201]);
    CHECK_EQUAL(499, mappable[401]);

    mappable.clear();
    find_mappable_ranges_between(cms, 150, 500, mappable);

    CHECK_EQUAL(2, mappable.size());
    CHECK_EQUAL(299, mappable[201]);
    CHECK_EQUAL(499, mappable[401]);

    mappable.clear();
    find_mappable_ranges_between(cms, 150, 600, mappable);

    CHECK_EQUAL(2, mappable.size());
    CHECK_EQUAL(299, mappable[201]);
    CHECK_EQUAL(499, mappable[401]);

    mappable.clear();
    find_mappable_ranges_between(cms, 100, 600, mappable);

    CHECK_EQUAL(2, mappable.size());
    CHECK_EQUAL(299, mappable[201]);
    CHECK_EQUAL(499, mappable[401]);

    mappable.clear();
    find_mappable_ranges_between(cms, 99, 601, mappable);

    CHECK_EQUAL(4, mappable.size());
    CHECK_EQUAL(99, mappable[99]);
    CHECK_EQUAL(299, mappable[201]);
    CHECK_EQUAL(499, mappable[401]);
    CHECK_EQUAL(601, mappable[601]);

    mappable.clear();
    cms.emplace_back(201, 299);
    find_mappable_ranges_between(cms, 150, 550, mappable);

    CHECK_EQUAL(1, mappable.size());
    CHECK_EQUAL(499, mappable[401]);
}

void iterate_mapping(std::function<void(SiteResolver::Addr start, SiteResolver::Addr end, const MRegion::Event& e)> cb) {
    MRegion::Parser parser([&](const MRegion::Event& e) {
            std::size_t pos;
            std::uint64_t start = std::stoull(e.range.start, &pos, 16);
            assert(pos == e.range.start.length());
            std::uint64_t end = std::stoull(e.range.end, &pos, 16);
            assert(pos == e.range.end.length());

            cb(start, end, e);

            return true;
        });
    auto pid = getpid();
    std::fstream f_maps("/proc/" + std::to_string(pid) + "/maps", std::ios::in);
    parser.feed(f_maps);
}

void map_one_anon_executable_page_between_executable_and_testlib(void **mmap_region, long& pg_sz, std::string path) {
    auto dir = path.substr(0, path.rfind("/"));
    std::pair<SiteResolver::Addr, SiteResolver::Addr> test_bin, test_util_lib;
    CurrMappings curr_mappings;
    iterate_mapping([&](SiteResolver::Addr start, SiteResolver::Addr end, const MRegion::Event& e) {
            curr_mappings.push_back({start, end});

            if (e.perms.find('x') != std::string::npos) {
                if (e.path == path) test_bin = {start, end};
                if (e.path == my_test_helper_lib()) test_util_lib = {start, end};
            }
        });

    SiteResolver::Addr desired_anon_exec_map_lowerb, desired_anon_exec_map_upperb;

    if (test_bin.first < test_util_lib.first) {
        desired_anon_exec_map_lowerb = test_bin.second + 1;
        desired_anon_exec_map_upperb = test_util_lib.first - 1;
    } else {
        desired_anon_exec_map_lowerb = test_util_lib.second + 1;
        desired_anon_exec_map_upperb = test_bin.first - 1;
    }

    MappableRanges mappable;
    find_mappable_ranges_between(curr_mappings, desired_anon_exec_map_lowerb, desired_anon_exec_map_upperb, mappable);

    pg_sz = sysconf(_SC_PAGESIZE);

    CHECK(mappable.size() > 0); //otherwise this test can't proceed (unlucky shot, please try again)
    auto buff_mapped = false;
    *mmap_region = nullptr;
    for (const auto& m : mappable) {
        if ((m.second - m.first) >= (3 * pg_sz)) {
            *mmap_region = reinterpret_cast<void*>(m.first - (m.first % pg_sz) + pg_sz);
            *mmap_region = mmap(*mmap_region, pg_sz, PROT_EXEC, MAP_PRIVATE | MAP_ANONYMOUS | MAP_FIXED, 0, 0);
            buff_mapped = (*mmap_region != MAP_FAILED);
            break;
        }
    }
    CHECK(buff_mapped); //otherwise this test can't proceed (unlucky shot, please try again)
}

void find_atleast_16_bytes_wide_unmapped_range(std::uint64_t& start, std::uint64_t& end) {
    CurrMappings curr_mappings;
    iterate_mapping([&curr_mappings](SiteResolver::Addr start, SiteResolver::Addr end, const MRegion::Event& e) {
            if (e.perms.find('w') == std::string::npos) return;
            curr_mappings.emplace_back(start, end);
        });

    MappableRanges mappable_ranges;

    find_mappable_ranges_between(curr_mappings, 0, std::numeric_limits<SiteResolver::Addr>::max(), mappable_ranges);

    CHECK(mappable_ranges.size() > 1);
    auto it = mappable_ranges.begin();
    auto prev_it = it;
    it++;
    while (prev_it != std::end(mappable_ranges) && (((it->second - it->first) <= 16) || ((prev_it->second - it->first - 2) >= 16))) {
        prev_it = it++;
    }
    assert(it != std::end(mappable_ranges));

    start = it->first;
    end = it->second;
}
