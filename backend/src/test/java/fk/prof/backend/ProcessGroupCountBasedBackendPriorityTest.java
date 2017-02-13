package fk.prof.backend;

import fk.prof.backend.model.association.BackendDetail;
import fk.prof.backend.model.association.ProcessGroupCountBasedBackendComparator;
import fk.prof.backend.proto.BackendDTO;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import recording.Recorder;

import java.util.Arrays;
import java.util.List;
import java.util.PriorityQueue;

public class ProcessGroupCountBasedBackendPriorityTest {

  PriorityQueue<BackendDetail> backendDetailPriorityQueue;
  List<Recorder.ProcessGroup> mockProcessGroups;
  List<BackendDetail> mockBackends;

  @Before
  public void setBefore() {
    backendDetailPriorityQueue = new PriorityQueue<>(new ProcessGroupCountBasedBackendComparator());
    mockProcessGroups = Arrays.asList(
        Recorder.ProcessGroup.newBuilder().setAppId("a").setCluster("c").setProcName("p1").build(),
        Recorder.ProcessGroup.newBuilder().setAppId("a").setCluster("c").setProcName("p2").build(),
        Recorder.ProcessGroup.newBuilder().setAppId("a").setCluster("c").setProcName("p3").build(),
        Recorder.ProcessGroup.newBuilder().setAppId("a").setCluster("c").setProcName("p4").build(),
        Recorder.ProcessGroup.newBuilder().setAppId("a").setCluster("c").setProcName("p5").build(),
        Recorder.ProcessGroup.newBuilder().setAppId("a").setCluster("c").setProcName("p6").build()
    );
  }

  @Test
  public void shouldPrioritizeLowerProcessGroupBackends() throws Exception {
    int reportFrequency = 1;
    int allowedSkips = 10;
    BackendDetail b1 = new BackendDetail("1", reportFrequency, allowedSkips);
    b1.associateProcessGroup(mockProcessGroups.get(0));
    b1.associateProcessGroup(mockProcessGroups.get(1));

    BackendDetail b2 = new BackendDetail("2", reportFrequency, allowedSkips);
    b2.associateProcessGroup(mockProcessGroups.get(2));
    b2.associateProcessGroup(mockProcessGroups.get(3));
    b2.associateProcessGroup(mockProcessGroups.get(4));

    BackendDetail b3 = new BackendDetail("3", reportFrequency, allowedSkips);
    b3.associateProcessGroup(mockProcessGroups.get(5));

    backendDetailPriorityQueue.offer(b1);
    backendDetailPriorityQueue.offer(b2);
    backendDetailPriorityQueue.offer(b3);

    Assert.assertEquals("3", backendDetailPriorityQueue.poll().getBackendIPAddress());
    Assert.assertEquals("1", backendDetailPriorityQueue.poll().getBackendIPAddress());
    Assert.assertEquals("2", backendDetailPriorityQueue.poll().getBackendIPAddress());
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


    Assert.assertEquals("3", backendDetailPriorityQueue.poll().getBackendIPAddress());

    //Re-adding backends to queue after modifying associated process groups should affect their priority order
    backendDetailPriorityQueue.clear();
    backendDetailPriorityQueue.offer(b1);
    backendDetailPriorityQueue.offer(b2);
    backendDetailPriorityQueue.offer(b3);

    Assert.assertEquals("2", backendDetailPriorityQueue.poll().getBackendIPAddress());
    Assert.assertEquals("3", backendDetailPriorityQueue.poll().getBackendIPAddress());
    Assert.assertEquals("1", backendDetailPriorityQueue.poll().getBackendIPAddress());
    Assert.assertNull(backendDetailPriorityQueue.poll());
  }
}
