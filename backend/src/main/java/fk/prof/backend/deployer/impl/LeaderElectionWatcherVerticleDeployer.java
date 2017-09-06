package fk.prof.backend.deployer.impl;

import com.google.common.base.Preconditions;
import fk.prof.backend.Configuration;
import fk.prof.backend.deployer.VerticleDeployer;
import fk.prof.backend.leader.election.LeaderElectionWatcher;
import fk.prof.backend.model.election.LeaderWriteContext;
import io.vertx.core.DeploymentOptions;
import io.vertx.core.Verticle;
import io.vertx.core.Vertx;
import org.apache.curator.framework.CuratorFramework;

public class LeaderElectionWatcherVerticleDeployer extends VerticleDeployer {

  private final CuratorFramework curatorClient;
  private final LeaderWriteContext leaderWriteContext;

  public LeaderElectionWatcherVerticleDeployer(Vertx vertx,
                                               Configuration config,
                                               CuratorFramework curatorClient,
                                               LeaderWriteContext leaderWriteContext) {
    super(vertx, config);
    this.curatorClient = Preconditions.checkNotNull(curatorClient);
    this.leaderWriteContext = Preconditions.checkNotNull(leaderWriteContext);
  }

  @Override
  protected DeploymentOptions getDeploymentOptions() {
    return getConfig().getLeaderElectionDeploymentOpts();
  }

  @Override
  protected Verticle buildVerticle() {
    return new LeaderElectionWatcher(curatorClient, leaderWriteContext);
  }
}
