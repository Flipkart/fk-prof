package fk.prof.backend;

import policy.PolicyDetails;
import recording.Recorder;

import java.util.Arrays;
import java.util.List;

/**
 * MockData to be used in tests
 * Created by rohit.patiyal on 10/05/17.
 */
public class MockData {
    public static List<Recorder.ProcessGroup> mockProcessGroups = Arrays.asList(
            Recorder.ProcessGroup.newBuilder().setAppId("a1").setCluster("c1").setProcName("p1").build(),
            Recorder.ProcessGroup.newBuilder().setAppId("a1").setCluster("c1").setProcName("p2").build(),
            Recorder.ProcessGroup.newBuilder().setAppId("a1").setCluster("c2").setProcName("p3").build(),
            Recorder.ProcessGroup.newBuilder().setAppId("a2").setCluster("c1").setProcName("p1").build()
    );
    public static List<PolicyDetails> mockPolicies = Arrays.asList(
            PolicyDetails.newBuilder().setAdministrator("admin1").setCreatedAt("3").setModifiedAt("3").setLastScheduled("3:20").build(),
            PolicyDetails.newBuilder().setAdministrator("admin1").setCreatedAt("4").setModifiedAt("4").setLastScheduled("4:30").build(),
            PolicyDetails.newBuilder().setAdministrator("admin2").setCreatedAt("5").setModifiedAt("5").setLastScheduled("5:40").build()
    );
}
