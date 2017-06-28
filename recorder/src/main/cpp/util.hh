#ifndef UTIL_H
#define UTIL_H

#include <sstream>
#include <regex>
#include <sys/stat.h>

namespace Util {
    namespace {
        template <typename T> void to_s(std::stringstream& ss, T t) {
            ss << t;
        }

        template <typename T, typename... Args> void to_s(std::stringstream& ss, T t, Args... args) {
            to_s(ss, t);
            to_s(ss, args...);
        }
    }

    template <typename... Args> std::string to_s(Args... args) {
        std::stringstream ss;
        to_s(ss, args...);
        return ss.str();
    }

    template <typename T> const T& min(const T& first, const T& second) {
        return first > second ? second : first;
    }

    template <typename T> const T& max(const T& first, const T& second) {
        return first < second ? second : first;
    }

    std::string content(const std::string& path, const std::regex* after, const std::regex* before);

    template <typename T> T stoun(const std::string& str);

    extern template std::uint16_t stoun<std::uint16_t>(const std::string& str);

    bool dir_exists(const char *path);

    bool file_exists(const char *path);

}


#endif
