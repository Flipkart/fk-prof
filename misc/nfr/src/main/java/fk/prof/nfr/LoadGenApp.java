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

    private final HibernateBundle<LoadGenAppConfig> hibernate = !isDriver() ?
        new HibernateBundle<LoadGenAppConfig>(User.class) {
            private PooledDataSourceFactory sessionFactory = null;
            @Override
            public PooledDataSourceFactory getDataSourceFactory(LoadGenAppConfig config) {
                if (sessionFactory == null) {
                    sessionFactory = config.getDataSourceFactory();
                }
                return sessionFactory;
            }
        } : null;

    public static void main(String[] args) throws Exception {
        new LoadGenApp().run(args);
    }

    @Override
    public void initialize(Bootstrap<LoadGenAppConfig> bootstrap) {
        if(!isDriver()) {
            bootstrap.addBundle(hibernate);
        }
    }

    @Override
    public void run(LoadGenAppConfig config, Environment environment) throws Exception {
        // app in test
        if (!isDriver()) {
            startLoad(config, environment);
        } else {
            // driver app
            environment.jersey().register(new Resource());

            final HttpClient httpClient = new HttpClientBuilder(environment)
                .using(config.getHttpClientConfiguration())
                .build(getName());
            final Driver driverApp = new Driver(httpClient, config.getAppIp(), config.getAppPort(), config.getAppPort2());

            Executors.newSingleThreadExecutor().submit(driverApp);
        }
    }

    private void startLoad(LoadGenAppConfig config, Environment environment) {
        if (config.getEnableIoWork()) {
            final UserDAO dao = new UserDAO(hibernate.getSessionFactory());
            IOService svc = new IOService(config.getDriverIp(), config.getDriverPort(), dao);
            environment.jersey().register(new DropwizardResource(svc));

            // netty server
            new NettyHttpServer(config.getAppPort2(), svc, hibernate.getSessionFactory()).start();
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

    private boolean isDriver() {
        return Boolean.parseBoolean(System.getProperties().getProperty("driver", "false"));
    }
}
