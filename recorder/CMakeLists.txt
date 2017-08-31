##########################################################
# CMake Configuration

cmake_minimum_required(VERSION 2.8 FATAL_ERROR)

project(fk-prof)

enable_testing()

set(CMAKE_RUNTIME_OUTPUT_DIRECTORY "build")

include(CheckLibraryExists)
include(CheckSymbolExists)
include(CheckFunctionExists)

##########################################################
# Version

execute_process(
  COMMAND git rev-parse --abbrev-ref HEAD
  WORKING_DIRECTORY ${CMAKE_SOURCE_DIR}
  OUTPUT_VARIABLE GIT_BRANCH
  OUTPUT_STRIP_TRAILING_WHITESPACE)
execute_process(
  COMMAND git log -1 --format=%H
  WORKING_DIRECTORY ${CMAKE_SOURCE_DIR}
  OUTPUT_VARIABLE GIT_COMMIT
  OUTPUT_STRIP_TRAILING_WHITESPACE)

set(CAPTURE_BUILD_ENV "No" CACHE STRING "Optionally capture the build-env info (basically it will base64 encode /etc/os-release file) and capture as a global variable 'const char* fkprec_build_env'. Defaults to no.")
if (${CAPTURE_BUILD_ENV} MATCHES "y")
  execute_process(
    COMMAND bash "-c" "cat /etc/os-release | base64 -w0"
    WORKING_DIRECTORY ${CMAKE_SOURCE_DIR}
    OUTPUT_VARIABLE BUILD_ENV
    OUTPUT_STRIP_TRAILING_WHITESPACE)
endif()

set(fkp_version_major 1)
set(fkp_version_minor 5)
set(fkp_version_patch 0)

add_definitions("-DGIT_COMMIT=${GIT_COMMIT}")
add_definitions("-DGIT_BRANCH=${GIT_BRANCH}")
add_definitions("-DFKP_VERSION=${fkp_version_major}.${fkp_version_minor}.${fkp_version_patch}")
add_definitions("-DFKP_VERSION=${fkp_version_major}.${fkp_version_minor}.${fkp_version_patch}")
add_definitions("-DBUILD_ENV='${BUILD_ENV}'")

##########################################################
# Paths
set(SRC "src/main/cpp")
set(SRC_PRELOAD "src/main/c")
set(INCLUDE_VENDOR_CONCQUEUE "vendor/concurrentqueue")
set(INCLUDE_VENDOR_SPDLOG "vendor/spdlog/include")
set(SRC_TEST "src/test/cpp")
set(BIN "build")
set(BASE_OUTPUT "bagent")
set(AGENT_LIB "fkpagent")
set(PRELOAD_LIB "prefkp")
set(CMAKE_LIBRARY_OUTPUT_DIRECTORY ${BIN})
#protobuf idl setup
add_subdirectory(src/idl)
include_directories(src/idl src/main/cpp)

##########################################################
# Libraries & Code

list(APPEND CMAKE_MODULE_PATH "${CMAKE_CURRENT_SOURCE_DIR}/cmake_modules")

find_package(PkgConfig REQUIRED)

find_package(LibCuckoo REQUIRED)
include_directories(${LIBCUCKOO_INCLUDE_DIRS})

find_package(JNI REQUIRED)
include_directories(${JAVA_INCLUDE_PATH})
include_directories(${JAVA_INCLUDE_PATH2})

pkg_check_modules(ZLIB REQUIRED zlib)
pkg_check_modules(CURL REQUIRED libcurl)
pkg_check_modules(MEDIDA REQUIRED libmedida>=1.0.0)
pkg_check_modules(ELF REQUIRED libelf)
pkg_check_modules(UNWIND REQUIRED libunwind)
include_directories(${MEDIDA_INCLUDE_DIRS})

if (DEFINED ENV{UNITTEST_INCLUDE_DIRS})
    message("User has configured " $ENV{UNITTEST_INCLUDE_DIRS} " as the unit test include directory")
    include_directories($ENV{UNITTEST_INCLUDE_DIRS})
else()
    if(${CMAKE_SYSTEM_NAME} MATCHES "Darwin")
        pkg_check_modules (unittest++ REQUIRED UnitTest++)
    else()
        pkg_check_modules (unittest++ REQUIRED unittest++)
    endif()
    include_directories(${unittest++_INCLUDE_DIRS})
endif()

find_package(Boost 1.49 REQUIRED)
if(Boost_FOUND)
  include_directories(${Boost_INCLUDE_DIRS})
endif()

check_include_file("valgrind/valgrind.h" HAS_VALGRIND_H)

# include of gcc 4.8 headers specifically to work around
# https://bugs.debian.org/cgi-bin/bugreport.cgi?bug=729933
# equivalent in ubuntu 13.10
include_directories("/usr/include/i386-linux-gnu/c++/4.8/")

