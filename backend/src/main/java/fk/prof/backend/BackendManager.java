package fk.prof.backend;

import com.codahale.metrics.*;
import com.google.common.base.Preconditions;
import fk.prof.aggregation.model.AggregationWindowStorage;
import fk.prof.backend.deployer.VerticleDeployer;
import fk.prof.backend.deployer.impl.*;
import fk.prof.backend.http.ApiPathConstants;
import fk.prof.backend.leader.election.LeaderElectedTask;
import fk.prof.backend.model.aggregation.ActiveAggregationWindows;
import fk.prof.backend.model.assignment.AssociatedProcessGroups;
import fk.prof.backend.model.assignment.impl.AssociatedProcessGroupsImpl;
import fk.prof.backend.model.slot.WorkSlotPool;
import fk.prof.backend.model.association.BackendAssociationStore;
import fk.prof.backend.model.association.ProcessGroupCountBasedBackendComparator;
import fk.prof.backend.model.association.impl.ZookeeperBasedBackendAssociationStore;
import fk.prof.backend.model.election.impl.InMemoryLeaderStore;
import fk.prof.backend.model.aggregation.impl.ActiveAggregationWindowsImpl;
import fk.prof.backend.model.policy.PolicyStore;
import fk.prof.metrics.MetricName;
import fk.prof.storage.AsyncStorage;
import fk.prof.storage.S3AsyncStorage;
import fk.prof.storage.S3ClientFactory;
import fk.prof.storage.buffer.ByteBufferPoolFactory;
import io.vertx.core.*;
import io.vertx.core.Future;
import io.vertx.core.logging.Logger;
import io.vertx.core.logging.LoggerFactory;
import io.vertx.core.metrics.MetricsOptions;
import io.vertx.ext.dropwizard.DropwizardMetricsOptions;
import io.vertx.ext.dropwizard.Match;
import io.vertx.ext.dropwizard.MatchType;
import org.apache.commons.pool2.impl.GenericObjectPool;
import org.apache.commons.pool2.impl.GenericObjectPoolConfig;
import org.apache.curator.framework.CuratorFramework;
import org.apache.curator.framework.CuratorFrameworkFactory;
import org.apache.curator.retry.ExponentialBackoffRetry;
import org.apache.zookeeper.KeeperException;

import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.*;
import java.util.stream.Collectors;

public class BackendManager {
  private static Logger logger = LoggerFactory.getLogger(BackendManager.class);

  private final Vertx vertx;
  private final Configuration config;
  private final CuratorFramework curatorClient;
  private AsyncStorage storage;
  private GenericObjectPool<ByteBuffer> bufferPool;
  private MetricRegistry metricRegistry;

  public BackendManager(String configFilePath) throws Exception {
    this(ConfigManager.loadConfig(configFilePath));
  }

  public BackendManager(Configuration config) throws Exception {
    ConfigManager.setDefaultSystemProperties();
    this.config = Preconditions.checkNotNull(config);

    VertxOptions vertxOptions = new VertxOptions(config.getVertxOptions());
    vertxOptions.setMetricsOptions(buildMetricsOptions());
    this.vertx = Vertx.vertx(vertxOptions);
    this.metricRegistry = SharedMetricRegistries.getOrCreate(ConfigManager.METRIC_REGISTRY);

    this.curatorClient = createCuratorClient();
    curatorClient.start();
    curatorClient.blockUntilConnected(config.getCuratorConfig().getConnectionTimeoutMs(), TimeUnit.MILLISECONDS);
    ensureRequiredZkNodesPresent();

    initStorage();
  }

  public Future<Void> close() {
    Future future = Future.future();
    vertx.close(closeResult -> {
      if (closeResult.succeeded()) {
        logger.info("Shutdown successful for vertx instance");
        curatorClient.close();
        future.complete();
      } else {
        logger.error("Error shutting down vertx instance");
        future.fail(closeResult.cause());
      }
    });

    return future;
  }

