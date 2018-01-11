package fk.prof.backend.http.policy;

import fk.prof.backend.ConfigManager;
import fk.prof.backend.Configuration;
import fk.prof.backend.deployer.VerticleDeployer;
import fk.prof.backend.deployer.impl.LeaderHttpVerticleDeployer;
import fk.prof.backend.exception.PolicyException;
import fk.prof.backend.http.ApiPathConstants;
import fk.prof.backend.mock.MockPolicyData;
import fk.prof.backend.model.association.BackendAssociationStore;
import fk.prof.backend.model.policy.PolicyStore;
import fk.prof.backend.util.ProtoUtil;
import fk.prof.backend.util.proto.RecorderProtoUtil;
import fk.prof.idl.PolicyEntities;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.vertx.core.CompositeFuture;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.VertxOptions;
import io.vertx.core.http.HttpClient;
import io.vertx.ext.unit.Async;
import io.vertx.ext.unit.TestContext;
import io.vertx.ext.unit.junit.VertxUnitRunner;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.IOException;
import java.util.concurrent.CompletableFuture;

import static fk.prof.backend.util.ZookeeperUtil.DELIMITER;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Test for policy APIs in LeaderHttpVerticle
 * Created by rohit.patiyal on 14/03/17.
 */
@RunWith(VertxUnitRunner.class)
public class LeaderPolicyAPITest {
    private PolicyStore policyStore;
    private Vertx vertx;
    private HttpClient client;
    private int leaderPort;