set(SOURCE_FILES
    ${SRC}/checksum.cc
    ${SRC}/checksum.hh
    ${SRC}/circular_queue.cc
    ${SRC}/circular_queue.hh
    ${SRC}/common.cc
    ${SRC}/common.hh
    ${SRC}/controller.cc
    ${SRC}/controller.hh
    ${SRC}/metrics.hh
    ${SRC}/globals.hh
    ${SRC}/globals.cc
    ${SRC}/ti_thd.hh
    ${SRC}/ti_thd.cc
    ${SRC}/signal_handler.cc
    ${SRC}/signal_handler.hh
    ${SRC}/processor.cc
    ${SRC}/processor.hh
    ${SRC}/mapping_parser.hh
    ${SRC}/mapping_parser.cc
    ${SRC}/site_resolver.cc
    ${SRC}/site_resolver.hh
    ${SRC}/profiler.cc
    ${SRC}/profiler.hh
    ${SRC}/stacktraces.hh
    ${SRC}/stacktraces.cc
    ${SRC}/trace.hh
    ${SRC}/thread_map.hh
    ${SRC}/thread_map.cc
    ${SRC}/concurrent_map.hh
    ${SRC}/concurrent_map.cc
    ${SRC}/profile_writer.cc
    ${SRC}/profile_writer.hh
    ${SRC}/blocking_ring_buffer.cc
    ${SRC}/blocking_ring_buffer.hh
    ${SRC}/config.hh
    ${SRC}/config.cc
    ${SRC}/scheduler.hh
    ${SRC}/scheduler.cc
    ${SRC}/util.hh
    ${SRC}/perf_ctx_jni.hh
    ${SRC}/perf_ctx.hh
    ${SRC}/perf_ctx.cc
    ${SRC}/prob_pct.hh
    ${SRC}/prob_pct.cc
    ${SRC}/metric_formatter.hh
    ${SRC}/metric_formatter.cc
    ${SRC}/unique_readsafe_ptr.hh)

set(PRELOAD_SOURCE_FILES
    ${SRC_PRELOAD}/preload.c)

set(TI_SRC_FILES
    ${SRC}/agent.cc)

set(TEST_BASE_FILES
    ${SRC_TEST}/test.cc)
    
set(TEST_FILES
    ${SRC_TEST}/fixtures.hh
    ${SRC_TEST}/test_circular_queue.cc
    ${SRC_TEST}/test_cpu_sampling_profile_serialization.cc
    ${SRC_TEST}/test_config.cc
    ${SRC_TEST}/test.hh
    ${SRC_TEST}/test_checksum.cc
    ${SRC_TEST}/test_maps.cc
    ${SRC_TEST}/test_scheduler.cc
    ${SRC_TEST}/test_perf_ctx_tracker.cc
    ${SRC_TEST}/test_perf_ctx_registry.cc
    ${SRC_TEST}/test_prob_pct.cc
    ${SRC_TEST}/test_blocking_ring_buffer.cc
    ${SRC_TEST}/test_syslog_tsdb_metric_formatter.cc
    ${SRC_TEST}/test_unique_readsafe_ptr.cc
    ${SRC_TEST}/test_site_resolver.cc
    ${SRC_TEST}/test.cc)

set(TEST_UTIL_FILES
    ${SRC_TEST}/test_helpers.hh
    ${SRC_TEST}/test_helpers.cc)

set(SYMINFO_TEST_EXT_FILES
    ${SRC_TEST}/syminfo_test_ext.cc)

set(TEST_JNI_FILES
    ${SRC_TEST}/test_jni.hh
    ${SRC_TEST}/test_jni.cc)

##########################################################
# Compiler Options

set(GLOBAL_WARNINGS "-Wall -Wformat-security -Wno-char-subscripts")
set(GLOBAL_WARNINGS "${GLOBAL_WARNINGS} -Wno-sign-compare -Wno-strict-overflow -Wwrite-strings")
set(GLOBAL_WARNINGS "${GLOBAL_WARNINGS} -Wnon-virtual-dtor -Woverloaded-virtual")

set(GLOBAL_COPTS "-fdiagnostics-show-option -fno-omit-frame-pointer -fno-strict-aliasing -DHAS_VALGRIND=${HAS_VALGRIND_H}")
set(GLOBAL_COPTS "${GLOBAL_COPTS} -funsigned-char -fno-asynchronous-unwind-tables -msse2 -g -D__STDC_FORMAT_MACROS -fPIC")

# Platform Specific
if(${CMAKE_SYSTEM_NAME} MATCHES "Darwin")
    set(GLOBAL_COPTS "${GLOBAL_COPTS} -DTARGET_RT_MAC_CFM=0")
    if("${CMAKE_SIZEOF_VOID_P}" EQUAL "8") 
        message("-- Detected 64 bit Mac")
        set(GLOBAL_COPTS "${GLOBAL_COPTS} -D__LP64__=1")
    endif()
endif()

