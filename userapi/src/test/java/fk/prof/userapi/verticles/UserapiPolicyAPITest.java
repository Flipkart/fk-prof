package fk.prof.userapi.verticles;

import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.util.JsonFormat;
import fk.prof.idl.PolicyEntities;
import fk.prof.userapi.Configuration;
import fk.prof.userapi.UserapiConfigManager;
import fk.prof.userapi.api.ProfileStoreAPI;
import fk.prof.userapi.deployer.VerticleDeployer;
import fk.prof.userapi.deployer.impl.UserapiHttpVerticleDeployer;
import fk.prof.userapi.http.UserapiApiPathConstants;
import fk.prof.userapi.http.UserapiHttpHelper;
import fk.prof.userapi.util.ProtoUtil;
import io.vertx.core.Vertx;
import io.vertx.core.VertxOptions;
import io.vertx.core.http.HttpClient;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.HttpServer;
import io.vertx.ext.unit.Async;
import io.vertx.ext.unit.TestContext;
import io.vertx.ext.unit.junit.VertxUnitRunner;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.handler.BodyHandler;
import mock.MockPolicyData;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mockito;

import java.io.IOException;

/**
 * Tests for Policy APIs of HttpVerticle
 * Created by rohitpatiyal on 20/6/17.
 */
@RunWith(VertxUnitRunner.class)
public class UserapiPolicyAPITest {
    private static final String DELIMITER = "/";

    private HttpServer backendServer;
    private HttpClient client;
    private static Vertx vertx;
    private static int backendPort;
    private static int userapiPort;

