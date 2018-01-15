package fk.prof.backend.util.proto;

import fk.prof.idl.Backend;
import fk.prof.idl.PolicyEntities;
import fk.prof.idl.WorkEntities;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Utility methods for policy proto
 * Created by rohit.patiyal on 22/05/17.
 */
public class PolicyEntitiesProtoUtil {
  private static String policyDetailsCompactRepr(PolicyEntities.PolicyDetails policyDetails) {
    return String.format("modAt=%s,creatAt=%s,creatBy=%s,policy={%s}", policyDetails.getModifiedAt(), policyDetails.getCreatedAt(), policyDetails.getModifiedBy(), policyCompactRepr(policyDetails.getPolicy()));
  }

  private static String policyCompactRepr(PolicyEntities.Policy policy) {
    return String.format("desc:%s,sched:{%s},work:[%s]", policy.getDescription(),policyScheduleCompactRepr(policy.getSchedule()),policyWorkListCompactRepr(policy.getWorkList()));
  }

  private static String policyWorkListCompactRepr(List<WorkEntities.Work> workList) {
    StringBuilder sb = new StringBuilder();
    for(WorkEntities.Work work: workList){
      sb.append(policyWorkCompactRepr(work));
    }
    return sb.toString();
  }

  private static String policyWorkCompactRepr(WorkEntities.Work work) {
    StringBuilder sb = new StringBuilder();
    if(work.hasCpuSample()){
      sb.append("cpuSample:");
      WorkEntities.CpuSampleWork cpuSample = work.getCpuSample();
      sb.append(String.format("{freq=%d,maxFram=%d}",cpuSample.getFrequency(), cpuSample.getMaxFrames()));
    }
    return sb.toString();
  }

  private static String policyScheduleCompactRepr(PolicyEntities.Schedule schedule) {
    String policySchedule = String.format("aft:%s,dur:%d,cov:%d", schedule.getAfter(), schedule.getDuration(), schedule.getPgCovPct());
    ;
    if(schedule.hasMinHealthy()){
      policySchedule = policySchedule + String.format(",minHeal=%d", schedule.getMinHealthy());
    }
    return policySchedule;
  }

  public static String versionedPolicyDetailsCompactRepr(PolicyEntities.VersionedPolicyDetails versionedPolicyDetails) {
    return String.format("version=%d,policyDetails={%s}", versionedPolicyDetails.getVersion(), policyDetailsCompactRepr(versionedPolicyDetails.getPolicyDetails()));
  }

  public static Backend.RecordingPolicy translateToBackendRecordingPolicy(PolicyEntities.VersionedPolicyDetails versionedPolicy) {
    PolicyEntities.Policy policyDTOPolicy = versionedPolicy.getPolicyDetails().getPolicy();
    Backend.RecordingPolicy.Builder recordingPolicyBuilder = Backend.RecordingPolicy.newBuilder()
        .setCoveragePct(policyDTOPolicy.getSchedule().getPgCovPct())
        .setDuration(policyDTOPolicy.getSchedule().getDuration())
        .setDescription(policyDTOPolicy.getDescription())
        .addAllWork(policyDTOPolicy.getWorkList().stream().map(PolicyEntitiesProtoUtil::translateToBackendDTOWork).collect(Collectors.toList()));
    if (policyDTOPolicy.getSchedule().hasMinHealthy()) {
      recordingPolicyBuilder.setMinHealthy(policyDTOPolicy.getSchedule().getMinHealthy());
    }
    return recordingPolicyBuilder.build();
  }

  private static WorkEntities.WorkType translateToBackendDTOWorkType(WorkEntities.WorkType workType) {
    switch (workType) {
      case cpu_sample_work:
        return WorkEntities.WorkType.cpu_sample_work;
      default:
        return null;
    }
  }

  private static WorkEntities.Work translateToBackendDTOWork(WorkEntities.Work work) {
    WorkEntities.Work.Builder backendDTOWorkBuilder = WorkEntities.Work.newBuilder().setWType(translateToBackendDTOWorkType(work.getWType()));

    if (work.hasCpuSample()) {
      WorkEntities.CpuSampleWork policyDTOCPUSample = work.getCpuSample();
      backendDTOWorkBuilder.setCpuSample(WorkEntities.CpuSampleWork.newBuilder().setFrequency(policyDTOCPUSample.getFrequency())
          .setMaxFrames(policyDTOCPUSample.getMaxFrames()).build());
    }
    return backendDTOWorkBuilder.build();
  }

  public static void validatePolicyValues(PolicyEntities.VersionedPolicyDetails versionedPolicyDetails) throws Exception {
    PolicyEntities.Policy policy = versionedPolicyDetails.getPolicyDetails().getPolicy();
    validateField("duration", policy.getSchedule().getDuration(), 60, 960);
    validateField("pgCovPct", policy.getSchedule().getPgCovPct(), 0, 100);
    if(policy.getSchedule().hasMinHealthy()){
      validateField("minHealthy", policy.getSchedule().getMinHealthy(), 1,10000);
    }
    for (WorkEntities.Work work : policy.getWorkList()) {
      int workDetailsCount = 0;
      if (work.hasCpuSample()) {
        validateField("cpuSample: frequency", work.getCpuSample().getFrequency(), 50, 100);
        validateField("cpuSample: maxFrames", work.getCpuSample().getMaxFrames(), 1, 999);
        workDetailsCount++;
      }
      if (workDetailsCount != 1)
        throw new IllegalArgumentException("Only one work details per work supported, given: " + workDetailsCount);
    }
  }

  private static <T extends Comparable<T>> void validateField(String name, T value, T min, T max) throws Exception {
    if (value.compareTo(min) < 0 || value.compareTo(max) > 0) {
      throw new IllegalArgumentException("Value of " + name + " should be between [" + min + "," + max + "], given: " + value);
    }
  }
}
