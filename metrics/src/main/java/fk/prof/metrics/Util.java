package fk.prof.metrics;

import com.codahale.metrics.*;

public class Util {

  public static final String METRIC_REGISTRY_PROPERTY = "app_metric_registry";
  private static String metricRegistryName = null;

  public static String encodeTags(String... tags) {
    StringBuilder result = new StringBuilder();
    boolean first = true;
    for(String tag: tags) {
      if(!first) {
        result.append('_');
      }
      result.append(encodeTag(tag));
      first = false;
    }
    return result.toString();
  }

  private static String encodeTag(String tag) {
    if(tag == null) {
      return null;
    }

    StringBuilder result = new StringBuilder();
    for(int i = 0; i < tag.length(); i++) {
      char ch = tag.charAt(i);
      int ascii = (int) ch;
      if(ascii > 255) {
        // Dropping char outside standard ASCII range, because we use 2-length hex code for encoding.
        // Beyond 255, it takes more than 2-length hex code to uniquely identify a character.
        continue;
      }
      if((ascii >= 48 && ascii <= 57) || (ascii >= 65 && ascii <= 90) || (ascii >= 97 && ascii <= 122)) {
        result.append(ch);
      } else {
        result.append(String.format(".%02X", ascii));
      }
    }
    return result.toString();
  }

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

  public static void gauge(String name, Gauge gauge) {
    MetricRegistry registry = getMetricRegistry();
    if(!registry.getGauges().containsKey(name)) {
      registry.register(name, gauge);
    }
  }

  public static MetricRegistry getMetricRegistry() {
    // get registry name from system properties
    if(metricRegistryName == null || metricRegistryName.isEmpty()) {
      metricRegistryName = System.getProperty(METRIC_REGISTRY_PROPERTY);
      if(metricRegistryName == null || metricRegistryName.isEmpty()) {
        metricRegistryName = "metric-registry";
      }
    }

    return SharedMetricRegistries.getOrCreate(metricRegistryName);
  }
}
