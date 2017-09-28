package fk.prof.backend;

import com.google.common.io.Files;
import fk.prof.aggregation.model.AggregationWindowStorage;
import fk.prof.backend.deployer.VerticleDeployer;
import fk.prof.backend.deployer.impl.*;
import fk.prof.backend.http.ProfHttpClient;
import fk.prof.backend.leader.election.LeaderElectedTask;
import fk.prof.backend.model.aggregation.ActiveAggregationWindows;
import fk.prof.backend.model.aggregation.impl.ActiveAggregationWindowsImpl;
import fk.prof.backend.model.assignment.AssociatedProcessGroups;
import fk.prof.backend.model.assignment.impl.AssociatedProcessGroupsImpl;
import fk.prof.backend.model.slot.WorkSlotPool;
import fk.prof.backend.model.association.BackendAssociationStore;
import fk.prof.backend.model.association.ProcessGroupCountBasedBackendComparator;
import fk.prof.backend.model.association.impl.ZookeeperBasedBackendAssociationStore;
import fk.prof.backend.model.election.impl.InMemoryLeaderStore;
import fk.prof.backend.model.policy.PolicyStore;
import fk.prof.backend.proto.BackendDTO;
import fk.prof.backend.util.BitOperationUtil;
import fk.prof.backend.util.ProtoUtil;
import io.vertx.core.CompositeFuture;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.VertxOptions;
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
import org.mockito.invocation.InvocationOnMock;
import org.mockito.stubbing.Answer;
import recording.Recorder;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

@RunWith(VertxUnitRunner.class)
public class PollAndLoadApiTest {
  private Vertx vertx;
  private Configuration config;
  private CuratorFramework curatorClient;
  private TestingServer testingServer;

  private InMemoryLeaderStore leaderStore;
  private AssociatedProcessGroups associatedProcessGroups;
  private ActiveAggregationWindows activeAggregationWindows;
  private WorkSlotPool workSlotPool;
  private BackendAssociationStore backendAssociationStore;
  private PolicyStore policyStore;
  private AggregationWindowStorage aggregationWindowStorage;

  private final int thresholdForDefunctRecorderInSecs = 4;
  private String backendDaemonVerticleDeployment;
  private List<String> backendHttpVerticleDeployments;

