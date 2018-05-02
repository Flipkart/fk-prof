package fk.prof.nfr;

import fk.prof.nfr.config.LoadGenAppConfig;
import fk.prof.nfr.driver.Driver;
import fk.prof.nfr.driver.Resource;
import io.dropwizard.Application;
import io.dropwizard.client.HttpClientBuilder;
import io.dropwizard.db.PooledDataSourceFactory;
import io.dropwizard.hibernate.HibernateBundle;
import io.dropwizard.setup.Bootstrap;
import io.dropwizard.setup.Environment;
import java.util.concurrent.Executors;
import org.apache.http.client.HttpClient;

public class LoadGenApp extends Application<LoadGenAppConfig> {

    private final HibernateBundle<LoadGenAppConfig> hibernate = new HibernateBundle<LoadGenAppConfig>(
        User.class) {
        @Override
        public PooledDataSourceFactory getDataSourceFactory(LoadGenAppConfig dropwizardConfig) {
            return dropwizardConfig.getDataSourceFactory();
        }
    };

    public static void main(String[] args) throws Exception {
        new LoadGenApp().run(args);
    }

    @Override
    public void initialize(Bootstrap<LoadGenAppConfig> bootstrap) {
        bootstrap.addBundle(hibernate);
    }

    @Override
    public void run(LoadGenAppConfig config, Environment environment) throws Exception {
        // app in test
        if (!config.isDriver()) {
            startLoad(config, environment);
        } else {
            // driver app
            environment.jersey().register(new Resource());

            final HttpClient httpClient = new HttpClientBuilder(environment)
                .using(config.getHttpClientConfiguration())
                .build(getName());
            final Driver driverApp = new Driver(httpClient, config.getAppIp(), config.getAppPort());

            Executors.newSingleThreadExecutor().submit(driverApp);
        }
    }

    private void startLoad(LoadGenAppConfig config, Environment environment) {
        if (config.getEnableIoWork()) {
            final UserDAO dao = new UserDAO(hibernate.getSessionFactory());
            environment.jersey().register(new DropwizardResource(config.getDriverIp(), config.getDriverPort(), dao));
        }

        if (config.getEnableCpuWork()) {
            Executors.newSingleThreadExecutor().submit(new CpuWorkLoad(config.getCpuWorkConfig(), config.getDebug()));

            if (config.getThreadSpawnConfig().threadCnt > 0) {
                Executors.newSingleThreadExecutor()
                    .submit(() -> new ThreadSpawner(config.getThreadSpawnConfig().threadCnt,
                        config.getThreadSpawnConfig().threadSleepDuration, config.getDebug()).doWork());
            }
        }
    }
}
