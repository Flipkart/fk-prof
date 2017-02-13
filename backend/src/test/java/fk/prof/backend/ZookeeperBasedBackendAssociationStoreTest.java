package fk.prof.backend;

import fk.prof.backend.model.association.BackendAssociationStore;
import fk.prof.backend.model.association.ProcessGroupCountBasedBackendComparator;
import fk.prof.backend.model.association.impl.ZookeeperBasedBackendAssociationStore;
import fk.prof.backend.proto.BackendDTO;
import io.vertx.core.CompositeFuture;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.VertxOptions;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.unit.Async;
import io.vertx.ext.unit.TestContext;
import io.vertx.ext.unit.junit.VertxUnitRunner;
import org.apache.curator.framework.CuratorFramework;
import org.apache.curator.framework.CuratorFrameworkFactory;
import org.apache.curator.retry.RetryOneTime;
import org.apache.curator.test.TestingServer;
import org.junit.*;
import org.junit.runner.RunWith;
import recording.Recorder;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.TimeUnit;

@RunWith(VertxUnitRunner.class)
public class ZookeeperBasedBackendAssociationStoreTest {
  private final static String backendAssociationPath = "/assoc";
  private static List<Recorder.ProcessGroup> mockProcessGroups;
  private static Vertx vertx;
  private static JsonObject curatorConfig;
  private TestingServer testingServer;
  private CuratorFramework curatorClient;
  private BackendAssociationStore backendAssociationStore;

  @BeforeClass
  public static void setBeforeClass(TestContext context)
      throws Exception {
    mockProcessGroups = Arrays.asList(
        Recorder.ProcessGroup.newBuilder().setAppId("a").setCluster("c").setProcName("p1").build(),
        Recorder.ProcessGroup.newBuilder().setAppId("a").setCluster("c").setProcName("p2").build(),
        Recorder.ProcessGroup.newBuilder().setAppId("a").setCluster("c").setProcName("p3").build()
    );

    ConfigManager.setDefaultSystemProperties();
    JsonObject config = ConfigManager.loadFileAsJson(ZookeeperBasedBackendAssociationStoreTest.class.getClassLoader().getResource("config.json").getFile());

    JsonObject vertxConfig = ConfigManager.getVertxConfig(config);
    vertx = vertxConfig != null ? Vertx.vertx(new VertxOptions(vertxConfig)) : Vertx.vertx();

    curatorConfig = ConfigManager.getCuratorConfig(config);
    assert curatorConfig != null;
  }

  @AfterClass
  public static void tearDownAfterClass(TestContext context) {
    VertxManager.close(vertx);
  }

  @Before
  public void setBefore()
      throws Exception {
    testingServer = new TestingServer();
    curatorClient = CuratorFrameworkFactory.newClient(testingServer.getConnectString(), 500, 500, new RetryOneTime(1));
    curatorClient.start();
    curatorClient.blockUntilConnected(10, TimeUnit.SECONDS);
    curatorClient.create().forPath(backendAssociationPath);

    backendAssociationStore = ZookeeperBasedBackendAssociationStore.newBuilder()
        .setCuratorClient(curatorClient)
        .setBackendAssociationPath(backendAssociationPath)
        .setBackedPriorityComparator(new ProcessGroupCountBasedBackendComparator())
        .setReportingFrequencyInSeconds(2)
        .setMaxAllowedSkips(0)
        .build(vertx);
  }

  @After
  public void tearDown(TestContext context) throws IOException {
    curatorClient.close();
    testingServer.close();
  }

  @Test(timeout = 10000)
  public void testReportOfNewBackends(TestContext context) {
    final Async async = context.async();
    Future<Recorder.ProcessGroups> f1 = backendAssociationStore.reportBackendLoad(
        BackendDTO.LoadReportRequest.newBuilder().setIp("1").setLoad(0.1f).build());
    Future<Recorder.ProcessGroups> f2 = backendAssociationStore.reportBackendLoad(
        BackendDTO.LoadReportRequest.newBuilder().setIp("2").setLoad(0.2f).build());
    CompositeFuture.all(Arrays.asList(f1, f2)).setHandler(ar -> {
      if(ar.failed()) {
        context.fail(ar.cause());
      } else {
        List<Set<Recorder.ProcessGroup>> results = ar.result().list();
        context.assertEquals(Recorder.ProcessGroups.newBuilder().build(), results.get(0));
        context.assertEquals(Recorder.ProcessGroups.newBuilder().build(), results.get(1));
        async.complete();
      }
    });
  }