    @Before
    public void setup(TestContext context) throws IOException {
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
    public void testGetPolicyProxiedToBackend(TestContext context) throws Exception {
        final Async async = context.async();
        Router router = Router.router(vertx);
        UserapiHttpHelper.attachHandlersToRoute(router, HttpMethod.GET,
                UserapiApiPathConstants.GET_POLICY_FOR_APP_CLUSTER_PROC, req ->{
                    try {
                        PolicyEntities.VersionedPolicyDetails versionedPolicyDetails = MockPolicyData.getMockVersionedPolicyDetails(MockPolicyData.mockPolicyDetails.get(0),0);
                        req.response().end(ProtoUtil.buildBufferFromProto(versionedPolicyDetails));
                    } catch (IOException e) {
                        context.fail(e);
                    }
                });
        backendServer.requestHandler(router::accept);
        backendServer.listen(backendPort, result -> {
            if(result.succeeded()){
                String userapiPolicyPath = UserapiApiPathConstants.POLICY_PREFIX + DELIMITER + MockPolicyData.mockProcessGroups.get(0).getAppId() + DELIMITER + MockPolicyData.mockProcessGroups.get(0).getCluster() + DELIMITER + MockPolicyData.mockProcessGroups.get(0).getProcName();
                client.getNow(userapiPort, "localhost", userapiPolicyPath, res -> {
                    res.bodyHandler(buffer -> {
                        try {
                            context.assertEquals(buffer.toString(), JsonFormat.printer().print(MockPolicyData.getMockVersionedPolicyDetails(MockPolicyData.mockPolicyDetails.get(0), 0)));
                        } catch (InvalidProtocolBufferException e) {
                            context.fail(e);
                        }
                        async.complete();
                   });
                });
            }else{
                context.fail();
            }
        });
    }

    @Test
    public void testPutPolicyProxiedToLeader(TestContext context) {
        final Async async = context.async();
        Router router = Router.router(vertx);
        String jsonPayload = null;
        try {
            jsonPayload = JsonFormat.printer().print(MockPolicyData.getMockVersionedPolicyDetails(MockPolicyData.mockPolicyDetails.get(0), 0));
        } catch (IOException e) {
            context.fail(e);
        }
        String finalJsonPayload = jsonPayload;
        UserapiHttpHelper.attachHandlersToRoute(router, HttpMethod.PUT,
                UserapiApiPathConstants.PUT_POLICY_FOR_APP_CLUSTER_PROC,
                BodyHandler.create().setBodyLimit(1024 * 10), req -> {
                    try {
                        PolicyEntities.VersionedPolicyDetails expected = MockPolicyData.getMockVersionedPolicyDetails(MockPolicyData.mockPolicyDetails.get(0),0);
                        PolicyEntities.VersionedPolicyDetails got = PolicyEntities.VersionedPolicyDetails.parseFrom(req.getBody().getBytes());
                        context.assertEquals(expected, got);
                        req.response().end(ProtoUtil.buildBufferFromProto(got.toBuilder().setVersion(got.getVersion() + 1).build()));
                    } catch (IOException e) {
                        context.fail(e);
                    }
                });
        backendServer.requestHandler(router::accept);
        backendServer.listen(backendPort, result -> {
            if (result.succeeded()) {
                String userapiPolicyPath = UserapiApiPathConstants.POLICY_PREFIX + DELIMITER + MockPolicyData.mockProcessGroups.get(0).getAppId() + DELIMITER + MockPolicyData.mockProcessGroups.get(0).getCluster() + DELIMITER + MockPolicyData.mockProcessGroups.get(0).getProcName();

                client.put(userapiPort, "localhost", userapiPolicyPath, res -> {
                    res.bodyHandler(buffer -> {
                        try {
                            context.assertEquals(buffer.toString(), JsonFormat.printer().print(MockPolicyData.getMockVersionedPolicyDetails(MockPolicyData.mockPolicyDetails.get(0), 1)));
                        } catch (InvalidProtocolBufferException e) {
                            context.fail(e);
                        }
                        async.complete();
                    });
                }).end(finalJsonPayload);
            } else {
                context.fail();
            }
        });
    }

    @Test
    public void testPostPolicyProxiedToLeader(TestContext context) {
        final Async async = context.async();
        Router router = Router.router(vertx);
        String jsonPayload = null;
        try {
            jsonPayload = JsonFormat.printer().print(MockPolicyData.getMockVersionedPolicyDetails(MockPolicyData.mockPolicyDetails.get(0), -1));
        } catch (IOException e) {
            context.fail(e);
        }
        String finalJsonPayload = jsonPayload;
        UserapiHttpHelper.attachHandlersToRoute(router, HttpMethod.POST,
                UserapiApiPathConstants.POST_POLICY_FOR_APP_CLUSTER_PROC,
                BodyHandler.create().setBodyLimit(1024 * 10), req -> {
                    try {
                        PolicyEntities.VersionedPolicyDetails expected = MockPolicyData.getMockVersionedPolicyDetails(MockPolicyData.mockPolicyDetails.get(0),-1);
                        PolicyEntities.VersionedPolicyDetails got = PolicyEntities.VersionedPolicyDetails.parseFrom(req.getBody().getBytes());
                        context.assertEquals(expected, got);
                        req.response().end(ProtoUtil.buildBufferFromProto(got.toBuilder().setVersion(got.getVersion() + 1).build()));
                    } catch (IOException e) {
                        context.fail(e);
                    }
                });
        backendServer.requestHandler(router::accept);
        backendServer.listen(backendPort, result -> {
            if (result.succeeded()) {
                String userapiPolicyPath = UserapiApiPathConstants.POLICY_PREFIX + DELIMITER + MockPolicyData.mockProcessGroups.get(0).getAppId() + DELIMITER + MockPolicyData.mockProcessGroups.get(0).getCluster() + DELIMITER + MockPolicyData.mockProcessGroups.get(0).getProcName();
                client.post(userapiPort, "localhost", userapiPolicyPath, res -> {
                    res.bodyHandler(buffer -> {
                        try {
                            context.assertEquals(buffer.toString(), JsonFormat.printer().print(MockPolicyData.getMockVersionedPolicyDetails(MockPolicyData.mockPolicyDetails.get(0), 0)));
                        } catch (InvalidProtocolBufferException e) {
                            context.fail(e);
                        }
                        async.complete();
                    });
                }).end(finalJsonPayload);
            } else {
                context.fail();
            }
        });
    }
}
