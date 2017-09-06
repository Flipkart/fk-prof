package fk.prof.backend.deployer;

import com.google.common.base.Preconditions;
import fk.prof.backend.ConfigManager;
import fk.prof.backend.Configuration;
import io.vertx.core.*;
import io.vertx.core.logging.Logger;
import io.vertx.core.logging.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

public abstract class VerticleDeployer {
  private static Logger logger = LoggerFactory.getLogger(VerticleDeployer.class);

  private final Vertx vertx;
  private final Configuration config;

  public VerticleDeployer(Vertx vertx, Configuration config) {
    this.vertx = Preconditions.checkNotNull(vertx);
    this.config = Preconditions.checkNotNull(config);
  }

  protected Configuration getConfig() {
    return config;
  }

  protected abstract DeploymentOptions getDeploymentOptions();
  protected abstract Verticle buildVerticle();

  public CompositeFuture deploy() {
    DeploymentOptions deploymentOptions = getDeploymentOptions();
    int verticleCount = deploymentOptions.getConfig().getInteger("verticle.count", 1);
    List<Future> deployFutures = new ArrayList<>();

    for (int i = 1; i <= verticleCount; i++) {
      Future<String> future = Future.future();
      deployFutures.add(future);

      Verticle verticle = buildVerticle();
      vertx.deployVerticle(verticle, deploymentOptions, deployResult -> {
        if (deployResult.succeeded()) {
          logger.info("Deployment of " + verticle.getClass().getName() + " succeeded with deploymentId = " + deployResult.result());
          future.complete(deployResult.result());
        } else {
          logger.error("Deployment of " + verticle.getClass().getName() + " failed", deployResult.cause());
          future.fail(deployResult.cause());
        }
      });
    }
    return CompositeFuture.all(deployFutures);
  }
}