  @Test(timeout = 10000)
  public void testAssociationOfBackendsWithNewProcessGroups(TestContext context) {
    final Async async = context.async();
    Future<Recorder.ProcessGroups> f1 = backendAssociationStore.reportBackendLoad(
        BackendDTO.LoadReportRequest.newBuilder().setIp("1").setLoad(0.1f).build());
    Future<Recorder.ProcessGroups> f2 = backendAssociationStore.reportBackendLoad(
        BackendDTO.LoadReportRequest.newBuilder().setIp("2").setLoad(0.2f).build());
    CompositeFuture.all(Arrays.asList(f1, f2)).setHandler(ar1 -> {
      if(ar1.failed()) {
        context.fail(ar1.cause());
      } else {
        Future<String> f3 = backendAssociationStore.getAssociatedBackend(mockProcessGroups.get(0));
        Future<String> f4 = backendAssociationStore.getAssociatedBackend(mockProcessGroups.get(1));
        Future<String> f5 = backendAssociationStore.getAssociatedBackend(mockProcessGroups.get(2));
        CompositeFuture.all(Arrays.asList(f3, f4, f5)).setHandler(ar2 -> {
          if(ar2.failed()) {
            context.fail(ar2.cause());
          } else {
            List<String> associations = ar2.result().list();
            Collections.sort(associations);
            context.assertEquals(3, associations.size());
            List<String> e1 = Arrays.asList("1", "2", "2");
            List<String> e2 = Arrays.asList("1", "1", "2");
            context.assertTrue(e1.equals(associations) || e2.equals(associations));
            async.complete();
          }
        });
      }
    });
  }

  @Test(timeout = 10000)
  public void testReloadOfAssociationsFromZK(TestContext context) {
    final Async async = context.async();
    Future<Recorder.ProcessGroups> f1 = backendAssociationStore.reportBackendLoad(
        BackendDTO.LoadReportRequest.newBuilder().setIp("1").setLoad(0.1f).build());
    Future<Recorder.ProcessGroups> f2 = backendAssociationStore.reportBackendLoad(
        BackendDTO.LoadReportRequest.newBuilder().setIp("2").setLoad(0.2f).build());
    CompositeFuture.all(Arrays.asList(f1, f2)).setHandler(ar1 -> {
      if(ar1.failed()) {
        context.fail(ar1.cause());
      } else {
        Future<String> f3 = backendAssociationStore.getAssociatedBackend(mockProcessGroups.get(0));
        Future<String> f4 = backendAssociationStore.getAssociatedBackend(mockProcessGroups.get(1));
        Future<String> f5 = backendAssociationStore.getAssociatedBackend(mockProcessGroups.get(2));
        CompositeFuture.all(Arrays.asList(f3, f4, f5)).setHandler(ar2 -> {
          if(ar2.failed()) {
            context.fail(ar2.cause());
          } else {
            List<String> associations = ar2.result().list();
            try {
              BackendAssociationStore anotherAssociationStore = ZookeeperBasedBackendAssociationStore.newBuilder()
                  .setCuratorClient(curatorClient)
                  .setBackendAssociationPath(backendAssociationPath)
                  .setBackedPriorityComparator(new ProcessGroupCountBasedBackendComparator())
                  .setReportingFrequencyInSeconds(2)
                  .setMaxAllowedSkips(0)
                  .build(vertx);
              Future<String> f3_1 = anotherAssociationStore.getAssociatedBackend(mockProcessGroups.get(0));
              Future<String> f4_1 = anotherAssociationStore.getAssociatedBackend(mockProcessGroups.get(1));
              Future<String> f5_1 = anotherAssociationStore.getAssociatedBackend(mockProcessGroups.get(2));
              CompositeFuture.all(Arrays.asList(f3_1, f4_1, f5_1)).setHandler(ar3 -> {
                if(ar3.failed()) {
                  context.fail(ar3.cause());
                } else {
                  List<String> reloadedAssociations = ar3.result().list();
                  context.assertEquals(associations, reloadedAssociations);
                  async.complete();
                }
              });
            } catch (Exception ex) {
              context.fail(ex);
            }
          }
        });
      }
    });
  }

