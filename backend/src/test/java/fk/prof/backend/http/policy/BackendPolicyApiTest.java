package fk.prof.backend.http.policy;

import fk.prof.backend.AssociationApiTest;
import fk.prof.backend.ConfigManager;
import fk.prof.backend.deployer.VerticleDeployer;
import fk.prof.backend.deployer.impl.BackendHttpVerticleDeployer;
import fk.prof.backend.deployer.impl.LeaderElectionParticipatorVerticleDeployer;
import fk.prof.backend.deployer.impl.LeaderElectionWatcherVerticleDeployer;
import fk.prof.backend.deployer.impl.LeaderHttpVerticleDeployer;
import fk.prof.backend.http.ApiPathConstants;
import fk.prof.backend.leader.election.LeaderElectedTask;
import fk.prof.backend.model.aggregation.impl.ActiveAggregationWindowsImpl;
import fk.prof.backend.model.assignment.AssociatedProcessGroups;
import fk.prof.backend.model.assignment.impl.AssociatedProcessGroupsImpl;
import fk.prof.backend.model.association.BackendAssociationStore;
import fk.prof.backend.model.association.ProcessGroupCountBasedBackendComparator;
import fk.prof.backend.model.association.impl.ZookeeperBasedBackendAssociationStore;
import fk.prof.backend.model.election.impl.InMemoryLeaderStore;
import fk.prof.backend.model.policy.PolicyStore;
import fk.prof.backend.util.ProtoUtil;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.vertx.core.CompositeFuture;
import io.vertx.core.Vertx;
import io.vertx.core.VertxOptions;
import io.vertx.core.http.HttpClient;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.unit.Async;
import io.vertx.ext.unit.TestContext;
import io.vertx.ext.unit.junit.VertxUnitRunner;
import org.apache.curator.framework.CuratorFramework;
import org.apache.curator.framework.CuratorFrameworkFactory;
import org.apache.curator.retry.RetryOneTime;
import org.apache.curator.test.TestingServer;
import org.apache.curator.test.Timing;
import org.junit.After;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;
import policy.PolicyDetails;
import recording.Recorder;

import java.io.IOException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.mockito.Mockito.*;

/**
 * Test for policy API in BackendHttpVerticle
 * Created by rohit.patiyal on 20/03/17.
 */

@RunWith(VertxUnitRunner.class)
public class BackendPolicyApiTest {
  private static final String DELIMITER = "/";
  private Vertx vertx;
  private Integer port;
  private TestingServer testingServer;
  private CuratorFramework curatorClient;
  private ConfigManager configManager;
  private PolicyStore policyStore;
  private InMemoryLeaderStore inMemoryLeaderStore;
  private AssociatedProcessGroups associatedProcessGroups;

  private static List<Recorder.ProcessGroup> mockProcessGroups;
  private static List<PolicyDetails> mockPolicies;
  private HttpClient client;
  private BackendAssociationStore backendAssociationStore;

  @BeforeClass
  public static void setBeforeClass()
      throws Exception {
    mockProcessGroups = Arrays.asList(
        Recorder.ProcessGroup.newBuilder().setAppId("a1").setCluster("c1").setProcName("p1").build(),
        Recorder.ProcessGroup.newBuilder().setAppId("a1").setCluster("c1").setProcName("p2").build(),
        Recorder.ProcessGroup.newBuilder().setAppId("a1").setCluster("c2").setProcName("p3").build(),
        Recorder.ProcessGroup.newBuilder().setAppId("a2").setCluster("c1").setProcName("p1").build()
    );
    mockPolicies = Arrays.asList(
        PolicyDetails.newBuilder().setAdministrator("admin1").setCreatedAt("3").setModifiedAt("3").setLastScheduled("3:20").build(),
        PolicyDetails.newBuilder().setAdministrator("admin1").setCreatedAt("4").setModifiedAt("4").setLastScheduled("4:30").build(),
        PolicyDetails.newBuilder().setAdministrator("admin2").setCreatedAt("5").setModifiedAt("5").setLastScheduled("5:40").build()
    );
  }

