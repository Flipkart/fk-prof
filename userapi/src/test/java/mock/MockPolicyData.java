package mock;

import proto.PolicyDTO;
import recording.Recorder;

import java.util.Arrays;
import java.util.List;

/**
 * MockPolicyData to be used in tests
 * Created by rohit.patiyal on 10/05/17.
 */
public class MockPolicyData {
    private static PolicyDTO.CpuSampleWork mockCpuSampleWork = PolicyDTO.CpuSampleWork.newBuilder().setFrequency(50).setMaxFrames(64).build();
    private static PolicyDTO.Work mockWork = PolicyDTO.Work.newBuilder().setWType(PolicyDTO.WorkType.cpu_sample_work).setCpuSample(mockCpuSampleWork).build();

    private static List<PolicyDTO.Schedule> mockSchedules = Arrays.asList(
        PolicyDTO.Schedule.newBuilder().setAfter("0").setDuration(120).setPgCovPct(100).build(),
        PolicyDTO.Schedule.newBuilder().setAfter("0").setDuration(120).setPgCovPct(100).setMinHealthy(1).build(),
        PolicyDTO.Schedule.newBuilder().setAfter("0").setDuration(120).setPgCovPct(100).setMinHealthy(2).build()
    );

    private static List<PolicyDTO.Policy> mockPolicies = Arrays.asList(
        PolicyDTO.Policy.newBuilder().addWork(mockWork).setSchedule(mockSchedules.get(0)).setDescription("Test policy").build(),
        PolicyDTO.Policy.newBuilder().addWork(mockWork).setSchedule(mockSchedules.get(1)).setDescription("Test policy").build(),
        PolicyDTO.Policy.newBuilder().addWork(mockWork).setSchedule(mockSchedules.get(2)).setDescription("Test policy").build()

    );

    public static List<Recorder.ProcessGroup> mockProcessGroups = Arrays.asList(
        Recorder.ProcessGroup.newBuilder().setAppId("a1").setCluster("c1").setProcName("p1").build(),
        Recorder.ProcessGroup.newBuilder().setAppId("a1").setCluster("c1").setProcName("p2").build(),
        Recorder.ProcessGroup.newBuilder().setAppId("a1").setCluster("c2").setProcName("p3").build(),
        Recorder.ProcessGroup.newBuilder().setAppId("a2").setCluster("c1").setProcName("p1").build(),
        Recorder.ProcessGroup.newBuilder().setAppId("b1").setCluster("c1").setProcName("p1").build()
    );

    public static List<PolicyDTO.PolicyDetails> mockPolicyDetails = Arrays.asList(
        PolicyDTO.PolicyDetails.newBuilder().setPolicy(mockPolicies.get(0)).setModifiedBy("admin").setCreatedAt("3").setModifiedAt("3").build(),
        PolicyDTO.PolicyDetails.newBuilder().setPolicy(mockPolicies.get(0)).setModifiedBy("admin").setCreatedAt("4").setModifiedAt("4").build(),
        PolicyDTO.PolicyDetails.newBuilder().setPolicy(mockPolicies.get(0)).setModifiedBy("admin").setCreatedAt("5").setModifiedAt("5").build(),
        PolicyDTO.PolicyDetails.newBuilder().setPolicy(mockPolicies.get(1)).setModifiedBy("admin").setCreatedAt("5").setModifiedAt("5").build(),
        PolicyDTO.PolicyDetails.newBuilder().setPolicy(mockPolicies.get(2)).setModifiedBy("admin").setCreatedAt("5").setModifiedAt("5").build()
    );

    public static PolicyDTO.VersionedPolicyDetails getMockVersionedPolicyDetails(PolicyDTO.PolicyDetails policyDetails, int version) {
        return PolicyDTO.VersionedPolicyDetails.newBuilder().setPolicyDetails(policyDetails).setVersion(version).build();
    }
}