# Compiler Specific
if ("${CMAKE_CXX_COMPILER_ID}" STREQUAL "Clang")
    message("-- using Clang")
    set(GLOBAL_WARNINGS, "${GLOBAL_WARNINGS} -Weverything -Wno-c++98-compat-pedantic -Wno-padded")
    set(global_warNINGS, "${GLOBAL_WARNINGS} -Wno-missing-prototypes -Wno-weak-vtables -Wno-global-constructors")
elseif ("${CMAKE_CXX_COMPILER_ID}" STREQUAL "GNU")
    message("-- using GCC")
    #set(GLOBAL_COPTS "${GLOBAL_COPTS} -mfpmath=sse -fno-tree-loop-distribute-patterns")
    set(GLOBAL_COPTS "${GLOBAL_COPTS} -mfpmath=sse")
    set(GLOBAL_WARNINGS, "${GLOBAL_WARNINGS} -Wframe-larger-than=16384 -Wno-unused-but-set-variable")
    set(GLOBAL_WARNINGS, "${GLOBAL_WARNINGS} -Wunused-but-set-parameter -Wvla -Wno-conversion-null")
    set(GLOBAL_WARNINGS, "${GLOBAL_WARNINGS} -Wno-unknown-pragmas -Wno-builtin-macro-redefined -Wl,-fatal_warnings")
endif()

set(CMAKE_CXX_FLAGS_RELWITHDEBUGINFO  "-O2 -g3 -ggdb")
set(CMAKE_CXX_FLAGS_DEBUG  "-O0 -g3 -ggdb") # -fsanitize=address when needed
SET(VERBOSE_LOGS "n" CACHE STRING "'Enable Verbose Logging' flag, turns on compile-time controlled debug and trace logs, defaults to 'n'")
if (${VERBOSE_LOGS} MATCHES "y")
  set(CMAKE_CXX_FLAGS_DEBUG  "${CMAKE_CXX_FLAGS_DEBUG} -DSPDLOG_TRACE_ON -DSPDLOG_DEBUG_ON")
endif()
# to enable tracing add: -DENABLE_TRACING
set(CMAKE_CXX_FLAGS "-I${INCLUDE_VENDOR_SPDLOG} -I${INCLUDE_VENDOR_CONCQUEUE} -I${PROTOBUF_INCLUDE_DIR} ${CMAKE_CXX_FLAGS} ${GLOBAL_WARNINGS} ${GLOBAL_COPTS} -pthread -std=c++0x -fno-omit-frame-pointer")

##########################################################
# Build

add_library(${BASE_OUTPUT} STATIC ${SOURCE_FILES})
add_library(${AGENT_LIB} SHARED ${TI_SRC_FILES})

if ("${CMAKE_SYSTEM_NAME}" STREQUAL "FreeBSD") 
    target_link_libraries(${BASE_OUTPUT} proto ${PROTOBUF_LIBRARY} ${ZLIB_LDFLAGS} ${CURL_LDFLAGS} ${MEDIDA_LDFLAGS} ${ELF_LDFLAGS} ${UNWIND_LDFLAGS})
else()
    target_link_libraries(${BASE_OUTPUT} proto ${PROTOBUF_LIBRARY} ${ZLIB_LDFLAGS} ${CURL_LDFLAGS} ${MEDIDA_LDFLAGS} ${ELF_LDFLAGS} ${UNWIND_LDFLAGS} dl)
endif()
target_link_libraries(${AGENT_LIB} ${BASE_OUTPUT})
add_library(${PRELOAD_LIB} SHARED ${PRELOAD_SOURCE_FILES})

add_library(testutil SHARED ${TEST_UTIL_FILES})
add_library(testbase STATIC ${TEST_BASE_FILES})
add_executable(tests ${TEST_FILES} ${SRC_TEST}/main.cc)
add_library(testjni SHARED ${TEST_JNI_FILES})
add_library(syminfo_test_ext SHARED ${SYMINFO_TEST_EXT_FILES})

if (DEFINED ENV{UNITTEST_LIBRARIES})
  message("User has configured " $ENV{UNITTEST_LIBRARIES} " as the unit test libraries")
  target_link_libraries(tests testutil testbase ${BASE_OUTPUT} $ENV{UNITTEST_LIBRARIES})
  target_link_libraries(testjni testutil testbase ${BASE_OUTPUT} $ENV{UNITTEST_LIBRARIES})
else()
  target_link_libraries(tests testutil testbase ${BASE_OUTPUT} ${unittest++_LIBRARIES})
  target_link_libraries(testjni testutil testbase ${BASE_OUTPUT} ${unittest++_LIBRARIES})
endif()


# make test
add_test(tests ${BIN}/tests)

# install
install(TARGETS  ${AGENT_LIB}
LIBRARY DESTINATION lib)
install(TARGETS  ${PRELOAD_LIB}
LIBRARY DESTINATION lib)