  @Before
  public void setUp() throws Exception {
    ConfigManager.setDefaultSystemProperties();

    testingServer = new TestingServer();
    Timing timing = new Timing();
    curatorClient = CuratorFrameworkFactory.newClient(testingServer.getConnectString(), timing.session(), timing.connection(), new RetryOneTime(1));
    curatorClient.start();
    curatorClient.blockUntilConnected(10, TimeUnit.SECONDS);
    curatorClient.create().forPath("/policy");
    curatorClient.create().forPath("/assoc");

    configManager = new ConfigManager(AssociationApiTest.class.getClassLoader().getResource("config.json").getFile());
    vertx = Vertx.vertx(new VertxOptions(configManager.getVertxConfig()));
    port = configManager.getBackendHttpPort();

    backendAssociationStore = new ZookeeperBasedBackendAssociationStore(vertx, curatorClient, "/assoc", 1, 1, new ProcessGroupCountBasedBackendComparator());

    policyStore = mock(PolicyStore.class);

    inMemoryLeaderStore = spy(new InMemoryLeaderStore(configManager.getIPAddress(), configManager.getLeaderHttpPort()));

    associatedProcessGroups = new AssociatedProcessGroupsImpl(configManager.getRecorderDefunctThresholdInSeconds());

    client = vertx.createHttpClient();
    VerticleDeployer backendHttpVerticleDeployer = new BackendHttpVerticleDeployer(vertx, configManager, inMemoryLeaderStore, new ActiveAggregationWindowsImpl(), associatedProcessGroups);

    backendHttpVerticleDeployer.deploy();
    //Wait for some time for deployment to complete
    Thread.sleep(1000);
  }

  @After
  public void tearDown(TestContext context) throws IOException {
    System.out.println("Tearing down");
    vertx.close(result -> {
      System.out.println("Vertx shutdown");
      curatorClient.close();
      try {
        testingServer.close();
      } catch (IOException ex) {
        ex.printStackTrace();
      }
      if (result.failed()) {
        context.fail(result.cause());
      }
    });
  }

  @Test(timeout = 10000)
  public void testPolicyAPIWhenLeaderNotElected(TestContext context)
      throws IOException {
    final Async async = context.async();
    CompletableFuture<Void> f1 = new CompletableFuture<>();
    CompletableFuture<Void> f2 = new CompletableFuture<>();
    CompletableFuture<Void> f3 = new CompletableFuture<>();
    CompletableFuture<Void> f4 = new CompletableFuture<>();
    CompletableFuture<Void> f5 = new CompletableFuture<>();

    client.getNow(port, "localhost", ApiPathConstants.BACKEND_POLICIES + mockProcessGroups.get(0).getAppId(), httpClientResponse -> {
      context.assertEquals(httpClientResponse.statusCode(), HttpResponseStatus.SERVICE_UNAVAILABLE.code());
      httpClientResponse.bodyHandler(buffer -> f1.complete(null));
    });
    client.getNow(port, "localhost", ApiPathConstants.BACKEND_POLICIES + mockProcessGroups.get(0).getAppId() + DELIMITER + mockProcessGroups.get(0).getCluster(), httpClientResponse -> {
      context.assertEquals(httpClientResponse.statusCode(), HttpResponseStatus.SERVICE_UNAVAILABLE.code());
      httpClientResponse.bodyHandler(buffer -> f2.complete(null));
    });
    client.getNow(port, "localhost", ApiPathConstants.BACKEND_POLICIES + mockProcessGroups.get(0).getAppId() + DELIMITER + mockProcessGroups.get(0).getCluster() + DELIMITER + mockProcessGroups.get(0).getProcName(), httpClientResponse -> {
      context.assertEquals(httpClientResponse.statusCode(), HttpResponseStatus.SERVICE_UNAVAILABLE.code());
      httpClientResponse.bodyHandler(buffer -> f3.complete(null));
    });
    client.put(port, "localhost", ApiPathConstants.BACKEND_POLICIES + mockProcessGroups.get(0).getAppId() + DELIMITER + mockProcessGroups.get(0).getCluster() + DELIMITER + mockProcessGroups.get(0).getProcName(), httpClientResponse -> {
      context.assertEquals(httpClientResponse.statusCode(), HttpResponseStatus.SERVICE_UNAVAILABLE.code());
      httpClientResponse.bodyHandler(buffer -> f4.complete(null));
    }).end(ProtoUtil.buildBufferFromProto(mockPolicies.get(0)));
    client.delete(port, "localhost", ApiPathConstants.BACKEND_POLICIES + mockProcessGroups.get(0).getAppId() + DELIMITER + mockProcessGroups.get(0).getCluster() + DELIMITER + mockProcessGroups.get(0).getProcName(), httpClientResponse -> {
      context.assertEquals(httpClientResponse.statusCode(), HttpResponseStatus.SERVICE_UNAVAILABLE.code());
      httpClientResponse.bodyHandler(buffer -> f5.complete(null));
    }).end(new JsonObject().put("administrator", mockPolicies.get(0).getAdministrator()).toString());
    CompletableFuture.allOf(f1, f2, f4, f4, f5).whenComplete((aVoid, throwable) -> async.complete());
  }