  public Future<Void> launch() {
    Future result = Future.future();
    InMemoryLeaderStore leaderStore = new InMemoryLeaderStore(config.getIpAddress(), config.getLeaderHttpServerOpts().getPort());
    ActiveAggregationWindows activeAggregationWindows = new ActiveAggregationWindowsImpl();
    AssociatedProcessGroups associatedProcessGroups = new AssociatedProcessGroupsImpl(config.getRecorderDefunctThresholdSecs());
    WorkSlotPool workSlotPool = new WorkSlotPool(config.getScheduleSlotPoolCapacity());
    AggregationWindowStorage aggregationWindowStorage = new AggregationWindowStorage(config.getProfilesBaseDir(), storage, bufferPool, metricRegistry);

    VerticleDeployer backendHttpVerticleDeployer = new BackendHttpVerticleDeployer(vertx, config, leaderStore, activeAggregationWindows, associatedProcessGroups);
    VerticleDeployer backendDaemonVerticleDeployer = new BackendDaemonVerticleDeployer(vertx, config, leaderStore, associatedProcessGroups, activeAggregationWindows, workSlotPool, aggregationWindowStorage);
    CompositeFuture backendDeploymentFuture = CompositeFuture.all(backendHttpVerticleDeployer.deploy(), backendDaemonVerticleDeployer.deploy());
    backendDeploymentFuture.setHandler(backendDeployResult -> {
      if (backendDeployResult.succeeded()) {
        try {
          List<String> backendDeployments = backendDeployResult.result().list().stream()
              .flatMap(fut -> ((CompositeFuture)fut).list().stream())
              .map(deployment -> (String)deployment)
              .collect(Collectors.toList());

          BackendAssociationStore backendAssociationStore = createBackendAssociationStore(vertx, curatorClient);
          PolicyStore policyStore = new PolicyStore(curatorClient);

          VerticleDeployer leaderHttpVerticleDeployer = new LeaderHttpVerticleDeployer(vertx, config, backendAssociationStore, policyStore);
          Runnable leaderElectedTask = createLeaderElectedTask(vertx, leaderHttpVerticleDeployer, backendDeployments, backendAssociationStore, policyStore);

          VerticleDeployer leaderElectionParticipatorVerticleDeployer = new LeaderElectionParticipatorVerticleDeployer(
              vertx, config, curatorClient, leaderElectedTask);
          VerticleDeployer leaderElectionWatcherVerticleDeployer = new LeaderElectionWatcherVerticleDeployer(vertx, config, curatorClient, leaderStore);

          CompositeFuture leaderDeployFuture = CompositeFuture.all(
              leaderElectionParticipatorVerticleDeployer.deploy(), leaderElectionWatcherVerticleDeployer.deploy());
          leaderDeployFuture.setHandler(leaderDeployResult -> {
            if(leaderDeployResult.succeeded()) {
              result.complete();
            } else {
              result.fail(leaderDeployResult.cause());
            }
          });
        } catch (Exception ex) {
          result.fail(ex);
        }
      } else {
        result.fail(backendDeployResult.cause());
      }
    });

    return result;
  }

  private void initStorage() {
    Configuration.StorageConfig.S3Config s3Config = config.getStorageConfig().getS3Config();
    Configuration.StorageConfig.FixedSizeThreadPoolConfig threadPoolConfig = config.getStorageConfig().getTpConfig();
    Meter threadPoolRejectionsMtr = metricRegistry.meter(MetricName.S3_Threadpool_Rejection.get());

    // thread pool with bounded queue for s3 io.
    BlockingQueue ioTaskQueue = new LinkedBlockingQueue(threadPoolConfig.getQueueMaxSize());
    ExecutorService storageExecSvc = new InstrumentedExecutorService(
        new ThreadPoolExecutor(threadPoolConfig.getCoreSize(), threadPoolConfig.getMaxSize(), threadPoolConfig.getIdleTimeSec(), TimeUnit.SECONDS, ioTaskQueue,
                new AbortPolicy("s3ExectorSvc", threadPoolRejectionsMtr)),
        metricRegistry, "executors.fixed_thread_pool.storage");

    this.storage = new S3AsyncStorage(S3ClientFactory.create(s3Config.getEndpoint(), s3Config.getAccessKey(), s3Config.getSecretKey()),
        storageExecSvc, s3Config.getListObjectsTimeoutMs());

    // buffer pool to temporarily store serialized bytes
    Configuration.BufferPoolConfig bufferPoolConfig = config.getBufferPoolConfig();
    GenericObjectPoolConfig poolConfig = new GenericObjectPoolConfig();
    poolConfig.setMaxTotal(bufferPoolConfig.getMaxTotal());
    poolConfig.setMaxIdle(bufferPoolConfig.getMaxIdle());

    this.bufferPool = new GenericObjectPool<>(new ByteBufferPoolFactory(bufferPoolConfig.getBufferSize(), false), poolConfig);
  }

