package fk.prof.backend;

import fk.prof.backend.deployer.VerticleDeployer;
import fk.prof.backend.deployer.impl.BackendHttpVerticleDeployer;
import fk.prof.backend.deployer.impl.LeaderElectionParticipatorVerticleDeployer;
import fk.prof.backend.deployer.impl.LeaderElectionWatcherVerticleDeployer;
import fk.prof.backend.deployer.impl.LeaderHttpVerticleDeployer;
import fk.prof.backend.leader.election.LeaderElectedTask;
import fk.prof.backend.model.assignment.AssociatedProcessGroups;
import fk.prof.backend.model.assignment.impl.AssociatedProcessGroupsImpl;
import fk.prof.backend.model.association.BackendAssociationStore;
import fk.prof.backend.model.association.ProcessGroupCountBasedBackendComparator;
import fk.prof.backend.model.association.impl.ZookeeperBasedBackendAssociationStore;
import fk.prof.backend.model.election.impl.InMemoryLeaderStore;
import fk.prof.backend.model.policy.PolicyStore;
import fk.prof.backend.proto.BackendDTO;
import fk.prof.backend.model.aggregation.impl.ActiveAggregationWindowsImpl;
import fk.prof.backend.http.ProfHttpClient;
import fk.prof.backend.util.ProtoUtil;
import io.vertx.core.*;
import io.vertx.core.http.HttpClientRequest;
import io.vertx.ext.unit.Async;
import io.vertx.ext.unit.TestContext;
import io.vertx.ext.unit.junit.VertxUnitRunner;
import org.apache.curator.framework.CuratorFramework;
import org.apache.curator.framework.CuratorFrameworkFactory;
import org.apache.curator.retry.RetryOneTime;
import org.apache.curator.test.TestingServer;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import recording.Recorder;

import java.io.IOException;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

@RunWith(VertxUnitRunner.class)
public class AssociationApiTest {
  private Vertx vertx;
  private Integer port;
  private int leaderPort;
  private TestingServer testingServer;
  private CuratorFramework curatorClient;
  private BackendAssociationStore backendAssociationStore;
  private AssociatedProcessGroups associatedProcessGroups;
  private PolicyStore policyStore;
  private InMemoryLeaderStore inMemoryLeaderStore;
  private ConfigManager configManager;

  private final String backendAssociationPath = "/assoc";
  private static final String policyPath = "/policy";

  @Before
  public void setBefore() throws Exception {
    ConfigManager.setDefaultSystemProperties();

    testingServer = new TestingServer();
    curatorClient = CuratorFrameworkFactory.newClient(testingServer.getConnectString(), 500, 500, new RetryOneTime(1));
    curatorClient.start();
    curatorClient.blockUntilConnected(10, TimeUnit.SECONDS);
    curatorClient.create().forPath(backendAssociationPath);
    curatorClient.create().forPath(policyPath);
    configManager = new ConfigManager(AssociationApiTest.class.getClassLoader().getResource("config.json").getFile());
    vertx = Vertx.vertx(new VertxOptions(configManager.getVertxConfig()));
    port = configManager.getBackendHttpPort();
    leaderPort = configManager.getLeaderHttpPort();

    backendAssociationStore = new ZookeeperBasedBackendAssociationStore(vertx, curatorClient, "/assoc", 1, 1, new ProcessGroupCountBasedBackendComparator());
    inMemoryLeaderStore = spy(new InMemoryLeaderStore(configManager.getIPAddress(), configManager.getLeaderHttpPort()));
    associatedProcessGroups = new AssociatedProcessGroupsImpl(configManager.getRecorderDefunctThresholdInSeconds());
    policyStore = mock(PolicyStore.class);

    VerticleDeployer backendHttpVerticleDeployer = new BackendHttpVerticleDeployer(vertx, configManager, inMemoryLeaderStore, new ActiveAggregationWindowsImpl(), associatedProcessGroups);
    backendHttpVerticleDeployer.deploy();
    //Wait for some time for deployment to complete
    Thread.sleep(1000);
  }