  @Test(timeout = 10000)
  public void testGetPolicyGivenAppIdWhenLeaderIsSelf(TestContext context)
      throws IOException {
    final Async async = context.async();
    CompletableFuture<Void> f1 = new CompletableFuture<>();
    CompletableFuture<Void> f2 = new CompletableFuture<>();
    CompletableFuture<Void> f3 = new CompletableFuture<>();
    CompletableFuture<Void> f4 = new CompletableFuture<>();
    CompletableFuture<Void> f5 = new CompletableFuture<>();

    CountDownLatch latch = new CountDownLatch(1);
    Runnable leaderElectedTask = latch::countDown;

    VerticleDeployer leaderParticipatorDeployer = new LeaderElectionParticipatorVerticleDeployer(vertx, configManager, curatorClient, leaderElectedTask);
    VerticleDeployer leaderWatcherDeployer = new LeaderElectionWatcherVerticleDeployer(vertx, configManager, curatorClient, inMemoryLeaderStore);

    CompositeFuture.all(leaderParticipatorDeployer.deploy(), leaderWatcherDeployer.deploy()).setHandler(deployResult -> {
      if (deployResult.succeeded()) {
        boolean released;
        try {
          released = latch.await(5, TimeUnit.SECONDS);
          if (!released) {
            context.fail("Latch timed out but leader election task was not run");
          }
          //This sleep should be enough for leader store to get updated with the new leader
          Thread.sleep(1500);

          client.getNow(port, "localhost", ApiPathConstants.BACKEND_POLICIES + mockProcessGroups.get(0).getAppId(), httpClientResponse -> {
            context.assertEquals(httpClientResponse.statusCode(), HttpResponseStatus.BAD_REQUEST.code());
            httpClientResponse.bodyHandler(buffer -> f1.complete(null));
          });
          client.getNow(port, "localhost", ApiPathConstants.BACKEND_POLICIES + mockProcessGroups.get(0).getAppId() + DELIMITER + mockProcessGroups.get(0).getCluster(), httpClientResponse -> {
            context.assertEquals(httpClientResponse.statusCode(), HttpResponseStatus.BAD_REQUEST.code());
            httpClientResponse.bodyHandler(buffer -> f2.complete(null));
          });
          client.getNow(port, "localhost", ApiPathConstants.BACKEND_POLICIES + mockProcessGroups.get(0).getAppId() + DELIMITER + mockProcessGroups.get(0).getCluster() + DELIMITER + mockProcessGroups.get(0).getProcName(), httpClientResponse -> {
            context.assertEquals(httpClientResponse.statusCode(), HttpResponseStatus.BAD_REQUEST.code());
            httpClientResponse.bodyHandler(buffer -> f3.complete(null));
          });
          client.put(port, "localhost", ApiPathConstants.BACKEND_POLICIES + mockProcessGroups.get(0).getAppId() + DELIMITER + mockProcessGroups.get(0).getCluster() + DELIMITER + mockProcessGroups.get(0).getProcName(), httpClientResponse -> {
            context.assertEquals(httpClientResponse.statusCode(), HttpResponseStatus.BAD_REQUEST.code());
            httpClientResponse.bodyHandler(buffer -> f4.complete(null));
          }).end(ProtoUtil.buildBufferFromProto(mockPolicies.get(0)));
          client.delete(port, "localhost", ApiPathConstants.BACKEND_POLICIES + mockProcessGroups.get(0).getAppId() + DELIMITER + mockProcessGroups.get(0).getCluster() + DELIMITER + mockProcessGroups.get(0).getProcName(), httpClientResponse -> {
            context.assertEquals(httpClientResponse.statusCode(), HttpResponseStatus.BAD_REQUEST.code());
            httpClientResponse.bodyHandler(buffer -> f5.complete(null));
          }).end(new JsonObject().put("administrator", mockPolicies.get(0).getAdministrator()).toString());

          CompletableFuture.allOf(f1, f2, f4, f4, f5).whenComplete((aVoid, throwable) -> async.complete());
        } catch (InterruptedException e) {
          context.fail(e);
        } catch (IOException e) {
          e.printStackTrace();
        }
      } else {
        context.fail(deployResult.cause());
      }
    });
  }

