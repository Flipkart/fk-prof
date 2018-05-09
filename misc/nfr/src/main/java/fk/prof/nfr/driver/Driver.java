package fk.prof.nfr.driver;

import com.codahale.metrics.Meter;
import com.codahale.metrics.MetricRegistry;
import fk.prof.nfr.netty.client.HttpClient;
import io.netty.util.concurrent.EventExecutorGroup;
import io.netty.util.concurrent.Promise;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.Random;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class Driver implements Runnable {

    private static final Logger logger = LoggerFactory.getLogger(Driver.class);

    private final HttpClient client;

    private final EventExecutorGroup executors;

    private final Random rndm = new Random();

    private final List<InetSocketAddress> servers;

    private final int maxRequestsInFlight;

    private final Meter requestsInitiated, requestsCompleted;

    private final AtomicBoolean running = new AtomicBoolean(false);

    public Driver(HttpClient client, EventExecutorGroup executors, List<InetSocketAddress> servers,
                  int maxRequestsInFlight, MetricRegistry metricsRegistry) {
        this.client = client;
        this.executors = executors;
        this.servers = servers;
        this.maxRequestsInFlight = maxRequestsInFlight;
        this.requestsInitiated = metricsRegistry.meter("driver.requests.initiated");
        this.requestsCompleted = metricsRegistry.meter("driver.requests.completed");
        metricsRegistry.gauge("driver.running", () -> running::get);

        Runtime.getRuntime().addShutdownHook(new Thread(() -> running.set(false)));
    }

    @Override
    public void run() {
        startRequests();
    }

    private void startRequests() {
        new Thread(() -> {
            running.set(true);
            while (running.get()) {
                if (requestsInitiated.getCount() - requestsCompleted.getCount() > maxRequestsInFlight) {
                    try {
                        Thread.sleep(100);
                        continue;
                    } catch (Exception e) {
                        // ignore
                        logger.error(
                            "Sleep interrupted for driver, " + requestsInitiated.getCount() + ", " + requestsCompleted
                                .getCount());
                    }
                }

                int serverId = rndm.nextInt(servers.size());
                InetSocketAddress address = servers.get(serverId);

                Promise<String> promise = executors.next().newPromise();
                client.doGet(address.getHostString(), address.getPort(), path(), promise, true);
                requestsInitiated.mark();

                promise.addListener(f -> {
                    requestsCompleted.mark();
                    if (logger.isDebugEnabled()) {
                        logger.debug(
                            "driver -- " + requestsInitiated.getCount() + " -- " + requestsCompleted.getCount() + " "
                                + (
                                f.isSuccess() ? f.getNow()
                                    : f.cause().getMessage()));
                    }
                });
            }
        }).start();
    }

    private String path() {
        return "/load-gen-app/io?id=" + rndmId();
    }

    private int rndmId() {
        return rndm.nextInt(1000);
    }
}
