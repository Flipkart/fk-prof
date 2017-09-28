package fk.prof.backend;

import fk.prof.backend.model.assignment.RecorderIdentifier;
import fk.prof.backend.model.assignment.WorkAssignmentSchedule;
import fk.prof.backend.model.assignment.impl.ProcessGroupDetail;
import io.vertx.core.Vertx;
import io.vertx.core.VertxOptions;
import io.vertx.ext.unit.TestContext;
import io.vertx.ext.unit.junit.VertxUnitRunner;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import recording.Recorder;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;

import static fk.prof.backend.PollAndLoadApiTest.enableCpuSampling;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@RunWith(VertxUnitRunner.class)
public class ProcessGroupDetailTest {
  private Vertx vertx;
  private Configuration config;
  private Recorder.ProcessGroup mockPG;
  private List<Recorder.RecorderInfo.Builder> mockRIBuilders;

  @Before
  public void setBefore() throws Exception {
    mockPG = Recorder.ProcessGroup.newBuilder().setAppId("1").setCluster("1").setProcName("1").build();
    mockRIBuilders = Arrays.asList(
        buildRecorderInfo("1"),
        buildRecorderInfo("2"),
        buildRecorderInfo("3")
    );

    ConfigManager.setDefaultSystemProperties();
    config = ConfigManager.loadConfig(ProcessGroupDetailTest.class.getClassLoader().getResource("config.json").getFile());
    vertx = Vertx.vertx(new VertxOptions(config.getVertxOptions()));
  }

  @Test
  public void testWorkAssignmentReturnedInReponseToVaryingPollRequests(TestContext context) {
    ProcessGroupDetail processGroupDetail = new ProcessGroupDetail(mockPG, 1);
    Recorder.WorkAssignment wa = Recorder.WorkAssignment.getDefaultInstance();
//    when(wa.getWorkId()).thenReturn(1L);
    WorkAssignmentSchedule was = mock(WorkAssignmentSchedule.class);
    when(was.getNextWorkAssignment(RecorderIdentifier.from(mockRIBuilders.get(0).build())))
        .thenReturn(wa);
    when(was.getNextWorkAssignment(RecorderIdentifier.from(mockRIBuilders.get(1).build())))
        .thenReturn(null);

    Recorder.PollReq pollReq1 = Recorder.PollReq.newBuilder()
        .setRecorderInfo(mockRIBuilders.get(0).setRecorderTick(1).build())
        .setWorkLastIssued(Recorder.WorkResponse.newBuilder()
            .setElapsedTime(100)
            .setWorkId(0)
            .setWorkResult(Recorder.WorkResponse.WorkResult.success)
            .setWorkState(Recorder.WorkResponse.WorkState.complete).build())
        .build();
    Recorder.WorkAssignment response = processGroupDetail.getWorkAssignment(pollReq1);
    context.assertNull(response);

    //Update wa such that it returns non-null for mockRIBuilders.get(0), null for mockRIBuilders.get(1)
    processGroupDetail.updateWorkAssignmentSchedule(was);

    Recorder.PollReq pollReq2 = Recorder.PollReq.newBuilder()
        .setRecorderInfo(mockRIBuilders.get(0).setRecorderTick(2).build())
        .setWorkLastIssued(Recorder.WorkResponse.newBuilder()
            .setElapsedTime(100)
            .setWorkId(100)
            .setWorkResult(Recorder.WorkResponse.WorkResult.success)
            .setWorkState(Recorder.WorkResponse.WorkState.complete).build())
        .build();
    response = processGroupDetail.getWorkAssignment(pollReq2);
    context.assertEquals(wa, response);

    Recorder.PollReq pollReq3 = Recorder.PollReq.newBuilder()
        .setRecorderInfo(mockRIBuilders.get(1).setRecorderTick(1).build())
        .setWorkLastIssued(Recorder.WorkResponse.newBuilder()
            .setElapsedTime(100)
            .setWorkId(100)
            .setWorkResult(Recorder.WorkResponse.WorkResult.success)
            .setWorkState(Recorder.WorkResponse.WorkState.complete).build())
        .build();
    response = processGroupDetail.getWorkAssignment(pollReq3);
    context.assertNull(response);
  }

