package fk.prof.backend.http.processGroup;

import com.google.common.collect.Lists;
import fk.prof.backend.AssociationApiTest;
import fk.prof.backend.ConfigManager;
import fk.prof.backend.Configuration;
import fk.prof.backend.deployer.VerticleDeployer;
import fk.prof.backend.deployer.impl.LeaderHttpVerticleDeployer;
import fk.prof.backend.http.ApiPathConstants;
import fk.prof.backend.model.association.BackendAssociationStore;
import fk.prof.backend.model.policy.PolicyStore;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.vertx.core.CompositeFuture;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.VertxOptions;
import io.vertx.core.http.HttpClient;
import io.vertx.ext.unit.Async;
import io.vertx.ext.unit.TestContext;
import io.vertx.ext.unit.junit.VertxUnitRunner;
import org.apache.curator.test.TestingServer;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentMatchers;
import org.mockito.internal.util.collections.Sets;

import java.util.Random;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Tests for ProcessGroupAPIs in LeaderHttpVerticle which
 * are getAppIds, getClusterIds, getProcNames
 * Created by rohit.patiyal on 30/05/17.
 */
@RunWith(VertxUnitRunner.class)
public class LeaderProcessGroupListingAPITest {
    public static final String DELIMITER = "/";
    private static final String P_APP_ID = "app1";
    private static final String NP_APP_ID = "foo";
    private static final String P_CLUSTER_ID = "cluster1";
    private static final String NP_CLUSTER_ID = "bar";
    private static final String P_PROC = "process1";
    private static final String NP_PROC = "main";

    private TestingServer testingServer;
    private PolicyStore policyStore;
    private Vertx vertx;
    private HttpClient client;
    private int leaderPort;

    @Before
    public void setUp(TestContext context) throws Exception {
        final Async async = context.async();
        ConfigManager.setDefaultSystemProperties();
        testingServer = new TestingServer();

        Configuration config = ConfigManager.loadConfig(AssociationApiTest.class.getClassLoader().getResource("config.json").getFile());
        vertx = Vertx.vertx(new VertxOptions(config.getVertxOptions()));
        leaderPort = config.getLeaderHttpServerOpts().getPort();

        BackendAssociationStore backendAssociationStore = mock(BackendAssociationStore.class);
        policyStore = mock(PolicyStore.class);
        client = vertx.createHttpClient();
        VerticleDeployer leaderHttpVerticleDeployer = new LeaderHttpVerticleDeployer(vertx, config, backendAssociationStore, policyStore);
        CompositeFuture future = leaderHttpVerticleDeployer.deploy();
        future.setHandler(aR -> {
            if (aR.succeeded())
                async.complete();
            else
                context.fail();
        });
    }

    @After
    public void tearDown(TestContext context) throws Exception {
        final Async async = context.async();
        client.close();
        testingServer.close();
        vertx.close(result -> {
            if (result.succeeded()) {
                async.complete();
            } else {
                context.fail();
            }
        });
    }

    @Test
    public void testGetAppIdsRoute(TestContext context) throws Exception {
        final Async async = context.async();
        String pPrefixSet = "(^$|a|ap|app|app1)";
        String npPrefixSet = "(f|fo|foo)";

        when(policyStore.getAppIds(ArgumentMatchers.matches(pPrefixSet))).then(invocation -> Sets.newSet(P_APP_ID));
        when(policyStore.getAppIds(ArgumentMatchers.matches(npPrefixSet))).then(invocation -> null);
        when(policyStore.getAppIds(null)).then(invocation ->Sets.newSet(P_APP_ID));

        Future<Void> pCorrectPrefix = Future.future();
        Future<Void> pIncorrectPrefix = Future.future();
        Future<Void> pNoPrefix = Future.future();
        Future<Void> nullPrefix = Future.future();

        client.getNow(leaderPort, "localhost", ApiPathConstants.LEADER_GET_APPS + "?prefix=" + P_APP_ID.substring(0, 1 + new Random().nextInt(P_APP_ID.length() - 1)), httpClientResponse -> {
            context.assertEquals(httpClientResponse.statusCode(), HttpResponseStatus.OK.code());
            httpClientResponse.bodyHandler(buffer -> {
                context.assertTrue(buffer.toString().contains(P_APP_ID));
                context.assertFalse(buffer.toString().contains(NP_APP_ID));
                pCorrectPrefix.complete();
            });
        });
        client.getNow(leaderPort, "localhost", ApiPathConstants.LEADER_GET_APPS, httpClientResponse -> {
            context.assertEquals(httpClientResponse.statusCode(), HttpResponseStatus.OK.code());
            httpClientResponse.bodyHandler(buffer -> {
                context.assertTrue(buffer.toString().contains(P_APP_ID));
                context.assertFalse(buffer.toString().contains(NP_APP_ID));
                nullPrefix.complete();
            });
        });
        client.getNow(leaderPort, "localhost", ApiPathConstants.LEADER_GET_APPS + "?prefix=", httpClientResponse -> {
            context.assertEquals(httpClientResponse.statusCode(), HttpResponseStatus.OK.code());
            httpClientResponse.bodyHandler(buffer -> {
                context.assertTrue(buffer.toString().contains(P_APP_ID));
                context.assertFalse(buffer.toString().contains(NP_APP_ID));
                pNoPrefix.complete();
            });
        });
        client.getNow(leaderPort, "localhost", ApiPathConstants.LEADER_GET_APPS + "?prefix=" + NP_APP_ID.substring(0, 1 + new Random().nextInt(NP_APP_ID.length() - 1)), httpClientResponse -> {
            context.assertEquals(httpClientResponse.statusCode(), HttpResponseStatus.OK.code());
            httpClientResponse.bodyHandler(buffer -> {
                context.assertFalse(buffer.toString().contains(P_APP_ID));
                context.assertFalse(buffer.toString().contains(NP_APP_ID));
                pIncorrectPrefix.complete();
            });
        });

        CompositeFuture.all(pCorrectPrefix, pIncorrectPrefix, pNoPrefix).setHandler(compositeFutureAsyncResult -> async.complete());

    }