  private CuratorFramework createCuratorClient() {
    Configuration.CuratorConfig curatorConfig = config.getCuratorConfig();
    return CuratorFrameworkFactory.builder()
        .connectString(curatorConfig.getConnectionUrl())
        .retryPolicy(new ExponentialBackoffRetry(1000, curatorConfig.getMaxRetries()))
        .connectionTimeoutMs(curatorConfig.getConnectionTimeoutMs())
        .sessionTimeoutMs(curatorConfig.getSessionTineoutMs())
        .namespace(curatorConfig.getNamespace())
        .build();
  }

  private void ensureRequiredZkNodesPresent() throws  Exception {
    try {
      curatorClient.create().forPath(config.getAssociationsConfig().getAssociationPath());
    } catch (KeeperException.NodeExistsException ex) {
      logger.warn(ex);
    }
  }

  private BackendAssociationStore createBackendAssociationStore(
      Vertx vertx, CuratorFramework curatorClient)
      throws Exception {
    int loadReportIntervalInSeconds = config.getLoadReportItvlSecs();
    String backendAssociationPath = config.getAssociationsConfig().getAssociationPath();
    int loadMissTolerance = config.getAssociationsConfig().getLoadMissTolerance();
    return new ZookeeperBasedBackendAssociationStore(vertx, curatorClient, backendAssociationPath,
        loadReportIntervalInSeconds, loadMissTolerance, new ProcessGroupCountBasedBackendComparator());
  }

  public static Runnable createLeaderElectedTask(Vertx vertx, VerticleDeployer leaderHttpVerticleDeployer, List<String> backendDeployments,
                                                 BackendAssociationStore associationStore, PolicyStore policyStore) {
    LeaderElectedTask.Builder builder = LeaderElectedTask.newBuilder();
    builder.disableBackend(backendDeployments);
    return builder.build(vertx, leaderHttpVerticleDeployer, associationStore, policyStore);
  }

  private MetricsOptions buildMetricsOptions() {
    MetricsOptions metricsOptions = new DropwizardMetricsOptions()
        .setJmxDomain("fk.prof")
        .setEnabled(true)
        .setJmxEnabled(true)
        .setRegistryName(ConfigManager.METRIC_REGISTRY)
        .addMonitoredHttpServerUri(new Match().setValue(ApiPathConstants.AGGREGATOR_POST_PROFILE).setType(MatchType.EQUALS))
        .addMonitoredHttpServerUri(new Match().setValue(ApiPathConstants.BACKEND_POST_POLL).setType(MatchType.EQUALS))
        .addMonitoredHttpServerUri(new Match().setValue(ApiPathConstants.BACKEND_POST_ASSOCIATION).setType(MatchType.EQUALS))
        .addMonitoredHttpServerUri(new Match().setValue(ApiPathConstants.LEADER_POST_ASSOCIATION).setType(MatchType.EQUALS))
        .addMonitoredHttpServerUri(new Match().setValue(ApiPathConstants.LEADER_POST_LOAD).setType(MatchType.EQUALS))
        .addMonitoredHttpServerUri(new Match().setValue(ApiPathConstants.LEADER_GET_WORK + ".*").setAlias(ApiPathConstants.LEADER_GET_WORK).setType(MatchType.REGEX));
    return metricsOptions;
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
