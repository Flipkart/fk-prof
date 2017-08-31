#ifndef SITE_RESOLVER_H
#define SITE_RESOLVER_H

#include "globals.hh"
#include "stacktraces.hh"
#include <map>
#include <memory>
#include <gelf.h>
#include <functional>

namespace SiteResolver {
    class MethodListener {
    public:
        virtual int64_t recordNewMethod(const jmethodID method_id, const char *file_name, const char *class_name, const char *method_name, const char* method_signature) = 0;
        virtual ~MethodListener() { }
    };

    typedef bool (*MethodInfoResolver)(const jmethodID method_id, jvmtiEnv* jvmti, MethodListener& listener, std::int64_t& assigned_id);
    bool method_info(const jmethodID method_id, jvmtiEnv* jvmti, MethodListener& listener, std::int64_t& assigned_id);

    typedef jint (*LineNoResolver)(const jint bci, const jmethodID method_id, jvmtiEnv* jvmti);
    jint line_no(const jint bci, const jmethodID method_id, jvmtiEnv* jvmti);

    class SymInfoError : std::runtime_error {
    public:
        SymInfoError(const char* msg);
        SymInfoError(const std::string& msg);
        virtual ~SymInfoError();
        static SymInfoError err(int err_num);
    };

    typedef NativeFrame Addr;

    struct MappedRegion {
        Addr start_;
        Addr v_addr_start_;
        Addr end_;
        const std::string file_;
        std::map<Addr, std::string> symbols_;

        MappedRegion(Addr start, Addr v_addr_start, Addr end, std::string file, bool index_symbols);

        ~MappedRegion();

        const bool site_for(Addr addr, std::string& fn_name, Addr& pc_offset) const;

    private:
        SymInfoError section_error(Elf* elf, size_t shstrndx, Elf_Scn *scn, GElf_Shdr *shdr, const std::string& msg);

        void load_symbols(Elf* elf, GElf_Ehdr* ehdr, Elf_Scn* scn, Elf_Scn* xndxscn, GElf_Shdr* shdr);

        void load_elf(Elf* elf);
    };

    class SymInfo {
    private:
        typedef std::map<Addr, std::unique_ptr<MappedRegion>> Mapped;

        Mapped mapped; //start -> region

        typedef Mapped::const_iterator It;

        It region_for(Addr addr) const;

        void update_mapped_ranges(std::function<void()> post_parse_cb = []{});

    public:
        SymInfo();

        // This constructor is meant for _tests_ and should never actually be used in production code
        SymInfo(std::function<void()> post_parse_cb);

        void index(const char* path, Addr start, Addr v_addr_start, Addr end, bool index_symbols);

        void site_for(Addr addr, std::string& file_name, std::string& fn_name, Addr& pc_offset) const;
    };
}

#endif
