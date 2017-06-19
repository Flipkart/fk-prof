#include <vector>
#include <map>
#include <site_resolver.hh>
#include <mapping_parser.hh>

typedef std::vector<std::pair<SiteResolver::Addr, SiteResolver::Addr>> CurrMappings;
typedef std::map<SiteResolver::Addr, SiteResolver::Addr> MappableRanges;

void find_mappable_ranges_between(const CurrMappings& curr_mappings, SiteResolver::Addr desired_start, SiteResolver::Addr desired_end, MappableRanges& mappable);

void iterate_mapping(std::function<void(SiteResolver::Addr start, SiteResolver::Addr end, const MRegion::Event& e)> cb);

void map_one_anon_executable_page_between_executable_and_testlib(void **mmap_region, long& pg_sz, std::string path);

void find_atleast_16_bytes_wide_unmapped_range(std::uint64_t& start, std::uint64_t& end);
