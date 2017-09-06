package fk.prof.userapi.deployer.impl;

import com.google.common.base.Preconditions;
import fk.prof.userapi.Configuration;
import fk.prof.userapi.api.ProfileStoreAPI;
import fk.prof.userapi.deployer.VerticleDeployer;
import fk.prof.userapi.verticles.HttpVerticle;
import io.vertx.core.DeploymentOptions;
import io.vertx.core.Verticle;
import io.vertx.core.Vertx;

public class UserapiHttpVerticleDeployer extends VerticleDeployer {

    private final ProfileStoreAPI profileStoreAPI;

    public UserapiHttpVerticleDeployer(Vertx vertx, Configuration configuration, ProfileStoreAPI profileStoreAPI) {
        super(vertx, configuration);
        this.profileStoreAPI = Preconditions.checkNotNull(profileStoreAPI);
    }

    @Override
    protected DeploymentOptions getDeploymentOptions() {
        return new DeploymentOptions(getConfig().getHttpVerticleConfig());
    }

    @Override
    protected Verticle buildVerticle() {
        Configuration config = getConfig();
        return new HttpVerticle(config.getHttpConfig(), profileStoreAPI, config.getProfilesBaseDir(), config.getMaxListProfilesDurationInDays());
    }
}
