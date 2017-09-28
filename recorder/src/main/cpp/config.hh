#ifndef CONFIG_H
#define CONFIG_H

#ifdef IN_TEST
char *safe_copy_string(const char *value, const char *next);
void safe_free_string(char *&value);
#endif

#include <cstdint>
#include "globals.hh"

static const std::uint32_t DEFAULT_BACKOFF_MULTIPLIER = 2;
static const std::uint32_t DEFAULT_MAX_RETRIES = 3;

// all of these are seconds
static const std::uint32_t MIN_BACKOFF_START = 5;
static const std::uint32_t DEFAULT_BACKOFF_MAX = 10 * 60;
static const std::uint32_t DEFAULT_POLLING_INTERVAL = 60;

static const std::uint32_t DEFAULT_METRICS_DEST_PORT = 11514;

struct ConfigurationOptions {
    char* service_endpoint;
    
    /*self-info*/
    char* ip;
    char* host;
    char* app_id;
    char* inst_grp;
    char* inst_id;
    char* cluster;
    char* proc;
    char* vm_id;
    char* zone;
    char* inst_typ;

    std::uint32_t backoff_start;
    std::uint32_t backoff_multiplier;
    std::uint32_t backoff_max;
    std::uint32_t max_retries;
    std::uint32_t poll_itvl;
    
    spdlog::level::level_enum log_level;
    std::uint16_t metrics_dst_port;

    std::uint8_t noctx_cov_pct;

    bool allow_sigprof;

    char* pctx_jar_path;

    std::uint32_t rpc_timeout;
    double slow_tx_tolerance;
    std::uint32_t tx_ring_sz;

    char* stats_syslog_tag;

    ConfigurationOptions(const char* options) :
        service_endpoint(nullptr),
        ip(nullptr),
        host(nullptr),
        app_id(nullptr),
        inst_grp(nullptr),
        inst_id(nullptr),
        cluster(nullptr),
        proc(nullptr),
        vm_id(nullptr),
        zone(nullptr),
        inst_typ(nullptr),
        backoff_start(MIN_BACKOFF_START), backoff_multiplier(DEFAULT_BACKOFF_MULTIPLIER), backoff_max(DEFAULT_BACKOFF_MAX), max_retries(DEFAULT_MAX_RETRIES),
        poll_itvl(DEFAULT_POLLING_INTERVAL),
        log_level(spdlog::level::info), metrics_dst_port(DEFAULT_METRICS_DEST_PORT),
        noctx_cov_pct(0),
        allow_sigprof(true),
        pctx_jar_path(nullptr),
        rpc_timeout(10),
        slow_tx_tolerance(1.5),
        tx_ring_sz(1024 * 1024),
        stats_syslog_tag(nullptr) {

        load(options);
    }

    virtual ~ConfigurationOptions();

    bool valid();

private:
    void load(const char* options);
};

#endif
