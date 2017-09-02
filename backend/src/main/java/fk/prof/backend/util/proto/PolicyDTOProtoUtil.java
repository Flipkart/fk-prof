package fk.prof.backend.util.proto;

import fk.prof.backend.proto.BackendDTO;
import proto.PolicyDTO;

import java.util.stream.Collectors;

/**
 * Utility methods for policy proto
 * Created by rohit.patiyal on 22/05/17.
 */
public class PolicyDTOProtoUtil {
  private static String policyDetailsCompactRepr(PolicyDTO.PolicyDetails policyDetails) {
    return String.format("modAt=%s,creatAt=%s,creatBy=%s", policyDetails.getModifiedAt(), policyDetails.getModifiedBy(), policyDetails.getCreatedAt());
  }

  public static String versionedPolicyDetailsCompactRepr(PolicyDTO.VersionedPolicyDetails versionedPolicyDetails) {
    return String.format("version=%d,policy=(%s)", versionedPolicyDetails.getVersion(), policyDetailsCompactRepr(versionedPolicyDetails.getPolicyDetails()));
  }

  public static BackendDTO.RecordingPolicy translateToBackendRecordingPolicy(PolicyDTO.VersionedPolicyDetails versionedPolicy) {
    PolicyDTO.Policy policyDTOPolicy = versionedPolicy.getPolicyDetails().getPolicy();
    BackendDTO.RecordingPolicy.Builder recordingPolicyBuilder = BackendDTO.RecordingPolicy.newBuilder()
        .setCoveragePct(policyDTOPolicy.getSchedule().getPgCovPct())
        .setDuration(policyDTOPolicy.getSchedule().getDuration())
        .setDescription(policyDTOPolicy.getDescription())
        .addAllWork(policyDTOPolicy.getWorkList().stream().map(PolicyDTOProtoUtil::translateToBackendDTOWork).collect(Collectors.toList()));
    if(policyDTOPolicy.getSchedule().hasMinHealthy()){
      recordingPolicyBuilder.setMinHealthy(policyDTOPolicy.getSchedule().getMinHealthy());
    }
    return recordingPolicyBuilder.build();
  }

  private static BackendDTO.WorkType translateToBackendDTOWorkType(PolicyDTO.WorkType workType) {
    switch (workType) {
      case cpu_sample_work:
        return BackendDTO.WorkType.cpu_sample_work;
      case thread_sample_work:
        return BackendDTO.WorkType.thread_sample_work;
      case monitor_contention_work:
        return BackendDTO.WorkType.monitor_contention_work;
      case monitor_wait_work:
        return BackendDTO.WorkType.monitor_wait_work;
      default:
        return null;
    }
  }

  private static BackendDTO.Work translateToBackendDTOWork(PolicyDTO.Work work) {
    BackendDTO.Work.Builder backendDTOWorkBuilder = BackendDTO.Work.newBuilder().setWType(translateToBackendDTOWorkType(work.getWType()));

    if (work.hasCpuSample()) {
      PolicyDTO.CpuSampleWork policyDTOCPUSample = work.getCpuSample();
      backendDTOWorkBuilder.setCpuSample(BackendDTO.CpuSampleWork.newBuilder().setFrequency(policyDTOCPUSample.getFrequency())
          .setMaxFrames(policyDTOCPUSample.getMaxFrames()).build());
    }
    if (work.hasThdSample()) {
      PolicyDTO.ThreadSampleWork policyDTOThdSample = work.getThdSample();
      backendDTOWorkBuilder.setThdSample(BackendDTO.ThreadSampleWork.newBuilder().setFrequency(policyDTOThdSample.getFrequency())
          .setMaxFrames(policyDTOThdSample.getMaxFrames()).build());
    }
    if (work.hasMonitorBlock()) {
      PolicyDTO.MonitorContentionWork policyDTOMonitorBlock = work.getMonitorBlock();
      backendDTOWorkBuilder.setMonitorBlock(BackendDTO.MonitorContentionWork.newBuilder().setMaxMonitors(policyDTOMonitorBlock.getMaxMonitors())
          .setMaxFrames(policyDTOMonitorBlock.getMaxFrames()).build());
    }
    if (work.hasMonitorWait()) {
      PolicyDTO.MonitorWaitWork policyDTOMonitorWait = work.getMonitorWait();
      backendDTOWorkBuilder.setMonitorWait(BackendDTO.MonitorWaitWork.newBuilder().setMaxMonitors(policyDTOMonitorWait.getMaxMonitors())
          .setMaxFrames(policyDTOMonitorWait.getMaxFrames()).build());
    }
    return backendDTOWorkBuilder.build();
  }

  public static void validatePolicyValues(PolicyDTO.VersionedPolicyDetails versionedPolicyDetails) throws Exception {
    PolicyDTO.Policy policy = versionedPolicyDetails.getPolicyDetails().getPolicy();
    validateField("duration", policy.getSchedule().getDuration(), 60, 960);
    validateField("pgCovPct", policy.getSchedule().getPgCovPct(), 0, 100);
    for (PolicyDTO.Work work : policy.getWorkList()) {
      int workDetailsCount = 0;
      if (work.hasCpuSample()) {
        validateField("cpuSample: frequency", work.getCpuSample().getFrequency(), 50, 100);
        validateField("cpuSample: maxFrames", work.getCpuSample().getMaxFrames(), 1, 999);
        workDetailsCount++;
      }
      if (work.hasThdSample()) {
        validateField("threadSample: frequency", work.getThdSample().getFrequency(), 50, 100);
        validateField("threadSample: maxFrames", work.getThdSample().getMaxFrames(), 1, 999);
        workDetailsCount++;
      }
      if (work.hasMonitorBlock()) {
        validateField("monitorBlock: maxMonitors", work.getMonitorBlock().getMaxMonitors(), 50, 100);
        validateField("monitorBlock: maxFrames", work.getMonitorBlock().getMaxFrames(), 1, 999);
        workDetailsCount++;
      }
      if (work.hasMonitorWait()) {
        validateField("monitorWait: maxMonitors", work.getMonitorWait().getMaxMonitors(), 50, 100);
        validateField("monitorWait: maxFrames", work.getMonitorWait().getMaxFrames(), 1, 999);
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
