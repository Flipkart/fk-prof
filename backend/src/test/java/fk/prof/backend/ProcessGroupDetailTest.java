package fk.prof.backend;

import com.google.common.collect.Sets;
import fk.prof.backend.model.assignment.RecorderIdentifier;
import fk.prof.backend.model.assignment.WorkAssignmentSchedule;
import fk.prof.backend.model.assignment.impl.ProcessGroupDetail;
import fk.prof.idl.Entities;
import fk.prof.idl.Recorder;
import fk.prof.idl.WorkEntities;
import io.vertx.core.Vertx;
import io.vertx.core.VertxOptions;
import io.vertx.ext.unit.TestContext;
import io.vertx.ext.unit.junit.VertxUnitRunner;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

import static fk.prof.backend.PollAndLoadApiTest.enableCpuSamplingAndIOTracing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@RunWith(VertxUnitRunner.class)
public class ProcessGroupDetailTest {
  private Vertx vertx;
  private Configuration config;
  private Entities.ProcessGroup mockPG;
  private List<Recorder.RecorderInfo.Builder> mockRIBuilders;

  @Before
  public void setBefore() throws Exception {
    mockPG = Entities.ProcessGroup.newBuilder().setAppId("1").setCluster("1").setProcName("1").build();
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
  public void testWorkAssignmentReturnedInResponseToVaryingPollRequests(TestContext context) {
    ProcessGroupDetail processGroupDetail = new ProcessGroupDetail(mockPG, 1);
    WorkEntities.WorkAssignment wa = WorkEntities.WorkAssignment.getDefaultInstance();
//    when(wa.getWorkId()).thenReturn(1L);
    WorkAssignmentSchedule was = mock(WorkAssignmentSchedule.class);
    when(was.getNextWorkAssignment(RecorderIdentifier.from(mockRIBuilders.get(0).build())))
        .thenReturn(wa);
    when(was.getNextWorkAssignment(RecorderIdentifier.from(mockRIBuilders.get(1).build())))
        .thenReturn(null);

    Recorder.PollReq pollReq1 = Recorder.PollReq.newBuilder()
        .setRecorderInfo(mockRIBuilders.get(0).setRecorderTick(1).build())
        .setWorkLastIssued(WorkEntities.WorkResponse.newBuilder()
            .setElapsedTime(100)
            .setWorkId(0)
            .setWorkResult(WorkEntities.WorkResponse.WorkResult.success)
            .setWorkState(WorkEntities.WorkResponse.WorkState.complete).build())
        .build();
    WorkEntities.WorkAssignment response = processGroupDetail.getWorkAssignment(pollReq1);
    context.assertNull(response);

    //Update wa such that it returns non-null for mockRIBuilders.get(0), null for mockRIBuilders.get(1)
    processGroupDetail.updateWorkAssignmentSchedule(was);

    Recorder.PollReq pollReq2 = Recorder.PollReq.newBuilder()
        .setRecorderInfo(mockRIBuilders.get(0).setRecorderTick(2).build())
        .setWorkLastIssued(WorkEntities.WorkResponse.newBuilder()
            .setElapsedTime(100)
            .setWorkId(100)
            .setWorkResult(WorkEntities.WorkResponse.WorkResult.success)
            .setWorkState(WorkEntities.WorkResponse.WorkState.complete).build())
        .build();
    response = processGroupDetail.getWorkAssignment(pollReq2);
    context.assertEquals(wa, response);

    Recorder.PollReq pollReq3 = Recorder.PollReq.newBuilder()
        .setRecorderInfo(mockRIBuilders.get(1).setRecorderTick(1).build())
        .setWorkLastIssued(WorkEntities.WorkResponse.newBuilder()
            .setElapsedTime(100)
            .setWorkId(100)
            .setWorkResult(WorkEntities.WorkResponse.WorkResult.success)
            .setWorkState(WorkEntities.WorkResponse.WorkState.complete).build())
        .build();
    response = processGroupDetail.getWorkAssignment(pollReq3);
    context.assertNull(response);
  }

  @Test
  public void testTargetRecordersCalculationGivenCoverage(TestContext context) throws InterruptedException {
    testTargetRecordersCalculationGivenCoverageAndWorkTypes(context, Sets.newHashSet(WorkEntities.WorkType.cpu_sample_work), Sets.newHashSet(WorkEntities.WorkType.cpu_sample_work, WorkEntities.WorkType.io_trace_work));
    testTargetRecordersCalculationGivenCoverageAndWorkTypes(context, Sets.newHashSet(WorkEntities.WorkType.io_trace_work), Sets.newHashSet(WorkEntities.WorkType.cpu_sample_work));
    testTargetRecordersCalculationGivenCoverageAndWorkTypes(context, Sets.newHashSet(WorkEntities.WorkType.cpu_sample_work, WorkEntities.WorkType.io_trace_work), null);
  }

  @Test
  public void testEquality(TestContext context) {
    ProcessGroupDetail pgd1 = new ProcessGroupDetail(mockPG, 1);
    ProcessGroupDetail pgd2 = new ProcessGroupDetail(mockPG, 10);
    context.assertEquals(pgd1, pgd2);
  }

  private void testTargetRecordersCalculationGivenCoverageAndWorkTypes(TestContext context, Set<WorkEntities.WorkType> wtypes, Set<WorkEntities.WorkType> complement) throws InterruptedException {
    ProcessGroupDetail processGroupDetail = new ProcessGroupDetail(mockPG, 1);
    List<Recorder.RecorderInfo.Builder> builders = Arrays.asList(
        mockRIBuilders.get(0).setCapabilities(buildRecCapabilities(wtypes)),
        mockRIBuilders.get(1).setCapabilities(buildRecCapabilities(wtypes)),
        mockRIBuilders.get(2).setCapabilities(buildRecCapabilities(wtypes))
    );

    Recorder.PollReq pollReq1 = Recorder.PollReq.newBuilder()
        .setRecorderInfo(builders.get(0).setRecorderTick(1).build())
        .setWorkLastIssued(WorkEntities.WorkResponse.newBuilder()
            .setElapsedTime(100)
            .setWorkId(0)
            .setWorkResult(WorkEntities.WorkResponse.WorkResult.success)
            .setWorkState(WorkEntities.WorkResponse.WorkState.complete).build())
        .build();
    processGroupDetail.getWorkAssignment(pollReq1);

    //Ensure first recorder goes defunct
    Thread.sleep(1000);

    Recorder.PollReq pollReq2 = Recorder.PollReq.newBuilder()
        .setRecorderInfo(builders.get(1).setRecorderTick(1).build())
        .setWorkLastIssued(WorkEntities.WorkResponse.newBuilder()
            .setElapsedTime(100)
            .setWorkId(100)
            .setWorkResult(WorkEntities.WorkResponse.WorkResult.unknown)
            .setWorkState(WorkEntities.WorkResponse.WorkState.running).build())
        .build();
    processGroupDetail.getWorkAssignment(pollReq2);

    Recorder.PollReq pollReq3 = Recorder.PollReq.newBuilder()
        .setRecorderInfo(builders.get(2).setRecorderTick(1).build())
        .setWorkLastIssued(WorkEntities.WorkResponse.newBuilder()
            .setElapsedTime(100)
            .setWorkId(0)
            .setWorkResult(WorkEntities.WorkResponse.WorkResult.success)
            .setWorkState(WorkEntities.WorkResponse.WorkState.complete).build())
        .build();
    processGroupDetail.getWorkAssignment(pollReq3);

    context.assertEquals(2, processGroupDetail.getHealthyRecordersCount(wtypes));
    context.assertEquals(2, processGroupDetail.applyCoverage(processGroupDetail.getHealthyRecordersCount(wtypes), 100));
    context.assertEquals(1, processGroupDetail.applyCoverage(processGroupDetail.getHealthyRecordersCount(wtypes), 99));
    context.assertEquals(0, processGroupDetail.applyCoverage(processGroupDetail.getHealthyRecordersCount(wtypes), 34));
    context.assertEquals(0, processGroupDetail.applyCoverage(processGroupDetail.getHealthyRecordersCount(wtypes), 0));

    //first recorder comes back up
    Recorder.PollReq pollReq4 = Recorder.PollReq.newBuilder()
        .setRecorderInfo(builders.get(0).setRecorderTick(1).build())
        .setWorkLastIssued(WorkEntities.WorkResponse.newBuilder()
            .setElapsedTime(100)
            .setWorkId(0)
            .setWorkResult(WorkEntities.WorkResponse.WorkResult.success)
            .setWorkState(WorkEntities.WorkResponse.WorkState.complete).build())
        .build();
    processGroupDetail.getWorkAssignment(pollReq4);

    context.assertEquals(3, processGroupDetail.getHealthyRecordersCount(wtypes));
    if(complement != null ) {
      context.assertEquals(0, processGroupDetail.getHealthyRecordersCount(complement));
    }
    context.assertEquals(3, processGroupDetail.applyCoverage(processGroupDetail.getHealthyRecordersCount(wtypes), 100));
    context.assertEquals(2, processGroupDetail.applyCoverage(processGroupDetail.getHealthyRecordersCount(wtypes), 99));
    context.assertEquals(1, processGroupDetail.applyCoverage(processGroupDetail.getHealthyRecordersCount(wtypes), 34));
    context.assertEquals(0, processGroupDetail.applyCoverage(processGroupDetail.getHealthyRecordersCount(wtypes), 0));
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
        .setCapabilities(enableCpuSamplingAndIOTracing())
        .setIp(recorderId);
  }

  private Recorder.RecorderCapabilities.Builder buildRecCapabilities(Set<WorkEntities.WorkType> wtypes) {
    Recorder.RecorderCapabilities.Builder builder = Recorder.RecorderCapabilities.newBuilder();
    builder.setCanCpuSample(false);
    builder.setCanTraceIo(false);
    builder.setCanInstrumentJava(false);
    builder.setCanTraceAlloc(false);
    builder.setCanTraceElapsedTime(false);
    builder.setCanTraceMonitor(false);
    builder.setCanTraceOffcpuTime(false);

    for(WorkEntities.WorkType wtype: wtypes) {
      switch (wtype) {
        case cpu_sample_work:
          builder.setCanCpuSample(true);
          break;
        case io_trace_work:
          builder.setCanTraceIo(true);
          break;
      }
    }
    return builder;
  }

}