  @After
  public void tearDown(TestContext context) throws IOException {
    vertx.close(result -> {
      curatorClient.close();
      try {
        testingServer.close();
      } catch (IOException ex) {
      }
      if (result.failed()) {
        context.fail(result.cause());
      }
    });
  }

  @Test(timeout = 20000)
  public void getAssociationWhenLeaderNotElected(TestContext context)
      throws IOException {
    final Async async = context.async();
    Recorder.ProcessGroup processGroup = Recorder.ProcessGroup.newBuilder().setAppId("a").setCluster("c").setProcName("p1").build();
    makeRequestPostAssociation(buildRecorderInfoFromProcessGroup(processGroup)).setHandler(ar -> {
      if(ar.succeeded()) {
        context.assertEquals(503, ar.result().getStatusCode());
        async.complete();
      } else {
        context.fail(ar.cause());
      }
    });
  }

  @Test(timeout = 20000)
  public void getAssociationWhenLeaderIsSelf(TestContext context) throws InterruptedException, IOException {
    final Async async = context.async();
    CountDownLatch latch = new CountDownLatch(1);
    Runnable leaderElectedTask = () -> {
      latch.countDown();
    };

    VerticleDeployer leaderParticipatorDeployer = new LeaderElectionParticipatorVerticleDeployer(vertx, configManager, curatorClient, leaderElectedTask);
    VerticleDeployer leaderWatcherDeployer = new LeaderElectionWatcherVerticleDeployer(vertx, configManager, curatorClient, inMemoryLeaderStore);

    CompositeFuture.all(leaderParticipatorDeployer.deploy(), leaderWatcherDeployer.deploy()).setHandler(deployResult -> {
      if(deployResult.succeeded()) {
        try {
          boolean released = latch.await(10, TimeUnit.SECONDS);
          if (!released) {
            context.fail("Latch timed out but leader election task was not run");
          }
          //This sleep should be enough for leader store to get updated with the new leader
          Thread.sleep(1500);

          //Leader has been elected, it will be same as backend, since backend verticles were not undeployed
          Recorder.ProcessGroup processGroup = Recorder.ProcessGroup.newBuilder().setAppId("a").setCluster("c").setProcName("p1").build();
          makeRequestPostAssociation(buildRecorderInfoFromProcessGroup(processGroup)).setHandler(ar -> {
            if (ar.succeeded()) {
              context.assertEquals(400, ar.result().getStatusCode());
              async.complete();
            } else {
              context.fail(ar.cause());
            }
          });
        } catch (Exception ex) {
          context.fail(ex);
        }
      } else {
        context.fail(deployResult.cause());
      }
    });


  }

