#include <iostream>
#include <functional>
#include <string>
#include <complex>
#include <cstdint>
#include <fstream>
#include <atomic>
#include <memory>
#include <cstring>
#include <map>
#include <thread>

#include <sys/types.h>
#include <sys/stat.h>
#include <fcntl.h>
#include <unistd.h>
#include <gelf.h>
#include <cxxabi.h>
#include <link.h>
#include <signal.h>

#include "error_backtrace_different_compilation_unit_helper.hh"

#ifdef W_JNI
#include "BtPrinter.h"
#endif

typedef std::uint64_t Addr;

class SymInfoError : std::runtime_error {
public:
    SymInfoError(const char* msg) : std::runtime_error(msg) {}
    SymInfoError(const std::string& msg) : std::runtime_error(msg) {}
    virtual ~SymInfoError() {}

    static SymInfoError err(int err_num) {
        int sz = 1024;
        std::unique_ptr<char[]> buff(new char[sz]);
        if (strerror_r(err_num, buff.get(), sz) != 0) {
            std::string unknown_error = "Unknown error: " + std::to_string(err_num);
            throw SymInfoError(unknown_error.c_str());
        }
        return SymInfoError(buff.get());
    }
};

class ElfFile {
private:
    int fd_;
    Elf *elf_;
    std::string path_;

public:
    ElfFile(const std::string& path) : path_(path) {
        fd_ = open(path.c_str(), O_RDONLY);
        if (fd_ == -1) throw SymInfoError::err(errno);
        elf_ = elf_begin(fd_, ELF_C_READ_MMAP, NULL);
        if (elf_ == nullptr) {
            throw SymInfoError("File format not recognizable for: " + path_);
        }
        if (elf_kind(elf_) != ELF_K_ELF) {
            throw SymInfoError("File " + path_ + " is not an elf.");
        }
    }

    ~ElfFile() {
        if (elf_ != nullptr) {
            if (elf_end(elf_) != 0) {
                std::cout << "Couldn't close elf\n";//log me
            }
        }
        if (fd_ > -1) {
            close(fd_);
        }
    }

    Elf* get() {
        return elf_;
    }
};

struct MappedRegion {
    Addr start_;
    const std::string file_;
    std::map<Addr, std::string> symbols_;

    MappedRegion(Addr start, std::string file): start_(start), file_(file) {
        ElfFile elf_file(file_);
        load_elf(elf_file.get());
    }

    ~MappedRegion() {}

    const std::string site_for(Addr addr) {
        auto unrelocated_address = addr - start_;
        auto it = symbols_.lower_bound(unrelocated_address);
        if (it == std::end(symbols_)) return "???";
        it--;
        return it->second + " +" + std::to_string(unrelocated_address - it->first);
    }

private:

    SymInfoError section_error(Elf* elf, size_t shstrndx, Elf_Scn *scn, GElf_Shdr *shdr, const std::string& msg) {
        std::stringstream ss;
        ss << file_ << ": " << elf_ndxscn(scn) << " " <<
            elf_strptr(elf, shstrndx, shdr->sh_name)<< ": " << msg;
        std::string err = ss.str();
        throw SymInfoError(err.c_str());
    }

    void load_symbols(Elf* elf, GElf_Ehdr* ehdr, Elf_Scn* scn, Elf_Scn* xndxscn, GElf_Shdr* shdr) {
        size_t shstrndx;
        if (elf_getshdrstrndx (elf, &shstrndx) < 0) {
            throw SymInfoError("Cannot get section header str-table index");
        }
        size_t size = shdr->sh_size;
        size_t entsize = shdr->sh_entsize;

        if (entsize == 0
            || entsize != gelf_fsize (elf, ELF_T_SYM, 1, EV_CURRENT)) {
            throw section_error(elf, shstrndx, scn, shdr, "Entry size in section not as expected");
        } else if (size % entsize != 0) {
            throw section_error(elf, shstrndx, scn, shdr, "Size of section not multiple of entry sz");
        }

        size_t nentries = size / (entsize ?: 1);

        Elf_Data *data = elf_getdata (scn, NULL);
        Elf_Data *xndxdata = elf_getdata (xndxscn, NULL);
        if (data == NULL || (xndxscn != NULL && xndxdata == NULL))
            throw SymInfoError("Couldn't get section/extended-section data");

        GElf_Sym sym_buff;
        Elf32_Word xndx;
        for (size_t cnt = 0; cnt < nentries; cnt++) {
            GElf_Sym* sym = gelf_getsymshndx(data, xndxdata, cnt, &sym_buff, &xndx);
            if (sym == nullptr) {
                throw SymInfoError("Symbol was null");
            }
            if (sym->st_shndx == SHN_UNDEF) continue;
            const char* symstr = elf_strptr(elf, shdr->sh_link, sym->st_name);
            if (symstr == nullptr) continue;

            bool sym_loaded = false;
            if ((strlen(symstr) > 1) && (symstr[0] == '_') && (symstr[1] == 'Z')) {
                int status = -1;
                char* dmsymstr = abi::__cxa_demangle(symstr, nullptr, 0, &status);
                if (status == 0) {
                    symbols_[sym->st_value] = dmsymstr;
                    sym_loaded = true;
                    free(dmsymstr);
                }
            }
            if (! sym_loaded) symbols_[sym->st_value] = symstr;
        }
    }

