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
import fk.prof.userapi.api.*;
import fk.prof.userapi.cache.ClusteredProfileCache;
import fk.prof.userapi.deployer.impl.UserapiHttpVerticleDeployer;
import fk.prof.userapi.model.json.CustomSerializers;
import fk.prof.userapi.model.json.ProtoSerializers;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.VertxOptions;
import io.vertx.core.WorkerExecutor;
import io.vertx.core.json.Json;
import io.vertx.core.logging.Logger;
import io.vertx.core.logging.LoggerFactory;
import io.vertx.core.metrics.MetricsOptions;
import io.vertx.ext.dropwizard.DropwizardMetricsOptions;
import io.vertx.ext.dropwizard.Match;
import io.vertx.ext.dropwizard.MatchType;
import org.apache.curator.framework.CuratorFramework;
import org.apache.curator.framework.CuratorFrameworkFactory;
import org.apache.curator.retry.ExponentialBackoffRetry;

import java.util.concurrent.*;

import static fk.prof.userapi.http.UserapiApiPathConstants.*;

public class UserapiManager {
    private static final Logger logger = LoggerFactory.getLogger(UserapiManager.class);

    private final Vertx vertx;
    private final Configuration config;
    private final CuratorFramework curatorClient;

    public UserapiManager(String configFilePath) throws Exception {
        this(UserapiConfigManager.loadConfig(configFilePath));
    }

    public UserapiManager(Configuration config) throws Exception {
        UserapiConfigManager.setDefaultSystemProperties();
        this.config = Preconditions.checkNotNull(config);

        VertxOptions vertxOptions = config.getVertxOptions();
        vertxOptions.setMetricsOptions(buildMetricsOptions());
        this.vertx = Vertx.vertx(vertxOptions);

        this.curatorClient = createCuratorClient();
    }

