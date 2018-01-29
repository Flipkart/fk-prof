package fk.prof.backend;

import fk.prof.backend.model.association.BackendDetail;
import fk.prof.backend.model.association.ProcessGroupCountBasedBackendComparator;
import fk.prof.idl.Entities;
import fk.prof.idl.Recorder;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.PriorityQueue;

public class ProcessGroupCountBasedBackendPriorityTest {

  PriorityQueue<BackendDetail> backendDetailPriorityQueue;
  List<Entities.ProcessGroup> mockProcessGroups;
  List<Recorder.AssignedBackend> mockBackends;

  @Before
  public void setBefore() {
    backendDetailPriorityQueue = new PriorityQueue<>(new ProcessGroupCountBasedBackendComparator());
    mockProcessGroups = Arrays.asList(
        Entities.ProcessGroup.newBuilder().setAppId("a").setCluster("c").setProcName("p1").build(),
        Entities.ProcessGroup.newBuilder().setAppId("a").setCluster("c").setProcName("p2").build(),
        Entities.ProcessGroup.newBuilder().setAppId("a").setCluster("c").setProcName("p3").build(),
        Entities.ProcessGroup.newBuilder().setAppId("a").setCluster("c").setProcName("p4").build(),
        Entities.ProcessGroup.newBuilder().setAppId("a").setCluster("c").setProcName("p5").build(),
        Entities.ProcessGroup.newBuilder().setAppId("a").setCluster("c").setProcName("p6").build()
    );
    mockBackends = Arrays.asList(
        Recorder.AssignedBackend.newBuilder().setHost("1").setPort(0).build(),
        Recorder.AssignedBackend.newBuilder().setHost("2").setPort(0).build(),
        Recorder.AssignedBackend.newBuilder().setHost("3").setPort(0).build()
    );
  }

  @Test
  public void shouldPrioritizeLowerProcessGroupBackends() throws Exception {
    int loadReportIntervalInSeconds = 1;
    int loadMissTolerance = 10;
    BackendDetail b1 = new BackendDetail(mockBackends.get(0), loadReportIntervalInSeconds, loadMissTolerance);
    b1.associateProcessGroup(mockProcessGroups.get(0));
    b1.associateProcessGroup(mockProcessGroups.get(1));

    BackendDetail b2 = new BackendDetail(mockBackends.get(1), loadReportIntervalInSeconds, loadMissTolerance);
    b2.associateProcessGroup(mockProcessGroups.get(2));
    b2.associateProcessGroup(mockProcessGroups.get(3));
    b2.associateProcessGroup(mockProcessGroups.get(4));

    BackendDetail b3 = new BackendDetail(mockBackends.get(2), loadReportIntervalInSeconds, loadMissTolerance);
    b3.associateProcessGroup(mockProcessGroups.get(5));

    backendDetailPriorityQueue.offer(b1);
    backendDetailPriorityQueue.offer(b2);
    backendDetailPriorityQueue.offer(b3);

    Assert.assertEquals(mockBackends.get(2), backendDetailPriorityQueue.poll().getBackend());
    Assert.assertEquals(mockBackends.get(0), backendDetailPriorityQueue.poll().getBackend());
    Assert.assertEquals(mockBackends.get(1), backendDetailPriorityQueue.poll().getBackend());
    Assert.assertNull(backendDetailPriorityQueue.poll());

    //Modifying associated process groups of topmost element in queue should not change its priority
    backendDetailPriorityQueue.offer(b1);
    backendDetailPriorityQueue.offer(b2);
    backendDetailPriorityQueue.offer(b3);

    b2.deAssociateProcessGroup(mockProcessGroups.get(2));
    b2.deAssociateProcessGroup(mockProcessGroups.get(3));
    b2.deAssociateProcessGroup(mockProcessGroups.get(4));
    b1.associateProcessGroup(mockProcessGroups.get(2));
    b1.associateProcessGroup(mockProcessGroups.get(3));
    b3.associateProcessGroup(mockProcessGroups.get(4));


    Assert.assertEquals(mockBackends.get(2), backendDetailPriorityQueue.poll().getBackend());

    //Re-adding backends to queue after modifying associated process groups should affect their priority order
    backendDetailPriorityQueue.clear();
    backendDetailPriorityQueue.offer(b1);
    backendDetailPriorityQueue.offer(b2);
    backendDetailPriorityQueue.offer(b3);

    Assert.assertEquals(mockBackends.get(1), backendDetailPriorityQueue.poll().getBackend());
    Assert.assertEquals(mockBackends.get(2), backendDetailPriorityQueue.poll().getBackend());
    Assert.assertEquals(mockBackends.get(0), backendDetailPriorityQueue.poll().getBackend());
    Assert.assertNull(backendDetailPriorityQueue.poll());
  }
}
