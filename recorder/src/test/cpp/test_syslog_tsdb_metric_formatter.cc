#include "../../main/cpp/metric_formatter.hh"
#include "test.hh"
#include <ctime>

#include <regex>
#include <string>
#include <unistd.h>
#include <limits.h>

std::string time_now() {
    std::time_t t = std::time(nullptr);
    std::tm tm = *std::localtime(&t);
    char buffer[120];
    strftime(buffer, sizeof(buffer),"%b %d %H:%M:%S", &tm);
    return std::string(buffer);
}

std::uint64_t seconds_since_epoch() {
    return std::time(nullptr);
}

#define ASSERT_IS_BETWEEN(start, end, value)    \
    {                                           \
        CHECK(value >= start);                  \
        CHECK(value <= end);                    \
    }

#define TM_AT(name)                                    \
    std::string name##_str = time_now();               \
    std::uint64_t name##_ts = time(nullptr);


struct SyslogTsdbMsg {
    //syslog stuff
    std::uint16_t pri_sev;
    std::string tm;
    std::string host;
    std::string tag;

    //TSDB stuff
    std::uint64_t tsd_ts;
    std::string tsd_metric;
    std::string tsd_value;
    std::string tsd_tags;
};

void parse(const std::string& msg, SyslogTsdbMsg& stm) {
    std::regex r("<(\\d+)>(\\S+ \\d+ \\d+:\\d+:\\d+) (\\S+) (\\S+) (\\d+) (\\S+) (\\d+.?\\d*) (.+)");
    std::smatch m;

    auto match = std::regex_match(msg, m, r);
    assert(match);
    assert(m.size() == 9);

    stm.pri_sev = std::stoi(m[1]); 
    stm.tm = m[2];
    stm.host = m[3];
    stm.tag = m[4];

    stm.tsd_ts = std::stol(m[5]);
    stm.tsd_metric = m[6];
    stm.tsd_value = m[7];
    stm.tsd_tags = m[8];
}

TEST(Parsing_helper) {
    SyslogTsdbMsg stm;
    parse("<128>Mar 15 19:03:13 prod-log-rcvr-bv1-382760 stream.cosmos 1489584793 logsvc.rcvr.rcvd.low 5941 app_and_cluster=elb//NULL/ app_id=elb cluster=/NULL/ app=prod-log zone=in-chennai-1 type=c1.medium group=rcvr-bv1 _version=3.0.11 _collector=cosmos-base host=prod-log-rcvr-bv1-382760", stm);
    
    CHECK_EQUAL(128, stm.pri_sev);
    CHECK_EQUAL("Mar 15 19:03:13", stm.tm);
    CHECK_EQUAL("prod-log-rcvr-bv1-382760", stm.host);
    CHECK_EQUAL("stream.cosmos", stm.tag);
    CHECK_EQUAL(1489584793, stm.tsd_ts);
    CHECK_EQUAL("logsvc.rcvr.rcvd.low", stm.tsd_metric);
    CHECK_EQUAL("5941", stm.tsd_value);
    CHECK_EQUAL("app_and_cluster=elb//NULL/ app_id=elb cluster=/NULL/ app=prod-log zone=in-chennai-1 type=c1.medium group=rcvr-bv1 _version=3.0.11 _collector=cosmos-base host=prod-log-rcvr-bv1-382760", stm.tsd_tags);
}

TEST(SyslogTsdbFormatter__should_format_unitless_uint64) {
    MetricFormatter::SyslogTsdbFormatter stf("quux=corge grault=garply", "foo,bar", "baz.host", 126);

    std::string name = "thermometer";
    stf.set_name(name);

    TM_AT(start);
    auto msg = stf("temp", "gradient", static_cast<std::uint64_t>(100));
    TM_AT(end);

    SyslogTsdbMsg stm;
    parse(msg, stm);

    CHECK_EQUAL(126, stm.pri_sev);
    ASSERT_IS_BETWEEN(start_str, end_str, stm.tm);
    CHECK_EQUAL("baz.host", stm.host);
    CHECK_EQUAL("foo,bar", stm.tag);
    ASSERT_IS_BETWEEN(start_ts, end_ts, stm.tsd_ts);
    CHECK_EQUAL("thermometer.temp.gradient", stm.tsd_metric);
    CHECK_EQUAL("100", stm.tsd_value);
    CHECK_EQUAL("quux=corge grault=garply", stm.tsd_tags);
}

