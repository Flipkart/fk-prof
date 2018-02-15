#ifndef METRIC_FORMATTER_H
#define METRIC_FORMATTER_H

#include "globals.hh"
#include <algorithm>
#include "metrics.hh"
#include "util.hh"
#include <unistd.h>
#include <limits>

namespace MetricFormatter {
    std::string hostname();
    
    class SyslogTsdbFormatter : public medida::reporting::UdpReporter::Formatter {
    private:
        const std::string tsdb_tags;
        const std::string tags;
        const std::string host;
        const std::uint16_t pri_sev;
        
    public:
        SyslogTsdbFormatter(std::string tsdb_tags, std::string tags = "fkprof", std::string host = hostname(), std::uint16_t pri_sev = 128);
        ~SyslogTsdbFormatter();

        typedef medida::reporting::UdpReporter::MetricType MetricType;
        typedef medida::reporting::UdpReporter::PropName PropName;
        typedef medida::reporting::UdpReporter::Unit Unit;
        typedef medida::reporting::UdpReporter::EventType EventType;

        std::string operator()(MetricType type, PropName prop, std::uint64_t val);
        std::string operator()(MetricType type, PropName prop, std::int64_t val);
        std::string operator()(MetricType type, PropName prop, double val);
        std::string operator()(MetricType type, PropName prop, std::uint64_t val, Unit unit);
        std::string operator()(MetricType type, PropName prop, double val, Unit unit);
        std::string operator()(MetricType type, EventType evt_typ, PropName prop, std::uint64_t val, Unit unit);
        std::string operator()(MetricType type, EventType evt_typ, PropName prop, double val, Unit unit);
    };
}

#endif
