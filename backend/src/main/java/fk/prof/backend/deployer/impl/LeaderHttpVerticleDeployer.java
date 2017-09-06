package fk.prof.backend.deployer.impl;

import com.google.common.base.Preconditions;
import fk.prof.backend.Configuration;
import fk.prof.backend.deployer.VerticleDeployer;
import fk.prof.backend.http.LeaderHttpVerticle;
import fk.prof.backend.model.association.BackendAssociationStore;
import fk.prof.backend.model.policy.PolicyStore;
import io.vertx.core.DeploymentOptions;
import io.vertx.core.Verticle;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;

public class LeaderHttpVerticleDeployer extends VerticleDeployer {

  private final BackendAssociationStore backendAssociationStore;
  private final PolicyStore policyStore;

  public LeaderHttpVerticleDeployer(Vertx vertx,
                                    Configuration config,
                                    BackendAssociationStore backendAssociationStore,
                                    PolicyStore policyStore) {
    super(vertx, config);
    this.backendAssociationStore = Preconditions.checkNotNull(backendAssociationStore);
    this.policyStore = Preconditions.checkNotNull(policyStore);
  }

  @Override
  protected DeploymentOptions getDeploymentOptions() {
    return getConfig().getLeaderDeploymentOpts();
  }

  @Override
  protected Verticle buildVerticle() {
    return new LeaderHttpVerticle(getConfig(), backendAssociationStore, policyStore);
  }
}