  @Test(timeout = 10000)
  public void testGetPolicyGivenAppIdProxiedToLeader(TestContext context) {
    final Async async = context.async();
    when(policyStore.getUserPolicies(mockProcessGroups.get(0).getAppId())).thenAnswer(invocation -> new HashMap<Object, Object>() {
      {
        put(mockProcessGroups.get(0).getAppId(), new HashMap<Object, Object>() {
          {
            put(mockProcessGroups.get(0).getCluster(), new HashMap<Object, Object>() {
              {
                put(mockProcessGroups.get(0).getProcName(), mockPolicies.get(0));
                put(mockProcessGroups.get(1).getProcName(), mockPolicies.get(1));
              }
            });
            put(mockProcessGroups.get(2).getCluster(), new HashMap<Object, Object>() {
              {
                put(mockProcessGroups.get(2).getProcName(), mockPolicies.get(2));
              }
            });
          }
        });
      }
    });

    VerticleDeployer leaderHttpDeployer = new LeaderHttpVerticleDeployer(vertx, configManager, backendAssociationStore, policyStore);
    Runnable leaderElectedTask = LeaderElectedTask.newBuilder().build(vertx, leaderHttpDeployer, backendAssociationStore, policyStore);

    VerticleDeployer leaderParticipatorDeployer = new LeaderElectionParticipatorVerticleDeployer(vertx, configManager, curatorClient, leaderElectedTask);
    VerticleDeployer leaderWatcherDeployer = new LeaderElectionWatcherVerticleDeployer(vertx, configManager, curatorClient, inMemoryLeaderStore);

    CompositeFuture.all(leaderParticipatorDeployer.deploy(), leaderWatcherDeployer.deploy()).setHandler(deployResult -> {
      if (deployResult.succeeded()) {
        try {
          //This sleep should be enough for leader store to get updated with the new leader and leader elected task to be executed
          Thread.sleep(5000);
          when(inMemoryLeaderStore.isLeader()).thenReturn(false);

          client.getNow(port, "localhost", ApiPathConstants.BACKEND_POLICIES + mockProcessGroups.get(0).getAppId(), httpClientResponse -> {
            context.assertEquals(httpClientResponse.statusCode(), HttpResponseStatus.OK.code());
            httpClientResponse.bodyHandler(buffer -> {
              context.assertFalse(buffer.toString().contains(mockProcessGroups.get(0).getAppId()));
              context.assertTrue(buffer.toJsonArray().size() == 3);
              async.complete();
            });
          });
        } catch (InterruptedException e) {
          e.printStackTrace();
        }
      } else {
        context.fail(deployResult.cause());
      }
    });
  }

