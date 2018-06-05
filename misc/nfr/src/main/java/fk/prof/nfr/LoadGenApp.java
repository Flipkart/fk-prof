package fk.prof.nfr;

import com.codahale.metrics.SharedMetricRegistries;
import fk.prof.nfr.config.LoadGenAppConfig;
import fk.prof.nfr.driver.Driver;
import fk.prof.nfr.netty.client.HttpClient;
import fk.prof.nfr.netty.server.HttpServer;
import fk.prof.nfr.netty.server.RequestHandler;
import io.dropwizard.Application;
import io.dropwizard.db.PooledDataSourceFactory;
import io.dropwizard.hibernate.HibernateBundle;
import io.dropwizard.jetty.HttpConnectorFactory;
import io.dropwizard.server.DefaultServerFactory;
import io.dropwizard.setup.Bootstrap;
import io.dropwizard.setup.Environment;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.util.ResourceLeakDetector;
import io.netty.util.ResourceLeakDetector.Level;
import io.netty.util.concurrent.DefaultEventExecutorGroup;
import io.netty.util.concurrent.EventExecutorGroup;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Enumeration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

public class LoadGenApp extends Application<LoadGenAppConfig> {

    private final HibernateBundle<LoadGenAppConfig> hibernate = new HibernateBundle<LoadGenAppConfig>(User.class) {
        private PooledDataSourceFactory sessionFactory = null;

        @Override
        public PooledDataSourceFactory getDataSourceFactory(LoadGenAppConfig config) {
            if (sessionFactory == null) {
                sessionFactory = config.getDataSourceFactory();
            }
            return sessionFactory;
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
        int jettyPort = getJettyServerPort(config);
        int nettyPort = getNettyServerPort(config);

        ResourceLeakDetector.setLevel(Level.PARANOID);

        Set<InetSocketAddress> serversToIgnore = enumerateNetDevices().stream()
            .map(ip -> Arrays.asList(
                InetSocketAddress.createUnresolved(ip, jettyPort),
                InetSocketAddress.createUnresolved(ip, nettyPort)))
            .flatMap(List::stream)
            .collect(Collectors.toSet());

        List<InetSocketAddress> servers = config.getServers().stream()
            .filter(s -> !serversToIgnore.contains(s))
            .collect(Collectors.toList());

        EventLoopGroup nioELGroup = new NioEventLoopGroup(config.getIoWorkConfig().nioThreadCnt);
        EventExecutorGroup executors = new DefaultEventExecutorGroup(config.getIoWorkConfig().workerThreadCnt);

        HttpClient asyncHttpClient = new HttpClient(nioELGroup);

        // start server
        startServer(config, environment, servers, nioELGroup, executors, asyncHttpClient);

        // start load
        if (servers.size() > 0) {
            new Driver(asyncHttpClient,
                       executors,
                       servers,
                       config.getIoWorkConfig().driverRequestsInFlight,
                       SharedMetricRegistries.getDefault())
                .run();
        }
    }

    private void startServer(LoadGenAppConfig config, Environment environment, List<InetSocketAddress> servers,
                             EventLoopGroup nioELGroup, EventExecutorGroup executors, HttpClient asyncHttpClient) {
        if (config.getEnableIoWork()) {
            final UserDAO dao = new UserDAO(hibernate.getSessionFactory());
            IOService svc = new IOService(hibernate.getSessionFactory(), dao);

            // jetty server
            environment.jersey().register(new fk.prof.nfr.dropwizard.server.Resource(svc.syncSvc(), servers));

            // netty server
            new HttpServer(nioELGroup, getNettyServerPort(config),
                           new RequestHandler(
                               new fk.prof.nfr.netty.server.Resource(svc.asyncSvc(asyncHttpClient, executors),
                                                                     servers)))
                .start();
        }

        if (config.getEnableCpuWork()) {
            Executors.newSingleThreadExecutor().submit(new CpuWorkLoad(config.getCpuWorkConfig(), config.getDebug()));

            if (config.getThreadSpawnConfig().threadCnt > 0) {
                Executors.newSingleThreadExecutor()
                    .submit(() -> new ThreadSpawner(config.getThreadSpawnConfig().threadCnt,
                                                    config.getThreadSpawnConfig().threadSleepDuration,
                                                    config.getDebug()).doWork());
            }
        }
    }

    private List<String> enumerateNetDevices() throws SocketException {
        List<String> ips = new ArrayList<>();
        Enumeration<NetworkInterface> e = NetworkInterface.getNetworkInterfaces();
        while (e.hasMoreElements()) {
            NetworkInterface n = e.nextElement();
            Enumeration ee = n.getInetAddresses();
            while (ee.hasMoreElements()) {
                InetAddress i = (InetAddress) ee.nextElement();
                if (!(i instanceof Inet6Address)) {
                    ips.add(i.getHostAddress());
                }
            }
        }

        return ips;
    }

    private int getJettyServerPort(LoadGenAppConfig config) {
        DefaultServerFactory serverFactory = (DefaultServerFactory) config.getServerFactory();
        HttpConnectorFactory connectorFactory = (HttpConnectorFactory) serverFactory.getApplicationConnectors().get(0);
        return connectorFactory.getPort();
    }

    private int getNettyServerPort(LoadGenAppConfig config) {
        return getJettyServerPort(config) + 1;
    }
}
