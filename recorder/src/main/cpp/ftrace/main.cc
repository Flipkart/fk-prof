#include <signal.h>
#include "server.hh"
#include <stdexcept>
#include "logging.hh"
#include <atomic>
#include <signal.h>
#include <chrono>
#include "../config_shared.hh"
#include "../metrics.hh"
#include "../metric_formatter.hh"
#include "config.hh"
#include <thread>

LoggerP logger = spdlog::syslog_logger("syslog", "fk-prof-rec-ftracer", LOG_PID);

std::atomic<bool> cancel_startup {false};
std::atomic<ftrace::Server*> server {nullptr};
static medida::MetricsRegistry metrics_registry;


static void sigterm_handler(int signal_no, siginfo_t* info, void* context) {
    cancel_startup.store(true, std::memory_order_seq_cst);
    auto s = server.load(std::memory_order_seq_cst);
    if (s != nullptr) {
        s->stop();
    }
}

void setup_term_action() {
    struct sigaction sa;
    sa.sa_handler = NULL;
    sa.sa_sigaction = sigterm_handler;
    sa.sa_flags = SA_RESTART | SA_SIGINFO;

    sigemptyset(&sa.sa_mask);

    if (sigaction(SIGTERM, &sa, nullptr) != 0) {
        char err_msg[128];
        int err = errno;
        auto msg = strerror_r(err, err_msg, sizeof(err_msg));
        logger->error("Couldn't setup sigterm handler: {} ({})", msg, err);
        throw new std::runtime_error("Failed to setup signal-handler, aborting");
    }
}

medida::MetricsRegistry& get_metrics_registry() {
    return metrics_registry;
}

static void metrics_reporting_loop(std::atomic<bool>& run, medida::reporting::UdpReporter& reporter) {
    auto itvl = std::chrono::seconds(5);
    while (run.load(std::memory_order_relaxed)) {
        std::this_thread::sleep_for(itvl);
        reporter.run();
    }
}

int main(int argc, char** argv) {
    if (argc > 2) {
        logger->error("Expected {} arguments, got {}", 1, argc - 1);
        throw std::runtime_error("Bad usage: fk-prof-ftracer <comma-separated-config-string>");
    }
    setup_term_action();

    std::unique_ptr<ftrace::Config> cfg(new ftrace::Config(argc == 1 ? "" : argv[1]));
    init_logging(cfg->log_level, "ftrace-server");

    MetricFormatter::SyslogTsdbFormatter metrics_formatter(metrics_tsdb_tag, cfg->stats_syslog_tag);
    medida::reporting::UdpReporter metrics_reporter(metrics_registry, metrics_formatter, cfg->metrics_dst_port);
    std::atomic<bool> report_metrics {true};

    std::thread metrics_poller(metrics_reporting_loop, std::ref(report_metrics), std::ref(metrics_reporter));
    
    server.store(new ftrace::Server(cfg->trace_dir, cfg->listener_socket), std::memory_order_seq_cst);
    
    if (cancel_startup.load(std::memory_order_seq_cst)) return -1;
    
    server.load(std::memory_order_seq_cst)->run();

    report_metrics.store(false, std::memory_order_relaxed);

    metrics_poller.join();

    return 0;
}
