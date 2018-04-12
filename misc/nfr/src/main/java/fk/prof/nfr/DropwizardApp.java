package fk.prof.nfr;

import fk.prof.nfr.driver.Driver;
import fk.prof.nfr.driver.Resource;
import io.dropwizard.Application;
import io.dropwizard.client.HttpClientBuilder;
import io.dropwizard.db.PooledDataSourceFactory;
import io.dropwizard.hibernate.HibernateBundle;
import io.dropwizard.setup.Bootstrap;
import io.dropwizard.setup.Environment;
import org.apache.http.client.HttpClient;

import java.util.concurrent.Executors;

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
    public void run(DropwizardConfig config, Environment environment) throws Exception {
        // app in test
        if(!config.isDriver()) {
            final UserDAO dao = new UserDAO(hibernate.getSessionFactory());
            environment.jersey().register(
                new DropwizardResource(
                    config.getDriverIp(), config.getDriverPort(), dao));
        } else {
            // driver app
            environment.jersey().register(new Resource());

            final HttpClient httpClient = new HttpClientBuilder(environment).using(config.getHttpClientConfiguration())
                .build(getName());
            final Driver driverApp = new Driver(httpClient, config.getAppIp(), config.getAppPort());

            Executors.newSingleThreadExecutor().submit(driverApp);
        }
    }

    public static void main(String[] args) throws Exception {
        new DropwizardApp().run(args);
    }
}
