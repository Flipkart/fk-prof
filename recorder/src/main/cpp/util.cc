#include "util.hh"
#include <fstream>
#include <string>
#include <fstream>
#include <streambuf>
#include <cassert>

std::string Util::content(const std::string& path, const std::regex* after, const std::regex* before) { // (after, before) semantics, as opposed to [), (] or [] -jj
    std::ifstream f_in(path, std::ios_base::in);
    std::stringstream ss;
    std::string line;
    bool before_matched = false;
    bool after_matched = (after == nullptr);
    while(! f_in.eof()) {
        if (! f_in.good()) {
            throw std::runtime_error("Encountered error while reading file");
        }
        std::getline(f_in, line);
        if (after_matched && before != nullptr) before_matched =  before_matched || std::regex_match(line, *before);
        if (before_matched) break;
        if (after_matched) {
            ss << line;
            if (! f_in.eof()) ss << "\n";
        } else {
            after_matched = std::regex_match(line, *after);
        }
    }
    if (f_in.eof() && before != nullptr) throw std::runtime_error("Didn't find the marker-pattern in file: " + path);
    return ss.str();
}


template <typename T> T Util::stoun(const std::string& str) {
     std::size_t end;
     std::uint64_t result = std::stoul(str, &end, 10);
     assert(end == str.length());
     if (result > std::numeric_limits<T>::max()) {
         throw std::out_of_range("stoun");
     }
     return result;
 }

template std::uint16_t Util::stoun<std::uint16_t>(const std::string& str);

bool Util::dir_exists(const char *path) {
    struct stat info;
    if(stat(path, &info) != 0)
        return false;
    return S_ISDIR(info.st_mode);
}

bool Util::file_exists(const char *path) {
    struct stat info;
    if(stat(path, &info) != 0)
        return false;
    return (S_IFMT & info.st_mode) != 0;
}

