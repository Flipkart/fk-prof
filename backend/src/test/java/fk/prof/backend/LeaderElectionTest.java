package fk.prof.backend;

import fk.prof.backend.deployer.VerticleDeployer;
import fk.prof.backend.deployer.impl.BackendHttpVerticleDeployer;
import fk.prof.backend.deployer.impl.LeaderElectionParticipatorVerticleDeployer;
import fk.prof.backend.deployer.impl.LeaderElectionWatcherVerticleDeployer;
import fk.prof.backend.deployer.impl.LeaderHttpVerticleDeployer;
import fk.prof.backend.leader.election.LeaderElectedTask;
import fk.prof.backend.mock.MockLeaderStores;
import fk.prof.backend.model.aggregation.ActiveAggregationWindows;
import fk.prof.backend.model.aggregation.impl.ActiveAggregationWindowsImpl;
import fk.prof.backend.model.assignment.AssociatedProcessGroups;
import fk.prof.backend.model.assignment.impl.AssociatedProcessGroupsImpl;
import fk.prof.backend.model.association.BackendAssociationStore;
import fk.prof.backend.model.association.ProcessGroupCountBasedBackendComparator;
import fk.prof.backend.model.association.impl.ZookeeperBasedBackendAssociationStore;
import fk.prof.backend.model.election.LeaderWriteContext;
import fk.prof.backend.model.election.impl.InMemoryLeaderStore;
import fk.prof.backend.model.policy.PolicyStore;
import fk.prof.backend.model.policy.ZookeeperBasedPolicyStore;
import fk.prof.idl.*;
import io.vertx.core.CompositeFuture;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.VertxOptions;
import io.vertx.core.impl.CompositeFutureImpl;
import io.vertx.ext.unit.TestContext;
import io.vertx.ext.unit.junit.VertxUnitRunner;
import org.apache.commons.lang3.mutable.MutableObject;
import org.apache.curator.framework.CuratorFramework;
import org.apache.curator.framework.CuratorFrameworkFactory;
import org.apache.curator.retry.RetryOneTime;
import org.apache.curator.test.TestingServer;
import org.apache.zookeeper.KeeperException;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static fk.prof.backend.util.ZookeeperUtil.DELIMITER;
import static org.mockito.Mockito.*;

@RunWith(VertxUnitRunner.class)
public class LeaderElectionTest {

  private Vertx vertx;
  private Configuration config;

  private TestingServer testingServer;
  private CuratorFramework curatorClient;

