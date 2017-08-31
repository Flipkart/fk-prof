#ifndef METRICS_H
#define METRICS_H

#include <medida/medida.h>

namespace metrics {
    typedef medida::Timer Timer;
    typedef medida::Histogram Hist;
    typedef medida::Value Value;
    typedef medida::Counter Ctr;
    typedef medida::Meter Mtr;
};

#define METRICS_DOMAIN "fkpr"
#define METRICS_TYPE_RPC "rpc"
#define METRICS_TYPE_WAIT "wait"
#define METRICS_TYPE_SZ "sz"
#define METRICS_TYPE_STATE "state"
#define METRICS_TYPE_LOCK "lock"
#define METRICS_TYPE_OP "op"

medida::MetricsRegistry& get_metrics_registry();

#endif
