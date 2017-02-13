package fk.prof.backend;

import fk.prof.backend.model.association.BackendAssociationStore;
import fk.prof.backend.proto.BackendDTO;
import fk.prof.backend.service.ProfileWorkService;
import fk.prof.backend.http.ConfigurableHttpClient;
import fk.prof.backend.model.election.LeaderDiscoveryStore;
import io.vertx.core.DeploymentOptions;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.VertxOptions;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpClientRequest;
import io.vertx.core.json.JsonObject;
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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

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
  private LeaderDiscoveryStore leaderDiscoveryStore;
  private JsonObject config;

  private final String backendAssociationPath = "/assoc";

  @Before
  public void setBefore() throws Exception {
    ConfigManager.setDefaultSystemProperties();

    testingServer = new TestingServer();
    curatorClient = CuratorFrameworkFactory.newClient(testingServer.getConnectString(), 500, 500, new RetryOneTime(1));
    curatorClient.start();
    curatorClient.blockUntilConnected(10, TimeUnit.SECONDS);
    curatorClient.create().forPath(backendAssociationPath);

    config = ConfigManager.loadFileAsJson(AssociationApiTest.class.getClassLoader().getResource("config.json").getFile());
    JsonObject vertxConfig = ConfigManager.getVertxConfig(config);
    vertx = vertxConfig != null ? Vertx.vertx(new VertxOptions(vertxConfig)) : Vertx.vertx();

    JsonObject backendHttpServerConfig = ConfigManager.getBackendHttpServerConfig(config);
    assert backendHttpServerConfig != null;
    port = backendHttpServerConfig.getInteger("port");

    JsonObject leaderHttpServerConfig = ConfigManager.getLeaderHttpServerConfig(config);
    assert leaderHttpServerConfig != null;
    leaderPort = leaderHttpServerConfig.getInteger("port");

    JsonObject backendHttpDeploymentConfig = ConfigManager.getBackendHttpDeploymentConfig(config);
    assert backendHttpDeploymentConfig != null;
    DeploymentOptions backendHttpDeploymentOptions = new DeploymentOptions(backendHttpDeploymentConfig);
    backendAssociationStore = VertxManager.getDefaultBackendAssociationStore(vertx, curatorClient, "/assoc", 1, 1);
    leaderDiscoveryStore = spy(VertxManager.getDefaultLeaderDiscoveryStore(vertx));

    VertxManager.deployBackendHttpVerticles(vertx,
        backendHttpServerConfig,
        ConfigManager.getHttpClientConfig(config),
        leaderPort,
        backendHttpDeploymentOptions,
        leaderDiscoveryStore,
        new ProfileWorkService());

  }

  @After
  public void tearDown(TestContext context) throws IOException {
    System.out.println("Tearing down");
    VertxManager.close(vertx).setHandler(result -> {
      System.out.println("Vertx shutdown");
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

  @Test
  public void getAssociationWhenLeaderNotElected(TestContext context) {
    final Async async = context.async();
    Recorder.ProcessGroup processGroup = Recorder.ProcessGroup.newBuilder().setAppId("a").setCluster("c").setProcName("p1").build();
    makeRequestGetAssociation(processGroup).setHandler(ar -> {
      if(ar.succeeded()) {
        context.assertEquals(503, ar.result().getStatusCode());
        async.complete();
      } else {
        context.fail(ar.cause());
      }
    });
  }

  @Test
  public void getAssociationWhenLeaderIsSelf(TestContext context) throws InterruptedException {
    final Async async = context.async();
    CountDownLatch latch = new CountDownLatch(1);
    Runnable leaderElectedTask = () -> {
      latch.countDown();
    };

    VertxManager.deployLeaderElectionWorkerVerticles(
        vertx,
        new DeploymentOptions(ConfigManager.getLeaderElectionDeploymentConfig(config)),
        curatorClient,
        leaderElectedTask,
        leaderDiscoveryStore
    );

    boolean released = latch.await(10, TimeUnit.SECONDS);
    if (!released) {
      context.fail("Latch timed out but leader election task was not run");
    }
    //This sleep should be enough for leader discovery store to get updated with the new leader
    Thread.sleep(1500);

    //Leader has been elected, it will be same as backend, since backend verticles were not undeployed
    Recorder.ProcessGroup processGroup = Recorder.ProcessGroup.newBuilder().setAppId("a").setCluster("c").setProcName("p1").build();
    makeRequestGetAssociation(processGroup).setHandler(ar -> {
      if(ar.succeeded()) {
        context.assertEquals(400, ar.result().getStatusCode());
        async.complete();
      } else {
        context.fail(ar.cause());
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
  @Test
  public void getAssociationProxiedToLeader(TestContext context) throws InterruptedException {
    final Async async = context.async();
    CountDownLatch latch = new CountDownLatch(1);
    Runnable leaderElectedTask = VertxManager.getDefaultLeaderElectedTask(
        vertx, true, null, true,
        ConfigManager.getLeaderHttpServerConfig(config),
        new DeploymentOptions(ConfigManager.getLeaderHttpDeploymentConfig(config)),
        backendAssociationStore);

    VertxManager.deployLeaderElectionWorkerVerticles(
        vertx,
        new DeploymentOptions(ConfigManager.getLeaderElectionDeploymentConfig(config)),
        curatorClient,
        leaderElectedTask,
        leaderDiscoveryStore
    );

    //This sleep should be enough for leader discovery store to get updated with the new leader and leader elected task to be executed
    Thread.sleep(5000);
    when(leaderDiscoveryStore.isLeader()).thenReturn(false);

    //Leader has been elected, it will be same as backend, since backend verticles were not undeployed
    Recorder.ProcessGroup processGroup = Recorder.ProcessGroup.newBuilder().setAppId("a").setCluster("c").setProcName("p1").build();
    makeRequestGetAssociation(processGroup).setHandler(ar -> {
      if(ar.succeeded()) {
        context.assertEquals(500, ar.result().getStatusCode());
        makeRequestReportLoad(BackendDTO.LoadReportRequest.newBuilder().setIp("1").setLoad(0.5f).build())
            .setHandler(ar1 -> {
              context.assertTrue(ar1.succeeded());
              makeRequestGetAssociation(processGroup).setHandler(ar2 -> {
                context.assertTrue(ar2.succeeded());
                System.out.println(ar2.result());
                context.assertEquals(200, ar2.result().getStatusCode());
                context.assertEquals("1", ar2.result().getResponse().toString());
                async.complete();
              });
            });
      } else {
        context.fail(ar.cause());
      }
    });
  }

  private Future<ConfigurableHttpClient.ResponseWithStatusTuple> makeRequestGetAssociation(Recorder.ProcessGroup payload) {
    Future<ConfigurableHttpClient.ResponseWithStatusTuple> future = Future.future();
    HttpClientRequest request = vertx.createHttpClient()
          .post(port, "localhost", "/association")
        .handler(response -> {
          response.bodyHandler(buffer -> {
            try {
              future.complete(ConfigurableHttpClient.ResponseWithStatusTuple.of(response.statusCode(),buffer));
            } catch (Exception ex) {
              future.fail(ex);
            }
          });
        }).exceptionHandler(ex -> future.fail(ex));
    request.end(Buffer.buffer(payload.toByteArray()));
    return future;
  }

  private Future<ConfigurableHttpClient.ResponseWithStatusTuple> makeRequestReportLoad(BackendDTO.LoadReportRequest payload) {
    Future<ConfigurableHttpClient.ResponseWithStatusTuple> future = Future.future();
    HttpClientRequest request = vertx.createHttpClient()
        .post(leaderPort, "localhost", "/leader/load")
        .handler(response -> {
          response.bodyHandler(buffer -> {
            try {
              future.complete(ConfigurableHttpClient.ResponseWithStatusTuple.of(response.statusCode(),buffer));
            } catch (Exception ex) {
              future.fail(ex);
            }
          });
        }).exceptionHandler(ex -> {
          future.fail(ex);
        });
    request.end(Buffer.buffer(payload.toByteArray()));
    return future;
  }

}
