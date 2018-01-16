package fk.prof.backend.http.policy;

import fk.prof.backend.ConfigManager;
import fk.prof.backend.Configuration;
import fk.prof.backend.deployer.VerticleDeployer;
import fk.prof.backend.deployer.impl.BackendHttpVerticleDeployer;
import fk.prof.backend.http.ApiPathConstants;
import fk.prof.backend.http.HttpHelper;
import fk.prof.backend.mock.MockPolicyData;
import fk.prof.backend.model.aggregation.impl.ActiveAggregationWindowsImpl;
import fk.prof.backend.model.assignment.AssociatedProcessGroups;
import fk.prof.backend.model.assignment.impl.AssociatedProcessGroupsImpl;
import fk.prof.backend.model.election.impl.InMemoryLeaderStore;
import fk.prof.backend.util.ProtoUtil;
import fk.prof.idl.Backend;
import fk.prof.idl.PolicyEntities;
import io.vertx.core.CompositeFuture;
import io.vertx.core.Vertx;
import io.vertx.core.VertxOptions;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpClient;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.HttpServer;
import io.vertx.ext.unit.Async;
import io.vertx.ext.unit.TestContext;
import io.vertx.ext.unit.junit.VertxUnitRunner;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.handler.BodyHandler;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.IOException;

import static fk.prof.backend.util.ZookeeperUtil.DELIMITER;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

@RunWith(VertxUnitRunner.class)
public class BackendPolicyAPITest {

    private HttpServer leaderServer;
    private HttpClient client;
    private static int backendPort;
    private static int leaderPort;
    private static String LEADER_IP = "localhost";
    private InMemoryLeaderStore inMemoryLeaderStore;
    private static Vertx vertx;

    @Before
    public void setUp(TestContext context) throws Exception {
        final Async async = context.async();
        ConfigManager.setDefaultSystemProperties();
        Configuration config = ConfigManager.loadConfig(BackendPolicyAPITest.class.getClassLoader().getResource("config.json").getFile());
        vertx = Vertx.vertx(new VertxOptions(config.getVertxOptions()));

        client = vertx.createHttpClient();
        leaderServer = vertx.createHttpServer();
        backendPort = config.getBackendHttpServerOpts().getPort();
        leaderPort = config.getLeaderHttpServerOpts().getPort();
        inMemoryLeaderStore = spy(new InMemoryLeaderStore(config.getIpAddress(), config.getLeaderHttpServerOpts().getPort()));
        AssociatedProcessGroups associatedProcessGroups = new AssociatedProcessGroupsImpl(config.getRecorderDefunctThresholdSecs());
        VerticleDeployer backendHttpVerticleDeployer = new BackendHttpVerticleDeployer(vertx, config, inMemoryLeaderStore, new ActiveAggregationWindowsImpl(), associatedProcessGroups);
        CompositeFuture future = backendHttpVerticleDeployer.deploy();
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
        leaderServer.close();
        vertx.close(result -> {
            if (result.succeeded()) {
                async.complete();
            } else {
                context.fail();
            }
        });
    }

    @Test
    public void testGetPolicyProxiedToLeader(TestContext context) {
        final Async async = context.async();
        Router router = Router.router(vertx);
        HttpHelper.attachHandlersToRoute(router, HttpMethod.GET,
                ApiPathConstants.LEADER_GET_POLICY_FOR_APP_CLUSTER_PROC, req -> {
                    PolicyEntities.VersionedPolicyDetails versionedPolicyDetails = MockPolicyData.getMockVersionedPolicyDetails(MockPolicyData.mockPolicyDetails.get(0), 0);
                    try {
                        req.response().end(ProtoUtil.buildBufferFromProto(versionedPolicyDetails));
                    } catch (IOException e) {
                        context.fail(e);
                    }
                });
        leaderServer.requestHandler(router::accept);
        leaderServer.listen(leaderPort, result -> {
            if (result.succeeded()) {
                when(inMemoryLeaderStore.getLeader()).thenReturn(Backend.LeaderDetail.newBuilder().setHost(LEADER_IP).setPort(leaderPort).build());
                String backendPolicyPath = ApiPathConstants.POLICY_PREFIX + DELIMITER + MockPolicyData.mockProcessGroups.get(0).getAppId() + DELIMITER + MockPolicyData.mockProcessGroups.get(0).getCluster() + DELIMITER + MockPolicyData.mockProcessGroups.get(0).getProcName();

                client.getNow(backendPort, "localhost", backendPolicyPath, res -> {
                    res.bodyHandler(buffer -> {
                        try {
                            context.assertEquals(buffer, ProtoUtil.buildBufferFromProto(MockPolicyData.getMockVersionedPolicyDetails(MockPolicyData.mockPolicyDetails.get(0), 0)));
                        } catch (IOException e) {
                            context.fail(e);
                        }
                        async.complete();
                    });
                });
            } else {
                context.fail(result.cause());
            }
        });
    }