  @Before
  public void setBefore(TestContext context) throws Exception {
    final Async async = context.async();
    ConfigManager.setDefaultSystemProperties();

    JsonObject config = new JsonObject(Files.toString(
        new File(PollAndLoadApiTest.class.getClassLoader().getResource("config.json").getFile()), StandardCharsets.UTF_8));
    config.put("load.report.interval.secs", 1);
    config.put("recorder.defunct.threshold.secs", thresholdForDefunctRecorderInSecs);
    config.getJsonObject("curatorOptions").put("connection.timeout.ms", 1000);
    config.getJsonObject("curatorOptions").put("session.timeout.ms", 1000);

    this.config = ConfigManager.loadConfig(config);
    String backendAssociationPath = this.config.getAssociationsConfig().getAssociationPath();

    testingServer = new TestingServer();
    curatorClient = CuratorFrameworkFactory.newClient(testingServer.getConnectString(), 500, 500, new RetryOneTime(1));
    curatorClient.start();
    curatorClient.blockUntilConnected(10, TimeUnit.SECONDS);
    curatorClient.create().forPath(backendAssociationPath);

    vertx = Vertx.vertx(new VertxOptions(this.config.getVertxOptions()));
    backendAssociationStore = new ZookeeperBasedBackendAssociationStore(vertx, curatorClient, backendAssociationPath, 1, 1, new ProcessGroupCountBasedBackendComparator());
    leaderStore = spy(new InMemoryLeaderStore(this.config.getIpAddress(), this.config.getLeaderHttpServerOpts().getPort()));
    when(leaderStore.isLeader()).thenReturn(false);
    associatedProcessGroups = new AssociatedProcessGroupsImpl(this.config.getRecorderDefunctThresholdSecs());
    workSlotPool = new WorkSlotPool(this.config.getScheduleSlotPoolCapacity());
    activeAggregationWindows = new ActiveAggregationWindowsImpl();
    policyStore = spy(new PolicyStore(curatorClient));
    aggregationWindowStorage = mock(AggregationWindowStorage.class);

    VerticleDeployer backendHttpVerticleDeployer = new BackendHttpVerticleDeployer(vertx, this.config, leaderStore,
        activeAggregationWindows, associatedProcessGroups);
    VerticleDeployer backendDaemonVerticleDeployer = new BackendDaemonVerticleDeployer(vertx, this.config, leaderStore,
        associatedProcessGroups, activeAggregationWindows, workSlotPool, aggregationWindowStorage);
    CompositeFuture.all(backendHttpVerticleDeployer.deploy(), backendDaemonVerticleDeployer.deploy()).setHandler(ar -> {
      if(ar.failed()) {
        context.fail(ar.result().cause());
      }
      try {
        backendHttpVerticleDeployments = ((CompositeFuture)ar.result().list().get(0)).list();
        backendDaemonVerticleDeployment = (String)((CompositeFuture)ar.result().list().get(0)).list().get(0);
        VerticleDeployer leaderHttpVerticleDeployer = new LeaderHttpVerticleDeployer(vertx, this.config, backendAssociationStore, policyStore);

        CountDownLatch latch = new CountDownLatch(1);
        Runnable leaderElectedTask = LeaderElectedTask.newBuilder().build(vertx, leaderHttpVerticleDeployer, backendAssociationStore, policyStore);
        Runnable leaderElectedTaskWithLatch = () -> {
          leaderElectedTask.run();
          latch.countDown();
        };
        VerticleDeployer leaderElectionParticipatorVerticleDeployer = new LeaderElectionParticipatorVerticleDeployer(
            vertx, this.config, curatorClient, leaderElectedTaskWithLatch);
        VerticleDeployer leaderElectionWatcherVerticleDeployer = new LeaderElectionWatcherVerticleDeployer(vertx, this.config, curatorClient, leaderStore);
        CompositeFuture.all(leaderElectionParticipatorVerticleDeployer.deploy(), leaderElectionWatcherVerticleDeployer.deploy()).setHandler(ar1 -> {
          if(ar1.failed()) {
            context.fail(ar1.cause());
          }
          try {
            boolean released = latch.await(5, TimeUnit.SECONDS);
            if (!released) {
              context.fail("Latch timed out but leader election task was not run");
            }
            async.complete();
          } catch (Exception ex) {
            context.fail(ex);
          }
        });
      } catch (Exception ex) {
        context.fail(ex);
      }
    });
  }