  @Test
  public void testTargetRecordersCalculationGivenCoverage(TestContext context) throws InterruptedException {
    ProcessGroupDetail processGroupDetail = new ProcessGroupDetail(mockPG, 1);
    Recorder.PollReq pollReq1 = Recorder.PollReq.newBuilder()
        .setRecorderInfo(mockRIBuilders.get(0).setRecorderTick(1).build())
        .setWorkLastIssued(Recorder.WorkResponse.newBuilder()
            .setElapsedTime(100)
            .setWorkId(0)
            .setWorkResult(Recorder.WorkResponse.WorkResult.success)
            .setWorkState(Recorder.WorkResponse.WorkState.complete).build())
        .build();
    processGroupDetail.getWorkAssignment(pollReq1);

    //Ensure first recorder goes defunct
    Thread.sleep(1000);

    Recorder.PollReq pollReq2 = Recorder.PollReq.newBuilder()
        .setRecorderInfo(mockRIBuilders.get(1).setRecorderTick(1).build())
        .setWorkLastIssued(Recorder.WorkResponse.newBuilder()
            .setElapsedTime(100)
            .setWorkId(100)
            .setWorkResult(Recorder.WorkResponse.WorkResult.unknown)
            .setWorkState(Recorder.WorkResponse.WorkState.running).build())
        .build();
    processGroupDetail.getWorkAssignment(pollReq2);

    Recorder.PollReq pollReq3 = Recorder.PollReq.newBuilder()
        .setRecorderInfo(mockRIBuilders.get(2).setRecorderTick(1).build())
        .setWorkLastIssued(Recorder.WorkResponse.newBuilder()
            .setElapsedTime(100)
            .setWorkId(0)
            .setWorkResult(Recorder.WorkResponse.WorkResult.success)
            .setWorkState(Recorder.WorkResponse.WorkState.complete).build())
        .build();
    processGroupDetail.getWorkAssignment(pollReq3);

    context.assertEquals(2, processGroupDetail.getRecorderTargetCountToMeetCoverage(100));
    context.assertEquals(1, processGroupDetail.getRecorderTargetCountToMeetCoverage(99));
    context.assertEquals(0, processGroupDetail.getRecorderTargetCountToMeetCoverage(34));
    context.assertEquals(0, processGroupDetail.getRecorderTargetCountToMeetCoverage(0));

    //first recorder comes back up
    Recorder.PollReq pollReq4 = Recorder.PollReq.newBuilder()
        .setRecorderInfo(mockRIBuilders.get(0).setRecorderTick(1).build())
        .setWorkLastIssued(Recorder.WorkResponse.newBuilder()
            .setElapsedTime(100)
            .setWorkId(0)
            .setWorkResult(Recorder.WorkResponse.WorkResult.success)
            .setWorkState(Recorder.WorkResponse.WorkState.complete).build())
        .build();
    processGroupDetail.getWorkAssignment(pollReq4);
    context.assertEquals(3, processGroupDetail.getRecorderTargetCountToMeetCoverage(100));
    context.assertEquals(2, processGroupDetail.getRecorderTargetCountToMeetCoverage(99));
    context.assertEquals(1, processGroupDetail.getRecorderTargetCountToMeetCoverage(34));
    context.assertEquals(0, processGroupDetail.getRecorderTargetCountToMeetCoverage(0));
  }

  @Test
  public void testEquality(TestContext context) {
    ProcessGroupDetail pgd1 = new ProcessGroupDetail(mockPG, 1);
    ProcessGroupDetail pgd2 = new ProcessGroupDetail(mockPG, 10);
    context.assertEquals(pgd1, pgd2);
  }

  private Recorder.RecorderInfo.Builder buildRecorderInfo(String recorderId) {
    return Recorder.RecorderInfo.newBuilder()
        .setAppId("1")
        .setCluster("1")
        .setHostname("1")
        .setInstanceGrp("1")
        .setInstanceId(recorderId)
        .setInstanceType("1")
        .setLocalTime(LocalDateTime.now(Clock.systemUTC()).toString())
        .setProcName("1")
        .setRecorderTick(0)
        .setRecorderUptime(100)
        .setRecorderVersion(1)
        .setVmId("1")
        .setZone("1")
        .setCapabilities(enableCpuSampling())
        .setIp(recorderId);
  }

}