    @Test
    public void testPutPolicyProxiedToLeader(TestContext context) {
        final Async async = context.async();
        Router router = Router.router(vertx);
        Buffer payloadAsBuffer = null;
        try {
            payloadAsBuffer = ProtoUtil.buildBufferFromProto(MockPolicyData.getMockVersionedPolicyDetails(MockPolicyData.mockPolicyDetails.get(0), 0));
        } catch (IOException e) {
            context.fail(e);
        }
        Buffer finalPayloadAsBuffer = payloadAsBuffer;
        HttpHelper.attachHandlersToRoute(router, HttpMethod.PUT,
                ApiPathConstants.LEADER_GET_POLICY_FOR_APP_CLUSTER_PROC,
                BodyHandler.create().setBodyLimit(1024 * 10), req -> {
                    try {
                        PolicyEntities.VersionedPolicyDetails expected = PolicyEntities.VersionedPolicyDetails.parseFrom(finalPayloadAsBuffer.getBytes());
                        PolicyEntities.VersionedPolicyDetails got = PolicyEntities.VersionedPolicyDetails.parseFrom(req.getBody().getBytes());
                        context.assertEquals(expected, got);
                        req.response().end(ProtoUtil.buildBufferFromProto(got.toBuilder().setVersion(got.getVersion() + 1).build()));
                    } catch (IOException e) {
                        context.fail(e);
                    }
                });
        leaderServer.requestHandler(router::accept);
        leaderServer.listen(leaderPort, result -> {
            if (result.succeeded()) {
                when(inMemoryLeaderStore.getLeader()).thenReturn(Backend.LeaderDetail.newBuilder().setHost(LEADER_IP).setPort(leaderPort).build());
                String backendPolicyPath = ApiPathConstants.POLICY_PREFIX + DELIMITER + MockPolicyData.mockProcessGroups.get(0).getAppId() + DELIMITER + MockPolicyData.mockProcessGroups.get(0).getCluster() + DELIMITER + MockPolicyData.mockProcessGroups.get(0).getProcName();

                client.put(backendPort, "localhost", backendPolicyPath, res -> {
                    res.bodyHandler(buffer -> {
                        try {
                            context.assertEquals(buffer, ProtoUtil.buildBufferFromProto(MockPolicyData.getMockVersionedPolicyDetails(MockPolicyData.mockPolicyDetails.get(0), 1)));
                        } catch (IOException e) {
                            context.fail(e);
                        }
                        async.complete();
                    });
                }).end(finalPayloadAsBuffer);
            } else {
                context.fail();
            }
        });
    }

    @Test
    public void testPostPolicyProxiedToLeader(TestContext context) {
        final Async async = context.async();
        Router router = Router.router(vertx);
        Buffer payloadAsBuffer = null;
        try {
            payloadAsBuffer = ProtoUtil.buildBufferFromProto(MockPolicyData.getMockVersionedPolicyDetails(MockPolicyData.mockPolicyDetails.get(0), -1));
        } catch (IOException e) {
            context.fail(e);
        }
        Buffer finalPayloadAsBuffer = payloadAsBuffer;
        HttpHelper.attachHandlersToRoute(router, HttpMethod.POST,
                ApiPathConstants.LEADER_GET_POLICY_FOR_APP_CLUSTER_PROC,
                BodyHandler.create().setBodyLimit(1024 * 10), req -> {
                    try {
                        PolicyEntities.VersionedPolicyDetails expected = PolicyEntities.VersionedPolicyDetails.parseFrom(finalPayloadAsBuffer.getBytes());
                        PolicyEntities.VersionedPolicyDetails got = PolicyEntities.VersionedPolicyDetails.parseFrom(req.getBody().getBytes());
                        context.assertEquals(expected, got);
                        req.response().end(ProtoUtil.buildBufferFromProto(got.toBuilder().setVersion(got.getVersion() + 1).build()));
                    } catch (IOException e) {
                        context.fail(e);
                    }
                });
        leaderServer.requestHandler(router::accept);
        leaderServer.listen(leaderPort, result -> {
            if (result.succeeded()) {
                when(inMemoryLeaderStore.getLeader()).thenReturn(Backend.LeaderDetail.newBuilder().setHost(LEADER_IP).setPort(leaderPort).build());
                String backendPolicyPath = ApiPathConstants.POLICY_PREFIX + DELIMITER + MockPolicyData.mockProcessGroups.get(0).getAppId() + DELIMITER + MockPolicyData.mockProcessGroups.get(0).getCluster() + DELIMITER + MockPolicyData.mockProcessGroups.get(0).getProcName();

                client.post(backendPort, "localhost", backendPolicyPath, res -> {
                    res.bodyHandler(buffer -> {
                        try {
                            context.assertEquals(buffer, ProtoUtil.buildBufferFromProto(MockPolicyData.getMockVersionedPolicyDetails(MockPolicyData.mockPolicyDetails.get(0), 0)));
                        } catch (IOException e) {
                            context.fail(e);
                        }
                        async.complete();
                    });
                }).end(finalPayloadAsBuffer);
            } else {
                context.fail(result.cause());
            }
        });
    }
}