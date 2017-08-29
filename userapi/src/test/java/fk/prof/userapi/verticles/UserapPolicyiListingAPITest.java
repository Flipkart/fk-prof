package fk.prof.userapi.verticles;

import fk.prof.userapi.Configuration;
import fk.prof.userapi.UserapiConfigManager;
import fk.prof.userapi.api.ProfileStoreAPI;
import fk.prof.userapi.deployer.VerticleDeployer;
import fk.prof.userapi.deployer.impl.UserapiHttpVerticleDeployer;
import fk.prof.userapi.http.UserapiApiPathConstants;
import fk.prof.userapi.http.UserapiHttpHelper;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.vertx.core.Vertx;
import io.vertx.core.VertxOptions;
import io.vertx.core.http.HttpClient;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.HttpServer;
import io.vertx.core.json.Json;
import io.vertx.ext.unit.Async;
import io.vertx.ext.unit.TestContext;
import io.vertx.ext.unit.junit.VertxUnitRunner;
import io.vertx.ext.web.Router;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mockito;
import org.mockito.internal.util.collections.Sets;

import java.util.Random;

import static fk.prof.userapi.http.UserapiApiPathConstants.*;
/**
 * Tests for ProcessGroupListingAPIs in LeaderHttpVerticle which
 * are getAppIds, getClusterIds, getProcNames
 * Created by rohit.patiyal on 30/05/17.
 */
@RunWith(VertxUnitRunner.class)
public class UserapPolicyiListingAPITest {
    public static final String DELIMITER = "/";
    private static final String APP_ID = "app1";
    private static final String CLUSTER_ID = "cluster1";
    private static final String PROC = "process1";


    private HttpServer backendServer;
    private HttpClient client;
    private static Vertx vertx;
    private static int backendPort;
    private static int userapiPort;
    @Before
    public void setUp(TestContext context) throws Exception {
        final Async async = context.async();
        UserapiConfigManager.setDefaultSystemProperties();
        Configuration config = UserapiConfigManager.loadConfig(UserapiPolicyAPITest.class.getClassLoader().getResource("userapi-conf.json").getFile());
        backendPort = config.getBackendConfig().getPort();
        userapiPort = config.getHttpConfig().getHttpPort();
        vertx = Vertx.vertx(new VertxOptions(config.getVertxOptions()));
        client = vertx.createHttpClient();
        backendServer = vertx.createHttpServer();

        VerticleDeployer userapiHttpVerticleDeployer = new UserapiHttpVerticleDeployer(vertx, config, Mockito.mock(ProfileStoreAPI.class));
        userapiHttpVerticleDeployer.deploy().setHandler(aR -> {
            if(aR.succeeded()) async.complete();
            else context.fail();
        });
    }

    @After
    public void tearDown(TestContext context) throws Exception {
        final Async async = context.async();
        backendServer.close();
        client.close();
        vertx.close(result -> {
            if(result.succeeded()) {
                async.complete();
            } else {
                context.fail();
            }
        });
    }

    @Test
    public void testGetAppIdsRoute(TestContext context) throws Exception {
        final Async async = context.async();
        String userapiGetAppIdsURI = UserapiApiPathConstants.POLICIES_APPS + "?prefix=" + APP_ID.substring(0, 1 + new Random().nextInt(APP_ID.length() - 1));
        Router router = Router.router(vertx);
        UserapiHttpHelper.attachHandlersToRoute(router, HttpMethod.GET,
                UserapiApiPathConstants.POLICIES_APPS.substring((META_PREFIX + POLICIES_PREFIX).length()), routingContext -> {
                    context.assertEquals(userapiGetAppIdsURI.substring((META_PREFIX + POLICIES_PREFIX).length()), routingContext.normalisedPath()+"?"+routingContext.request().query());
                    routingContext.response().end(Json.encode(Sets.newSet(APP_ID)));
                });
        backendServer.requestHandler(router::accept);
        backendServer.listen(backendPort, result -> {
            if (result.succeeded()) {
                client.getNow(userapiPort, "localhost", userapiGetAppIdsURI, httpClientResponse -> {
                    context.assertEquals(httpClientResponse.statusCode(), HttpResponseStatus.OK.code());
                    httpClientResponse.bodyHandler(buffer -> {
                        context.assertEquals(buffer.toJsonArray().size(), 1);
                        context.assertTrue(buffer.toString().contains(APP_ID));
                        async.complete();
                    });
                });
            } else {
                context.fail();
            }
        });
    }

    @Test
    public void testGetClusterIdsRoute(TestContext context) throws Exception {
        final Async async = context.async();
        String userapiGetClusterIdsURI = (META_PREFIX + POLICIES_PREFIX) + "/clusters/" + APP_ID + "?prefix=" + CLUSTER_ID.substring(0, 1 + new Random().nextInt(CLUSTER_ID.length() - 1));
        Router router = Router.router(vertx);
        UserapiHttpHelper.attachHandlersToRoute(router, HttpMethod.GET,
                UserapiApiPathConstants.POLICIES_CLUSTERS_FOR_APP.substring((META_PREFIX + POLICIES_PREFIX).length()), routingContext -> {
                    context.assertEquals(userapiGetClusterIdsURI.substring((META_PREFIX + POLICIES_PREFIX).length()), routingContext.normalisedPath()+"?"+routingContext.request().query());
                    routingContext.response().end(Json.encode(Sets.newSet(CLUSTER_ID)));
                });
        backendServer.requestHandler(router::accept);
        backendServer.listen(backendPort, result -> {
            if (result.succeeded()) {
                client.getNow(userapiPort, "localhost", userapiGetClusterIdsURI, httpClientResponse -> {
                    context.assertEquals(httpClientResponse.statusCode(), HttpResponseStatus.OK.code());
                    httpClientResponse.bodyHandler(buffer -> {
                        context.assertEquals(buffer.toJsonArray().size(), 1);
                        context.assertTrue(buffer.toString().contains(CLUSTER_ID));
                        async.complete();
                    });
                });
            } else {
                context.fail();
            }
        });
    }


    @Test
    public void testGetProcNamesRoute(TestContext context) throws Exception {
        final Async async = context.async();
        String userapiGetProcNamesURI = (META_PREFIX + POLICIES_PREFIX) + "/procs/" + APP_ID + DELIMITER + CLUSTER_ID + "?prefix=" + PROC.substring(0, 1 + new Random().nextInt(PROC.length() - 1));
        Router router = Router.router(vertx);
        UserapiHttpHelper.attachHandlersToRoute(router, HttpMethod.GET,
                UserapiApiPathConstants.POLICIES_PROCS_FOR_APP_CLUSTER.substring((META_PREFIX + POLICIES_PREFIX).length()), req -> req.response().end(Json.encode(Sets.newSet(PROC))));
        backendServer.requestHandler(router::accept);
        backendServer.listen(backendPort, result -> {
            if (result.succeeded()) {
                client.getNow(userapiPort, "localhost", userapiGetProcNamesURI, httpClientResponse -> {
                    context.assertEquals(httpClientResponse.statusCode(), HttpResponseStatus.OK.code());
                    httpClientResponse.bodyHandler(buffer -> {
                        context.assertEquals(buffer.toJsonArray().size(), 1);
                        context.assertTrue(buffer.toString().contains(PROC));
                        async.complete();
                    });
                });
            } else {
                context.fail();
            }
        });
    }
}