    void load_elf(Elf* elf) {
        GElf_Ehdr ehdr_mem;
        GElf_Ehdr *ehdr = gelf_getehdr(elf, &ehdr_mem);
        if (ehdr->e_type != ET_EXEC && ehdr->e_type != ET_DYN) {
            throw SymInfoError("Elf " + file_ + " wasn't an executable or a shared-lib");
        }
        Elf_Scn* scn = nullptr;
        while ((scn = elf_nextscn(elf, scn)) != nullptr) {
            GElf_Shdr shdr_mem;
            GElf_Shdr *shdr = gelf_getshdr (scn, &shdr_mem);
            if (shdr == nullptr) {
                //report this error
                continue;
            }
            if ((shdr->sh_type == SHT_SYMTAB) || (shdr->sh_type == SHT_DYNSYM)) {
                Elf_Scn* xndxscn = nullptr;
                if (shdr->sh_type == SHT_SYMTAB) {
                    size_t scnndx = elf_ndxscn(scn);
                    while ((xndxscn = elf_nextscn(elf, xndxscn)) != nullptr) {
                        GElf_Shdr xndxshdr_mem;
                        GElf_Shdr *xndxshdr = gelf_getshdr(xndxscn, &xndxshdr_mem);

                        if (xndxshdr == NULL) { /*report error*/ }

                        if (xndxshdr->sh_type == SHT_SYMTAB_SHNDX
                            && xndxshdr->sh_link == scnndx)
                            break;
                    }
                }
                load_symbols(elf, ehdr, scn, xndxscn, shdr);
            }
        }
    }
};

static int dyn_link_handler(struct dl_phdr_info* info, size_t size, void* data);

class SymInfo {
private:
    typedef std::map<Addr, std::unique_ptr<MappedRegion>> Mapped;
    Mapped mapped; //start -> region
    typedef Mapped::const_iterator It;

    It region_for(Addr addr) const {
        auto it = mapped.lower_bound(addr);
        it--;
        return it;
    }

public:
    SymInfo() {
        elf_version(EV_CURRENT);
        dl_iterate_phdr(dyn_link_handler, this);
    }

    void index(const char* path, Addr start) {
        mapped[start] = std::unique_ptr<MappedRegion>(new MappedRegion(start, path));
    }

    const std::string& file_for(Addr addr) const {
        auto it = region_for(addr);
        if (it == std::end(mapped)) throw std::runtime_error("can't resolve addresss " + std::to_string(addr));
        return it->second->file_;
    }

    std::string site_for(Addr addr) const {
        auto it = region_for(addr);
        if (it == std::end(mapped)) throw std::runtime_error("can't resolve addresss " + std::to_string(addr));
        return it->second->site_for(addr);
    }

    void print_frame(Addr pc) const {
        auto site = site_for(pc);
        auto file = file_for(pc);
        std::cout << "0x" << std::hex << pc << " > " << site << " @ " << file <<'\n';
     }
};

static int dyn_link_handler(struct dl_phdr_info* info, size_t size, void* data) {
    auto si = reinterpret_cast<SymInfo*>(data);

    if (strlen(info->dlpi_name) == 0) {
        auto max_path_sz = 1024;
        std::unique_ptr<char[]> buff(new char[max_path_sz]);
        auto path_len = readlink("/proc/self/exe", buff.get(), max_path_sz);
        if ((path_len > 0) && (path_len < max_path_sz)) {
            buff.get()[path_len] = '\0';
            si->index(buff.get(), info->dlpi_addr);
        } else {
            si->index("/proc/self/exe", info->dlpi_addr);
        }
    } else if (strstr(info->dlpi_name, "linux-vdso.so") != info->dlpi_name) {
        si->index(info->dlpi_name, info->dlpi_addr);
    }
    
    return 0;
}

void print_bt() {
    SymInfo syms;
    std::uint64_t rbp, rpc, rax;
    asm("movq %%rbp, %%rax;"
        "movq %%rax, %0;"
        "lea (%%rip), %%rax;"
        "movq %%rax, %1;"
        : "=r"(rbp), "=r"(rpc)
        :);

    std::cout << "**************************\n";
    std::cout << "base: 0x" << std::hex << rbp << "    PC: 0x" << std::hex << rpc << '\n';

    syms.print_frame(rpc);
    while (true) {
        rpc = *reinterpret_cast<std::uint64_t*>(rbp + 8);
        syms.print_frame(rpc);
        rbp = *reinterpret_cast<std::uint64_t*>(rbp);
        if (rbp == 0) break;
    }
}

int baz() {
    Foo f;
    f.quux();
    return 5;
}

int bar() {
    return 5 + baz();
}

namespace Corge {
    void grault() {
        print_bt();
    }
}

void bt_sig_hdlr(int signum, siginfo_t *info, void *context) {
    Corge::grault();
}

void hookup_sighdlr() {
    struct sigaction sa;
    struct sigaction sa_old;

    sa.sa_handler = nullptr;
    sa.sa_sigaction = bt_sig_hdlr;
    sa.sa_flags = SA_RESTART | SA_SIGINFO;
    sigemptyset(&sa.sa_mask);

    if (sigaction(SIGUSR1, &sa, &sa_old) != 0) {
        std::cerr << "Couldn't hook up sig-hdlr\n";
    }
}

#ifndef W_JNI

int main() {
    hookup_sighdlr();

    return foo();
}

#else

JNIEXPORT void JNICALL Java_BtPrinter_printBt(JNIEnv* jni, jobject self) {
    foo();
}

#endif
