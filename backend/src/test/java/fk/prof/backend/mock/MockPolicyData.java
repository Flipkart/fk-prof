package fk.prof.backend.mock;

import fk.prof.backend.proto.PolicyDTO;
import recording.Recorder;

import java.util.Arrays;
import java.util.List;

/**
 * MockPolicyData to be used in tests
 * Created by rohit.patiyal on 10/05/17.
 */
public class MockPolicyData {
    private static PolicyDTO.Work mockWork = PolicyDTO.Work.newBuilder().setWType(PolicyDTO.WorkType.cpu_sample_work).build();
    private static PolicyDTO.Schedule mockSchedule = PolicyDTO.Schedule.newBuilder().setAfter("w1").setDuration(5).setPgCovPct(10).build();
    private static PolicyDTO.Policy mockPolicy = PolicyDTO.Policy.newBuilder().addWork(mockWork).setSchedule(mockSchedule).build();
    public static List<Recorder.ProcessGroup> mockProcessGroups = Arrays.asList(
            Recorder.ProcessGroup.newBuilder().setAppId("a1").setCluster("c1").setProcName("p1").build(),
            Recorder.ProcessGroup.newBuilder().setAppId("a1").setCluster("c1").setProcName("p2").build(),
            Recorder.ProcessGroup.newBuilder().setAppId("a1").setCluster("c2").setProcName("p3").build(),
            Recorder.ProcessGroup.newBuilder().setAppId("a2").setCluster("c1").setProcName("p1").build(),
            Recorder.ProcessGroup.newBuilder().setAppId("b1").setCluster("c1").setProcName("p1").build()
    );

    public static List<PolicyDTO.PolicyDetails> mockPolicyDetails = Arrays.asList(
            PolicyDTO.PolicyDetails.newBuilder().setPolicy(mockPolicy).setModifiedBy("admin").setCreatedAt("3").setModifiedAt("3").build(),
            PolicyDTO.PolicyDetails.newBuilder().setPolicy(mockPolicy).setModifiedBy("admin").setCreatedAt("4").setModifiedAt("4").build(),
            PolicyDTO.PolicyDetails.newBuilder().setPolicy(mockPolicy).setModifiedBy("admin").setCreatedAt("5").setModifiedAt("5").build()
    );
    public static List<PolicyDTO.VersionedPolicyDetails> mockVersionedPolicyDetails = Arrays.asList(
            PolicyDTO.VersionedPolicyDetails.newBuilder().setPolicyDetails(mockPolicyDetails.get(0)).setVersion(-1).build(),
            PolicyDTO.VersionedPolicyDetails.newBuilder().setPolicyDetails(mockPolicyDetails.get(1)).setVersion(0).build(),
            PolicyDTO.VersionedPolicyDetails.newBuilder().setPolicyDetails(mockPolicyDetails.get(2)).setVersion(0).build(),
            PolicyDTO.VersionedPolicyDetails.newBuilder().setPolicyDetails(mockPolicyDetails.get(2)).setVersion(1).build()
    );

    public static PolicyDTO.VersionedPolicyDetails getMockVersionedPolicyDetails(PolicyDTO.PolicyDetails policyDetails, int version) {
        return PolicyDTO.VersionedPolicyDetails.newBuilder().setPolicyDetails(policyDetails).setVersion(version).build();
    }
}