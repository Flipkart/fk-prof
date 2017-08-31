#include <thread>
#include <vector>
#include <iostream>
#include "fixtures.hh"
#include "test.hh"
#define IN_TEST
#include "../../main/cpp/config.hh"

TEST(ParsesAllOptions) {
    TestEnv _;
    std::string str("service_endpoint=http://10.20.30.40:9070,"
                    "ip=50.60.70.80,"
                    "host=foo.host,"
                    "app_id=bar_app,"
                    "inst_grp=baz_grp,"
                    "cluster=quux_cluster,"
                    "inst_id=corge_iid,"
                    "proc=grault_proc,"
                    "vm_id=garply_vm_id,"
                    "zone=waldo_zone,"
                    "inst_type=c0.medium,"
                    "backoff_start=2,"
                    "backoff_multiplier=3,"
                    "max_retries=7,"
                    "backoff_max=15,"
                    "log_lvl=warn,"
                    "poll_itvl=30,"
                    "metrics_dst_port=10203,"
                    "noctx_cov_pct=25,"
                    "allow_sigprof=n,"
                    "pctx_jar_path=/tmp/foo.jar,"
                    "rpc_timeout=7,"
                    "slow_tx_tolerance=1.25,"
                    "tx_ring_sz=102400,"
                    "stats_syslog_tag=foo");
    
    ConfigurationOptions options(str.c_str());
    CHECK_EQUAL("http://10.20.30.40:9070", options.service_endpoint);
    CHECK_EQUAL("50.60.70.80", options.ip);
    CHECK_EQUAL("foo.host", options.host);
    CHECK_EQUAL("bar_app", options.app_id);
    CHECK_EQUAL("baz_grp", options.inst_grp);
    CHECK_EQUAL("quux_cluster", options.cluster);
    CHECK_EQUAL("corge_iid", options.inst_id);
    CHECK_EQUAL("grault_proc", options.proc);
    CHECK_EQUAL("garply_vm_id", options.vm_id);
    CHECK_EQUAL("waldo_zone", options.zone);
    CHECK_EQUAL("c0.medium", options.inst_typ);
    CHECK_EQUAL(2, options.backoff_start);
    CHECK_EQUAL(3, options.backoff_multiplier);
    CHECK_EQUAL(7, options.max_retries);
    CHECK_EQUAL(15, options.backoff_max);
    CHECK_EQUAL(spdlog::level::warn, options.log_level);
    CHECK_EQUAL(30, options.poll_itvl);
    CHECK_EQUAL(10203, options.metrics_dst_port);
    CHECK_EQUAL(25, options.noctx_cov_pct);
    CHECK_EQUAL(false, options.allow_sigprof);
    CHECK_EQUAL("/tmp/foo.jar", options.pctx_jar_path);
    CHECK_EQUAL(7, options.rpc_timeout);
    CHECK_EQUAL(1.25, options.slow_tx_tolerance);
    CHECK_EQUAL(102400, options.tx_ring_sz);
    CHECK_EQUAL("foo", options.stats_syslog_tag);
    CHECK_EQUAL(true, options.valid());

}

TEST(Understand_AllowSigprof) {
    TestEnv _;
    std::string str("service_endpoint=http://10.20.30.40:9070,"
                    "ip=50.60.70.80,"
                    "host=foo.host,"
                    "app_id=bar_app,"
                    "inst_grp=baz_grp,"
                    "cluster=quux_cluster,"
                    "inst_id=corge_iid,"
                    "proc=grault_proc,"
                    "vm_id=garply_vm_id,"
                    "zone=waldo_zone,"
                    "inst_type=c0.medium,"
                    "allow_sigprof=y,"
                    "pctx_jar_path=/tmp/foo.jar,"
                    "stats_syslog_tag=foo");

    ConfigurationOptions options(str.c_str());
    CHECK_EQUAL(true, options.allow_sigprof);
    CHECK_EQUAL(true, options.valid());
}

#define UNRELATED_FIELDS "service_endpoint=http://10.20.30.40:9070," \
    "ip=50.60.70.80,"                                                \
    "host=foo.host,"                                                 \
    "app_id=bar_app,"                                                \
    "inst_grp=baz_grp,"                                              \
    "cluster=quux_cluster,"                                          \
    "inst_id=corge_iid,"                                             \
    "proc=grault_proc,"                                              \
    "vm_id=garply_vm_id,"                                            \
    "zone=waldo_zone,"                                               \
    "inst_type=c0.medium,"                                           \
    "pctx_jar_path=/tmp/foo.jar,"                                    \
    "stats_syslog_tag=bar,"

TEST(Understand_Match_allow_sigprof_field_value_case_insensitively) {
    TestEnv _;

    std::string str(UNRELATED_FIELDS "allow_sigprof=Y");
    ConfigurationOptions options0(str.c_str());
    CHECK_EQUAL(true, options0.allow_sigprof);
    CHECK_EQUAL(true, options0.valid());

    str = (UNRELATED_FIELDS "allow_sigprof=y");
    ConfigurationOptions options1(str.c_str());
    CHECK_EQUAL(true, options1.allow_sigprof);
    CHECK_EQUAL(true, options1.valid());
}

