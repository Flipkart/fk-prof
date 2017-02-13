package fk.prof.backend;

import fk.prof.backend.leader.election.LeaderElectedTask;
import fk.prof.backend.leader.election.LeaderElectionParticipator;
import fk.prof.backend.leader.election.LeaderElectionWatcher;
import fk.prof.backend.model.association.BackendAssociationStore;
import fk.prof.backend.model.association.ProcessGroupCountBasedBackendComparator;
import fk.prof.backend.model.association.impl.ZookeeperBasedBackendAssociationStore;
import fk.prof.backend.model.election.LeaderDiscoveryStore;
import fk.prof.backend.model.election.impl.InMemoryLeaderDiscoveryStore;
import fk.prof.backend.service.IProfileWorkService;
import fk.prof.backend.service.ProfileWorkService;
import fk.prof.backend.http.BackendHttpVerticle;
import fk.prof.backend.http.LeaderHttpVerticle;
import io.vertx.core.*;
import io.vertx.core.json.JsonObject;
import io.vertx.core.logging.Logger;
import io.vertx.core.logging.LoggerFactory;
import io.vertx.ext.dropwizard.DropwizardMetricsOptions;
import io.vertx.ext.dropwizard.Match;
import io.vertx.ext.dropwizard.MatchType;
import org.apache.curator.framework.CuratorFramework;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * TODO: Deployment process is liable to changes later
 */
public class VertxManager {
  private static Logger logger = LoggerFactory.getLogger(VertxManager.class);

  public static Vertx initialize(JsonObject vertxConfig) throws IOException {
    VertxOptions vertxOptions = vertxConfig != null
        ? new VertxOptions(vertxConfig)
        : new VertxOptions();

    vertxOptions.setMetricsOptions(new DropwizardMetricsOptions()
        .setEnabled(true)
        .setJmxEnabled(true)
        .setRegistryName(ConfigManager.METRIC_REGISTRY)
        .addMonitoredHttpServerUri(new Match().setValue("/.*").setType(MatchType.REGEX)));
    return Vertx.vertx(vertxOptions);
  }