  @Test(timeout = 10000)
  public void testAssociationOfBackendsWithExistingProcessGroups(TestContext context) {
    final Async async = context.async();
    Future<Recorder.ProcessGroups> f1 = backendAssociationStore.reportBackendLoad(
        BackendDTO.LoadReportRequest.newBuilder().setIp("1").setLoad(0.1f).build());
    Future<Recorder.ProcessGroups> f2 = backendAssociationStore.reportBackendLoad(
        BackendDTO.LoadReportRequest.newBuilder().setIp("2").setLoad(0.2f).build());
    CompositeFuture.all(Arrays.asList(f1, f2)).setHandler(ar1 -> {
      if(ar1.failed()) {
        context.fail(ar1.cause());
      } else {
        Future<String> f3 = backendAssociationStore.getAssociatedBackend(mockProcessGroups.get(0));
        Future<String> f4 = backendAssociationStore.getAssociatedBackend(mockProcessGroups.get(1));
        Future<String> f5 = backendAssociationStore.getAssociatedBackend(mockProcessGroups.get(2));
        CompositeFuture.all(Arrays.asList(f3, f4, f5)).setHandler(ar2 -> {
          if(ar2.failed()) {
            context.fail(ar2.cause());
          } else {
            List<String> associations = ar2.result().list();
            Future<String> f3_1 = backendAssociationStore.getAssociatedBackend(mockProcessGroups.get(0));
            Future<String> f4_1 = backendAssociationStore.getAssociatedBackend(mockProcessGroups.get(1));
            Future<String> f5_1 = backendAssociationStore.getAssociatedBackend(mockProcessGroups.get(2));
            CompositeFuture.all(Arrays.asList(f3_1, f4_1, f5_1)).setHandler(ar3 -> {
              if(ar3.failed()) {
                context.fail(ar3.cause());
              } else {
                List<String> refetchedAssociations = ar3.result().list();
                context.assertEquals(associations, refetchedAssociations);
                async.complete();
              }
            });
          }
        });
      }
    });
  }

  @Test(timeout = 10000)
  public void testReAssociationOfBackendsWithDefunctBackend(TestContext context) {
    final Async async = context.async();
    Future<Recorder.ProcessGroups> f1 = backendAssociationStore.reportBackendLoad(
        BackendDTO.LoadReportRequest.newBuilder().setIp("1").setLoad(0.1f).build());
    Future<Recorder.ProcessGroups> f2 = backendAssociationStore.reportBackendLoad(
        BackendDTO.LoadReportRequest.newBuilder().setIp("2").setLoad(0.2f).build());
    CompositeFuture.all(Arrays.asList(f1, f2)).setHandler(ar1 -> {
      if(ar1.failed()) {
        context.fail(ar1.cause());
      } else {
        Future<String> f3 = backendAssociationStore.getAssociatedBackend(mockProcessGroups.get(0));
        Future<String> f4 = backendAssociationStore.getAssociatedBackend(mockProcessGroups.get(1));
        Future<String> f5 = backendAssociationStore.getAssociatedBackend(mockProcessGroups.get(2));
        CompositeFuture.all(Arrays.asList(f3, f4, f5)).setHandler(ar2 -> {
          if(ar2.failed()) {
            context.fail(ar2.cause());
          } else {
            List<String> associations = ar2.result().list();
            context.assertTrue(associations.contains("1"));
            context.assertTrue(associations.contains("2"));
            vertx.setTimer(3000, timerId -> {
              backendAssociationStore.reportBackendLoad(
                  BackendDTO.LoadReportRequest.newBuilder().setIp("1").setLoad(0.5f).build())
                  .setHandler(ar3 -> {
                if(ar3.failed()) {
                  context.fail(ar3.cause());
                } else {
                  Future<String> f3_1 = backendAssociationStore.getAssociatedBackend(mockProcessGroups.get(0));
                  Future<String> f4_1 = backendAssociationStore.getAssociatedBackend(mockProcessGroups.get(1));
                  Future<String> f5_1 = backendAssociationStore.getAssociatedBackend(mockProcessGroups.get(2));

                  CompositeFuture.all(Arrays.asList(f3_1, f4_1, f5_1)).setHandler(ar4 -> {
                    if(ar4.failed()) {
                      context.fail(ar4.cause());
                    } else {
                      List<String> refetchedAssociations = ar4.result().list();
                      context.assertEquals(3, refetchedAssociations.size());
                      context.assertTrue(refetchedAssociations.contains("1"));
                      context.assertFalse(refetchedAssociations.contains("2"));
                      async.complete();
                    }
                  });
                }
              });
            });
          }
        });
      }
    });
  }

}
