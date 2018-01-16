package fk.prof.backend;

import fk.prof.backend.model.association.BackendDetail;
import fk.prof.idl.Entities;
import fk.prof.idl.Recorder;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

import java.io.IOException;
import java.util.*;

public class BackendDetailTest {
  List<Entities.ProcessGroup> mockProcessGroups;
  List<Recorder.AssignedBackend> mockBackends;

  @Before
  public void setBefore() {
    mockProcessGroups = Arrays.asList(
        Entities.ProcessGroup.newBuilder().setAppId("a").setCluster("c").setProcName("p1").build(),
        Entities.ProcessGroup.newBuilder().setAppId("a").setCluster("c").setProcName("p2").build(),
        Entities.ProcessGroup.newBuilder().setAppId("a").setCluster("c").setProcName("p3").build()
    );
    mockBackends = Arrays.asList(
        Recorder.AssignedBackend.newBuilder().setHost("1").setPort(0).build(),
        Recorder.AssignedBackend.newBuilder().setHost("2").setPort(0).build(),
        Recorder.AssignedBackend.newBuilder().setHost("3").setPort(0).build()
    );
  }

  @Test
  public void testEqualityOfBackendOnIPAddress()
    throws IOException {
    Set<Entities.ProcessGroup> processGroups = new HashSet<>(mockProcessGroups);
    BackendDetail b1 = new BackendDetail(mockBackends.get(0), 1, 2, processGroups);
    BackendDetail b2 = new BackendDetail(mockBackends.get(0), 1, 2);
    Assert.assertTrue(b1.equals(b2));
    BackendDetail b3 = new BackendDetail(mockBackends.get(1), 1, 2, processGroups);
    Assert.assertFalse(b2.equals(b3));
  }

  @Test
  public void testInitializationOfBackendWithProcessGroups()
      throws IOException {
    Set<Entities.ProcessGroup> processGroups = new HashSet<>(mockProcessGroups);
    BackendDetail backendDetail = new BackendDetail(mockBackends.get(0), 1, 2, processGroups);
    Assert.assertEquals(mockBackends.get(0), backendDetail.getBackend());
    Assert.assertEquals(processGroups, backendDetail.getAssociatedProcessGroups());
  }

  @Test
  public void testBackendIsDefunctOnInitializationWithProcessGroups()
      throws IOException {
    BackendDetail backendDetail = new BackendDetail(mockBackends.get(0), 1, 2, null);
    Assert.assertTrue(backendDetail.isDefunct());
  }

  @Test
  public void testBackendIsAvailableAfterReportOfLoad()
      throws IOException {
    BackendDetail backendDetail = new BackendDetail(mockBackends.get(0), 1, 2, null);
    backendDetail.reportLoad(0.5f, 1);
    Assert.assertFalse(backendDetail.isDefunct());
  }

  @Test
  public void testBackendIsDefunctIfLoadNotReportedInAllowedInterval()
      throws Exception {
    BackendDetail backendDetail = new BackendDetail(mockBackends.get(0), 1, 1, null);
    backendDetail.reportLoad(0.5f, 1);
    Assert.assertFalse(backendDetail.isDefunct());
    Thread.sleep(1000);
    Assert.assertFalse(backendDetail.isDefunct());
    Thread.sleep(2000);
    Assert.assertTrue(backendDetail.isDefunct());
  }
}
