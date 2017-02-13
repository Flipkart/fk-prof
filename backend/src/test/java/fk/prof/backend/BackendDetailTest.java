package fk.prof.backend;

import fk.prof.backend.model.association.BackendDetail;
import fk.prof.backend.proto.BackendDTO;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import recording.Recorder;

import java.io.IOException;
import java.util.*;

public class BackendDetailTest {
  List<Recorder.ProcessGroup> mockProcessGroups;

  @Before
  public void setBefore() {
    mockProcessGroups = Arrays.asList(
        Recorder.ProcessGroup.newBuilder().setAppId("a").setCluster("c").setProcName("p1").build(),
        Recorder.ProcessGroup.newBuilder().setAppId("a").setCluster("c").setProcName("p2").build(),
        Recorder.ProcessGroup.newBuilder().setAppId("a").setCluster("c").setProcName("p3").build()
    );
  }

  @Test
  public void testEqualityOfBackendOnIPAddress()
    throws IOException {
    Set<Recorder.ProcessGroup> processGroups = new HashSet<>(mockProcessGroups);
    byte[] serialized = BackendDetail.serializeProcessGroups(processGroups);
    BackendDetail b1 = new BackendDetail("1", 1, 2, serialized);
    BackendDetail b2 = new BackendDetail("1", 1, 2);
    Assert.assertTrue(b1.equals(b2));
    BackendDetail b3 = new BackendDetail("2", 1, 2);
    Assert.assertFalse(b2.equals(b3));
  }

  @Test
  public void testInitializationOfBackendWithProcessGroupBytes()
      throws IOException {
    Set<Recorder.ProcessGroup> processGroups = new HashSet<>(mockProcessGroups);
    byte[] serialized = BackendDetail.serializeProcessGroups(processGroups);
    BackendDetail backendDetail = new BackendDetail("1", 1, 2, serialized);
    Assert.assertEquals("1", backendDetail.getBackendIPAddress());
    Assert.assertEquals(processGroups, backendDetail.getAssociatedProcessGroups());
  }

  @Test
  public void testBackendIsDefunctOnInitialization()
      throws IOException {
    BackendDetail backendDetail = new BackendDetail("1", 1, 2);
    Assert.assertTrue(backendDetail.isDefunct());
  }

  @Test
  public void testBackendIsAvailableAfterReportOfLoad()
      throws IOException {
    BackendDetail backendDetail = new BackendDetail("1", 1, 2);
    backendDetail.reportLoad(0.5);
    Assert.assertFalse(backendDetail.isDefunct());
  }

  @Test
  public void testBackendIsDefunctIfLoadNotReportedInAllowedInterval()
      throws Exception {
    BackendDetail backendDetail = new BackendDetail("1", 1, 1);
    backendDetail.reportLoad(0.5);
    Assert.assertFalse(backendDetail.isDefunct());
    Thread.sleep(1000);
    Assert.assertFalse(backendDetail.isDefunct());
    Thread.sleep(2000);
    Assert.assertTrue(backendDetail.isDefunct());
  }
}
