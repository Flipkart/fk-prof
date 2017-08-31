#include "stacktraces.hh"

#define UNW_LOCAL_ONLY
#include <libunwind.h>

std::uint32_t Stacktraces::fill_backtrace(NativeFrame* buff, std::uint32_t capacity) {
    unw_cursor_t cursor; unw_context_t uc;
    unw_word_t ip;

    unw_getcontext(&uc);
    unw_init_local(&cursor, &uc);
    std::uint32_t i = 0;
    while ((unw_step(&cursor) > 0) && i < capacity) {
        unw_get_reg(&cursor, UNW_REG_IP, &ip);
        buff[i] = ip;
        i++;
    }
    return i;
}