TEST(SyslogTsdbFormatter__should_format_unitless_int64) {
    MetricFormatter::SyslogTsdbFormatter stf("grault=garply", "bar,foo", "quux", 128);

    std::string name = "pressure";
    stf.set_name(name);

    TM_AT(start);
    auto msg = stf("near_nozzle", "differential", static_cast<std::int64_t>(120));
    TM_AT(end);

    SyslogTsdbMsg stm;
    parse(msg, stm);

    CHECK_EQUAL(128, stm.pri_sev);
    ASSERT_IS_BETWEEN(start_str, end_str, stm.tm);
    CHECK_EQUAL("quux", stm.host);
    CHECK_EQUAL("bar,foo", stm.tag);
    ASSERT_IS_BETWEEN(start_ts, end_ts, stm.tsd_ts);
    CHECK_EQUAL("pressure.near_nozzle.differential", stm.tsd_metric);
    CHECK_EQUAL("120", stm.tsd_value);
    CHECK_EQUAL("grault=garply", stm.tsd_tags);
}

TEST(SyslogTsdbFormatter__should_format_unitless_double) {
    MetricFormatter::SyslogTsdbFormatter stf("grault=garply", "bar,foo", "quux", 128);

    std::string name = "pressure";
    stf.set_name(name);

    TM_AT(start);
    auto msg = stf("near_nozzle", "differential", static_cast<double>(120.3));
    TM_AT(end);

    SyslogTsdbMsg stm;
    parse(msg, stm);

    CHECK_EQUAL(128, stm.pri_sev);
    ASSERT_IS_BETWEEN(start_str, end_str, stm.tm);
    CHECK_EQUAL("quux", stm.host);
    CHECK_EQUAL("bar,foo", stm.tag);
    ASSERT_IS_BETWEEN(start_ts, end_ts, stm.tsd_ts);
    CHECK_EQUAL("pressure.near_nozzle.differential", stm.tsd_metric);
    CHECK_EQUAL("120.3", stm.tsd_value);
    CHECK_EQUAL("grault=garply", stm.tsd_tags);
}

TEST(SyslogTsdbFormatter__should_format_uintt64_with_unit) {
    MetricFormatter::SyslogTsdbFormatter stf("grault=garply", "bar,foo", "quux", 128);

    std::string name = "pressure";
    stf.set_name(name);

    TM_AT(start);
    auto msg = stf("near_nozzle", "differential", static_cast<std::uint64_t>(120), "psi");
    TM_AT(end);

    SyslogTsdbMsg stm;
    parse(msg, stm);

    CHECK_EQUAL(128, stm.pri_sev);
    ASSERT_IS_BETWEEN(start_str, end_str, stm.tm);
    CHECK_EQUAL("quux", stm.host);
    CHECK_EQUAL("bar,foo", stm.tag);
    ASSERT_IS_BETWEEN(start_ts, end_ts, stm.tsd_ts);
    CHECK_EQUAL("pressure.near_nozzle.differential.psi", stm.tsd_metric);
    CHECK_EQUAL("120", stm.tsd_value);
    CHECK_EQUAL("grault=garply", stm.tsd_tags);
}