TEST(DefaultAppropriately) {
    TestEnv _;
    std::string str("service_endpoint=http://10.20.30.40:9070,"
                    "ip=50.60.70.80,"
                    "host=foo.host,"
                    "app_id=bar_app,"
                    "inst_grp=baz_grp,"
                    "cluster=quux_cluster,"
                    "inst_id=corge_iid,"
                    "proc=grault_proc,"
                    "vm_id=garply_vm_id,"
                    "zone=waldo_zone,"
                    "inst_type=c0.medium,"
                    "pctx_jar_path=/tmp/quux.jar,"
                    "stats_syslog_tag=bar");
    
    ConfigurationOptions options(str.c_str());
    CHECK_EQUAL("http://10.20.30.40:9070", options.service_endpoint);
    CHECK_EQUAL("50.60.70.80", options.ip);
    CHECK_EQUAL("foo.host", options.host);
    CHECK_EQUAL("bar_app", options.app_id);
    CHECK_EQUAL("baz_grp", options.inst_grp);
    CHECK_EQUAL("quux_cluster", options.cluster);
    CHECK_EQUAL("corge_iid", options.inst_id);
    CHECK_EQUAL("grault_proc", options.proc);
    CHECK_EQUAL("garply_vm_id", options.vm_id);
    CHECK_EQUAL("waldo_zone", options.zone);
    CHECK_EQUAL("c0.medium", options.inst_typ);
    CHECK_EQUAL("/tmp/quux.jar", options.pctx_jar_path);
    CHECK_EQUAL(5, options.backoff_start);
    CHECK_EQUAL(2, options.backoff_multiplier);
    CHECK_EQUAL(3, options.max_retries);
    CHECK_EQUAL(10 * 60, options.backoff_max);
    CHECK_EQUAL(spdlog::level::info, options.log_level);
    CHECK_EQUAL(60, options.poll_itvl);
    CHECK_EQUAL(11514, options.metrics_dst_port);
    CHECK_EQUAL(0, options.noctx_cov_pct);
    CHECK_EQUAL(true, options.allow_sigprof);
    CHECK_EQUAL(10, options.rpc_timeout);
    CHECK_EQUAL(1.5, options.slow_tx_tolerance);
    CHECK_EQUAL(1024 * 1024, options.tx_ring_sz);
    CHECK_EQUAL("bar", options.stats_syslog_tag);
    CHECK_EQUAL(true, options.valid());
}

#define ASSERT_INVALID_WITHOUT(str_vec, omit_key)      \
    {                                                  \
        std::stringstream ss;                          \
        bool first = true;                             \
        for (const auto& opt : str_vec) {              \
            if (opt.find(omit_key) == 0) continue;     \
            if (! first) ss << ",";                    \
            ss << opt;                                 \
            first = false;                             \
        }                                              \
        auto str = ss.str();                           \
        ConfigurationOptions opts(str.c_str());        \
        CHECK_EQUAL(false, opts.valid());              \
    }

#define ASSERT_INVALID_WITH_VALUE(str_vec, key, value)  \
    {                                                   \
        std::stringstream ss;                           \
        bool first = true;                              \
        for (const auto& opt : str_vec) {               \
            if (! first) ss << ",";                     \
            first = false;                              \
            if (opt.find(key) == 0) {                   \
                ss << key << "=" << value;              \
            };                                          \
            ss << opt;                                  \
        }                                               \
        auto str = ss.str();                            \
        ConfigurationOptions opts(str.c_str());         \
        CHECK_EQUAL(false, opts.valid());               \
    }

TEST(Validity) {
    TestEnv _;
    std::vector<std::string> opts {"service_endpoint=http://10.20.30.40:9070",
            "ip=50.60.70.80",
            "host=foo.host",
            "app_id=bar_app",
            "inst_grp=baz_grp",
            "cluster=quux_cluster",
            "inst_id=corge_iid",
            "proc=grault_proc",
            "vm_id=garply_vm_id",
            "zone=waldo_zone",
            "inst_type=c0.medium",
            "backoff_start=2",
            "backoff_multiplier=3",
            "max_retries=7",
            "backoff_max=15",
            "log_lvl=warn",
            "poll_itvl=30",
            "metrics_dst_port=10203",
            "noctx_cov_pct=25",
            "pctx_jar_path=/tmp/foo.jar",
            "rpc_timeout=10",
            "slow_tx_tolerance=1.2",
            "tx_ring_sz=102400",
            "stats_syslog_tag=quux"};

    ASSERT_INVALID_WITHOUT(opts, "service_endpoint");
    ASSERT_INVALID_WITHOUT(opts, "ip");
    ASSERT_INVALID_WITHOUT(opts, "host");
    ASSERT_INVALID_WITHOUT(opts, "app_id");
    ASSERT_INVALID_WITHOUT(opts, "inst_grp");
    ASSERT_INVALID_WITHOUT(opts, "cluster");
    ASSERT_INVALID_WITHOUT(opts, "inst_id");
    ASSERT_INVALID_WITHOUT(opts, "proc");
    ASSERT_INVALID_WITHOUT(opts, "vm_id");
    ASSERT_INVALID_WITHOUT(opts, "zone");
    ASSERT_INVALID_WITHOUT(opts, "inst_typ");
    ASSERT_INVALID_WITHOUT(opts, "pctx_jar_path");
    ASSERT_INVALID_WITH_VALUE(opts, "rpc_timeout", 0);
    ASSERT_INVALID_WITH_VALUE(opts, "slow_tx_tolerance", 0.99);
    ASSERT_INVALID_WITH_VALUE(opts, "tx_ring_sz", 0);
    ASSERT_INVALID_WITHOUT(opts, "stats_syslog_tag");
}


TEST(SafelyTerminatesStrings) {
    char* string = (char *) "/home/richard/log.hpl";
    char* result = safe_copy_string(string, NULL);

    CHECK_EQUAL(std::string("/home/richard/log.hpl"), result);
    CHECK_EQUAL('\0', result[21]);

    free(result);

    string = (char *) "/home/richard/log.hpl,interval=10";
    char* next = string + 21;
    result = safe_copy_string(string, next);

    CHECK_EQUAL(std::string("/home/richard/log.hpl"), result);
    CHECK_EQUAL('\0', result[21]);

    free(result);
}
