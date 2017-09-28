find_path(LIBCUCKOO_ROOT include/libcuckoo/cuckoohash_map.hh)

if(LIBCUCKOO_ROOT)
    set(LIBCUCKOO_FOUND TRUE)
    set(LIBCUCKOO_INCLUDE_DIRS "${LIBCUCKOO_ROOT}/include/libcuckoo")
else()
    message("Can't find libcuckoo!")
    if(LibCuckoo_FIND_REQUIRED)
        message(FATAL_ERROR "Missing required package libcuckoo")
    endif()
endif()
