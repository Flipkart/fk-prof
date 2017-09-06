package fk.prof.backend;

import fk.prof.aggregation.model.AggregationWindowStorage;
import fk.prof.backend.deployer.VerticleDeployer;
import fk.prof.backend.deployer.impl.*;
import fk.prof.backend.http.ProfHttpClient;
import fk.prof.backend.model.aggregation.ActiveAggregationWindows;
import fk.prof.backend.model.aggregation.impl.ActiveAggregationWindowsImpl;
import fk.prof.backend.model.assignment.AssociatedProcessGroups;
import fk.prof.backend.model.association.BackendAssociationStore;
import fk.prof.backend.model.election.impl.InMemoryLeaderStore;
import fk.prof.backend.model.policy.PolicyStore;
import fk.prof.backend.model.slot.WorkSlotPool;
import io.vertx.core.CompositeFuture;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.VertxOptions;
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

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;

@RunWith(VertxUnitRunner.class)
public class HealthAPITest {
  private Vertx vertx;
  private Configuration config;
  private TestingServer testingServer;
  private CuratorFramework curatorClient;
  private InMemoryLeaderStore inMemoryLeaderStore;

  @Before
  public void setBefore(TestContext context) throws Exception {
    ConfigManager.setDefaultSystemProperties();

    testingServer = new TestingServer();
    curatorClient = CuratorFrameworkFactory.newClient(testingServer.getConnectString(), 500, 500, new RetryOneTime(1));
    curatorClient.start();
    curatorClient.blockUntilConnected(10, TimeUnit.SECONDS);
    curatorClient.create().forPath("/assoc");
    config = ConfigManager.loadConfig(HealthAPITest.class.getClassLoader().getResource("config.json").getFile());
  }

  @After
  public void tearDown(TestContext context) throws IOException {
    final Async async = context.async();
    vertx.close(result -> {
      curatorClient.close();
      try {
        testingServer.close();
        async.complete();
      } catch (IOException ex) {
        context.fail(ex);
      }
      if (result.failed()) {
        context.fail(result.cause());
      }
    });
  }

  @Test
  public void testBackendIsHealthy(TestContext context) throws Exception {
    final Async async = context.async();
    vertx = Vertx.vertx(new VertxOptions(config.getVertxOptions()));
    inMemoryLeaderStore = spy(new InMemoryLeaderStore(config.getIpAddress(), config.getLeaderHttpServerOpts().getPort()));
    VerticleDeployer backendHttpVerticleDeployer = new BackendHttpVerticleDeployer(vertx, config, inMemoryLeaderStore, new ActiveAggregationWindowsImpl(), mock(AssociatedProcessGroups.class));
    backendHttpVerticleDeployer.deploy().setHandler(ar1 -> {
      getHealthRequest().setHandler(ar -> {
        if(ar.failed()) {
          context.fail(ar.cause());
        }
        context.assertEquals(200, ar.result().getStatusCode());
        async.complete();
      });
    });
  }

  @Test
  public void testHealthCheckUnavailableWhenBackendIsLeader(TestContext context) throws Exception {
    final Async async = context.async();
    vertx = Vertx.vertx(new VertxOptions(config.getVertxOptions()));
    inMemoryLeaderStore = spy(new InMemoryLeaderStore(config.getIpAddress(), config.getLeaderHttpServerOpts().getPort()));
    BackendAssociationStore backendAssociationStore = mock(BackendAssociationStore.class);
    AssociatedProcessGroups associatedProcessGroups = mock(AssociatedProcessGroups.class);
    ActiveAggregationWindows activeAggregationWindows = mock(ActiveAggregationWindows.class);

    VerticleDeployer backendHttpVerticleDeployer = new BackendHttpVerticleDeployer(vertx, config, inMemoryLeaderStore,
        activeAggregationWindows, associatedProcessGroups);
    VerticleDeployer backendDaemonVerticleDeployer = new BackendDaemonVerticleDeployer(vertx, config, inMemoryLeaderStore,
        associatedProcessGroups, activeAggregationWindows, mock(WorkSlotPool.class), mock(AggregationWindowStorage.class));
    CompositeFuture.all(backendHttpVerticleDeployer.deploy(), backendDaemonVerticleDeployer.deploy()).setHandler(ar -> {
      if(ar.failed()) {
        context.fail(ar.result().cause());
      }
      try {
        List<String> backendDeployments = new ArrayList<String>(((CompositeFuture)ar.result().list().get(0)).list());
        backendDeployments.add((String)((CompositeFuture)ar.result().list().get(1)).list().get(0));

        PolicyStore policyStore = mock(PolicyStore.class);
        VerticleDeployer leaderHttpVerticleDeployer = new LeaderHttpVerticleDeployer(vertx, config, backendAssociationStore, policyStore);
        Runnable leaderElectedTask = BackendManager.createLeaderElectedTask(vertx, leaderHttpVerticleDeployer, backendDeployments, backendAssociationStore, policyStore);
        VerticleDeployer leaderElectionParticipatorVerticleDeployer = new LeaderElectionParticipatorVerticleDeployer(
            vertx, config, curatorClient, leaderElectedTask);
        VerticleDeployer leaderElectionWatcherVerticleDeployer = new LeaderElectionWatcherVerticleDeployer(vertx, config, curatorClient, inMemoryLeaderStore);
        CompositeFuture.all(leaderElectionParticipatorVerticleDeployer.deploy(), leaderElectionWatcherVerticleDeployer.deploy()).setHandler(ar1 -> {
          if(ar1.failed()) {
            context.fail(ar1.cause());
          }
          try {
            //wait for leader to be propagated to in memory leader store
            vertx.setTimer(3000, tid -> {
              System.out.println("leader details: \n" + inMemoryLeaderStore.getLeader());
              getHealthRequest().setHandler(ar2 -> {
                if(ar2.succeeded()) {
                  context.fail("Health check should have failed");
                }
                context.assertTrue(ar2.cause().getMessage().toLowerCase().contains("connection refused"));
                async.complete();
              });
            });
          } catch (Exception ex) {
            context.fail(ex);
          }
        });
      } catch (Exception ex) {
        context.fail(ex);
      }
    });
  }

  private Future<ProfHttpClient.ResponseWithStatusTuple> getHealthRequest() {
    Future<ProfHttpClient.ResponseWithStatusTuple> future = Future.future();
    HttpClientRequest request = vertx.createHttpClient()
        .get(config.getBackendHttpServerOpts().getPort(), "localhost", "/health")
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
    request.end();
    return future;
  }

}
