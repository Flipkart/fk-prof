package fk.prof.backend;

import fk.prof.backend.model.association.BackendAssociationStore;
import fk.prof.backend.model.association.ProcessGroupCountBasedBackendComparator;
import fk.prof.backend.model.association.impl.ZookeeperBasedBackendAssociationStore;
import fk.prof.backend.proto.BackendDTO;
import io.vertx.core.CompositeFuture;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.VertxOptions;
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
import java.util.stream.Collectors;

@RunWith(VertxUnitRunner.class)
public class ZookeeperBasedBackendAssociationStoreTest {
  private final static String backendAssociationPath = "/assoc";

  private static List<Recorder.ProcessGroup> mockProcessGroups;
  private static Vertx vertx;
  private static Configuration config;

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
    config = ConfigManager.loadConfig(ZookeeperBasedBackendAssociationStoreTest.class.getClassLoader().getResource("config.json").getFile());

    vertx = Vertx.vertx(new VertxOptions(config.getVertxOptions()));
  }

  @AfterClass
  public static void tearDownAfterClass(TestContext context) {
    vertx.close();
  }

  @Before
  public void setBefore()
      throws Exception {
    testingServer = new TestingServer();
    curatorClient = CuratorFrameworkFactory.newClient(testingServer.getConnectString(), 500, 500, new RetryOneTime(1));
    curatorClient.start();
    curatorClient.blockUntilConnected(10, TimeUnit.SECONDS);
    curatorClient.create().forPath(backendAssociationPath);

    backendAssociationStore = new ZookeeperBasedBackendAssociationStore(
        vertx, curatorClient, backendAssociationPath, 2, 0, new ProcessGroupCountBasedBackendComparator());
    backendAssociationStore.init();
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
        BackendDTO.LoadReportRequest.newBuilder().setIp("1").setPort(1).setLoad(0.1f).setCurrTick(1).build());
    Future<Recorder.ProcessGroups> f2 = backendAssociationStore.reportBackendLoad(
        BackendDTO.LoadReportRequest.newBuilder().setIp("2").setPort(1).setLoad(0.2f).setCurrTick(1).build());
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
        BackendDTO.LoadReportRequest.newBuilder().setIp("1").setPort(1).setLoad(0.1f).setCurrTick(1).build());
    Future<Recorder.ProcessGroups> f2 = backendAssociationStore.reportBackendLoad(
        BackendDTO.LoadReportRequest.newBuilder().setIp("2").setPort(1).setLoad(0.2f).setCurrTick(1).build());
    CompositeFuture.all(Arrays.asList(f1, f2)).setHandler(ar1 -> {
      if(ar1.failed()) {
        context.fail(ar1.cause());
      } else {
        Future<Recorder.AssignedBackend> f3 = backendAssociationStore.associateAndGetBackend(mockProcessGroups.get(0));
        Future<Recorder.AssignedBackend> f4 = backendAssociationStore.associateAndGetBackend(mockProcessGroups.get(1));
        Future<Recorder.AssignedBackend> f5 = backendAssociationStore.associateAndGetBackend(mockProcessGroups.get(2));
        CompositeFuture.all(Arrays.asList(f3, f4, f5)).setHandler(ar2 -> {
          if(ar2.failed()) {
            context.fail(ar2.cause());
          } else {
            List<Recorder.AssignedBackend> associations = ar2.result().list();
            List<String> associationIPs = associations.stream().map(Recorder.AssignedBackend::getHost).collect(Collectors.toList());
            Collections.sort(associationIPs);
            context.assertEquals(3, associations.size());
            List<String> e1 = Arrays.asList("1", "2", "2");
            List<String> e2 = Arrays.asList("1", "1", "2");
            context.assertTrue(e1.equals(associationIPs) || e2.equals(associationIPs));
            async.complete();
          }
        });
      }
    });
  }

  @Test(timeout = 10000)
  public void testGetOfAllAssociations(TestContext context) {
    final Async async = context.async();
    Future<Recorder.ProcessGroups> f1 = backendAssociationStore.reportBackendLoad(
        BackendDTO.LoadReportRequest.newBuilder().setIp("1").setPort(1).setLoad(0.1f).setCurrTick(1).build());
    Future<Recorder.ProcessGroups> f2 = backendAssociationStore.reportBackendLoad(
        BackendDTO.LoadReportRequest.newBuilder().setIp("2").setPort(1).setLoad(0.2f).setCurrTick(1).build());
    CompositeFuture.all(Arrays.asList(f1, f2)).setHandler(ar1 -> {
      if (ar1.failed()) {
        context.fail(ar1.cause());
      } else {
        Future<Recorder.AssignedBackend> f3 = backendAssociationStore.associateAndGetBackend(mockProcessGroups.get(0));
        Future<Recorder.AssignedBackend> f4 = backendAssociationStore.associateAndGetBackend(mockProcessGroups.get(1));
        Future<Recorder.AssignedBackend> f5 = backendAssociationStore.associateAndGetBackend(mockProcessGroups.get(2));
        CompositeFuture.all(Arrays.asList(f3, f4, f5)).setHandler(ar2 -> {
          if (ar2.failed()) {
            context.fail(ar2.cause());
          } else {
            Recorder.BackendAssociations associations = backendAssociationStore.getAssociations();
            Set<Recorder.ProcessGroup> expectedPGs = new HashSet<>(Arrays.asList(mockProcessGroups.get(0), mockProcessGroups.get(1), mockProcessGroups.get(2)));
            Set<Recorder.ProcessGroup> actualPGs = new HashSet<>();
            int actualPGCount = 0;
            Set<String> expectedIPs = new HashSet<>(Arrays.asList("1", "2"));
            Set<String> actualIPs = new HashSet<>();
            for (Recorder.BackendAssociation backendAssociation: associations.getAssociationsList()) {
              actualIPs.add(backendAssociation.getBackend().getHost());
              for (Recorder.ProcessGroup processGroup: backendAssociation.getProcessGroupsList()) {
                actualPGCount++;
                actualPGs.add(processGroup);
              }
            }
            context.assertEquals(expectedIPs, actualIPs);
            context.assertEquals(expectedPGs, actualPGs);
            context.assertEquals(expectedPGs.size(), actualPGCount); //asserting counts because actualPGs is a set and de-duplication can happen so just asserting equality of sets is not sufficient here
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
        BackendDTO.LoadReportRequest.newBuilder().setIp("1").setPort(1).setLoad(0.1f).setCurrTick(1).build());
    Future<Recorder.ProcessGroups> f2 = backendAssociationStore.reportBackendLoad(
        BackendDTO.LoadReportRequest.newBuilder().setIp("2").setPort(1).setLoad(0.2f).setCurrTick(1).build());
    CompositeFuture.all(Arrays.asList(f1, f2)).setHandler(ar1 -> {
      if(ar1.failed()) {
        context.fail(ar1.cause());
      } else {
        Future<Recorder.AssignedBackend> f3 = backendAssociationStore.associateAndGetBackend(mockProcessGroups.get(0));
        Future<Recorder.AssignedBackend> f4 = backendAssociationStore.associateAndGetBackend(mockProcessGroups.get(1));
        Future<Recorder.AssignedBackend> f5 = backendAssociationStore.associateAndGetBackend(mockProcessGroups.get(2));
        CompositeFuture.all(Arrays.asList(f3, f4, f5)).setHandler(ar2 -> {
          if(ar2.failed()) {
            context.fail(ar2.cause());
          } else {
            List<Recorder.AssignedBackend> associations = ar2.result().list();
            try {
              BackendAssociationStore anotherAssociationStore = new ZookeeperBasedBackendAssociationStore(
              vertx, curatorClient, backendAssociationPath, 2, 0, new ProcessGroupCountBasedBackendComparator());
              anotherAssociationStore.init();
              Future<Recorder.AssignedBackend> f3_1 = anotherAssociationStore.associateAndGetBackend(mockProcessGroups.get(0));
              Future<Recorder.AssignedBackend> f4_1 = anotherAssociationStore.associateAndGetBackend(mockProcessGroups.get(1));
              Future<Recorder.AssignedBackend> f5_1 = anotherAssociationStore.associateAndGetBackend(mockProcessGroups.get(2));
              CompositeFuture.all(Arrays.asList(f3_1, f4_1, f5_1)).setHandler(ar3 -> {
                if(ar3.failed()) {
                  context.fail(ar3.cause());
                } else {
                  List<Recorder.AssignedBackend> reloadedAssociations = ar3.result().list();
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
        BackendDTO.LoadReportRequest.newBuilder().setIp("1").setPort(1).setLoad(0.1f).setCurrTick(1).build());
    Future<Recorder.ProcessGroups> f2 = backendAssociationStore.reportBackendLoad(
        BackendDTO.LoadReportRequest.newBuilder().setIp("2").setPort(1).setLoad(0.2f).setCurrTick(1).build());
    CompositeFuture.all(Arrays.asList(f1, f2)).setHandler(ar1 -> {
      if(ar1.failed()) {
        context.fail(ar1.cause());
      } else {
        Future<Recorder.AssignedBackend> f3 = backendAssociationStore.associateAndGetBackend(mockProcessGroups.get(0));
        Future<Recorder.AssignedBackend> f4 = backendAssociationStore.associateAndGetBackend(mockProcessGroups.get(1));
        Future<Recorder.AssignedBackend> f5 = backendAssociationStore.associateAndGetBackend(mockProcessGroups.get(2));
        CompositeFuture.all(Arrays.asList(f3, f4, f5)).setHandler(ar2 -> {
          if(ar2.failed()) {
            context.fail(ar2.cause());
          } else {
            List<Recorder.AssignedBackend> associations = ar2.result().list();
            Future<Recorder.AssignedBackend> f3_1 = backendAssociationStore.associateAndGetBackend(mockProcessGroups.get(0));
            Future<Recorder.AssignedBackend> f4_1 = backendAssociationStore.associateAndGetBackend(mockProcessGroups.get(1));
            Future<Recorder.AssignedBackend> f5_1 = backendAssociationStore.associateAndGetBackend(mockProcessGroups.get(2));
            CompositeFuture.all(Arrays.asList(f3_1, f4_1, f5_1)).setHandler(ar3 -> {
              if(ar3.failed()) {
                context.fail(ar3.cause());
              } else {
                List<Recorder.AssignedBackend> refetchedAssociations = ar3.result().list();
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
        BackendDTO.LoadReportRequest.newBuilder().setIp("1").setPort(1).setLoad(0.1f).setCurrTick(1).build());
    Future<Recorder.ProcessGroups> f2 = backendAssociationStore.reportBackendLoad(
        BackendDTO.LoadReportRequest.newBuilder().setIp("2").setPort(1).setLoad(0.2f).setCurrTick(1).build());
    CompositeFuture.all(Arrays.asList(f1, f2)).setHandler(ar1 -> {
      if(ar1.failed()) {
        context.fail(ar1.cause());
      } else {
        Future<Recorder.AssignedBackend> f3 = backendAssociationStore.associateAndGetBackend(mockProcessGroups.get(0));
        Future<Recorder.AssignedBackend> f4 = backendAssociationStore.associateAndGetBackend(mockProcessGroups.get(1));
        Future<Recorder.AssignedBackend> f5 = backendAssociationStore.associateAndGetBackend(mockProcessGroups.get(2));
        CompositeFuture.all(Arrays.asList(f3, f4, f5)).setHandler(ar2 -> {
          if(ar2.failed()) {
            context.fail(ar2.cause());
          } else {
            List<Recorder.AssignedBackend> associations = ar2.result().list();
            List<String> associationIPs = associations.stream().map(Recorder.AssignedBackend::getHost).collect(Collectors.toList());
            context.assertTrue(associationIPs.contains("1"));
            context.assertTrue(associationIPs.contains("2"));
            vertx.setTimer(3000, timerId -> {
              backendAssociationStore.reportBackendLoad(
                  BackendDTO.LoadReportRequest.newBuilder().setIp("1").setPort(1).setLoad(0.5f).setCurrTick(2).build())
                  .setHandler(ar3 -> {
                if(ar3.failed()) {
                  context.fail(ar3.cause());
                } else {
                  Future<Recorder.AssignedBackend> f3_1 = backendAssociationStore.associateAndGetBackend(mockProcessGroups.get(0));
                  Future<Recorder.AssignedBackend> f4_1 = backendAssociationStore.associateAndGetBackend(mockProcessGroups.get(1));
                  Future<Recorder.AssignedBackend> f5_1 = backendAssociationStore.associateAndGetBackend(mockProcessGroups.get(2));

                  CompositeFuture.all(Arrays.asList(f3_1, f4_1, f5_1)).setHandler(ar4 -> {
                    if(ar4.failed()) {
                      context.fail(ar4.cause());
                    } else {
                      List<Recorder.AssignedBackend> refetchedAssociations = ar4.result().list();
                      List<String> refetchedAssociationIPs = refetchedAssociations.stream().map(Recorder.AssignedBackend::getHost).collect(Collectors.toList());
                      context.assertEquals(3, refetchedAssociations.size());
                      context.assertTrue(refetchedAssociationIPs.contains("1"));
                      context.assertFalse(refetchedAssociationIPs.contains("2"));
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