  @After
  public void teardown(TestContext context) {
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

  @Test(timeout = 10000)
  public void testReportOfLoadFromDaemon(TestContext context) throws Exception {
    final Async async = context.async();
    Recorder.ProcessGroup processGroup = Recorder.ProcessGroup.newBuilder().setAppId("1").setCluster("1").setProcName("1").build();
    //this test can be brittle because teardown of zk in previous running test, causes delay in setting up of leader when zk is setup again for this test
    //mocking leader address here so that association does not return 503
    when(leaderStore.getLeader())
        .thenReturn(BackendDTO.LeaderDetail.newBuilder().setHost("127.0.0.1").setPort(config.getLeaderHttpServerOpts().getPort()).build());
    try {
      makeRequestPostAssociation(buildRecorderInfoFromProcessGroup(processGroup)).setHandler(ar -> {
        if (ar.failed()) {
          context.fail(ar.cause());
        }
        context.assertEquals(400, ar.result().getStatusCode());
        //Wait for sometime for load to get reported at least twice
        vertx.setTimer(2500, timerId1 -> {
          try {
            makeRequestPostAssociation(buildRecorderInfoFromProcessGroup(processGroup)).setHandler(ar2 -> {
              if (ar2.failed()) {
                context.fail(ar2.cause());
              }
              try {
                //When backend reports load for the second time, it should make backend active for leader, and association should return 200
                context.assertEquals(200, ar2.result().getStatusCode());
                context.assertEquals("127.0.0.1", ProtoUtil.buildProtoFromBuffer(Recorder.AssignedBackend.parser(),
                    ar2.result().getResponse()).getHost());
                async.complete();
              } catch (Exception ex) {
                context.fail(ex);
              }
            });
          } catch (Exception ex) {
            context.fail(ex);
          }
        });
      });
    } catch (Exception ex) { context.fail(ex); }
  }

  @Test(timeout = 10000)
  public void testFetchForWorkForAggregationWindow(TestContext context) throws Exception {
    final Async async = context.async();
    Recorder.ProcessGroup processGroup = Recorder.ProcessGroup.newBuilder().setAppId("1").setCluster("1").setProcName("1").build();
    policyStore.put(processGroup, buildRecordingPolicy(1));
    CountDownLatch latch = new CountDownLatch(1);
    when(policyStore.get(processGroup)).then(new Answer<BackendDTO.RecordingPolicy>() {
      @Override
      public BackendDTO.RecordingPolicy answer(InvocationOnMock invocationOnMock) throws Throwable {
        latch.countDown();
        return (BackendDTO.RecordingPolicy)invocationOnMock.callRealMethod();
      }
    });

    //Wait for sometime for load to get reported twice, so that backend gets marked as available
    vertx.setTimer(2500, timerId -> {
      try {
        makeRequestPostAssociation(buildRecorderInfoFromProcessGroup(processGroup)).setHandler(ar -> {
          if (ar.failed()) {
            context.fail(ar.cause());
          }
          context.assertEquals(200, ar.result().getStatusCode());
          // wait for load report to happen so that backend receives association.
          // additionally wait for threshold for defunct recorder, so that work is fetched and first window is setup
          vertx.setTimer(1200 + (thresholdForDefunctRecorderInSecs * 1000), timerId1 -> {
            try {
              boolean released = latch.await(1, TimeUnit.SECONDS);
              if(!released) {
                context.fail("Latch timed out but work profile was not fetched from leader");
              }
              async.complete();
            } catch (Exception ex) {
              context.fail(ex);
            }
          });

        });
      } catch (Exception ex) {
        context.fail(ex);
      }
    });
  }

  @Test(timeout = 20000)
  public void testAggregationWindowSetupAndPollResponse(TestContext context) throws Exception {
    final Async async = context.async();
    Recorder.ProcessGroup processGroup = Recorder.ProcessGroup.newBuilder().setAppId("1").setCluster("1").setProcName("1").build();
    policyStore.put(processGroup, buildRecordingPolicy(1));

    Recorder.PollReq pollReq = Recorder.PollReq.newBuilder()
        .setRecorderInfo(buildRecorderInfo(processGroup, 1))
        .setWorkLastIssued(buildWorkResponse(0, Recorder.WorkResponse.WorkState.complete))
        .build();
    Recorder.AssignedBackend assignedBackend = Recorder.AssignedBackend.newBuilder().setHost(config.getIpAddress()).setPort(config.getBackendHttpServerOpts().getPort()).build();
    makePollRequest(assignedBackend, pollReq).setHandler(ar1 -> {
      if(ar1.failed()) {
        context.fail(ar1.cause());
      }
      try {
        //400 returned because backend is not associated with the process group of recorder sending poll request
        context.assertEquals(400, ar1.result().getStatusCode());
        //Wait for sometime for load to get reported twice, so that backend gets marked as available
        vertx.setTimer(2500, timerId -> {
          try {
            makeRequestPostAssociation(buildRecorderInfoFromProcessGroup(processGroup)).setHandler(ar2 -> {
              if (ar2.failed()) {
                context.fail(ar2.cause());
              }
              context.assertEquals(200, ar2.result().getStatusCode());
              try {
                //wait for some time so that backend reports load and fetches new associations
                vertx.setTimer(2000, timerId1 -> {
                  try {
                    Recorder.PollReq pollReq1 = Recorder.PollReq.newBuilder()
                        .setRecorderInfo(buildRecorderInfo(processGroup, 2))
                        .setWorkLastIssued(buildWorkResponse(0, Recorder.WorkResponse.WorkState.complete))
                        .build();
                    makePollRequest(assignedBackend, pollReq1).setHandler(ar3 -> {
                      if(ar3.failed()) {
                        context.fail(ar3.cause());
                      }
                      try {
                        context.assertEquals(200, ar3.result().getStatusCode());
                        Recorder.PollRes pollRes1 = ProtoUtil.buildProtoFromBuffer(Recorder.PollRes.parser(), ar3.result().getResponse());
                        context.assertEquals(Recorder.WorkAssignment.getDefaultInstance(), pollRes1.getAssignment());
                        Recorder.PollReq pollReq2 = Recorder.PollReq.newBuilder()
                            .setRecorderInfo(buildRecorderInfo(processGroup, 3))
                            .setWorkLastIssued(buildWorkResponse(0, Recorder.WorkResponse.WorkState.complete))
                            .build();
                        //wait for recorder defunct threshold time post which backend would have fetched work from leader and setup aggregation window
                        vertx.setTimer(thresholdForDefunctRecorderInSecs * 1000, timerId2 -> {
                          try {
                            makePollRequest(assignedBackend, pollReq2).setHandler(ar4 -> {
                              if(ar4.failed()) {
                                context.fail(ar4.cause());
                              }
                              try {
                                context.assertEquals(200, ar4.result().getStatusCode());
                                Recorder.PollRes pollRes2 = ProtoUtil.buildProtoFromBuffer(Recorder.PollRes.parser(), ar4.result().getResponse());
                                context.assertNotNull(pollRes2.getAssignment());
                                context.assertEquals(BitOperationUtil.constructLongFromInts(config.getBackendId(), 1),
                                    pollRes2.getAssignment().getWorkId());
                                async.complete();
                              } catch (Exception ex) {
                                context.fail(ex);
                              }
                            });
                          } catch (Exception ex) {
                            context.fail(ex);
                          }
                        });
                      } catch(Exception ex) {
                        context.fail(ex);
                      }
                    });
                  } catch(Exception ex) {
                    context.fail(ex);
                  }
                });
              } catch(Exception ex) {
                context.fail(ex);
              }
            });
          } catch (Exception ex) {
            context.fail(ex);
          }
        });
      } catch(Exception ex) {
        context.fail(ex);
      }
    });

  }

  private Future<ProfHttpClient.ResponseWithStatusTuple> makeRequestPostAssociation(Recorder.RecorderInfo payload)
      throws IOException {
    Future<ProfHttpClient.ResponseWithStatusTuple> future = Future.future();
    HttpClientRequest request = vertx.createHttpClient()
        .post(config.getBackendHttpServerOpts().getPort(), "localhost", "/association")
        .handler(response -> {
          response.bodyHandler(buffer -> {
            try {
              future.complete(ProfHttpClient.ResponseWithStatusTuple.of(response.statusCode(), buffer));
            } catch (Exception ex) {
              future.fail(ex);
            }
          });
        }).exceptionHandler(ex -> future.fail(ex));
    request.end(ProtoUtil.buildBufferFromProto(payload));
    return future;
  }

  private Future<ProfHttpClient.ResponseWithStatusTuple> makePollRequest(Recorder.AssignedBackend assignedBackend, Recorder.PollReq payload)
      throws IOException {
    Future<ProfHttpClient.ResponseWithStatusTuple> future = Future.future();
    HttpClientRequest request = vertx.createHttpClient()
        .post(assignedBackend.getPort(), assignedBackend.getHost(), "/poll")
        .handler(response -> {
          response.bodyHandler(buffer -> {
            try {
              future.complete(ProfHttpClient.ResponseWithStatusTuple.of(response.statusCode(), buffer));
            } catch (Exception ex) {
              future.fail(ex);
            }
          });
        }).exceptionHandler(ex -> future.fail(ex));
    request.end(ProtoUtil.buildBufferFromProto(payload));
    return future;
  }

  private BackendDTO.RecordingPolicy buildRecordingPolicy(int profileDuration) {
    return BackendDTO.RecordingPolicy.newBuilder()
        .setDuration(profileDuration)
        .setCoveragePct(100)
        .setDescription("Test work profile")
        .addWork(BackendDTO.Work.newBuilder()
            .setWType(BackendDTO.WorkType.cpu_sample_work)
            .setCpuSample(BackendDTO.CpuSampleWork.newBuilder().setFrequency(10).setMaxFrames(10))
            .build())
        .build();
  }

  private Recorder.RecorderInfo buildRecorderInfo(Recorder.ProcessGroup processGroup, long tick) {
    return Recorder.RecorderInfo.newBuilder()
        .setAppId(processGroup.getAppId())
        .setCluster(processGroup.getCluster())
        .setProcName(processGroup.getProcName())
        .setRecorderTick(tick)
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
        .setCapabilities(enableCpuSampling())
        .build();
  }

  public static Recorder.RecorderCapabilities enableCpuSampling() {
    return Recorder.RecorderCapabilities.newBuilder().setCanCpuSample(true).build();
  }

  private Recorder.WorkResponse buildWorkResponse(long workId, Recorder.WorkResponse.WorkState workState) {
    return Recorder.WorkResponse.newBuilder()
        .setWorkId(workId)
        .setWorkResult(Recorder.WorkResponse.WorkResult.success)
        .setWorkState(workState)
        .setElapsedTime(100)
        .build();
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
        .setCapabilities(enableCpuSampling())
        .build();
  }
}
