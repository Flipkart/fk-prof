package fk.prof.backend.util.proto;

import fk.prof.idl.PolicyEntities;
import fk.prof.idl.Profile;
import fk.prof.idl.WorkEntities;

import java.util.List;

/**
 * Utility methods for policy proto
 * Created by rohit.patiyal on 22/05/17.
 */
public class PolicyEntitiesProtoUtil {
  private static String policyDetailsCompactRepr(PolicyEntities.PolicyDetails policyDetails) {
    return String.format("modAt=%s,createAt=%s,createBy=%s,policy={%s}", policyDetails.getModifiedAt(), policyDetails.getCreatedAt(), policyDetails.getModifiedBy(), policyCompactRepr(policyDetails.getPolicy()));
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
    switch (work.getWType()) {
      case cpu_sample_work:
        if (work.hasCpuSample()) {
          sb.append("cpuSample:");
          WorkEntities.CpuSampleWork cpuSample = work.getCpuSample();
          sb.append(String.format("{freq=%d,maxFrames=%d}", cpuSample.getFrequency(), cpuSample.getMaxFrames()));
        }
        break;
      case io_trace_work:
        if (work.hasIoTrace()) {
          sb.append("ioTrace:");
          WorkEntities.IOTraceWork ioTrace = work.getIoTrace();
          sb.append(String.format("{latThresh=%d,maxFrames=%d}", ioTrace.getLatencyThresholdMs(), ioTrace.getMaxFrames()));
        }
        break;
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

  public static Profile.RecordingPolicy translateToRecordingPolicy(PolicyEntities.VersionedPolicyDetails versionedPolicy) {
    PolicyEntities.Policy policyDTOPolicy = versionedPolicy.getPolicyDetails().getPolicy();
    Profile.RecordingPolicy.Builder recordingPolicyBuilder = Profile.RecordingPolicy.newBuilder()
        .setCoveragePct(policyDTOPolicy.getSchedule().getPgCovPct())
        .setDuration(policyDTOPolicy.getSchedule().getDuration())
        .setDescription(policyDTOPolicy.getDescription())
        .addAllWork(policyDTOPolicy.getWorkList());
    if (policyDTOPolicy.getSchedule().hasMinHealthy()) {
      recordingPolicyBuilder.setMinHealthy(policyDTOPolicy.getSchedule().getMinHealthy());
    }
    return recordingPolicyBuilder.build();
  }

  public static void validatePolicyValues(PolicyEntities.VersionedPolicyDetails versionedPolicyDetails) throws Exception {
    PolicyEntities.Policy policy = versionedPolicyDetails.getPolicyDetails().getPolicy();
    validateField("duration", policy.getSchedule().getDuration(), 60, 960);
    validateField("pgCovPct", policy.getSchedule().getPgCovPct(), 0, 100);
    if(policy.getSchedule().hasMinHealthy()){
      validateField("minHealthy", policy.getSchedule().getMinHealthy(), 1,10000);
    }
    for (WorkEntities.Work work : policy.getWorkList()) {
      if (work.hasCpuSample()) {
        if(WorkEntities.WorkType.cpu_sample_work.equals(work.getWType())) {
          validateField("cpuSample: frequency", work.getCpuSample().getFrequency(), 50, 100);
          validateField("cpuSample: maxFrames", work.getCpuSample().getMaxFrames(), 1, 999);
        } else {
          throw new IllegalArgumentException("Wrong work type details provided for: " + work.getWType());
        }
      }

      if (work.hasIoTrace()) {
        if(WorkEntities.WorkType.io_trace_work.equals(work.getWType())) {
          validateField("ioTrace: latThreshold", work.getIoTrace().getLatencyThresholdMs(), 1, Integer.MAX_VALUE);
          validateField("ioTrace: maxFrames", work.getIoTrace().getMaxFrames(), 1, 999);
        } else {
          throw new IllegalArgumentException("Wrong work type details provided for: " + work.getWType());
        }
      }
    }
  }

  private static <T extends Comparable<T>> void validateField(String name, T value, T min, T max) throws Exception {
    if (value.compareTo(min) < 0 || value.compareTo(max) > 0) {
      throw new IllegalArgumentException("Value of " + name + " should be between [" + min + "," + max + "], given: " + value);
    }
  }
}