    public Future<Void> close() {
        Future future = Future.future();
        vertx.close(closeResult -> {
            curatorClient.close();
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

    Future<Void> launch() {
        // register serializers
        registerSerializers(Json.mapper);
        registerSerializers(Json.prettyMapper);

        SharedMetricRegistries.getOrCreate(UserapiConfigManager.METRIC_REGISTRY);

        WorkerExecutor workerExecutor = vertx.createSharedWorkerExecutor(
            config.getBlockingWorkerPool().getName(), config.getBlockingWorkerPool().getSize());

        // its non blocking as of now.
        AsyncStorage storage = initStorage();

        ProfileLoader profileLoader = new StorageBackedProfileLoader(storage);

        // curator client is initialized later on. currently cache only saves the reference in its constructor.
        ClusteredProfileCache profileCache = new ClusteredProfileCache(curatorClient,
            profileLoader,
            new ProfileViewCreator(),
            workerExecutor,
            config);

        return initCuratorAsync(workerExecutor)
            .compose(v -> profileCache.onClusterJoin())
            .compose(v -> {
                ProfileStoreAPI profileStoreAPI = new ProfileStoreAPIImpl(vertx,
                    storage,
                    profileLoader,
                    profileCache,
                    workerExecutor,
                    config);

                return new UserapiHttpVerticleDeployer(vertx, config, profileStoreAPI).deploy();
            })
            .mapEmpty();
    }

    private Future<Void> initCuratorAsync(WorkerExecutor we) {
        Future<Void> future = Future.future();
        we.executeBlocking(f -> {
            try {
                curatorClient.start();
                curatorClient.blockUntilConnected(config.getCuratorConfig().getConnectionTimeoutMs(), TimeUnit.MILLISECONDS);
                f.complete();
            } catch (Exception e) {
                f.fail(e);
            }
        }, future.completer());
        return future;
    }

    private void registerSerializers(ObjectMapper mapper) {
        // protobuf
        ProtoSerializers.registerSerializers(mapper);
        CustomSerializers.registerSerializers(mapper);

        // java 8, datetime
        mapper.registerModule(new Jdk8Module());
        mapper.registerModule(new JavaTimeModule());
        mapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        mapper.setPropertyNamingStrategy(PropertyNamingStrategy.SNAKE_CASE);
    }

    private CuratorFramework createCuratorClient() {
        Configuration.CuratorConfig curatorConfig = config.getCuratorConfig();
        return CuratorFrameworkFactory.builder()
            .connectString(curatorConfig.getConnectionUrl())
            .retryPolicy(new ExponentialBackoffRetry(1000, curatorConfig.getMaxRetries()))
            .connectionTimeoutMs(curatorConfig.getConnectionTimeoutMs())
            .sessionTimeoutMs(curatorConfig.getSessionTimeoutMs())
            .namespace(curatorConfig.getNamespace())
            .build();
    }

    private S3AsyncStorage initStorage() {
        MetricRegistry metricRegistry = SharedMetricRegistries.getOrCreate(UserapiConfigManager.METRIC_REGISTRY);

        Configuration.FixedSizeThreadPoolConfig threadPoolConfig = config.getStorageConfig().getTpConfig();
        Meter threadPoolRejectionsMtr = metricRegistry.meter(MetricRegistry.name(AsyncStorage.class, "threadpool.rejections"));

        // thread pool with bounded queue for s3 io.
        BlockingQueue ioTaskQueue = new LinkedBlockingQueue(threadPoolConfig.getQueueMaxSize());
        ExecutorService storageExecSvc = new InstrumentedExecutorService(
            new ThreadPoolExecutor(threadPoolConfig.getCoreSize(),
                threadPoolConfig.getMaxSize(),
                threadPoolConfig.getIdleTimeSec(),
                TimeUnit.SECONDS,
                ioTaskQueue,
                new AbortPolicy("storageExectorSvc", threadPoolRejectionsMtr)),
            metricRegistry, "executors.fixed_thread_pool.storage");

        Configuration.S3Config s3Config = config.getStorageConfig().getS3Config();
        return new S3AsyncStorage(S3ClientFactory.create(s3Config.getEndpoint(),
            s3Config.getAccessKey(),
            s3Config.getSecretKey()),
            storageExecSvc,
            s3Config.getListObjectsTimeoutMs());
    }


    private MetricsOptions buildMetricsOptions() {
        return new DropwizardMetricsOptions()
            .setEnabled(true)
            .setJmxEnabled(true)
            .setRegistryName(UserapiConfigManager.METRIC_REGISTRY)
            .addMonitoredHttpServerUri(new Match().setValue(META_PREFIX + PROFILES_PREFIX + APPS_PREFIX + ".*").setAlias(META_PREFIX + PROFILES_PREFIX + APPS_PREFIX).setType(MatchType.REGEX))
            .addMonitoredHttpServerUri(new Match().setValue(META_PREFIX + PROFILES_PREFIX + CLUSTERS_PREFIX + ".*").setAlias(META_PREFIX + PROFILES_PREFIX + CLUSTERS_PREFIX).setType(MatchType.REGEX))
            .addMonitoredHttpServerUri(new Match().setValue(META_PREFIX + PROFILES_PREFIX + PROCS_PREFIX + ".*").setAlias(META_PREFIX + PROFILES_PREFIX + PROCS_PREFIX).setType(MatchType.REGEX))
            .addMonitoredHttpServerUri(new Match().setValue(PROFILES_PREFIX + ".*").setAlias(PROFILES_PREFIX).setType(MatchType.REGEX))
            .addMonitoredHttpServerUri(new Match().setValue(PROFILE_PREFIX + ".*").setAlias(PROFILE_PREFIX).setType(MatchType.REGEX))
            .addMonitoredHttpServerUri(new Match().setValue(CALLEES_PREFIX + ".*").setAlias(CALLEES_PREFIX).setType(MatchType.REGEX))
            .addMonitoredHttpServerUri(new Match().setValue(CALLERS_PREFIX + ".*").setAlias(CALLERS_PREFIX).setType(MatchType.REGEX))

            .addMonitoredHttpServerUri(new Match().setValue(META_PREFIX + POLICIES_PREFIX + APPS_PREFIX + ".*").setAlias(META_PREFIX + POLICIES_PREFIX + APPS_PREFIX).setType(MatchType.REGEX))
            .addMonitoredHttpServerUri(new Match().setValue(META_PREFIX + POLICIES_PREFIX + CLUSTERS_PREFIX + ".*").setAlias(META_PREFIX + POLICIES_PREFIX + CLUSTERS_PREFIX).setType(MatchType.REGEX))
            .addMonitoredHttpServerUri(new Match().setValue(META_PREFIX + POLICIES_PREFIX + PROCS_PREFIX + ".*").setAlias(META_PREFIX + POLICIES_PREFIX + PROCS_PREFIX).setType(MatchType.REGEX))
            .addMonitoredHttpServerUri(new Match().setValue(POLICY_PREFIX + ".*").setAlias(POLICY_PREFIX).setType(MatchType.REGEX));
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