  @Test(timeout = 10000)
  public void testGetPolicyGivenAppIdClusterIdProxiedToLeader(TestContext context) {
    final Async async = context.async();
    when(policyStore.getUserPolicies(mockProcessGroups.get(0).getAppId(), mockProcessGroups.get(0).getCluster())).thenAnswer(invocation -> new HashMap<Object, Object>() {
      {
        put(mockProcessGroups.get(0).getAppId(), new HashMap<Object, Object>() {
          {
            put(mockProcessGroups.get(0).getCluster(), new HashMap<Object, Object>() {
              {
                put(mockProcessGroups.get(0).getProcName(), mockPolicies.get(0));
                put(mockProcessGroups.get(1).getProcName(), mockPolicies.get(1));
              }
            });
          }
        });
      }
    });

    VerticleDeployer leaderHttpDeployer = new LeaderHttpVerticleDeployer(vertx, configManager, backendAssociationStore, policyStore);
    Runnable leaderElectedTask = LeaderElectedTask.newBuilder().build(vertx, leaderHttpDeployer, backendAssociationStore, policyStore);

    VerticleDeployer leaderParticipatorDeployer = new LeaderElectionParticipatorVerticleDeployer(vertx, configManager, curatorClient, leaderElectedTask);
    VerticleDeployer leaderWatcherDeployer = new LeaderElectionWatcherVerticleDeployer(vertx, configManager, curatorClient, inMemoryLeaderStore);

    CompositeFuture.all(leaderParticipatorDeployer.deploy(), leaderWatcherDeployer.deploy()).setHandler(deployResult -> {
      if (deployResult.succeeded()) {
        try {
          //This sleep should be enough for leader store to get updated with the new leader and leader elected task to be executed
          Thread.sleep(5000);
          when(inMemoryLeaderStore.isLeader()).thenReturn(false);

          client.getNow(port, "localhost", ApiPathConstants.BACKEND_POLICIES + mockProcessGroups.get(0).getAppId() + DELIMITER + mockProcessGroups.get(0).getCluster(), httpClientResponse -> {
            context.assertEquals(httpClientResponse.statusCode(), HttpResponseStatus.OK.code());
            httpClientResponse.bodyHandler(buffer -> {
              context.assertFalse(buffer.toString().contains(mockProcessGroups.get(0).getAppId()));
              context.assertFalse(buffer.toString().contains(mockProcessGroups.get(0).getCluster()));
              context.assertTrue(buffer.toJsonArray().size() == 2);
              async.complete();
            });
          });
        } catch (InterruptedException e) {
          e.printStackTrace();
        }
      } else {
        context.fail(deployResult.cause());
      }
    });
  }

  @Test(timeout = 10000)
  public void testGetPolicyGivenAppIdClusterIdProcessProxiedToLeader(TestContext context) {
    final Async async = context.async();
    when(policyStore.getUserPolicies(mockProcessGroups.get(0).getAppId(), mockProcessGroups.get(0).getCluster(), mockProcessGroups.get(0).getProcName())).thenAnswer(invocation -> new HashMap<Object, Object>() {
      {
        put(mockProcessGroups.get(0).getAppId(), new HashMap<Object, Object>() {
          {
            put(mockProcessGroups.get(0).getCluster(), new HashMap<Object, Object>() {
              {
                put(mockProcessGroups.get(0).getProcName(), mockPolicies.get(0));
              }
            });
          }
        });
      }
    });

    VerticleDeployer leaderHttpDeployer = new LeaderHttpVerticleDeployer(vertx, configManager, backendAssociationStore, policyStore);
    Runnable leaderElectedTask = LeaderElectedTask.newBuilder().build(vertx, leaderHttpDeployer, backendAssociationStore, policyStore);

    VerticleDeployer leaderParticipatorDeployer = new LeaderElectionParticipatorVerticleDeployer(vertx, configManager, curatorClient, leaderElectedTask);
    VerticleDeployer leaderWatcherDeployer = new LeaderElectionWatcherVerticleDeployer(vertx, configManager, curatorClient, inMemoryLeaderStore);

    CompositeFuture.all(leaderParticipatorDeployer.deploy(), leaderWatcherDeployer.deploy()).setHandler(deployResult -> {
      if (deployResult.succeeded()) {
        try {
          //This sleep should be enough for leader store to get updated with the new leader and leader elected task to be executed
          Thread.sleep(5000);
          when(inMemoryLeaderStore.isLeader()).thenReturn(false);

          client.getNow(port, "localhost", ApiPathConstants.BACKEND_POLICIES + mockProcessGroups.get(0).getAppId() + DELIMITER + mockProcessGroups.get(0).getCluster() + DELIMITER + mockProcessGroups.get(0).getProcName(), httpClientResponse -> {
            context.assertEquals(httpClientResponse.statusCode(), HttpResponseStatus.OK.code());
            httpClientResponse.bodyHandler(buffer -> {
              context.assertFalse(buffer.toString().contains(mockProcessGroups.get(0).getAppId()));
              context.assertFalse(buffer.toString().contains(mockProcessGroups.get(0).getCluster()));
              context.assertFalse(buffer.toString().contains(mockProcessGroups.get(0).getProcName()));
              context.assertTrue(buffer.toJsonObject().containsKey("created_at"));
              async.complete();
            });
          });
        } catch (InterruptedException e) {
          e.printStackTrace();
        }
      } else {
        context.fail(deployResult.cause());
      }
    });
  }

