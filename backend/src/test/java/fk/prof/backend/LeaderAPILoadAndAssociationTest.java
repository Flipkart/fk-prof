package fk.prof.backend;

import fk.prof.backend.deployer.VerticleDeployer;
import fk.prof.backend.deployer.impl.LeaderHttpVerticleDeployer;
import fk.prof.backend.model.association.BackendAssociationStore;
import fk.prof.backend.model.association.ProcessGroupCountBasedBackendComparator;
import fk.prof.backend.model.association.impl.ZookeeperBasedBackendAssociationStore;
import fk.prof.backend.model.policy.PolicyStore;
import fk.prof.backend.proto.BackendDTO;
import fk.prof.backend.util.ProtoUtil;
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
import recording.Recorder;

import java.io.IOException;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

@RunWith(VertxUnitRunner.class)
public class LeaderAPILoadAndAssociationTest {
  private Vertx vertx;
  private Configuration config;
  private Integer port;

  private TestingServer testingServer;
  private CuratorFramework curatorClient;
  private List<Recorder.ProcessGroup> mockProcessGroups;


  @Before
  public void setBefore(TestContext context) throws Exception {
    mockProcessGroups = Arrays.asList(
      Recorder.ProcessGroup.newBuilder().setAppId("a").setCluster("c").setProcName("p1").build(),
      Recorder.ProcessGroup.newBuilder().setAppId("a").setCluster("c").setProcName("p2").build(),
      Recorder.ProcessGroup.newBuilder().setAppId("a").setCluster("c").setProcName("p3").build()
    );
    ConfigManager.setDefaultSystemProperties();

    testingServer = new TestingServer();
    curatorClient = CuratorFrameworkFactory.newClient(testingServer.getConnectString(), 500, 500, new RetryOneTime(1));
    curatorClient.start();
    curatorClient.blockUntilConnected(10, TimeUnit.SECONDS);

    config = ConfigManager.loadConfig(LeaderAPILoadAndAssociationTest.class.getClassLoader().getResource("config.json").getFile());
    vertx = Vertx.vertx(new VertxOptions(config.getVertxOptions()));
    port = config.getLeaderHttpServerOpts().getPort();

    String backendAssociationPath = config.getAssociationsConfig().getAssociationPath();
    curatorClient.create().forPath(backendAssociationPath);

    BackendAssociationStore backendAssociationStore = new ZookeeperBasedBackendAssociationStore(
        vertx, curatorClient, backendAssociationPath,
        config.getLoadReportItvlSecs(),
        config.getAssociationsConfig().getLoadMissTolerance(),
        new ProcessGroupCountBasedBackendComparator());
    PolicyStore policyStore = new PolicyStore(curatorClient);

    VerticleDeployer leaderHttpDeployer = new LeaderHttpVerticleDeployer(vertx, config, backendAssociationStore, policyStore);
    leaderHttpDeployer.deploy();
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

  @Test(timeout = 5000)
  public void reportNewBackendLoad(TestContext context) throws IOException {
    makeRequestReportLoad(BackendDTO.LoadReportRequest.newBuilder().setIp("1").setPort(1).setLoad(0.5f).setCurrTick(1).build())
        .setHandler(ar -> {
          if(ar.succeeded()) {
            context.assertEquals(0, ar.result().getProcessGroupList().size());
            context.async().complete();
          } else {
            context.fail(ar.cause());
          }
        });
  }

  /**
   * Simulates following scenario:
   * => backend ip=1 reported
   * => association fetched for process group=p1, should be backend ip=1, since this is the only backend available
   * => association fetched for process group=p2, should again be backend ip=1, since this is the only backend available
   * => backend ip=2 reported
   * => association fetched for process group=p3, should be backend ip=2, since this has zero assigned and takes priority over backend ip=1
   * => wait for sometime so that backends get marked as defunct, then report load for backend ip=1, so that it gets marked available
   * => association fetched for process group=p3 again, should be backend ip=1 now since backend ip=2 has been marked defunct
   * @param context
   */
  @Test(timeout = 5000)
  public void getAssociationForProcessGroups(TestContext context) throws IOException {
    final Async async = context.async();
    BackendDTO.LoadReportRequest.Builder loadRequestBuilder1 = BackendDTO.LoadReportRequest.newBuilder().setIp("1").setPort(1).setLoad(0.5f);
    BackendDTO.LoadReportRequest.Builder loadRequestBuilder2 = BackendDTO.LoadReportRequest.newBuilder().setIp("2").setPort(1).setLoad(0.5f);

    makeRequestReportLoad(loadRequestBuilder1.clone().setCurrTick(1).build())
        .setHandler(ar1 -> {
          if(ar1.succeeded()) {
            try {
              makeRequestPostAssociation(buildRecorderInfoFromProcessGroup(mockProcessGroups.get(0)))
                  .setHandler(ar2 -> {
                    if (ar2.succeeded()) {
                      try {
                        context.assertEquals("1", ar2.result().getHost());
                        makeRequestPostAssociation(buildRecorderInfoFromProcessGroup(mockProcessGroups.get(1)))
                            .setHandler(ar3 -> {
                              if (ar3.succeeded()) {
                                context.assertEquals("1", ar3.result().getHost());
                                try {
                                  makeRequestReportLoad(loadRequestBuilder2.clone().setCurrTick(1).build())
                                      .setHandler(ar4 -> {
                                        if (ar4.succeeded()) {
                                          try {
                                            makeRequestPostAssociation(buildRecorderInfoFromProcessGroup(mockProcessGroups.get(2)))
                                                .setHandler(ar5 -> {
                                                  if (ar5.succeeded()) {
                                                    context.assertEquals("2", ar5.result().getHost());
                                                    vertx.setTimer(3000, timerId -> {
                                                      try {
                                                        makeRequestReportLoad(loadRequestBuilder1.clone().setCurrTick(2).build())
                                                            .setHandler(ar6 -> {
                                                              if (ar6.succeeded()) {
                                                                try {
                                                                  makeRequestPostAssociation(buildRecorderInfoFromProcessGroup(mockProcessGroups.get(2)))
                                                                      .setHandler(ar7 -> {
                                                                        if (ar7.succeeded()) {
                                                                          context.assertEquals("1", ar7.result().getHost());
                                                                          async.complete();
                                                                        } else {
                                                                          context.fail(ar7.cause());
                                                                        }
                                                                      });
                                                                } catch (IOException ex) {
                                                                  context.fail(ex);
                                                                }
                                                              } else {
                                                                context.fail(ar6.cause());
                                                              }
                                                            });
                                                      } catch (IOException ex) {
                                                        context.fail(ex);
                                                      }
                                                    });
                                                  } else {
                                                    context.fail(ar5.cause());
                                                  }
                                                });
                                          } catch (IOException ex) {
                                            context.fail(ex);
                                          }
                                        } else {
                                          context.fail(ar4.cause());
                                        }
                                      });
                                } catch (IOException ex) {
                                  context.fail(ex);
                                }
                              } else {
                                context.fail(ar3.cause());
                              }
                            });
                      } catch (IOException ex) {
                        context.fail(ex);
                      }
                    } else {
                      context.fail(ar2.cause());
                    }
                  });
            } catch (IOException ex) { context.fail(ex); }
          } else {
            context.fail(ar1.cause());
          }
        });
  }

  @Test(timeout = 5000)
  public void getAllAssociations(TestContext context) throws IOException {
    final Async async = context.async();
    BackendDTO.LoadReportRequest loadRequest1 = BackendDTO.LoadReportRequest.newBuilder().setIp("1").setPort(1).setLoad(0.5f).setCurrTick(2).build();
    BackendDTO.LoadReportRequest loadRequest2 = BackendDTO.LoadReportRequest.newBuilder().setIp("2").setPort(2).setLoad(0.5f).setCurrTick(2).build();
    Future<Recorder.ProcessGroups> r1 = makeRequestReportLoad(loadRequest1);
    Future<Recorder.ProcessGroups> r2 = makeRequestReportLoad(loadRequest2);
    CompositeFuture.all(r1, r2).setHandler(ar -> {
      if(ar.failed()) {
        context.fail(ar.cause());
      }
      try {
        Future<Recorder.AssignedBackend> r3 = makeRequestPostAssociation(buildRecorderInfoFromProcessGroup(mockProcessGroups.get(0)));
        Future<Recorder.AssignedBackend> r4 = makeRequestPostAssociation(buildRecorderInfoFromProcessGroup(mockProcessGroups.get(1)));
        Future<Recorder.AssignedBackend> r5 = makeRequestPostAssociation(buildRecorderInfoFromProcessGroup(mockProcessGroups.get(2)));
        CompositeFuture.all(r3, r4, r5).setHandler(ar1 -> {
          if(ar1.failed()) {
            context.fail(ar1.cause());
          }
          try {
            Future<Recorder.BackendAssociations> r6 = makeRequestGetAssociations();
            r6.setHandler(ar2 -> {
              if(ar2.failed()) {
                context.fail(ar2.cause());
              }
              Recorder.BackendAssociations associations = ar2.result();
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
            });
          } catch (Exception ex1) {
            context.fail(ex1);
          }
        });
      } catch (Exception ex) {
        context.fail(ex);
      }
    });
  }

  private Future<Recorder.ProcessGroups> makeRequestReportLoad(BackendDTO.LoadReportRequest payload)
      throws IOException {
    Future<Recorder.ProcessGroups> future = Future.future();
    HttpClientRequest request = vertx.createHttpClient()
        .post(port, "localhost", "/leader/load")
        .handler(response -> {
          response.bodyHandler(buffer -> {
            try {
              if(response.statusCode() == 200) {
                Recorder.ProcessGroups result = Recorder.ProcessGroups.parseFrom(buffer.getBytes());
                future.complete(result);
              } else {
                future.fail(buffer.toString());
              }
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

  private Future<Recorder.AssignedBackend> makeRequestPostAssociation(Recorder.RecorderInfo payload)
      throws IOException {
    Future<Recorder.AssignedBackend> future = Future.future();
    HttpClientRequest request = vertx.createHttpClient()
        .post(port, "localhost", "/leader/association")
        .handler(response -> {
          response.bodyHandler(buffer -> {
            try {
              Recorder.AssignedBackend assignedBackend = Recorder.AssignedBackend.parseFrom(buffer.getBytes());
              future.complete(assignedBackend);
            } catch (Exception ex) {
              future.fail(ex);
            }
          });
        }).exceptionHandler(ex -> future.fail(ex));
    request.end(ProtoUtil.buildBufferFromProto(payload));
    return future;
  }

  private Future<Recorder.BackendAssociations> makeRequestGetAssociations()
      throws IOException {
    Future<Recorder.BackendAssociations> future = Future.future();
    HttpClientRequest request = vertx.createHttpClient()
        .get(port, "localhost", "/leader/associations")
        .handler(response -> {
          response.bodyHandler(buffer -> {
            try {
              if(response.statusCode() == 200) {
                Recorder.BackendAssociations result = Recorder.BackendAssociations.parseFrom(buffer.getBytes());
                future.complete(result);
              } else {
                future.fail(buffer.toString());
              }
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
}
