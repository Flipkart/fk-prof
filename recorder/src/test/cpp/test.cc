#include "test.hh"
#include "../../main/cpp/prob_pct.hh"
#include "../../main/cpp/thread_map.hh"
#include <sys/stat.h>
#include <sys/types.h>
#include <cstdlib>
#include <util.hh>

LoggerP logger(nullptr);
PerfCtx::Registry* ctx_reg = nullptr;
ProbPct* prob_pct = nullptr;
medida::MetricsRegistry* metrics_registry = nullptr;

TestEnv::TestEnv() : tmp_dir("") {
    logger = spdlog::stdout_color_mt("console");
    logger->set_level(spdlog::level::trace);
    logger->set_pattern("{%t} %+");
    metrics_registry = new medida::MetricsRegistry();
}

void kill_dir(const std::string& tmp_dir) {
    auto cmd = "rm -rf " + tmp_dir;
    auto ret = std::system(cmd.c_str());
    assert(ret == 0);
}

TestEnv::~TestEnv() {
    if (! tmp_dir.empty()) {
        kill_dir(tmp_dir);
    }
    logger.reset();
    spdlog::drop_all();
    delete metrics_registry;
}

const std::string& TestEnv::mk_tmp_dir() {
    if (! tmp_dir.empty()) throw std::runtime_error("Tmp directory already exists: " + tmp_dir + ", please use subdirectories");
    tmp_dir = "tmp.dir";
    if (Util::dir_exists(tmp_dir.c_str())) {
        kill_dir(tmp_dir);
    }
    auto ret = mkdir(tmp_dir.c_str(), S_IRWXU | S_IXGRP | S_IRGRP | S_IXGRP | S_IROTH | S_IXOTH);
    if (ret != 0) throw std::runtime_error("Couldn't create tmp-dir: " + tmp_dir);
    return tmp_dir;
}

static ThreadMap thread_map;
ThreadMap& get_thread_map() {
    return thread_map;
}

medida::MetricsRegistry& get_metrics_registry() {
    return *metrics_registry;
}

ProbPct& get_prob_pct() {
    return *prob_pct;
}

PerfCtx::Registry& get_ctx_reg() {
    return *ctx_reg;
}

std::ostream& operator<<(std::ostream& os, BacktraceType type) {
     switch (type) {
     case BacktraceType::Java:
         os << "Java-Backtrace";
         break;
     case BacktraceType::Native:
         os << "Native-Backtrace";
         break;
     default:
         assert(false);
     }
     return os;
 }