  @Test(timeout = 10000)
  public void testPutPolicyProxiedToLeader(TestContext context) {
    final Async async = context.async();
    when(policyStore.setUserPolicy(mockProcessGroups.get(0), mockPolicies.get(0))).thenAnswer(invocation -> CompletableFuture.completedFuture(null));

    VerticleDeployer leaderHttpDeployer = new LeaderHttpVerticleDeployer(vertx, configManager, backendAssociationStore, policyStore);
    Runnable leaderElectedTask = LeaderElectedTask.newBuilder().build(vertx, leaderHttpDeployer, backendAssociationStore, policyStore);

    VerticleDeployer leaderParticipatorDeployer = new LeaderElectionParticipatorVerticleDeployer(vertx, configManager, curatorClient, leaderElectedTask);
    VerticleDeployer leaderWatcherDeployer = new LeaderElectionWatcherVerticleDeployer(vertx, configManager, curatorClient, inMemoryLeaderStore);

    CompositeFuture.all(leaderParticipatorDeployer.deploy(), leaderWatcherDeployer.deploy()).setHandler(deployResult -> {
      if (deployResult.succeeded()) {
        try {
          //This sleep should be enough for leader store to get updated with the new leader and leader elected task to be executed
          Thread.sleep(5000);
          when(inMemoryLeaderStore.isLeader()).thenReturn(false);

          client.put(port, "localhost", ApiPathConstants.BACKEND_POLICIES + mockProcessGroups.get(0).getAppId() + DELIMITER + mockProcessGroups.get(0).getCluster() + DELIMITER + mockProcessGroups.get(0).getProcName(), httpClientResponse -> {
            context.assertEquals(httpClientResponse.statusCode(), HttpResponseStatus.OK.code());
            httpClientResponse.bodyHandler(buffer -> {
              context.assertFalse(buffer.toString().contains("error"));
              async.complete();
            });
          }).end(ProtoUtil.buildBufferFromProto(mockPolicies.get(0)));
        } catch (InterruptedException | IOException e) {
          e.printStackTrace();
        }
      } else {
        context.fail(deployResult.cause());
      }
    });
  }

  @Test(timeout = 10000)
  public void testDeletePolicyProxiedToLeader(TestContext context) {
    final Async async = context.async();
    when(policyStore.removeUserPolicy(mockProcessGroups.get(0), mockPolicies.get(0).getAdministrator())).thenAnswer(invocation -> CompletableFuture.completedFuture(null));

    VerticleDeployer leaderHttpDeployer = new LeaderHttpVerticleDeployer(vertx, configManager, backendAssociationStore, policyStore);
    Runnable leaderElectedTask = LeaderElectedTask.newBuilder().build(vertx, leaderHttpDeployer, backendAssociationStore, policyStore);

    VerticleDeployer leaderParticipatorDeployer = new LeaderElectionParticipatorVerticleDeployer(vertx, configManager, curatorClient, leaderElectedTask);
    VerticleDeployer leaderWatcherDeployer = new LeaderElectionWatcherVerticleDeployer(vertx, configManager, curatorClient, inMemoryLeaderStore);

    CompositeFuture.all(leaderParticipatorDeployer.deploy(), leaderWatcherDeployer.deploy()).setHandler(deployResult -> {
      if (deployResult.succeeded()) {
        try {
          //This sleep should be enough for leader store to get updated with the new leader and leader elected task to be executed
          Thread.sleep(5000);
          when(inMemoryLeaderStore.isLeader()).thenReturn(false);
          client.delete(port, "localhost", " /policies/" + mockProcessGroups.get(0).getAppId() + DELIMITER + mockProcessGroups.get(0).getCluster() + DELIMITER + mockProcessGroups.get(0).getProcName(), httpClientResponse -> {

            context.assertEquals(httpClientResponse.statusCode(), HttpResponseStatus.OK.code());
            httpClientResponse.bodyHandler(buffer -> {
              context.assertFalse(buffer.toString().contains("error"));
              async.complete();
            });
          }).end(new JsonObject().put("administrator", mockPolicies.get(0).getAdministrator()).toString());
        } catch (InterruptedException e) {
          e.printStackTrace();
        }
      } else {
        context.fail(deployResult.cause());
      }
    });
  }
}
