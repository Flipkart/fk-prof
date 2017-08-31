package fk.prof.userapi;

import com.codahale.metrics.InstrumentedExecutorService;
import com.codahale.metrics.Meter;
import com.codahale.metrics.MetricRegistry;
import com.codahale.metrics.SharedMetricRegistries;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategy;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jdk8.Jdk8Module;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.google.common.base.Preconditions;
import fk.prof.storage.AsyncStorage;
import fk.prof.storage.S3AsyncStorage;
import fk.prof.storage.S3ClientFactory;
import fk.prof.userapi.api.ProfileStoreAPI;
import fk.prof.userapi.api.ProfileStoreAPIImpl;
import fk.prof.userapi.deployer.VerticleDeployer;
import fk.prof.userapi.deployer.impl.UserapiHttpVerticleDeployer;
import fk.prof.userapi.http.UserapiApiPathConstants;
import fk.prof.userapi.model.json.ProtoSerializers;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.VertxOptions;
import io.vertx.core.json.Json;
import io.vertx.core.logging.Logger;
import io.vertx.core.logging.LoggerFactory;
import io.vertx.core.metrics.MetricsOptions;
import io.vertx.ext.dropwizard.DropwizardMetricsOptions;
import io.vertx.ext.dropwizard.Match;
import io.vertx.ext.dropwizard.MatchType;

import java.util.concurrent.*;

public class UserapiManager {
    private static Logger logger = LoggerFactory.getLogger(UserapiManager.class);

    private final Vertx vertx;
    private final Configuration config;
    private AsyncStorage storage;
    private MetricRegistry metricRegistry;

    public UserapiManager(String configFilePath) throws Exception {
        this(UserapiConfigManager.loadConfig(configFilePath));
    }

    public UserapiManager(Configuration config) throws Exception {
        UserapiConfigManager.setDefaultSystemProperties();
        this.config = Preconditions.checkNotNull(config);

        VertxOptions vertxOptions = config.getVertxOptions();
        vertxOptions.setMetricsOptions(buildMetricsOptions());
        this.vertx = Vertx.vertx(vertxOptions);
        this.metricRegistry = SharedMetricRegistries.getOrCreate(UserapiConfigManager.METRIC_REGISTRY);

        initStorage();
    }

    public Future<Void> close() {
        Future future = Future.future();
        vertx.close(closeResult -> {
            if (closeResult.succeeded()) {
                logger.info("Shutdown successful for vertx instance");
                future.complete();
            } else {
                logger.error("Error shutting down vertx instance");
                future.fail(closeResult.cause());
            }
        });

        return future;
    }

    public Future<Void> launch() {
        Future<Void> result = Future.future();
        // register serializers
        registerSerializers(Json.mapper);
        registerSerializers(Json.prettyMapper);

        ProfileStoreAPI profileStoreAPI = new ProfileStoreAPIImpl(vertx, this.storage, config.getProfileRetentionDurationMin(), config.getProfileLoadTimeout(), config.getVertxWorkerPoolSize());
        VerticleDeployer userapiHttpVerticleDeployer = new UserapiHttpVerticleDeployer(vertx, config, profileStoreAPI);

        userapiHttpVerticleDeployer.deploy().setHandler(verticleDeployCompositeResult -> {
            if (verticleDeployCompositeResult.succeeded()) {
                result.complete();
            } else {
                result.fail(verticleDeployCompositeResult.cause());
            }
        });

        return result;
    }

    private void registerSerializers(ObjectMapper mapper) {
        // protobuf
        ProtoSerializers.registerSerializers(mapper);

        // java 8, datetime
        mapper.registerModule(new Jdk8Module());
        mapper.registerModule(new JavaTimeModule());
        mapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        mapper.setPropertyNamingStrategy(PropertyNamingStrategy.SNAKE_CASE);
    }

    private void initStorage() {
        Configuration.FixedSizeThreadPoolConfig threadPoolConfig = config.getStorageConfig().getTpConfig();
        Meter threadPoolRejectionsMtr = metricRegistry.meter(MetricRegistry.name(AsyncStorage.class, "threadpool.rejections"));

        // thread pool with bounded queue for s3 io.
        BlockingQueue ioTaskQueue = new LinkedBlockingQueue(threadPoolConfig.getQueueMaxSize());
        ExecutorService storageExecSvc = new InstrumentedExecutorService(
            new ThreadPoolExecutor(threadPoolConfig.getCoreSize(), threadPoolConfig.getMaxSize(), threadPoolConfig.getIdleTimeSec(), TimeUnit.SECONDS, ioTaskQueue,
                new AbortPolicy("storageExectorSvc", threadPoolRejectionsMtr)),
            metricRegistry, "executors.fixed_thread_pool.storage");

        Configuration.S3Config s3Config = config.getStorageConfig().getS3Config();
        this.storage = new S3AsyncStorage(S3ClientFactory.create(s3Config.getEndpoint(), s3Config.getAccessKey(), s3Config.getSecretKey()),
            storageExecSvc, s3Config.getListObjectsTimeoutMs());
    }


    private MetricsOptions buildMetricsOptions() {
        return new DropwizardMetricsOptions()
            .setEnabled(true)
            .setJmxEnabled(true)
            .setRegistryName(UserapiConfigManager.METRIC_REGISTRY)
            .addMonitoredHttpServerUri(new Match().setValue(UserapiApiPathConstants.APPS + ".*").setAlias(UserapiApiPathConstants.APPS).setType(MatchType.REGEX))
            .addMonitoredHttpServerUri(new Match().setValue(UserapiApiPathConstants.CLUSTER_GIVEN_APPID + ".*").setAlias(UserapiApiPathConstants.CLUSTER_GIVEN_APPID).setType(MatchType.REGEX))
            .addMonitoredHttpServerUri(new Match().setValue(UserapiApiPathConstants.PROC_GIVEN_APPID_CLUSTERID + ".*").setAlias(UserapiApiPathConstants.PROC_GIVEN_APPID_CLUSTERID).setType(MatchType.REGEX))
            .addMonitoredHttpServerUri(new Match().setValue(UserapiApiPathConstants.PROFILES_GIVEN_APPID_CLUSTERID_PROCID).setAlias(UserapiApiPathConstants.PROFILE_GIVEN_APPID_CLUSTERID_PROCID_WORKTYPE_TRACENAME).setType(MatchType.REGEX))
            .addMonitoredHttpServerUri(new Match().setValue(UserapiApiPathConstants.PROFILE_GIVEN_APPID_CLUSTERID_PROCID_WORKTYPE_TRACENAME).setAlias(UserapiApiPathConstants.PROFILE_GIVEN_APPID_CLUSTERID_PROCID_WORKTYPE_TRACENAME).setType(MatchType.REGEX));
    }

    public static class AbortPolicy implements RejectedExecutionHandler {

        private String forExecutorSvc;
        private Meter meter;

        public AbortPolicy(String forExecutorSvc, Meter meter) {
            this.forExecutorSvc = forExecutorSvc;
            this.meter = meter;
        }

        public void rejectedExecution(Runnable r, ThreadPoolExecutor e) {
            meter.mark();
            throw new RejectedExecutionException("Task rejected from " + forExecutorSvc);
        }
    }
}
