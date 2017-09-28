package fk.prof.backend;

import com.fasterxml.jackson.annotation.JsonProperty;
import fk.prof.backend.leader.election.KillBehavior;
import io.vertx.core.DeploymentOptions;
import io.vertx.core.VertxOptions;
import io.vertx.core.http.HttpServerOptions;
import io.vertx.core.json.JsonObject;

import javax.validation.Valid;
import javax.validation.constraints.NotNull;
import java.util.Map;

/**
 * Created by gaurav.ashok on 21/05/17.
 */
public class Configuration {
    @NotNull
    @JsonProperty("ip.address")
    private String ipAddress;

    @NotNull
    @JsonProperty("backend.version")
    private Integer backendVersion;

    @NotNull
    @JsonProperty("backend.id")
    private Integer backendId;

    @NotNull
    @JsonProperty("load.report.interval.secs")
    private Integer loadReportItvlSecs;

    @NotNull
    @JsonProperty("recorder.defunct.threshold.secs")
    private Integer recorderDefunctThresholdSecs;

    @NotNull
    @JsonProperty("slot.pool.capacity")
    private Integer scheduleSlotPoolCapacity;

    @NotNull
    @Valid
    @JsonProperty("backend.http.server")
    private HttpServerOptions backendHttpServerOpts;

    private void setBackendHttpServerOpts(Map<String, Object> backendHttpServerCfgMap) {
        this.backendHttpServerOpts = toHttpServerOptions(backendHttpServerCfgMap);
    }

    @NotNull
    @Valid
    @JsonProperty("leader.http.server")
    private HttpServerOptions leaderHttpServerOpts;

    private void setLeaderHttpServerOpts(Map<String, Object> leaderHttpServerCfgMap) {
        this.leaderHttpServerOpts = toHttpServerOptions(leaderHttpServerCfgMap);
    }

    @NotNull
    @Valid
    @JsonProperty("http.client")
    private HttpClientConfig httpClientConfig;

    @NotNull
    @JsonProperty("vertxOptions")
    private VertxOptions vertxOptions = new VertxOptions();

    private void setVertxOptions(Map<String, Object> vertxOptionsMap) {
        this.vertxOptions = new VertxOptions(new JsonObject(vertxOptionsMap));
    }

    @NotNull
    @JsonProperty("backendHttpOptions")
    private DeploymentOptions backendDeploymentOpts;

    @NotNull
    @Valid
    private BackendHttpVerticleConfig backendHttpVerticleConfig;

    private void setBackendDeploymentOpts(Map<String, Object> backendDeploymentOptsMap) {
        this.backendDeploymentOpts = toDeploymentOptions(backendDeploymentOptsMap);
        this.backendHttpVerticleConfig = toVerticleConfig(this.backendDeploymentOpts, BackendHttpVerticleConfig.class);
    }

    @NotNull
    private DeploymentOptions leaderDeploymentOpts = new DeploymentOptions().setConfig(new JsonObject());

    @NotNull
    @JsonProperty("leaderElectionOptions")
    private DeploymentOptions leaderElectionDeploymentOpts;

    @NotNull
    @Valid
    private LeaderElectionVerticleConfig leaderElectionVerticleConfig;

    private void setLeaderElectionDeploymentOpts(Map<String, Object> leaderElectionDeploymentOptsMap) {
        this.leaderElectionDeploymentOpts = toDeploymentOptions(leaderElectionDeploymentOptsMap);
        this.leaderElectionVerticleConfig = toVerticleConfig(this.leaderElectionDeploymentOpts, LeaderElectionVerticleConfig.class);
    }

    @NotNull
    @Valid
    @JsonProperty("curatorOptions")
    private CuratorConfig curatorConfig;

    @NotNull
    @Valid
    @JsonProperty("backendAssociations")
    private BackendAssociationsConfig associationsConfig;

    @NotNull
    @JsonProperty("daemonOptions")
    private DeploymentOptions daemonDeploymentOpts;

    @NotNull
    @Valid
    private DaemonVerticleConfig daemonVerticleConfig;

    private void setDaemonDeploymentOpts(Map<String, Object> daemonDeploymentOptsMap) {
        this.daemonDeploymentOpts = toDeploymentOptions(daemonDeploymentOptsMap);
        //Backend daemon should never be deployed more than once, so hardcoding verticle count to 1, to protect from illegal configuration
        this.daemonDeploymentOpts.getConfig().put("verticle.count", 1);
        this.daemonVerticleConfig = toVerticleConfig(this.daemonDeploymentOpts, DaemonVerticleConfig.class);
    }

