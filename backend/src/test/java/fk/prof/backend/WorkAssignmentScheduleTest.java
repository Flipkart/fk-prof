package fk.prof.backend;

import fk.prof.backend.model.assignment.RecorderIdentifier;
import fk.prof.backend.model.assignment.WorkAssignmentSchedule;
import fk.prof.backend.model.assignment.WorkAssignmentScheduleBootstrapConfig;
import fk.prof.idl.WorkEntities;
import fk.prof.metrics.ProcessGroupTag;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

public class WorkAssignmentScheduleTest {
  private List<WorkEntities.WorkAssignment.Builder> mockWABuilders = new ArrayList<>();

  @Before
  public void setBefore() {
    ConfigManager.setDefaultSystemProperties();
    for(int i = 0; i < 10; i++) {
      mockWABuilders.add(getWorkAssignmentBuilder(i+1));
    }
  }

  @Test
  public void testFetchOfWorkAssignmentWithMaxDelay() throws InterruptedException {
    WorkAssignmentScheduleBootstrapConfig bootstrapConfig = new WorkAssignmentScheduleBootstrapConfig(60, 10, 5, 10);
    WorkAssignmentSchedule was = new WorkAssignmentSchedule(bootstrapConfig, mockWABuilders.toArray(new WorkEntities.WorkAssignment.Builder[mockWABuilders.size()]), 5, ProcessGroupTag.EMPTY);
    Assert.assertEquals(3, was.getMaxOverlap());

    WorkEntities.WorkAssignment r1 = was.getNextWorkAssignment(buildRI("1"));
    Assert.assertNotNull(r1);
    Assert.assertTrue(r1.getDelay() < 10);

    //already fetched for 1st recorder, so should return same work id
    WorkEntities.WorkAssignment r2 = was.getNextWorkAssignment(buildRI("1"));
    Assert.assertNotNull(r2);
    Assert.assertEquals(r1.getWorkId(), r2.getWorkId());

    //exhaust all entries scheduled in slot 1
    Assert.assertNotNull(was.getNextWorkAssignment(buildRI("2")));
    Assert.assertNotNull(was.getNextWorkAssignment(buildRI("3")));

    //too early for slot 2
    Assert.assertNull(was.getNextWorkAssignment(buildRI("4")));
  }

  @Test
  public void testBuildingOfScheduleWithTooLessConcurrentEntriesAllowed() throws InterruptedException {
    WorkAssignmentScheduleBootstrapConfig bootstrapConfig = new WorkAssignmentScheduleBootstrapConfig(60, 10, 5, 10);
    WorkAssignmentSchedule was = new WorkAssignmentSchedule(bootstrapConfig, mockWABuilders.toArray(new WorkEntities.WorkAssignment.Builder[mockWABuilders.size()]), 5, ProcessGroupTag.EMPTY);
    Assert.assertTrue(was.getMaxOverlap() > 1);
  }

  @Test
  public void testFetchOfWorkAssignmentWithMinDelay() throws InterruptedException {
    WorkAssignmentScheduleBootstrapConfig bootstrapConfig = new WorkAssignmentScheduleBootstrapConfig(60, 10, 2, 10);
    WorkAssignmentSchedule was = new WorkAssignmentSchedule(bootstrapConfig, mockWABuilders.toArray(new WorkEntities.WorkAssignment.Builder[mockWABuilders.size()]), 5, ProcessGroupTag.EMPTY);

    WorkEntities.WorkAssignment r1 = was.getNextWorkAssignment(buildRI("1"));
    Assert.assertNotNull(r1);
    Assert.assertTrue(r1.getDelay() < 4);

    Thread.sleep(3000); //this will cause a scheduling miss, so the next assignment returned will have higher delay
    r1 = was.getNextWorkAssignment(buildRI("2"));
    Assert.assertNotNull(r1);
    Assert.assertTrue(r1.getDelay() > 4);
  }

  private WorkEntities.WorkAssignment.Builder getWorkAssignmentBuilder(long workId) {
    return WorkEntities.WorkAssignment.newBuilder()
        .setWorkId(workId)
        .setDescription("")
        .setDuration(5)
        .addWork(WorkEntities.Work.newBuilder()
            .setWType(WorkEntities.WorkType.cpu_sample_work)
            .setCpuSample(WorkEntities.CpuSampleWork.newBuilder().setFrequency(10).setMaxFrames(10))
            .build());
  }

  private RecorderIdentifier buildRI(String ip) {
    return new RecorderIdentifier(ip, "1", "1", "1", "1", "1", "1", "1", "1", "1");
  }
}
