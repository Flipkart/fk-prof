#include "stacktraces.hh"
#include "mapping_parser.hh"
#include <fstream>

Backtracer::Backtracer(const std::string& path, bool _enabled) : enabled(_enabled) {
    MRegion::Parser parser([&](const MRegion::Event& e) {
            if (e.perms.find('w') == std::string::npos) return true;

            std::size_t pos;
            NativeFrame start = std::stoull(e.range.start, &pos, 16);
            assert(pos == e.range.start.length());
            NativeFrame end = std::stoull(e.range.end, &pos, 16);
            assert(pos == e.range.end.length());
            
            mapped.emplace_back(start, end);

            return true;
        });
    std::fstream f_maps(path, std::ios::in);
    parser.feed(f_maps);
}

static bool compare(const Backtracer::Entry& one, const Backtracer::Entry& other) {
    return one.second < other.second;
}

std::uint32_t Backtracer::fill_in(NativeFrame* buff, std::uint32_t capacity, bool& bt_unreadable) {//TODO: write me 3 tests { (capacity > stack), (stack > capacity) and (stack == capacity) }
    bt_unreadable = false;
    if (! enabled) return 0;
    std::uint64_t rbp, rpc;
    asm("movq %%rbp, %%rax;"
        "movq %%rax, %0;"
        : "=r"(rbp)
        :
        : "rax");

    //not adding current PC, because we are anyway not interested in showing ourselves on the backtrace
    std::uint32_t i = 0;
    Entry key;
    auto start = std::begin(mapped);
    auto end = std::end(mapped);
    while ((capacity - i) > 0) {
        key.second = rbp;
        auto it = std::lower_bound(start, end, key, compare);
        if (it == end || it->first > rbp || it->second < (rbp + 16)) {
            bt_unreadable = true;
            break;
        }
        rpc = *reinterpret_cast<std::uint64_t*>(rbp + 8);
        buff[i] = rpc;
        rbp = *reinterpret_cast<std::uint64_t*>(rbp);
        if (rbp == 0) break;
        i++;
    }
    return i;
}