    @NotNull
    @Valid
    @JsonProperty("serializationWorkerPool")
    private SerializationWorkerPoolConfig serializationWorkerPoolConfig;

    @NotNull
    @Valid
    @JsonProperty("storage")
    private StorageConfig storageConfig;

    @NotNull
    @Valid
    @JsonProperty("bufferPoolOptions")
    private BufferPoolConfig bufferPoolConfig;

    @NotNull
    @JsonProperty("aggregatedProfiles.baseDir")
    private String profilesBaseDir;

    public String getIpAddress() {
        return ipAddress;
    }

    public Integer getBackendVersion() {
        return backendVersion;
    }

    public Integer getBackendId() {
        return backendId;
    }

    public Integer getLoadReportItvlSecs() {
        return loadReportItvlSecs;
    }

    public Integer getRecorderDefunctThresholdSecs() {
        return recorderDefunctThresholdSecs;
    }

    public Integer getScheduleSlotPoolCapacity() {
        return scheduleSlotPoolCapacity;
    }

    public HttpServerOptions getBackendHttpServerOpts() {
        return backendHttpServerOpts;
    }

    public HttpServerOptions getLeaderHttpServerOpts() {
        return leaderHttpServerOpts;
    }

    public HttpClientConfig getHttpClientConfig() {
        return httpClientConfig;
    }

    public VertxOptions getVertxOptions() {
        return vertxOptions;
    }

    public DeploymentOptions getBackendDeploymentOpts() {
        return backendDeploymentOpts;
    }

    public BackendHttpVerticleConfig getBackendHttpVerticleConfig() {
        return backendHttpVerticleConfig;
    }

    public DeploymentOptions getLeaderDeploymentOpts() {
        return leaderDeploymentOpts;
    }

    public DeploymentOptions getLeaderElectionDeploymentOpts() {
        return leaderElectionDeploymentOpts;
    }

    public LeaderElectionVerticleConfig getLeaderElectionVerticleConfig() {
        return leaderElectionVerticleConfig;
    }

    public CuratorConfig getCuratorConfig() {
        return curatorConfig;
    }

    public BackendAssociationsConfig getAssociationsConfig() {
        return associationsConfig;
    }

    public DeploymentOptions getDaemonDeploymentOpts() {
        return daemonDeploymentOpts;
    }

    public DaemonVerticleConfig getDaemonVerticleConfig() {
        return daemonVerticleConfig;
    }

    public SerializationWorkerPoolConfig getSerializationWorkerPoolConfig() {
        return serializationWorkerPoolConfig;
    }

    public StorageConfig getStorageConfig() {
        return storageConfig;
    }

    public BufferPoolConfig getBufferPoolConfig() {
        return bufferPoolConfig;
    }

    public String getProfilesBaseDir() {
        return profilesBaseDir;
    }

    public static class HttpClientConfig {
        @JsonProperty("connect.timeout.ms")
        private Integer connectTimeoutMs = 5000;

        @JsonProperty("idle.timeout.secs")
        private Integer idleTimeoutSecs = 120;

        @JsonProperty("max.attempts")
        private Integer maxAttempts = 3;

        @JsonProperty("keepalive")
        private Boolean keepAlive = true;

        @JsonProperty("compression")
        private Boolean supportCompression = true;

        public Integer getConnectTimeoutMs() {
            return connectTimeoutMs;
        }

        public Integer getIdleTimeoutSecs() {
            return idleTimeoutSecs;
        }

        public Integer getMaxAttempts() {
            return maxAttempts;
        }

        public Boolean getKeepAlive() {
            return keepAlive;
        }

        public Boolean getSupportCompression() {
            return supportCompression;
        }
    }

    public static class BackendHttpVerticleConfig {
        @NotNull
        @JsonProperty("verticle.count")
        private Integer verticleCount;

        @NotNull
        @JsonProperty("report.load")
        private Boolean reportLoad;

        @NotNull
        @Valid
        @JsonProperty("parser")
        private ParserConfig parserConfig;

        public Integer getVerticleCount() {
            return verticleCount;
        }

        public Boolean getReportLoad() {
            return reportLoad;
        }

        public ParserConfig getParserConfig() {
            return parserConfig;
        }

        public static class ParserConfig {
            @NotNull
            @JsonProperty("recordingheader.max.bytes")
            private Integer recordingHeaderMaxSizeBytes;

            @NotNull
            @JsonProperty("wse.max.bytes")
            private Integer wseMaxSizeBytes;