  @Before
  public void setUp(TestContext context) throws Exception {
    ConfigManager.setDefaultSystemProperties();
    config = ConfigManager.loadConfig(LeaderElectionTest.class.getClassLoader().getResource("config.json").getFile());

    testingServer = new TestingServer();
    curatorClient = CuratorFrameworkFactory.newClient(testingServer.getConnectString(), 500, 500, new RetryOneTime(1));
    curatorClient.start();
    curatorClient.blockUntilConnected(10, TimeUnit.SECONDS);
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
  public void leaderTaskTriggerOnLeaderElection(TestContext testContext) throws InterruptedException {
    vertx = Vertx.vertx(new VertxOptions(config.getVertxOptions()));
    CountDownLatch latch = new CountDownLatch(1);
    Runnable leaderElectedTask = () -> {
      latch.countDown();
    };
    LeaderWriteContext leaderWriteContext = new InMemoryLeaderStore(config.getIpAddress(), config.getLeaderHttpServerOpts().getPort());

    VerticleDeployer leaderParticipatorDeployer = new LeaderElectionParticipatorVerticleDeployer(vertx, config, curatorClient, leaderElectedTask);
    VerticleDeployer leaderWatcherDeployer = new LeaderElectionWatcherVerticleDeployer(vertx, config, curatorClient, leaderWriteContext);

    leaderParticipatorDeployer.deploy();
    leaderWatcherDeployer.deploy();

    boolean released = latch.await(10, TimeUnit.SECONDS);
    if (!released) {
      testContext.fail("Latch timed out but leader election task was not run");
    }
  }

  @Test(timeout = 20000)
  public void leaderUpdateOnLeaderElection(TestContext testContext) throws InterruptedException {
    vertx = Vertx.vertx(new VertxOptions(config.getVertxOptions()));
    CountDownLatch latch = new CountDownLatch(1);
    Runnable leaderElectedTask = () -> {};
    MockLeaderStores.TestLeaderStore leaderStore = new MockLeaderStores.TestLeaderStore(config.getIpAddress(), config.getLeaderHttpServerOpts().getPort(), latch);

    VerticleDeployer leaderParticipatorDeployer = new LeaderElectionParticipatorVerticleDeployer(vertx, config, curatorClient, leaderElectedTask);
    VerticleDeployer leaderWatcherDeployer = new LeaderElectionWatcherVerticleDeployer(vertx, config, curatorClient, leaderStore);

    leaderParticipatorDeployer.deploy();
    leaderWatcherDeployer.deploy();

    boolean released = latch.await(10, TimeUnit.SECONDS);
    if (!released) {
      testContext.fail("Latch timed out but leader store was not updated with leader address");
    } else {
      testContext.assertEquals(config.getIpAddress(), leaderStore.getLeader().getHost());
      testContext.assertTrue(leaderStore.isLeader());
    }
  }

  @Test(timeout = 20000)
  public void leaderElectionAssertionsWithDisablingOfBackendDuties(TestContext testContext) throws InterruptedException {
    vertx = Vertx.vertx(new VertxOptions(config.getVertxOptions()));
    ActiveAggregationWindows activeAggregationWindows = new ActiveAggregationWindowsImpl();
    AssociatedProcessGroups associatedProcessGroups = new AssociatedProcessGroupsImpl(config.getRecorderDefunctThresholdSecs());
    InMemoryLeaderStore leaderStore = new InMemoryLeaderStore(config.getIpAddress(), config.getLeaderHttpServerOpts().getPort());
    List<String> backendDeployments = new ArrayList<>();
    CountDownLatch aggDepLatch = new CountDownLatch(1);

    VerticleDeployer backendVerticleDeployer = new BackendHttpVerticleDeployer(vertx, config, leaderStore, activeAggregationWindows, associatedProcessGroups);
    backendVerticleDeployer.deploy().setHandler(asyncResult -> {
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

      VerticleDeployer leaderHttpDeployer = mock(LeaderHttpVerticleDeployer.class);
      when(leaderHttpDeployer.deploy()).thenReturn(CompositeFutureImpl.all(Future.succeededFuture()));
      Runnable leaderElectedTask = LeaderElectedTask.newBuilder().disableBackend(backendDeployments).build(vertx, leaderHttpDeployer, mock(BackendAssociationStore.class), mock(PolicyStore.class));
      Runnable wrappedLeaderElectedTask = () -> {
        leaderElectedTask.run();
        leaderElectionLatch.countDown();
      };
      LeaderWriteContext leaderWriteContext = new MockLeaderStores.WrappedLeaderStore(leaderStore, leaderWatchedLatch);

      VerticleDeployer leaderParticipatorDeployer = new LeaderElectionParticipatorVerticleDeployer(vertx, config, curatorClient, wrappedLeaderElectedTask);
      VerticleDeployer leaderWatcherDeployer = new LeaderElectionWatcherVerticleDeployer(vertx, config, curatorClient, leaderWriteContext);
      leaderParticipatorDeployer.deploy();
      leaderWatcherDeployer.deploy();

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
        testContext.fail("Latch timed out but leader store was not updated with leader address");
      } else {
        testContext.assertNotNull(leaderStore.getLeader());
        testContext.assertTrue(leaderStore.isLeader());
      }

    }
  }

  @Test(timeout = 3000)
  public void   testBackendAssociationAndPolicyStoreInitOnLeaderSelect(TestContext context) throws Exception {
    vertx = Vertx.vertx(new VertxOptions(config.getVertxOptions()));

    Entities.ProcessGroup pg1 = Entities.ProcessGroup.newBuilder().setAppId("a1").setCluster("c1").setProcName("p1").build();
    Entities.ProcessGroup pg2 = Entities.ProcessGroup.newBuilder().setAppId("a1").setCluster("c1").setProcName("p2").build();

    WorkEntities.Work work = WorkEntities.Work.newBuilder()
            .setWType(WorkEntities.WorkType.cpu_sample_work)
            .setCpuSample(WorkEntities.CpuSampleWork.newBuilder()
                    .setMaxFrames(128).setFrequency(100)).build();
    PolicyEntities.Schedule schedule = PolicyEntities.Schedule.newBuilder().setDuration(200).setPgCovPct(100).setAfter("w0").build();
    PolicyEntities.PolicyDetails policyDetails = PolicyEntities.PolicyDetails.newBuilder().
            setPolicy(PolicyEntities.Policy.newBuilder().
                    addWork(work).setSchedule(schedule).setDescription("Test policy").build()).setCreatedAt("3").setModifiedAt("4").setModifiedBy("admin").build();

    // populate some data first
    CompositeFuture f = populateAssociationAndPolicies(pg1, pg2, policyDetails);
    f.setHandler(res -> {
      if(res.failed()) {
        context.fail(res.cause());
      }
      else {
        CompositeFuture cf =  res.result();
        try {
          // create fresh instance.
          BackendAssociationStore backendAssociationStore = createBackendAssociationStore(vertx, curatorClient);

          // get the httpVerticleDeployedFuture for reference.
          MutableObject<Future> httpVerticleDeployedFuture = new MutableObject<>();
          PolicyStore policyStore = new ZookeeperBasedPolicyStore(vertx, curatorClient, config.getPolicyBaseDir(), config.getPolicyVersion());
          VerticleDeployer leaderHttpVerticleDeployer = spy(new LeaderHttpVerticleDeployer(vertx, config, backendAssociationStore, policyStore));
          when(leaderHttpVerticleDeployer.deploy()).thenAnswer(inv -> {
            httpVerticleDeployedFuture.setValue((Future) inv.callRealMethod());
            return httpVerticleDeployedFuture.getValue();
          });

          // setup leader deployment
          CountDownLatch latch = new CountDownLatch(1);
          LeaderElectedTask.Builder builder = LeaderElectedTask.newBuilder();
          builder.disableBackend(Collections.emptyList());
          Runnable leaderElectedTask = builder.build(vertx, leaderHttpVerticleDeployer, backendAssociationStore, policyStore);
          Runnable leaderElectedTaskWithLatch = () -> {
            System.out.println("running leader elected task");
            leaderElectedTask.run();
            latch.countDown();
            System.out.println("latch down");
          };

          LeaderWriteContext leaderWriteContext = new InMemoryLeaderStore(config.getIpAddress(), config.getLeaderHttpServerOpts().getPort());

          VerticleDeployer leaderParticipatorDeployer = new LeaderElectionParticipatorVerticleDeployer(vertx, config, curatorClient, leaderElectedTaskWithLatch);
          VerticleDeployer leaderWatcherDeployer = new LeaderElectionWatcherVerticleDeployer(vertx, config, curatorClient, leaderWriteContext);

          leaderParticipatorDeployer.deploy();
          leaderWatcherDeployer.deploy();

          boolean released = latch.await(10, TimeUnit.SECONDS);
          if (!released) {
            context.fail("Latch timed out but leader election task was not run");
          }

          // wait for leader deployment finish
          while(!httpVerticleDeployedFuture.getValue().isComplete()) {
            Thread.sleep(1000);
          }

          // check the values in store
          context.assertEquals(policyDetails.getPolicy(), policyStore.getVersionedPolicy(pg1).getPolicyDetails().getPolicy(), "policy should match");
          Recorder.AssignedBackend backend1 = backendAssociationStore.getAssociatedBackend(pg1);
          context.assertEquals(cf.resultAt(0), backend1);
          Recorder.AssignedBackend backend2 = backendAssociationStore.getAssociatedBackend(pg2);
          context.assertEquals(cf.resultAt(1), backend2);
        }
        catch (Exception e) {
          context.fail(e);
        }
      }
    });
  }

  private CompositeFuture populateAssociationAndPolicies(Entities.ProcessGroup pg1, Entities.ProcessGroup pg2, PolicyEntities.PolicyDetails policy) throws Exception {
    // make sure association node is present
    try {
      curatorClient.create().forPath(config.getAssociationsConfig().getAssociationPath());
      curatorClient.create().creatingParentsIfNeeded().forPath(DELIMITER + config.getPolicyBaseDir() + DELIMITER + config.getPolicyVersion());

    } catch (KeeperException.NodeExistsException ex) {
      // ignore
    }

    BackendAssociationStore backendAssociationStore = createBackendAssociationStore(vertx, curatorClient);
    backendAssociationStore.init();

    PolicyStore policyStore = new ZookeeperBasedPolicyStore(vertx, curatorClient, "policy", "v0001");
    policyStore.init();

    backendAssociationStore.reportBackendLoad(Backend.LoadReportRequest.newBuilder().setCurrTick(1).setIp("1").setLoad(0.5f).setPort(1234).build());
    backendAssociationStore.reportBackendLoad(Backend.LoadReportRequest.newBuilder().setCurrTick(1).setIp("2").setLoad(0.5f).setPort(1234).build());

    Future f1 = Future.future();
    Future f2 = Future.future();
    Future composition = Future.future();
    backendAssociationStore.associateAndGetBackend(pg1)
        .compose(res -> {
          f1.complete(res);
          backendAssociationStore.associateAndGetBackend(pg2).compose(res2 -> {
            f2.complete(res2);
            composition.complete();
          }, composition);
        }, composition);

    policyStore.createVersionedPolicy(pg1, PolicyEntities.VersionedPolicyDetails.newBuilder().setPolicyDetails(policy).setVersion(-1).build());

    return CompositeFuture.all(f1, f2);
  }

  private BackendAssociationStore createBackendAssociationStore(
          Vertx vertx, CuratorFramework curatorClient)
          throws Exception {
    int loadReportIntervalInSeconds = config.getLoadReportItvlSecs();
    String backendAssociationPath = config.getAssociationsConfig().getAssociationPath();
    int loadMissTolerance = config.getAssociationsConfig().getLoadMissTolerance();
    return new ZookeeperBasedBackendAssociationStore(vertx, curatorClient, backendAssociationPath,
            loadReportIntervalInSeconds, loadMissTolerance, new ProcessGroupCountBasedBackendComparator());
  }
}
