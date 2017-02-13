package fk.prof.backend;

import fk.prof.backend.service.ProfileWorkService;
import fk.prof.backend.util.IPAddressUtil;
import fk.prof.backend.model.election.LeaderDiscoveryStore;
import io.vertx.core.CompositeFuture;
import io.vertx.core.DeploymentOptions;
import io.vertx.core.Vertx;
import io.vertx.core.VertxOptions;
import io.vertx.core.json.JsonObject;
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

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

@RunWith(VertxUnitRunner.class)
public class LeaderElectionTest {

  private Vertx vertx;
  private Integer leaderPort;
  private JsonObject leaderHttpServerConfig;
  private JsonObject config;
  private JsonObject vertxConfig;
  private DeploymentOptions leaderElectionDeploymentOptions;

  private TestingServer testingServer;
  private CuratorFramework curatorClient;

  @Before
  public void setUp(TestContext context) throws Exception {
    ConfigManager.setDefaultSystemProperties();
    config = ConfigManager.loadFileAsJson(LeaderElectionTest.class.getClassLoader().getResource("config.json").getFile());
    vertxConfig = ConfigManager.getVertxConfig(config);

    JsonObject leaderElectionDeploymentConfig = ConfigManager.getLeaderElectionDeploymentConfig(config);
    assert leaderElectionDeploymentConfig != null;

    leaderHttpServerConfig = ConfigManager.getLeaderHttpServerConfig(config);
    assert leaderHttpServerConfig != null;
    leaderPort = leaderHttpServerConfig.getInteger("port");
    leaderElectionDeploymentOptions = new DeploymentOptions(leaderElectionDeploymentConfig);

    testingServer = new TestingServer();
    curatorClient = CuratorFrameworkFactory.newClient(testingServer.getConnectString(), 500, 500, new RetryOneTime(1));
    curatorClient.start();
    curatorClient.blockUntilConnected(10, TimeUnit.SECONDS);
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

  @Test(timeout = 20000)
  public void leaderTaskTriggerOnLeaderElection(TestContext testContext) throws InterruptedException {
    vertx = vertxConfig != null ? Vertx.vertx(new VertxOptions(vertxConfig)) : Vertx.vertx();
    CountDownLatch latch = new CountDownLatch(1);
    Runnable leaderElectedTask = () -> {
      latch.countDown();
    };
    LeaderDiscoveryStore leaderDiscoveryStore = VertxManager.getDefaultLeaderDiscoveryStore(vertx);

    Thread.sleep(1000);
    VertxManager.deployLeaderElectionWorkerVerticles(
        vertx,
        leaderElectionDeploymentOptions,
        curatorClient,
        leaderElectedTask,
        leaderDiscoveryStore
    );

    boolean released = latch.await(10, TimeUnit.SECONDS);
    if (!released) {
      testContext.fail("Latch timed out but leader election task was not run");
    }
  }

  @Test(timeout = 20000)
  public void leaderDiscoveryUpdateOnLeaderElection(TestContext testContext) throws InterruptedException {
    vertx = vertxConfig != null ? Vertx.vertx(new VertxOptions(vertxConfig)) : Vertx.vertx();
    CountDownLatch latch = new CountDownLatch(1);
    Runnable leaderElectedTask = VertxManager.getDefaultLeaderElectedTask(vertx, true, null, false, null, null, null);
    LeaderDiscoveryStore leaderDiscoveryStore = new LeaderDiscoveryStore() {
      private String address = null;
      private boolean self = false;

      @Override
      public void setLeaderIPAddress(String ipAddress) {
        address = ipAddress;
        self = ipAddress != null && ipAddress.equals(IPAddressUtil.getIPAddressAsString());
        if (address != null) {
          latch.countDown();
        }
      }

      @Override
      public String getLeaderIPAddress() {
        return address;
      }

      @Override
      public boolean isLeader() {
        return self;
      }
    };

    Thread.sleep(1000);
    VertxManager.deployLeaderElectionWorkerVerticles(
        vertx,
        leaderElectionDeploymentOptions,
        curatorClient,
        leaderElectedTask,
        leaderDiscoveryStore
    );

    boolean released = latch.await(10, TimeUnit.SECONDS);
    if (!released) {
      testContext.fail("Latch timed out but leader discovery store was not updated with leader address");
    } else {
      testContext.assertEquals(IPAddressUtil.getIPAddressAsString(), leaderDiscoveryStore.getLeaderIPAddress());
      testContext.assertTrue(leaderDiscoveryStore.isLeader());
    }
  }

  @Test(timeout = 20000)
  public void leaderElectionAssertionsWithDisablingOfBackendDuties(TestContext testContext) throws InterruptedException {
    vertx = vertxConfig != null ? Vertx.vertx(new VertxOptions(vertxConfig)) : Vertx.vertx();
    ProfileWorkService profileWorkService = new ProfileWorkService();
    List<String> backendDeployments = new ArrayList<>();

    CompositeFuture aggDepFut = VertxManager.deployBackendHttpVerticles(
        vertx, ConfigManager.getBackendHttpServerConfig(config), ConfigManager.getHttpClientConfig(config),
        leaderPort, new DeploymentOptions(ConfigManager.getBackendHttpDeploymentConfig(config)),
        VertxManager.getDefaultLeaderDiscoveryStore(vertx), profileWorkService);

    CountDownLatch aggDepLatch = new CountDownLatch(1);
    aggDepFut.setHandler(asyncResult -> {
      if (asyncResult.succeeded()) {
        backendDeployments.addAll(asyncResult.result().list());
        aggDepLatch.countDown();
      } else {
        testContext.fail(asyncResult.cause());
      }
    });

    boolean aggDepLatchReleased = aggDepLatch.await(10, TimeUnit.SECONDS);
    if (!aggDepLatchReleased) {
      testContext.fail("Latch timed out but aggregation verticles were not deployed");
    } else {

      CountDownLatch leaderElectionLatch = new CountDownLatch(1);
      CountDownLatch leaderWatchedLatch = new CountDownLatch(1);

      Runnable defaultLeaderElectedTask = VertxManager.getDefaultLeaderElectedTask(
          vertx, false, backendDeployments,
          false, null, null, null);
      Runnable wrappedLeaderElectedTask = () -> {
        defaultLeaderElectedTask.run();
        leaderElectionLatch.countDown();
      };

      LeaderDiscoveryStore defaultLeaderDiscoveryStore = VertxManager.getDefaultLeaderDiscoveryStore(vertx);
      LeaderDiscoveryStore wrappedLeaderDiscoveryStore = new LeaderDiscoveryStore() {
        private LeaderDiscoveryStore toWrap;

        @Override
        public void setLeaderIPAddress(String ipAddress) {
          toWrap.setLeaderIPAddress(ipAddress);
          if (ipAddress != null) {
            leaderWatchedLatch.countDown();
          }
        }

        @Override
        public String getLeaderIPAddress() {
          return toWrap.getLeaderIPAddress();
        }

        @Override
        public boolean isLeader() {
          return toWrap.isLeader();
        }

        public LeaderDiscoveryStore initialize(LeaderDiscoveryStore toWrap) {
          this.toWrap = toWrap;
          return this;
        }
      }.initialize(defaultLeaderDiscoveryStore);

      Thread.sleep(1000);
      VertxManager.deployLeaderElectionWorkerVerticles(
          vertx,
          leaderElectionDeploymentOptions,
          curatorClient,
          wrappedLeaderElectedTask,
          wrappedLeaderDiscoveryStore
      );

      boolean leaderElectionLatchReleased = leaderElectionLatch.await(10, TimeUnit.SECONDS);
      Thread.sleep(2000); //wait for some time for aggregator verticles to be undeployed
      if (!leaderElectionLatchReleased) {
        testContext.fail("Latch timed out but leader election task was not run");
      } else {
        //Ensure aggregator verticles have been undeployed
        for (String aggDep : backendDeployments) {
          testContext.assertFalse(vertx.deploymentIDs().contains(aggDep));
        }
      }

      boolean leaderWatchedLatchReleased = leaderWatchedLatch.await(10, TimeUnit.SECONDS);
      if (!leaderWatchedLatchReleased) {
        testContext.fail("Latch timed out but leader discovery store was not updated with leader address");
      } else {
        testContext.assertNotNull(defaultLeaderDiscoveryStore.getLeaderIPAddress());
        testContext.assertTrue(defaultLeaderDiscoveryStore.isLeader());
      }

    }
  }

}
