package fk.prof.userapi;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.vertx.core.DeploymentOptions;
import io.vertx.core.VertxOptions;
import io.vertx.core.json.JsonObject;

import javax.validation.Valid;
import javax.validation.constraints.AssertTrue;
import javax.validation.constraints.NotNull;
import java.util.Map;

/**
 * Created by gaurav.ashok on 15/05/17.
 */
public class Configuration {

    @JsonProperty("vertxOptions")
    private VertxOptions vertxOptions = new VertxOptions();

    @JsonProperty("profile.retention.duration.min")
    private Integer profileRetentionDurationMin = 30;

    @JsonProperty("max.list_profiles.duration.days")
    private Integer maxListProfilesDurationInDays = 7;

    @JsonProperty("profile.load.timeout")
    private Integer profileLoadTimeout = 10000;

    @JsonProperty("vertx.worker.pool.size")
    private Integer vertxWorkerPoolSize;

    @NotNull
    @JsonProperty("userapiHttpOptions")
    private DeploymentOptions httpVerticleConfig;

    @NotNull
    @Valid
    @JsonIgnore
    private HttpConfig httpConfig;

    @NotNull
    @Valid
    @JsonProperty("storage")
    private StorageConfig storageConfig;

    @NotNull
    @JsonProperty("aggregatedProfiles.baseDir")
    private String profilesBaseDir;

    public VertxOptions getVertxOptions() {
        return vertxOptions;
    }

    public Integer getProfileRetentionDurationMin() {
        return profileRetentionDurationMin;
    }

    public Integer getMaxListProfilesDurationInDays() {
        return maxListProfilesDurationInDays;
    }

    public Integer getProfileLoadTimeout() {
        return profileLoadTimeout;
    }

    public Integer getVertxWorkerPoolSize() {
        return vertxWorkerPoolSize;
    }

    public DeploymentOptions getHttpVerticleConfig() {
        return httpVerticleConfig;
    }

    public HttpConfig getHttpConfig() {
        return httpConfig;
    }

    public StorageConfig getStorageConfig() {
        return storageConfig;
    }

    public String getProfilesBaseDir() {
        return profilesBaseDir;
    }

    private void setVertxOptions(Map<String, Object> vertxOptionsMap) {
        this.vertxOptions = new VertxOptions(new JsonObject(vertxOptionsMap));
    }

    private void setHttpVerticleConfig(Map<String, Object> httpVerticleConfigMap) {
        httpVerticleConfig = new DeploymentOptions(new JsonObject(httpVerticleConfigMap));
        httpConfig = httpVerticleConfig.getConfig().mapTo(HttpConfig.class);
    }

    public static class HttpConfig {
        @NotNull
        @JsonProperty("verticle.count")
        private Integer verticleCount;

        @NotNull
        @JsonProperty("http.port")
        private Integer httpPort;

        @NotNull
        @JsonProperty("req.timeout")
        private Long requestTimeout;

        public Integer getVerticleCount() {
            return verticleCount;
        }

        public Integer getHttpPort() {
            return httpPort;
        }

        public Long getRequestTimeout() {
            return requestTimeout;
        }
    }

    public static class StorageConfig {
        @NotNull
        @Valid
        @JsonProperty("s3")
        private S3Config s3Config;

        @NotNull
        @Valid
        @JsonProperty("thread.pool")
        private FixedSizeThreadPoolConfig tpConfig;

        public S3Config getS3Config() {
            return s3Config;
        }

        public FixedSizeThreadPoolConfig getTpConfig() {
            return tpConfig;
        }
    }

    public static class S3Config {
        @NotNull
        private String endpoint;

        @NotNull
        @JsonProperty("access.key")
        private String accessKey;

        @NotNull
        @JsonProperty("secret.key")
        private String secretKey;

        @NotNull
        @JsonProperty("list.objects.timeout.ms")
        private Long listObjectsTimeoutMs;

        public String getEndpoint() {
            return endpoint;
        }

        public String getAccessKey() {
            return accessKey;
        }

        public String getSecretKey() {
            return secretKey;
        }

        public Long getListObjectsTimeoutMs() {
            return listObjectsTimeoutMs;
        }
    }

    public static class FixedSizeThreadPoolConfig {
        @NotNull
        @JsonProperty("coresize")
        private Integer coreSize;

        @NotNull
        @JsonProperty("maxsize")
        private Integer maxSize;

        @NotNull
        @JsonProperty("idletime.secs")
        private Integer idleTimeSec;

        @NotNull
        @JsonProperty("queue.maxsize")
        private Integer queueMaxSize;

        public Integer getCoreSize() {
            return coreSize;
        }

        public Integer getMaxSize() {
            return maxSize;
        }

        public Integer getIdleTimeSec() {
            return idleTimeSec;
        }

        public Integer getQueueMaxSize() {
            return queueMaxSize;
        }
    }

    @AssertTrue(message = "request timeout must be greater than listObject timeout")
    private boolean isListTimeoutValid() {
        Long requestTimeout = httpConfig.requestTimeout;
        Long ListObjectTimeout = storageConfig.s3Config.listObjectsTimeoutMs;
        return requestTimeout > ListObjectTimeout;
    }
}
