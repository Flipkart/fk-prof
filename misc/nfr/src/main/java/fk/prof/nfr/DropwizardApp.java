package fk.prof.nfr;

import fk.prof.nfr.driver.Resource;
import io.dropwizard.Application;
import io.dropwizard.db.PooledDataSourceFactory;
import io.dropwizard.hibernate.HibernateBundle;
import io.dropwizard.setup.Bootstrap;
import io.dropwizard.setup.Environment;

public class DropwizardApp extends Application<DropwizardConfig> {

    private final HibernateBundle<DropwizardConfig> hibernate = new HibernateBundle<DropwizardConfig>(User.class) {
        @Override
        public PooledDataSourceFactory getDataSourceFactory(DropwizardConfig dropwizardConfig) {
            return dropwizardConfig.getDataSourceFactory();
        }
    };

    @Override
    public void initialize(Bootstrap<DropwizardConfig> bootstrap) {
        bootstrap.addBundle(hibernate);
    }

    @Override
    public void run(DropwizardConfig dropwizardConfig, Environment environment) throws Exception {
        // app in test
        if(!dropwizardConfig.isDriver()) {
            final UserDAO dao = new UserDAO(hibernate.getSessionFactory());
            environment.jersey().register(
                new DropwizardResource(
                    dropwizardConfig.getClientIp(), dropwizardConfig.getClientPort(), dao));
        } else {
            // driver app
            environment.jersey().register(new Resource());

        }
    }

    public static void main(String[] args) throws Exception {
        new DropwizardApp().run(args);
    }
}