  public static Future<Void> close(Vertx vertx) {
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

  public static CompositeFuture deployBackendHttpVerticles(
      Vertx vertx,
      JsonObject backendHttpServerConfig,
      JsonObject httpClientConfig,
      int leaderPort,
      DeploymentOptions backendDeploymentOptions,
      LeaderDiscoveryStore leaderDiscoveryStore,
      IProfileWorkService profileWorkService) {
    assert vertx != null;
    assert backendDeploymentOptions != null;
    assert profileWorkService != null;
    assert leaderDiscoveryStore != null;

    int verticleCount = backendDeploymentOptions.getConfig().getInteger("http.instances", 1);
    List<Future> deployFutures = new ArrayList<>();
    for (int i = 1; i <= verticleCount; i++) {
      Future<String> deployFuture = Future.future();
      deployFutures.add(deployFuture);

      Verticle backendHttpVerticle = new BackendHttpVerticle(backendHttpServerConfig, httpClientConfig, leaderPort, leaderDiscoveryStore, profileWorkService);
      vertx.deployVerticle(backendHttpVerticle, backendDeploymentOptions, deployResult -> {
        if (deployResult.succeeded()) {
          logger.info("Deployment of BackendHttpVerticle succeeded with deploymentId = " + deployResult.result());
          deployFuture.complete(deployResult.result());
        } else {
          logger.error("Deployment of BackendHttpVerticle failed", deployResult.cause());
          deployFuture.fail(deployResult.cause());
        }
      });
    }

    return CompositeFuture.all(deployFutures);
  }

  public static void deployLeaderElectionWorkerVerticles
      (Vertx vertx,
       DeploymentOptions leaderElectionDeploymentOptions,
       CuratorFramework curatorClient,
       Runnable leaderElectedTask,
       LeaderDiscoveryStore leaderDiscoveryStore) {
    assert vertx != null;
    assert leaderElectionDeploymentOptions != null;
    assert curatorClient != null;
    assert leaderElectedTask != null;
    assert leaderDiscoveryStore != null;

    Verticle leaderElectionParticipator = new LeaderElectionParticipator(curatorClient, leaderElectedTask);
    vertx.deployVerticle(leaderElectionParticipator, leaderElectionDeploymentOptions);

    Verticle leaderElectionWatcher = new LeaderElectionWatcher(
        curatorClient,
        leaderDiscoveryStore);
    vertx.deployVerticle(leaderElectionWatcher, leaderElectionDeploymentOptions);
  }

  public static void deployLeaderHttpVerticles
      (Vertx vertx,
       JsonObject leaderHttpServerConfig,
       DeploymentOptions leaderHttpDeploymentOptions,
       BackendAssociationStore backendAssociationStore) {
    assert vertx != null;
    assert leaderHttpDeploymentOptions != null;
    assert backendAssociationStore != null;

    Verticle leaderHttpVerticle = new LeaderHttpVerticle(leaderHttpServerConfig, backendAssociationStore);
    vertx.deployVerticle(leaderHttpVerticle, leaderHttpDeploymentOptions);
  }

  public static Runnable getDefaultLeaderElectedTask(Vertx vertx,
                                                     boolean aggregationEnabled,
                                                     List<String> backendDeployments,
                                                     boolean leaderHttpEnabled,
                                                     JsonObject leaderHttpServerConfig,
                                                     DeploymentOptions leaderHttpDeploymentOptions,
                                                     BackendAssociationStore backendAssociationStore) {
    LeaderElectedTask.Builder builder = LeaderElectedTask.newBuilder();
    if (!aggregationEnabled) {
      builder.disableBackend(backendDeployments);
    }
    if(leaderHttpEnabled) {
      builder.enableLeaderHttp(leaderHttpServerConfig, leaderHttpDeploymentOptions, backendAssociationStore);
    }
    return builder.build(vertx);
  }

  public static LeaderDiscoveryStore getDefaultLeaderDiscoveryStore(Vertx vertx) {
    return new InMemoryLeaderDiscoveryStore();
  }

  public static BackendAssociationStore getDefaultBackendAssociationStore(Vertx vertx, CuratorFramework curatorClient, String backendAssociationZKNodePath, int reportLoadFrequencyInSeconds, int maxAllowedSkips)
      throws Exception {
    return ZookeeperBasedBackendAssociationStore.newBuilder()
        .setCuratorClient(curatorClient)
        .setBackendAssociationPath(backendAssociationZKNodePath)
        .setBackedPriorityComparator(new ProcessGroupCountBasedBackendComparator())
        .setReportingFrequencyInSeconds(reportLoadFrequencyInSeconds)
        .setMaxAllowedSkips(maxAllowedSkips)
        .build(vertx);
  }

  public static Future<Void> launch(Vertx vertx, CuratorFramework curatorClient, JsonObject configOptions) {
    JsonObject backendHttpServerConfig = ConfigManager.getBackendHttpServerConfig(configOptions);
    if (backendHttpServerConfig == null) {
      throw new RuntimeException("Backend http server options are required");
    }

    JsonObject leaderHttpServerConfig = ConfigManager.getLeaderHttpServerConfig(configOptions);
    if (backendHttpServerConfig == null) {
      throw new RuntimeException("Leader http server options are required");
    }
    int leaderPort = leaderHttpServerConfig.getInteger("port");

    JsonObject backendDeploymentConfig = ConfigManager.getBackendHttpDeploymentConfig(configOptions);
    if (backendDeploymentConfig == null) {
      throw new RuntimeException("Backend deployment options are required");
    }

    JsonObject httpClientConfig = ConfigManager.getHttpClientConfig(configOptions);
    if (httpClientConfig == null) {
      throw new RuntimeException("Http client options are required");
    }

    JsonObject leaderElectionDeploymentConfig = ConfigManager.getLeaderElectionDeploymentConfig(configOptions);
    if (leaderElectionDeploymentConfig == null) {
      throw new RuntimeException("Leader election deployment options are required");
    }

    JsonObject leaderHttpDeploymentConfig = ConfigManager.getLeaderHttpDeploymentConfig(configOptions);
    if (leaderHttpDeploymentConfig == null) {
      throw new RuntimeException("Leader http deployment options are required");
    }

    Future future = Future.future();
    int loadReportIntervalInSeconds = ConfigManager.getLoadReportIntervalInSeconds(configOptions);
    DeploymentOptions backendDeploymentOptions = new DeploymentOptions(backendDeploymentConfig);
    ProfileWorkService profileWorkService = new ProfileWorkService();
    LeaderDiscoveryStore leaderDiscoveryStore = getDefaultLeaderDiscoveryStore(vertx);

    CompositeFuture backendHttpDeploymentFuture = deployBackendHttpVerticles(
        vertx, backendHttpServerConfig, httpClientConfig,
        leaderPort, backendDeploymentOptions, leaderDiscoveryStore, profileWorkService);

    backendHttpDeploymentFuture.setHandler(result -> {
      if (result.succeeded()) {
        //Deploy leader related verticles
        List<String> backendDeployments = result.result().list();
        DeploymentOptions leaderElectionDeploymentOptions = new DeploymentOptions(leaderElectionDeploymentConfig);
        DeploymentOptions leaderHttpDeploymentOptions = new DeploymentOptions(leaderHttpDeploymentConfig);

        try {
          BackendAssociationStore backendAssociationStore = getDefaultBackendAssociationStore(
              vertx, curatorClient,
              leaderHttpDeploymentConfig.getString("backend.association.path"),
              ConfigManager.getLoadReportIntervalInSeconds(configOptions),
              leaderHttpDeploymentConfig.getInteger("allowed.report.skips"));

          Runnable leaderElectedTask = getDefaultLeaderElectedTask(
              vertx,
              leaderElectionDeploymentOptions.getConfig().getBoolean("aggregation.enabled", true),
              backendDeployments,
              leaderElectionDeploymentOptions.getConfig().getBoolean("http.enabled", true),
              leaderHttpServerConfig,
              leaderHttpDeploymentOptions,
              backendAssociationStore
          );

          deployLeaderElectionWorkerVerticles(vertx, leaderElectionDeploymentOptions, curatorClient, leaderElectedTask, leaderDiscoveryStore);
        } catch (Exception ex) {
          future.fail(ex);
        }
      } else {
        future.fail(result.cause());
      }
    });

    return future;
  }

}