TEST(SyslogTsdbFormatter__should_format_double_with_unit) {
    MetricFormatter::SyslogTsdbFormatter stf("grault=garply", "bar,foo", "quux", 128);

    std::string name = "pressure";
    stf.set_name(name);

    TM_AT(start);
    auto msg = stf("near_nozzle", "differential", static_cast<double>(120.1), "psi");
    TM_AT(end);

    SyslogTsdbMsg stm;
    parse(msg, stm);

    CHECK_EQUAL(128, stm.pri_sev);
    ASSERT_IS_BETWEEN(start_str, end_str, stm.tm);
    CHECK_EQUAL("quux", stm.host);
    CHECK_EQUAL("bar,foo", stm.tag);
    ASSERT_IS_BETWEEN(start_ts, end_ts, stm.tsd_ts);
    CHECK_EQUAL("pressure.near_nozzle.differential.psi", stm.tsd_metric);
    CHECK_EQUAL("120.1", stm.tsd_value);
    CHECK_EQUAL("grault=garply", stm.tsd_tags);
}

TEST(SyslogTsdbFormatter__should_format_uint64_with_unit__and__event_type) {
    MetricFormatter::SyslogTsdbFormatter stf("grault=garply", "bar,foo", "quux", 128);

    std::string name = "temperature";
    stf.set_name(name);

    TM_AT(start);
    auto msg = stf("near_surface", "alcohol_thermometer", "10_min_avg", static_cast<std::uint64_t>(85), "C");
    TM_AT(end);

    SyslogTsdbMsg stm;
    parse(msg, stm);

    CHECK_EQUAL(128, stm.pri_sev);
    ASSERT_IS_BETWEEN(start_str, end_str, stm.tm);
    CHECK_EQUAL("quux", stm.host);
    CHECK_EQUAL("bar,foo", stm.tag);
    ASSERT_IS_BETWEEN(start_ts, end_ts, stm.tsd_ts);
    CHECK_EQUAL("temperature.near_surface.alcohol_thermometer.10_min_avg.C", stm.tsd_metric);
    CHECK_EQUAL("85", stm.tsd_value);
    CHECK_EQUAL("grault=garply", stm.tsd_tags);
}

TEST(SyslogTsdbFormatter__should_format_double_with_unit__and__event_type) {
    MetricFormatter::SyslogTsdbFormatter stf("grault=garply", "bar,foo", "quux", 128);

    std::string name = "temperature";
    stf.set_name(name);

    TM_AT(start);
    auto msg = stf("near_surface", "alcohol_thermometer", "10_min_avg", static_cast<double>(85.7), "C");
    TM_AT(end);

    SyslogTsdbMsg stm;
    parse(msg, stm);

    CHECK_EQUAL(128, stm.pri_sev);
    ASSERT_IS_BETWEEN(start_str, end_str, stm.tm);
    CHECK_EQUAL("quux", stm.host);
    CHECK_EQUAL("bar,foo", stm.tag);
    ASSERT_IS_BETWEEN(start_ts, end_ts, stm.tsd_ts);
    CHECK_EQUAL("temperature.near_surface.alcohol_thermometer.10_min_avg.C", stm.tsd_metric);
    CHECK_EQUAL("85.7", stm.tsd_value);
    CHECK_EQUAL("grault=garply", stm.tsd_tags);
}

TEST(SyslogTsdbFormatter__should_default_everything_sensibly) {
    char buff[HOST_NAME_MAX + 1];
    auto err = gethostname(buff, sizeof(buff));
    assert(err == 0);
    std::string actual_hostname(buff);
    
    MetricFormatter::SyslogTsdbFormatter stf("grault=garply");

    std::string name = "thermometer";
    stf.set_name(name);

    TM_AT(start);
    auto msg = stf("temp", "gradient", static_cast<std::uint64_t>(100));
    TM_AT(end);

    SyslogTsdbMsg stm;
    parse(msg, stm);

    CHECK_EQUAL(128, stm.pri_sev);
    ASSERT_IS_BETWEEN(start_str, end_str, stm.tm);
    CHECK_EQUAL(actual_hostname, stm.host);
    CHECK_EQUAL("fkprof", stm.tag);
    ASSERT_IS_BETWEEN(start_ts, end_ts, stm.tsd_ts);
    CHECK_EQUAL("thermometer.temp.gradient", stm.tsd_metric);
    CHECK_EQUAL("100", stm.tsd_value);
    CHECK_EQUAL("grault=garply", stm.tsd_tags);
}