            public Integer getRecordingHeaderMaxSizeBytes() {
                return recordingHeaderMaxSizeBytes;
            }

            public Integer getWseMaxSizeBytes() {
                return wseMaxSizeBytes;
            }
        }
    }

    public static class LeaderElectionVerticleConfig {
        @NotNull
        @JsonProperty("aggregation.enabled")
        private Boolean aggregationEnabled;

        @NotNull
        @JsonProperty("leader.watching.path")
        private String leaderWatchPath;

        @NotNull
        @JsonProperty("leader.mutex.path")
        private String leaderMutexPath;

        @NotNull
        @JsonProperty("kill.behavior")
        private KillBehavior killBehaviour;

        public Boolean getAggregationEnabled() {
            return aggregationEnabled;
        }

        public String getLeaderWatchPath() {
            return leaderWatchPath;
        }

        public String getLeaderMutexPath() {
            return leaderMutexPath;
        }

        public KillBehavior getKillBehaviour() {
            return killBehaviour;
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
        private Integer sessionTineoutMs;

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

        public Integer getSessionTineoutMs() {
            return sessionTineoutMs;
        }

        public Integer getMaxRetries() {
            return maxRetries;
        }
    }

    public static class BackendAssociationsConfig {
        @NotNull
        @JsonProperty("backend.association.path")
        private String associationPath;

        @NotNull
        @JsonProperty("load.miss.tolerance")
        private Integer loadMissTolerance;

        public String getAssociationPath() {
            return associationPath;
        }

        public Integer getLoadMissTolerance() {
            return loadMissTolerance;
        }
    }

    public static class DaemonVerticleConfig {
        @NotNull
        @JsonProperty("aggregation.window.duration.secs")
        private Integer aggrWindowDurationSecs;

        @NotNull
        @JsonProperty("aggregation.window.end.tolerance.secs")
        private Integer aggrWindowEndToleranceSecs;

        @NotNull
        @JsonProperty("policy.refresh.offset.secs")
        private Integer policyRefreshOffsetSecs;

        @NotNull
        @JsonProperty("scheduling.buffer.secs")
        private Integer schedulingBufferSecs;

        @NotNull
        @JsonProperty("work.assignment.max.delay.secs")
        private Integer workAssignmentMaxDelaySecs;

        @NotNull
        @JsonProperty("verticle.count")
        private Integer verticleCount;

        public Integer getAggrWindowDurationSecs() {
            return aggrWindowDurationSecs;
        }

        public Integer getAggrWindowEndToleranceSecs() {
            return aggrWindowEndToleranceSecs;
        }

        public Integer getPolicyRefreshOffsetSecs() {
            return policyRefreshOffsetSecs;
        }

        public Integer getSchedulingBufferSecs() {
            return schedulingBufferSecs;
        }

        public Integer getWorkAssignmentMaxDelaySecs() {
            return workAssignmentMaxDelaySecs;
        }

        public Integer getVerticleCount() {
            return verticleCount;
        }
    }

    public static class SerializationWorkerPoolConfig {
        @NotNull
        @JsonProperty("size")
        private Integer size;

        @NotNull
        @JsonProperty("timeout.secs")
        private Integer timeoutSecs;

        public Integer getSize() {
            return size;
        }

        public Integer getTimeoutSecs() {
            return timeoutSecs;
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

        public static class S3Config {
            @NotNull
            @JsonProperty("endpoint")
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
    }

    public static class BufferPoolConfig {
        @NotNull
        @JsonProperty("max.total")
        private Integer maxTotal;

        @NotNull
        @JsonProperty("max.idle")
        private Integer maxIdle;

        @NotNull
        @JsonProperty("buffer.size")
        private Integer bufferSize;

        public Integer getMaxTotal() {
            return maxTotal;
        }

        public Integer getMaxIdle() {
            return maxIdle;
        }

        public Integer getBufferSize() {
            return bufferSize;
        }
    }

    private static DeploymentOptions toDeploymentOptions(Map<String, Object> map) {
        if (map != null) {
            return new DeploymentOptions(new JsonObject(map));
        }
        return null;
    }

    private static <T> T toVerticleConfig(DeploymentOptions deploymentOpts, Class<T> clazz) {
        if (deploymentOpts != null) {
            return deploymentOpts.getConfig().mapTo(clazz);
        }
        return null;
    }

    private static HttpServerOptions toHttpServerOptions(Map<String, Object> map) {
        if(map != null) {
            return new HttpServerOptions(new JsonObject(map));
        }
        return null;
    }
}
