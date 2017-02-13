package fk.prof.backend.leader.election;

import fk.prof.backend.VertxManager;
import fk.prof.backend.model.association.BackendAssociationStore;
import io.vertx.core.DeploymentOptions;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import io.vertx.core.logging.Logger;
import io.vertx.core.logging.LoggerFactory;

import java.util.List;

/**
 * TODO: This class is partially complete and will undergo refactoring once all leader functions are implemented
 */
public class LeaderElectedTask implements Runnable {
  private static Logger logger = LoggerFactory.getLogger(LeaderElectedTask.class);

  private Runnable backendVerticlesUndeployer = null;
  private Runnable leaderHttpVerticlesDeployer;

  private LeaderElectedTask(Vertx vertx,
                            List<String> backendDeployments,
                            JsonObject leaderHttpServerConfig,
                            DeploymentOptions leaderHttpDeploymentOptions,
                            BackendAssociationStore backendAssociationStore) {

    if (backendDeployments != null) {
      // NOTE: If backend deployments supplied, leader will not serve as backend and only do leader related operations
      this.backendVerticlesUndeployer = () -> backendDeployments.stream().forEach(deploymentId ->
          vertx.undeploy(deploymentId, result -> {
            if (result.succeeded()) {
              logger.debug(String.format("Successfully un-deployed backend verticle=%s", deploymentId));
            } else {
              logger.error(String.format("Error when un-deploying backend verticle=%s", deploymentId), result.cause());
            }
          })
      );
    }

    if (leaderHttpServerConfig != null && leaderHttpDeploymentOptions != null && backendAssociationStore != null) {
      this.leaderHttpVerticlesDeployer = () ->
          VertxManager.deployLeaderHttpVerticles(vertx, leaderHttpServerConfig, leaderHttpDeploymentOptions, backendAssociationStore);
    }
  }

  @Override
  public void run() {
    if (this.backendVerticlesUndeployer != null) {
      this.backendVerticlesUndeployer.run();
    }
    if(this.leaderHttpVerticlesDeployer != null) {
      this.leaderHttpVerticlesDeployer.run();
    }
  }

  public static Builder newBuilder() {
    return new Builder();
  }

  public static class Builder {
    private List<String> backendDeployments = null;
    private JsonObject leaderHttpServerConfig;
    private DeploymentOptions leaderHttpDeploymentOptions;
    private BackendAssociationStore backendAssociationStore;

    public Builder disableBackend(List<String> backendDeployments) {
      if (backendDeployments == null) {
        throw new IllegalArgumentException("Backend deployments are required to disable backend operations");
      }
      this.backendDeployments = backendDeployments;
      return this;
    }

    public Builder enableLeaderHttp(JsonObject leaderHttpServerConfig,
                                    DeploymentOptions leaderHttpDeploymentOptions,
                                    BackendAssociationStore backendAssociationStore) {
      if(leaderHttpServerConfig == null) {
        throw new IllegalStateException("Leader HTTP server options are required");
      }
      if(leaderHttpDeploymentOptions == null) {
        throw new IllegalStateException("Leader http deployment options are required");
      }
      if(backendAssociationStore == null) {
        throw new IllegalStateException("Backend association store is required");
      }

      this.leaderHttpServerConfig = leaderHttpServerConfig;
      this.leaderHttpDeploymentOptions = leaderHttpDeploymentOptions;
      this.backendAssociationStore = backendAssociationStore;
      return this;
    }

    public LeaderElectedTask build(Vertx vertx) {
      if(vertx == null) {
        throw new IllegalStateException("Vertx instance is required");
      }

      return new LeaderElectedTask(vertx, backendDeployments, leaderHttpServerConfig, leaderHttpDeploymentOptions, backendAssociationStore);
    }
  }
}
