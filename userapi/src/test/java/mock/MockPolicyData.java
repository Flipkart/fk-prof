package mock;

import fk.prof.idl.Entities;
import fk.prof.idl.PolicyEntities;
import fk.prof.idl.WorkEntities;

import java.util.Arrays;
import java.util.List;

/**
 * MockPolicyData to be used in tests
 * Created by rohit.patiyal on 10/05/17.
 */
public class MockPolicyData {
    private static WorkEntities.CpuSampleWork mockCpuSampleWork = WorkEntities.CpuSampleWork.newBuilder().setFrequency(50).setMaxFrames(64).build();
    private static WorkEntities.Work mockWork = WorkEntities.Work.newBuilder().setWType(WorkEntities.WorkType.cpu_sample_work).setCpuSample(mockCpuSampleWork).build();

    private static List<PolicyEntities.Schedule> mockSchedules = Arrays.asList(
        PolicyEntities.Schedule.newBuilder().setAfter("0").setDuration(120).setPgCovPct(100).build(),
        PolicyEntities.Schedule.newBuilder().setAfter("0").setDuration(120).setPgCovPct(100).setMinHealthy(1).build(),
        PolicyEntities.Schedule.newBuilder().setAfter("0").setDuration(120).setPgCovPct(100).setMinHealthy(2).build()
    );

    private static List<PolicyEntities.Policy> mockPolicies = Arrays.asList(
        PolicyEntities.Policy.newBuilder().addWork(mockWork).setSchedule(mockSchedules.get(0)).setDescription("Test policy").build(),
        PolicyEntities.Policy.newBuilder().addWork(mockWork).setSchedule(mockSchedules.get(1)).setDescription("Test policy").build(),
        PolicyEntities.Policy.newBuilder().addWork(mockWork).setSchedule(mockSchedules.get(2)).setDescription("Test policy").build()

    );

    public static List<Entities.ProcessGroup> mockProcessGroups = Arrays.asList(
        Entities.ProcessGroup.newBuilder().setAppId("a1").setCluster("c1").setProcName("p1").build(),
        Entities.ProcessGroup.newBuilder().setAppId("a1").setCluster("c1").setProcName("p2").build(),
        Entities.ProcessGroup.newBuilder().setAppId("a1").setCluster("c2").setProcName("p3").build(),
        Entities.ProcessGroup.newBuilder().setAppId("a2").setCluster("c1").setProcName("p1").build(),
        Entities.ProcessGroup.newBuilder().setAppId("b1").setCluster("c1").setProcName("p1").build()
    );

    public static List<PolicyEntities.PolicyDetails> mockPolicyDetails = Arrays.asList(
        PolicyEntities.PolicyDetails.newBuilder().setPolicy(mockPolicies.get(0)).setModifiedBy("admin").setCreatedAt("3").setModifiedAt("3").build(),
        PolicyEntities.PolicyDetails.newBuilder().setPolicy(mockPolicies.get(0)).setModifiedBy("admin").setCreatedAt("4").setModifiedAt("4").build(),
        PolicyEntities.PolicyDetails.newBuilder().setPolicy(mockPolicies.get(0)).setModifiedBy("admin").setCreatedAt("5").setModifiedAt("5").build(),
        PolicyEntities.PolicyDetails.newBuilder().setPolicy(mockPolicies.get(1)).setModifiedBy("admin").setCreatedAt("5").setModifiedAt("5").build(),
        PolicyEntities.PolicyDetails.newBuilder().setPolicy(mockPolicies.get(2)).setModifiedBy("admin").setCreatedAt("5").setModifiedAt("5").build()
    );

    public static PolicyEntities.VersionedPolicyDetails getMockVersionedPolicyDetails(PolicyEntities.PolicyDetails policyDetails, int version) {
        return PolicyEntities.VersionedPolicyDetails.newBuilder().setPolicyDetails(policyDetails).setVersion(version).build();
    }
}