    @Before
    public void setUp(TestContext context) throws Exception {
        final Async async = context.async();
        ConfigManager.setDefaultSystemProperties();

        Configuration config = ConfigManager.loadConfig(LeaderPolicyAPITest.class.getClassLoader().getResource("config.json").getFile());
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

    @Test
    public void testGetPolicy(TestContext context) throws Exception {
        final Async async = context.async();
        //PRESENT
        when(policyStore.getVersionedPolicy(MockPolicyData.mockProcessGroups.get(0))).thenAnswer(invocation -> MockPolicyData.getMockVersionedPolicyDetails(MockPolicyData.mockPolicyDetails.get(0), 0));
        //NOT PRESENT
        when(policyStore.getVersionedPolicy(MockPolicyData.mockProcessGroups.get(1))).thenAnswer(invocation -> null);

        CompletableFuture<Void> f1 = new CompletableFuture<>();
        CompletableFuture<Void> f2 = new CompletableFuture<>();

        client.getNow(leaderPort, "localhost", ApiPathConstants.LEADER_PREFIX + ApiPathConstants.POLICY_PREFIX + DELIMITER + MockPolicyData.mockProcessGroups.get(0).getAppId() + DELIMITER + MockPolicyData.mockProcessGroups.get(0).getCluster() + DELIMITER + MockPolicyData.mockProcessGroups.get(0).getProcName(), httpClientResponse -> {
            context.assertEquals(httpClientResponse.statusCode(), HttpResponseStatus.OK.code());
            httpClientResponse.bodyHandler(buffer -> {
                try {
                    context.assertEquals(buffer, ProtoUtil.buildBufferFromProto(MockPolicyData.getMockVersionedPolicyDetails(MockPolicyData.mockPolicyDetails.get(0), 0)));
                } catch (IOException e) {
                    f1.completeExceptionally(e);
                }
                f1.complete(null);
            });
        });

        client.getNow(leaderPort, "localhost", ApiPathConstants.LEADER_PREFIX + ApiPathConstants.POLICY_PREFIX + DELIMITER + MockPolicyData.mockProcessGroups.get(1).getAppId() + DELIMITER + MockPolicyData.mockProcessGroups.get(1).getCluster() + DELIMITER + MockPolicyData.mockProcessGroups.get(1).getProcName(), httpClientResponse -> {
            context.assertEquals(httpClientResponse.statusCode(), HttpResponseStatus.NOT_FOUND.code());
            httpClientResponse.bodyHandler(buffer -> {
                context.assertTrue(buffer.toString().contains("not found"));
                f2.complete(null);
            });
        });

        CompletableFuture.allOf(f1, f2).whenComplete((aVoid, throwable) -> async.complete());
    }

    @Test
    public void testCreatePolicy(TestContext context) throws Exception {
        final Async async = context.async();
        when(policyStore.createVersionedPolicy(MockPolicyData.mockProcessGroups.get(0), MockPolicyData.getMockVersionedPolicyDetails(MockPolicyData.mockPolicyDetails.get(0), -1)))
                .thenAnswer(invocation -> Future.succeededFuture(MockPolicyData.getMockVersionedPolicyDetails(MockPolicyData.mockPolicyDetails.get(0), 0)));

        when(policyStore.createVersionedPolicy(MockPolicyData.mockProcessGroups.get(0), null))
                .thenAnswer(invocation -> Future.failedFuture(new IllegalArgumentException("PolicyDetails is required")));

        when(policyStore.createVersionedPolicy(MockPolicyData.mockProcessGroups.get(0), MockPolicyData.getMockVersionedPolicyDetails(MockPolicyData.mockPolicyDetails.get(0), 0)))
                .thenAnswer(invocation -> {
                    PolicyEntities.VersionedPolicyDetails versionedPolicyDetails = invocation.getArgument(1);
                    return Future.failedFuture(new PolicyException("Initial policy version must be -1, your version = " + versionedPolicyDetails.getVersion(), false));
                });

        CompletableFuture<Void> f1 = new CompletableFuture<>();
        CompletableFuture<Void> f2 = new CompletableFuture<>();
        CompletableFuture<Void> f3 = new CompletableFuture<>();

        client.post(leaderPort, "localhost", ApiPathConstants.LEADER_PREFIX + ApiPathConstants.POLICY_PREFIX + DELIMITER + MockPolicyData.mockProcessGroups.get(0).getAppId() + DELIMITER + MockPolicyData.mockProcessGroups.get(0).getCluster() + DELIMITER + MockPolicyData.mockProcessGroups.get(0).getProcName(), httpClientResponse -> {
            context.assertEquals(httpClientResponse.statusCode(), HttpResponseStatus.CREATED.code());
            httpClientResponse.bodyHandler(buffer -> {
                try {
                    context.assertEquals(buffer, ProtoUtil.buildBufferFromProto(MockPolicyData.getMockVersionedPolicyDetails(MockPolicyData.mockPolicyDetails.get(0),0)));
                } catch (IOException e) {
                    f1.completeExceptionally(e);
                }
                f1.complete(null);
            });
        }).end(ProtoUtil.buildBufferFromProto(MockPolicyData.getMockVersionedPolicyDetails(MockPolicyData.mockPolicyDetails.get(0), -1)));

        client.post(leaderPort, "localhost", ApiPathConstants.LEADER_PREFIX + ApiPathConstants.POLICY_PREFIX + DELIMITER + MockPolicyData.mockProcessGroups.get(0).getAppId() + DELIMITER + MockPolicyData.mockProcessGroups.get(0).getCluster() + DELIMITER + MockPolicyData.mockProcessGroups.get(0).getProcName(), httpClientResponse -> {
            context.assertEquals(httpClientResponse.statusCode(), HttpResponseStatus.BAD_REQUEST.code());
            httpClientResponse.bodyHandler(buffer -> {
                context.assertTrue(buffer.toString().contains("error"));
                f2.complete(null);
            });
        }).end();
        client.post(leaderPort, "localhost", ApiPathConstants.LEADER_PREFIX + ApiPathConstants.POLICY_PREFIX + DELIMITER + MockPolicyData.mockProcessGroups.get(0).getAppId() + DELIMITER + MockPolicyData.mockProcessGroups.get(0).getCluster() + DELIMITER + MockPolicyData.mockProcessGroups.get(0).getProcName(), httpClientResponse -> {
            context.assertEquals(httpClientResponse.statusCode(), HttpResponseStatus.BAD_REQUEST.code());
            httpClientResponse.bodyHandler(buffer -> {
                context.assertTrue(buffer.toString().contains("error"));
                f3.complete(null);
            });
        }).end(ProtoUtil.buildBufferFromProto(MockPolicyData.getMockVersionedPolicyDetails(MockPolicyData.mockPolicyDetails.get(0), 0)));

        CompletableFuture.allOf(f1, f2, f3).whenComplete((aVoid, throwable) -> async.complete());
    }

    @Test
    public void testUpdatePolicy(TestContext context) throws Exception {
        final Async async = context.async();
        when(policyStore.updateVersionedPolicy(MockPolicyData.mockProcessGroups.get(0), MockPolicyData.getMockVersionedPolicyDetails(MockPolicyData.mockPolicyDetails.get(0), 0)))
                .thenAnswer(invocation -> Future.succeededFuture(MockPolicyData.getMockVersionedPolicyDetails(MockPolicyData.mockPolicyDetails.get(0), 1)));

        when(policyStore.updateVersionedPolicy(MockPolicyData.mockProcessGroups.get(0), null))
                .thenAnswer(invocation -> Future.failedFuture(new IllegalArgumentException("PolicyDetails is required")));

        when(policyStore.updateVersionedPolicy(MockPolicyData.mockProcessGroups.get(0), MockPolicyData.getMockVersionedPolicyDetails(MockPolicyData.mockPolicyDetails.get(0), -1)))
                .thenAnswer(invocation -> {
                    PolicyEntities.VersionedPolicyDetails versionedPolicyDetails = invocation.getArgument(1);
                    return Future.failedFuture(new PolicyException("Policy Version mismatch, currentVersion = 0, your version = " + versionedPolicyDetails.getVersion() + ", for ProcessGroup = " + RecorderProtoUtil.processGroupCompactRepr(MockPolicyData.mockProcessGroups.get(0)), false));
                });

        CompletableFuture<Void> f1 = new CompletableFuture<>();
        CompletableFuture<Void> f2 = new CompletableFuture<>();
        CompletableFuture<Void> f3 = new CompletableFuture<>();

        client.put(leaderPort, "localhost", ApiPathConstants.LEADER_PREFIX + ApiPathConstants.POLICY_PREFIX + DELIMITER + MockPolicyData.mockProcessGroups.get(0).getAppId() + DELIMITER + MockPolicyData.mockProcessGroups.get(0).getCluster() + DELIMITER + MockPolicyData.mockProcessGroups.get(0).getProcName(), httpClientResponse -> {
            context.assertEquals(httpClientResponse.statusCode(), HttpResponseStatus.OK.code());
            httpClientResponse.bodyHandler(buffer -> {
                try {
                    context.assertEquals(buffer, ProtoUtil.buildBufferFromProto(MockPolicyData.getMockVersionedPolicyDetails(MockPolicyData.mockPolicyDetails.get(0),1)));
                } catch (IOException e) {
                    f1.completeExceptionally(e);
                }
                f1.complete(null);
            });
        }).end(ProtoUtil.buildBufferFromProto(MockPolicyData.getMockVersionedPolicyDetails(MockPolicyData.mockPolicyDetails.get(0), 0)));

        client.put(leaderPort, "localhost", ApiPathConstants.LEADER_PREFIX + ApiPathConstants.POLICY_PREFIX + DELIMITER + MockPolicyData.mockProcessGroups.get(0).getAppId() + DELIMITER + MockPolicyData.mockProcessGroups.get(0).getCluster() + DELIMITER + MockPolicyData.mockProcessGroups.get(0).getProcName(), httpClientResponse -> {
            context.assertEquals(httpClientResponse.statusCode(), HttpResponseStatus.BAD_REQUEST.code());
            httpClientResponse.bodyHandler(buffer -> {
                context.assertTrue(buffer.toString().contains("error"));
                f2.complete(null);
            });
        }).end();
        client.put(leaderPort, "localhost", ApiPathConstants.LEADER_PREFIX + ApiPathConstants.POLICY_PREFIX + DELIMITER + MockPolicyData.mockProcessGroups.get(0).getAppId() + DELIMITER + MockPolicyData.mockProcessGroups.get(0).getCluster() + DELIMITER + MockPolicyData.mockProcessGroups.get(0).getProcName(), httpClientResponse -> {
            context.assertEquals(httpClientResponse.statusCode(), HttpResponseStatus.BAD_REQUEST.code());
            httpClientResponse.bodyHandler(buffer -> {
                context.assertTrue(buffer.toString().contains("error"));
                f3.complete(null);
            });
        }).end(ProtoUtil.buildBufferFromProto(MockPolicyData.getMockVersionedPolicyDetails(MockPolicyData.mockPolicyDetails.get(0), -1)));

        CompletableFuture.allOf(f1, f2, f3).whenComplete((aVoid, throwable) -> async.complete());
    }

    @After
    public void tearDown(TestContext context) throws Exception {
        final Async async = context.async();
        client.close();
        vertx.close(result -> {
            if (result.succeeded()) {
                async.complete();
            } else {
                context.fail();
            }
        });
    }
}