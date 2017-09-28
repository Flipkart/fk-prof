package fk.prof.backend.leader.election;

import com.codahale.metrics.Counter;
import com.codahale.metrics.MetricRegistry;
import com.codahale.metrics.SharedMetricRegistries;
import com.google.common.base.Preconditions;
import fk.prof.backend.ConfigManager;
import fk.prof.backend.deployer.VerticleDeployer;
import fk.prof.backend.model.association.BackendAssociationStore;
import fk.prof.backend.model.policy.PolicyStore;
import fk.prof.metrics.MetricName;
import io.vertx.core.CompositeFuture;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.logging.Logger;
import io.vertx.core.logging.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.FutureTask;
import java.util.function.Supplier;

/**
 * TODO: This class should be supplier[future<void]] instead of runnable
 * If run fails because of backend undeployment failure or leader deployment failure, failed future should be returned
 * Post that, leader should relinquish leadership
 */
public class LeaderElectedTask implements Runnable {
  private static Logger logger = LoggerFactory.getLogger(LeaderElectedTask.class);

  private MetricRegistry metricRegistry = SharedMetricRegistries.getOrCreate(ConfigManager.METRIC_REGISTRY);
  private Counter ctrFailure = metricRegistry.counter(MetricName.Election_Task_Failure.get());

  private Supplier<CompositeFuture> backendVerticlesUndeployer = null;
  private VerticleDeployer leaderHttpVerticlesDeployer;
  private BackendAssociationStore associationStore;
  private PolicyStore policyStore;

  private LeaderElectedTask(Vertx vertx, VerticleDeployer leaderHttpVerticleDeployer,
                            List<String> backendDeployments, BackendAssociationStore associationStore,
                            PolicyStore policyStore) {

    if (backendDeployments != null) {
      // NOTE: If backend deployments supplied, leader will not serve as backend and only do leader related operations
      this.backendVerticlesUndeployer = () -> {
        logger.info("Unloading backend verticles");
        List<Future> undeployFutures =  new ArrayList<>();
        for(String deploymentId: backendDeployments) {
          Future<Void> future = Future.future();
          undeployFutures.add(future);

          vertx.undeploy(deploymentId, result -> {
            if (result.succeeded()) {
              logger.info(String.format("Successfully un-deployed backend verticle=%s", deploymentId));
              future.complete();
            } else {
              logger.error(String.format("Error when un-deploying backend verticle=%s", deploymentId), result.cause());
              future.fail(result.cause());
            }
          });
        }
        return CompositeFuture.all(undeployFutures);
      };
    }

    this.leaderHttpVerticlesDeployer = Preconditions.checkNotNull(leaderHttpVerticleDeployer);
    this.associationStore = associationStore;
    this.policyStore = policyStore;
  }

  @Override
  public void run() {
    logger.info("Beginning leader elected task");
    runTask().setHandler(ar -> {
      if(ar.failed()) {
        ctrFailure.inc();
      } else {
        logger.info("Successfully completed leader elected task");
      }
    });
  }

  private Future<?> runTask() {
    if (this.backendVerticlesUndeployer != null) {
      Future result = Future.future();
      this.backendVerticlesUndeployer.get().setHandler(ar -> {
        if(ar.succeeded()) {
          deployLeaderHttpVerticles().setHandler(result.completer());
        } else {
          logger.error("Aborting deployment of leader http verticles because error while un-deploying backend verticles", ar.cause());
          result.fail(ar.cause());
        }
      });
      return result;
    } else {
      return deployLeaderHttpVerticles();
    }
  }

  private Future deployLeaderHttpVerticles() {
    Future future = Future.future();
    logger.info("Stores are being initialized");
    CompositeFuture.all(runOnContext(associationStore::init), runOnContext(policyStore::init))
        .compose(initResult -> {
            logger.info("Leader http verticle is being deployed");
            leaderHttpVerticlesDeployer.deploy().setHandler(future.completer());
        }, future);
    return future;
  }

  interface CheckedRunnable {
    void run() throws Exception;
  }

  private Future runOnContext(CheckedRunnable r) {
    Future fut = Future.future();
    try {
      r.run();
      fut.complete();
    }
    catch (Exception e) {
      fut.fail(e);
    }
    return fut;
  }

  public static Builder newBuilder() {
    return new Builder();
  }

  public static class Builder {
    private List<String> backendDeployments = null;

    public Builder disableBackend(List<String> backendDeployments) {
      this.backendDeployments = Preconditions.checkNotNull(backendDeployments);
      return this;
    }

    public LeaderElectedTask build(Vertx vertx, VerticleDeployer leaderHttpVerticleDeployer, BackendAssociationStore associationStore, PolicyStore policyStore) {
      Preconditions.checkNotNull(vertx);
      Preconditions.checkNotNull(leaderHttpVerticleDeployer);
      Preconditions.checkNotNull(associationStore);
      Preconditions.checkNotNull(policyStore);
      return new LeaderElectedTask(vertx, leaderHttpVerticleDeployer, backendDeployments, associationStore, policyStore);
    }
  }
}
