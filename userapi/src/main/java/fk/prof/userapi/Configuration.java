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

    private static final String ioWorkerPoolName = "workerpool.io";
    private static final String blockingWorkerPoolName = "workerpool.blocking";

    @NotNull
    @JsonProperty("ip.address")
    private String ipAddress;

    @JsonProperty("vertxOptions")
    private VertxOptions vertxOptions = new VertxOptions();

    @JsonProperty("profile.retention.duration.min")
    private Integer profileRetentionDurationMin = 30;

    @JsonProperty("profileView.retention.duration.min")
    private Integer profileViewRetentionDurationMin = 10;

    @JsonProperty("max.list_profiles.duration.days")
    private Integer maxListProfilesDurationInDays = 7;

    @JsonProperty("profile.load.timeout")
    private Integer profileLoadTimeout = 10000;

    @NotNull
    @JsonProperty("userapiHttpOptions")
    private DeploymentOptions httpVerticleConfig;

    @NotNull
    @Valid
    @JsonProperty("curatorOptions")
    private CuratorConfig curatorConfig;

    @NotNull
    @Valid
    @JsonProperty("vertx.workerpool.io")
    private VertxWorkerPoolConfig ioWorkerPool;

    @NotNull
    @Valid
    @JsonProperty("vertx.workerpool.blocking")
    private VertxWorkerPoolConfig blockingWorkerPool;

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

    @NotNull
    @JsonProperty("maxDepthExpansion")
    private Integer maxDepthExpansion = 8;

    public String getIpAddress() {
        return ipAddress;
    }

    public VertxOptions getVertxOptions() {
        return vertxOptions;
    }

    public Integer getProfileRetentionDurationMin() {
        return profileRetentionDurationMin;
    }

    public Integer getProfileViewRetentionDurationMin() {
        return profileViewRetentionDurationMin;
    }

    public Integer getMaxListProfilesDurationInDays() {
        return maxListProfilesDurationInDays;
    }

    public Integer getProfileLoadTimeout() {
        return profileLoadTimeout;
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

    public CuratorConfig getCuratorConfig() {
        return curatorConfig;
    }

    public VertxWorkerPoolConfig getIoWorkerPool() {
        return ioWorkerPool;
    }

    public Integer getMaxDepthExpansion() {
        return maxDepthExpansion;
    }

    private void setIoWorkerPool(VertxWorkerPoolConfig ioWorkerPool) {
        this.ioWorkerPool = ioWorkerPool;
        this.ioWorkerPool.setName(ioWorkerPoolName);
    }

    public VertxWorkerPoolConfig getBlockingWorkerPool() {
        return blockingWorkerPool;
    }

    private void setBlockingWorkerPool(VertxWorkerPoolConfig blockingWorkerPool) {
        this.blockingWorkerPool = blockingWorkerPool;
        this.blockingWorkerPool.setName(blockingWorkerPoolName);
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

    public static class CuratorConfig {
        @NotNull
        @JsonProperty("connection.url")
        private String connectionUrl;

        @NotNull
        @JsonProperty("namespace")
        private String namespace;

        @NotNull
        @JsonProperty("connection.timeout.ms")
        private Integer connectionTimeoutMs;

        @NotNull
        @JsonProperty("session.timeout.ms")
        private Integer sessionTimeoutMs;

        @NotNull
        @JsonProperty("max.retries")
        private Integer maxRetries;

        public String getConnectionUrl() {
            return connectionUrl;
        }

        public String getNamespace() {
            return namespace;
        }

        public Integer getConnectionTimeoutMs() {
            return connectionTimeoutMs;
        }

        public Integer getSessionTimeoutMs() {
            return sessionTimeoutMs;
        }

        public Integer getMaxRetries() {
            return maxRetries;
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

    public static class VertxWorkerPoolConfig {
        @NotNull
        private String name;

        @NotNull
        @JsonProperty("size")
        private Integer size;

        public String getName() {
            return name;
        }

        // name does not come from external config file
        void setName(String name) {
            this.name = name;
        }

        public Integer getSize() {
            return size;
        }
    }

    @AssertTrue(message = "request timeout must be greater than listObject timeout")
    private boolean isListTimeoutValid() {
        Long requestTimeout = httpConfig.requestTimeout;
        Long ListObjectTimeout = storageConfig.s3Config.listObjectsTimeoutMs;
        return requestTimeout > ListObjectTimeout;
    }
}
