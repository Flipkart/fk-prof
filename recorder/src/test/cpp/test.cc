#include "test.hh"
#include "../../main/cpp/prob_pct.hh"
#include "../../main/cpp/thread_map.hh"

LoggerP logger(nullptr);
PerfCtx::Registry* ctx_reg = nullptr;
ProbPct* prob_pct = nullptr;
medida::MetricsRegistry* metrics_registry = nullptr;

TestEnv::TestEnv() {
    logger = spdlog::stdout_color_mt("console");
    logger->set_level(spdlog::level::trace);
    logger->set_pattern("{%t} %+");
    metrics_registry = new medida::MetricsRegistry();
}

TestEnv::~TestEnv() {
    logger.reset();
    spdlog::drop_all();
    delete metrics_registry;
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