  /**
   * Tests following scenario:
   * => association is requested from backend, proxied to leader, which returns 500 because no backends are known to leader
   * => one backend reports its load to leader
   * => association is requested again, proxied to leader, returns the backend which reported its load earlier
   * @param context
   * @throws InterruptedException
   */
  @Test(timeout = 20000)
  public void getAssociationProxiedToLeader(TestContext context) throws InterruptedException, IOException {
    final Async async = context.async();
    CountDownLatch latch = new CountDownLatch(1);
    VerticleDeployer leaderHttpDeployer = new LeaderHttpVerticleDeployer(vertx, configManager, backendAssociationStore, policyStore);
    Runnable leaderElectedTask = LeaderElectedTask.newBuilder().build(vertx, leaderHttpDeployer, backendAssociationStore, policyStore);

    VerticleDeployer leaderParticipatorDeployer = new LeaderElectionParticipatorVerticleDeployer(vertx, configManager, curatorClient, leaderElectedTask);
    VerticleDeployer leaderWatcherDeployer = new LeaderElectionWatcherVerticleDeployer(vertx, configManager, curatorClient, inMemoryLeaderStore);

    CompositeFuture.all(leaderParticipatorDeployer.deploy(), leaderWatcherDeployer.deploy()).setHandler(deployResult -> {
      if(deployResult.succeeded()) {
        try {
          //This sleep should be enough for leader store to get updated with the new leader and leader elected task to be executed
          Thread.sleep(5000);
          when(inMemoryLeaderStore.isLeader()).thenReturn(false);

          //Leader has been elected, it will be same as backend, since backend verticles were not undeployed
          Recorder.ProcessGroup processGroup = Recorder.ProcessGroup.newBuilder().setAppId("a").setCluster("c").setProcName("p1").build();
          makeRequestPostAssociation(buildRecorderInfoFromProcessGroup(processGroup)).setHandler(ar -> {
            if(ar.succeeded()) {
              context.assertEquals(400, ar.result().getStatusCode());
              try {
                makeRequestReportLoad(BackendDTO.LoadReportRequest.newBuilder().setIp("1").setPort(1).setLoad(0.5f).setCurrTick(1).build())
                    .setHandler(ar1 -> {
                      context.assertTrue(ar1.succeeded());
                      try {
                        makeRequestPostAssociation(buildRecorderInfoFromProcessGroup(processGroup)).setHandler(ar2 -> {
                          context.assertTrue(ar2.succeeded());
                          context.assertEquals(200, ar2.result().getStatusCode());
                          try {
                            Recorder.AssignedBackend assignedBackendResponse = Recorder.AssignedBackend.parseFrom(ar2.result().getResponse().getBytes());
                            context.assertEquals("1", assignedBackendResponse.getHost());
                            async.complete();
                          } catch (Exception ex) {
                            context.fail(ex);
                          }
                        });
                      } catch (IOException ex) {
                        context.fail(ex);
                      }
                    });
              } catch (IOException ex) {
                context.fail(ex);
              }
            } else {
              context.fail(ar.cause());
            }
          });
        } catch (Exception ex) {
          context.fail(ex);
        }
      } else {
        context.fail(deployResult.cause());
      }
    });
  }

  private Future<ProfHttpClient.ResponseWithStatusTuple> makeRequestPostAssociation(Recorder.RecorderInfo payload)
    throws IOException {
    Future<ProfHttpClient.ResponseWithStatusTuple> future = Future.future();
    HttpClientRequest request = vertx.createHttpClient()
          .post(port, "localhost", "/association")
        .handler(response -> {
          response.bodyHandler(buffer -> {
            try {
              future.complete(ProfHttpClient.ResponseWithStatusTuple.of(response.statusCode(),buffer));
            } catch (Exception ex) {
              future.fail(ex);
            }
          });
        }).exceptionHandler(ex -> future.fail(ex));
    request.end(ProtoUtil.buildBufferFromProto(payload));
    return future;
  }

  private static Recorder.RecorderInfo buildRecorderInfoFromProcessGroup(Recorder.ProcessGroup processGroup) {
    return Recorder.RecorderInfo.newBuilder()
        .setAppId(processGroup.getAppId())
        .setCluster(processGroup.getCluster())
        .setProcName(processGroup.getProcName())
        .setRecorderTick(1)
        .setHostname("1")
        .setInstanceGrp("1")
        .setInstanceId("1")
        .setInstanceType("1")
        .setLocalTime(LocalDateTime.now(Clock.systemUTC()).toString())
        .setRecorderUptime(100)
        .setRecorderVersion(1)
        .setVmId("1")
        .setZone("1")
        .setIp("1")
        .setCapabilities(Recorder.RecorderCapabilities.newBuilder().setCanCpuSample(true))
        .build();
  }

  private Future<ProfHttpClient.ResponseWithStatusTuple> makeRequestReportLoad(BackendDTO.LoadReportRequest payload)
      throws IOException {
    Future<ProfHttpClient.ResponseWithStatusTuple> future = Future.future();
    HttpClientRequest request = vertx.createHttpClient()
        .post(leaderPort, "localhost", "/leader/load")
        .handler(response -> {
          response.bodyHandler(buffer -> {
            try {
              future.complete(ProfHttpClient.ResponseWithStatusTuple.of(response.statusCode(), buffer));
            } catch (Exception ex) {
              future.fail(ex);
            }
          });
        }).exceptionHandler(ex -> {
          future.fail(ex);
        });
    request.end(ProtoUtil.buildBufferFromProto(payload));
    return future;
  }
}