    @Test
    public void testGetClusterIdsRoute(TestContext testContext) throws Exception {
        final Async async = testContext.async();
        String pPrefixSet = "(^$|c|cl|clu|clus|clust|cluste|cluster|cluster1)";
        String npPrefixSet = "(b|ba|bar)";

        when(policyStore.getClusterIds(eq(P_APP_ID), ArgumentMatchers.matches(pPrefixSet))).then(invocation -> Sets.newSet(P_CLUSTER_ID));
        when(policyStore.getClusterIds(eq(P_APP_ID), ArgumentMatchers.matches(npPrefixSet))).then(invocation -> null);
        when(policyStore.getClusterIds(eq(NP_APP_ID), ArgumentMatchers.matches(pPrefixSet))).then(invocation -> null);
        when(policyStore.getClusterIds(eq(NP_APP_ID), ArgumentMatchers.matches(npPrefixSet))).then(invocation -> null);
        when(policyStore.getClusterIds(eq(P_APP_ID), eq(null))).then(invocation -> Sets.newSet(P_CLUSTER_ID));

        Future<Void> pAndCorrectPrefix = Future.future();
        Future<Void> pAndIncorrectPrefix = Future.future();
        Future<Void> pAndNoPrefix = Future.future();

        Future<Void> npAndPPrefix = Future.future();
        Future<Void> npAndNpPrefix = Future.future();
        Future<Void> npAndNoPrefix = Future.future();
        Future<Void> nullPrefix = Future.future();


        client.getNow(leaderPort, "localhost", ApiPathConstants.LEADER_PREFIX + "/clusters/" + P_APP_ID, httpClientResponse -> {
            testContext.assertEquals(httpClientResponse.statusCode(), HttpResponseStatus.OK.code());
            httpClientResponse.bodyHandler(buffer -> {
                testContext.assertTrue(buffer.toString().contains(P_CLUSTER_ID));
                testContext.assertFalse(buffer.toString().contains(NP_CLUSTER_ID));
                nullPrefix.complete();
            });
        });
        client.getNow(leaderPort, "localhost", ApiPathConstants.LEADER_PREFIX + "/clusters/" + P_APP_ID + "?prefix=", httpClientResponse -> {
            testContext.assertEquals(httpClientResponse.statusCode(), HttpResponseStatus.OK.code());
            httpClientResponse.bodyHandler(buffer -> {
                testContext.assertTrue(buffer.toString().contains(P_CLUSTER_ID));
                testContext.assertFalse(buffer.toString().contains(NP_CLUSTER_ID));
                pAndNoPrefix.complete();
            });
        });
        client.getNow(leaderPort, "localhost", ApiPathConstants.LEADER_PREFIX + "/clusters/" + P_APP_ID + "?prefix=" + P_CLUSTER_ID.substring(0, 1 + new Random().nextInt(P_CLUSTER_ID.length() - 1)), httpClientResponse -> {
            testContext.assertEquals(httpClientResponse.statusCode(), HttpResponseStatus.OK.code());
            httpClientResponse.bodyHandler(buffer -> {
                testContext.assertTrue(buffer.toString().contains(P_CLUSTER_ID));
                testContext.assertFalse(buffer.toString().contains(NP_CLUSTER_ID));
                pAndCorrectPrefix.complete();
            });
        });
        client.getNow(leaderPort, "localhost", ApiPathConstants.LEADER_PREFIX + "/clusters/" + P_APP_ID + "?prefix=" + NP_CLUSTER_ID.substring(0, 1 + new Random().nextInt(NP_CLUSTER_ID.length() - 1)), httpClientResponse -> {
            testContext.assertEquals(httpClientResponse.statusCode(), HttpResponseStatus.OK.code());
            httpClientResponse.bodyHandler(buffer -> {
                testContext.assertFalse(buffer.toString().contains(P_CLUSTER_ID));
                testContext.assertFalse(buffer.toString().contains(NP_CLUSTER_ID));
                pAndIncorrectPrefix.complete();
            });
        });

        client.getNow(leaderPort, "localhost", ApiPathConstants.LEADER_PREFIX + "/clusters/" + NP_APP_ID + "?prefix=" + P_CLUSTER_ID.substring(0, 1 + new Random().nextInt(P_CLUSTER_ID.length() - 1)), httpClientResponse -> {
            testContext.assertEquals(httpClientResponse.statusCode(), HttpResponseStatus.OK.code());
            httpClientResponse.bodyHandler(buffer -> {
                testContext.assertFalse(buffer.toString().contains(P_CLUSTER_ID));
                testContext.assertFalse(buffer.toString().contains(NP_CLUSTER_ID));
                npAndPPrefix.complete();
            });
        });
        client.getNow(leaderPort, "localhost", ApiPathConstants.LEADER_PREFIX + "/clusters/" + NP_APP_ID + "?prefix=" + NP_CLUSTER_ID.substring(0, 1 + new Random().nextInt(NP_CLUSTER_ID.length() - 1)), httpClientResponse -> {
            testContext.assertEquals(httpClientResponse.statusCode(), HttpResponseStatus.OK.code());
            httpClientResponse.bodyHandler(buffer -> {
                testContext.assertFalse(buffer.toString().contains(P_CLUSTER_ID));
                testContext.assertFalse(buffer.toString().contains(NP_CLUSTER_ID));
                npAndNpPrefix.complete();
            });
        });
        client.getNow(leaderPort, "localhost", ApiPathConstants.LEADER_PREFIX + "/clusters/" + NP_APP_ID + "?prefix=", httpClientResponse -> {
            testContext.assertEquals(httpClientResponse.statusCode(), HttpResponseStatus.OK.code());
            httpClientResponse.bodyHandler(buffer -> {
                testContext.assertFalse(buffer.toString().contains(P_CLUSTER_ID));
                testContext.assertFalse(buffer.toString().contains(NP_CLUSTER_ID));
                npAndNoPrefix.complete();
            });
        });

        CompositeFuture.all(Lists.newArrayList(pAndCorrectPrefix, pAndIncorrectPrefix, pAndNoPrefix, npAndPPrefix, npAndNpPrefix, npAndNoPrefix, nullPrefix)).setHandler(compositeFutureAsyncResult -> async.complete());
    }

