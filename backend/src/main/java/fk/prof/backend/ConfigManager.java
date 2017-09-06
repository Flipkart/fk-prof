package fk.prof.backend;

import com.google.common.base.Preconditions;
import com.google.common.io.Files;
import io.vertx.core.json.JsonObject;

import javax.validation.ConstraintViolation;
import javax.validation.Validation;
import javax.validation.Validator;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Properties;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Map configuration to POJO and encapsulate default settings inside it
 * Use this POJO to access config and remove jsonobject getters from rest of the code base
 */
public class ConfigManager {

  private static Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

  private static final String LOGFACTORY_SYSTEM_PROPERTY_KEY = "vertx.logger-delegate-factory-class-name";
  private static final String LOGFACTORY_SYSTEM_PROPERTY_DEFAULT_VALUE = "io.vertx.core.logging.SLF4JLogDelegateFactory";

  public static final String METRIC_REGISTRY = "backend-metric-registry";

  public static Configuration loadConfig(String configFilePath) throws IOException {
    Preconditions.checkNotNull(configFilePath);
    JsonObject json = new JsonObject(Files.toString(new File(configFilePath), StandardCharsets.UTF_8));
    return loadConfig(json);
  }

  public static Configuration loadConfig(JsonObject jsonConfig) {
    Preconditions.checkNotNull(jsonConfig);
    Configuration config = jsonConfig.mapTo(Configuration.class);
    validateConfig(config);
    return config;
  }

  public static void setDefaultSystemProperties() {
    Properties properties = System.getProperties();
    properties.putIfAbsent(ConfigManager.LOGFACTORY_SYSTEM_PROPERTY_KEY, ConfigManager.LOGFACTORY_SYSTEM_PROPERTY_DEFAULT_VALUE);
    properties.putIfAbsent("vertx.metrics.options.enabled", true);
  }

  public static <T> void validateConfig(T config) {
    Set<ConstraintViolation<T>> violations = validator.validate(config);
    if (violations.size() > 0) {
      String message = "Configuration is invalid:\n" +
          String.join("\n",
              violations.stream().map(v -> v.getPropertyPath().toString() + " " + v.getMessage()).collect(Collectors.toList()));
      throw new RuntimeException(message);
    }
  }
}
