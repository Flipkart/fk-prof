package fk.prof.userapi.util;

import com.codahale.metrics.*;
import fk.prof.userapi.UserapiConfigManager;

/**
 * Created by gaurav.ashok on 16/08/17.
 */
public class MetricsUtil {

    public static Meter meter(String name, String... names) {
        return getMetricRegistry().meter(MetricRegistry.name(name, names));
    }

    public static Timer timer(String name, String... names) {
        return getMetricRegistry().timer(MetricRegistry.name(name, names));
    }

    public static Histogram histogram(String name, String... names) {
        return getMetricRegistry().histogram(MetricRegistry.name(name, names));
    }

    public static Counter counter(String name, String... names) {
        return getMetricRegistry().counter(MetricRegistry.name(name, names));
    }

    public static void gauage(String name, Gauge gauge) {
        getMetricRegistry().register(name, gauge);
    }

    public static MetricRegistry getMetricRegistry() {
        return SharedMetricRegistries.getOrCreate(UserapiConfigManager.METRIC_REGISTRY);
    }
}