    @Test
    public void testGetProcNameRoute(TestContext testContext) throws Exception {
        final Async async = testContext.async();
        String pPrefixSet = "(^$|p|pr|pro|proc|proce|proces|process|process1)";
        String npPrefixSet = "(m|ma|mai|main)";

        when(policyStore.getProcNames(eq(P_APP_ID), eq(P_CLUSTER_ID), ArgumentMatchers.matches(pPrefixSet))).then(invocation -> Sets.newSet(P_PROC));
        when(policyStore.getProcNames(eq(P_APP_ID), eq(P_CLUSTER_ID), ArgumentMatchers.matches(npPrefixSet))).then(invocation -> null);
        when(policyStore.getProcNames(eq(NP_APP_ID), eq(NP_CLUSTER_ID), ArgumentMatchers.matches(pPrefixSet))).then(invocation -> null);
        when(policyStore.getProcNames(eq(NP_APP_ID), eq(NP_CLUSTER_ID), ArgumentMatchers.matches(npPrefixSet))).then(invocation -> null);
        when(policyStore.getProcNames(eq(P_APP_ID), eq(P_CLUSTER_ID), eq(null))).then(invocation -> Sets.newSet(P_PROC));

        Future<Void> pAndCorrectPrefix = Future.future();
        Future<Void> pAndIncorrectPrefix = Future.future();
        Future<Void> pAndNoPrefix = Future.future();

        Future<Void> npAndPPrefix = Future.future();
        Future<Void> npAndNpPrefix = Future.future();
        Future<Void> npAndNoPrefix = Future.future();
        Future<Void> nullPrefix = Future.future();

        client.getNow(leaderPort, "localhost", ApiPathConstants.LEADER_PREFIX + "/procs/" + P_APP_ID + DELIMITER + P_CLUSTER_ID, httpClientResponse -> {
            testContext.assertEquals(httpClientResponse.statusCode(), HttpResponseStatus.OK.code());
            httpClientResponse.bodyHandler(buffer -> {
                testContext.assertTrue(buffer.toString().contains(P_PROC));
                testContext.assertFalse(buffer.toString().contains(NP_PROC));
                nullPrefix.complete();
            });

        });
        client.getNow(leaderPort, "localhost", ApiPathConstants.LEADER_PREFIX + "/procs/" + P_APP_ID + DELIMITER + P_CLUSTER_ID + "?prefix=" + P_PROC.substring(0, 1 + new Random().nextInt(P_PROC.length() - 1)), httpClientResponse -> {
            testContext.assertEquals(httpClientResponse.statusCode(), HttpResponseStatus.OK.code());
            httpClientResponse.bodyHandler(buffer -> {
                testContext.assertTrue(buffer.toString().contains(P_PROC));
                testContext.assertFalse(buffer.toString().contains(NP_PROC));
                pAndCorrectPrefix.complete();
            });

        });
        client.getNow(leaderPort, "localhost", ApiPathConstants.LEADER_PREFIX + "/procs/" + P_APP_ID + DELIMITER + P_CLUSTER_ID + "?prefix=" + NP_PROC.substring(0, 1 + new Random().nextInt(NP_PROC.length() - 1)), httpClientResponse -> {
            testContext.assertEquals(httpClientResponse.statusCode(), HttpResponseStatus.OK.code());
            httpClientResponse.bodyHandler(buffer -> {
                testContext.assertFalse(buffer.toString().contains(P_PROC));
                testContext.assertFalse(buffer.toString().contains(NP_PROC));
                pAndIncorrectPrefix.complete();
            });

        });
        client.getNow(leaderPort, "localhost", ApiPathConstants.LEADER_PREFIX + "/procs/" + P_APP_ID + DELIMITER + P_CLUSTER_ID + "?prefix=", httpClientResponse -> {
            testContext.assertEquals(httpClientResponse.statusCode(), HttpResponseStatus.OK.code());
            httpClientResponse.bodyHandler(buffer -> {
                testContext.assertTrue(buffer.toString().contains(P_PROC));
                testContext.assertFalse(buffer.toString().contains(NP_PROC));
                pAndNoPrefix.complete();
            });

        });
        client.getNow(leaderPort, "localhost", ApiPathConstants.LEADER_PREFIX + "/procs/" + NP_APP_ID + DELIMITER + NP_CLUSTER_ID + "?prefix=" + P_PROC.substring(0, 1 + new Random().nextInt(P_PROC.length() - 1)), httpClientResponse -> {
            testContext.assertEquals(httpClientResponse.statusCode(), HttpResponseStatus.OK.code());
            httpClientResponse.bodyHandler(buffer -> {
                testContext.assertFalse(buffer.toString().contains(P_PROC));
                testContext.assertFalse(buffer.toString().contains(NP_PROC));
                npAndPPrefix.complete();
            });

        });
        client.getNow(leaderPort, "localhost", ApiPathConstants.LEADER_PREFIX + "/procs/" + NP_APP_ID + DELIMITER + NP_CLUSTER_ID + "?prefix=" + NP_PROC.substring(0, 1 + new Random().nextInt(NP_PROC.length() - 1)), httpClientResponse -> {
            testContext.assertEquals(httpClientResponse.statusCode(), HttpResponseStatus.OK.code());
            httpClientResponse.bodyHandler(buffer -> {
                testContext.assertFalse(buffer.toString().contains(P_PROC));
                testContext.assertFalse(buffer.toString().contains(NP_PROC));
                npAndNpPrefix.complete();
            });

        });
        client.getNow(leaderPort, "localhost", ApiPathConstants.LEADER_PREFIX + "/procs/" + NP_APP_ID + DELIMITER + NP_CLUSTER_ID + "?prefix=", httpClientResponse -> {
            testContext.assertEquals(httpClientResponse.statusCode(), HttpResponseStatus.OK.code());
            httpClientResponse.bodyHandler(buffer -> {
                testContext.assertFalse(buffer.toString().contains(P_PROC));
                testContext.assertFalse(buffer.toString().contains(NP_PROC));
                npAndNoPrefix.complete();
            });

        });

        CompositeFuture.all(Lists.newArrayList(pAndCorrectPrefix, pAndIncorrectPrefix, pAndNoPrefix, npAndPPrefix, npAndNpPrefix, npAndNoPrefix, nullPrefix)).setHandler(compositeFutureAsyncResult -> async.complete());

    }

}

