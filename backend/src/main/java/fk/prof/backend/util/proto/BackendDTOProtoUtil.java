package fk.prof.backend.util.proto;

import fk.prof.backend.proto.BackendDTO;
import proto.PolicyDTO;

import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.stream.Collectors;

public class BackendDTOProtoUtil {
  private static final String DEFAULT_USER = "anonymous";
  private static final String DEFAULT_DESCRIPTION = "test description";
  private static final int DEFAULT_VERSION = -1;
  private static final String DEFAULT_AFTER = "0";

  public static String recordingPolicyCompactRepr(BackendDTO.RecordingPolicy recordingPolicy) {
    return String.format("dur=%d,cov=%d,desc=%s", recordingPolicy.getDuration(), recordingPolicy.getCoveragePct(), recordingPolicy.getDescription());
  }

  public static String leaderDetailCompactRepr(BackendDTO.LeaderDetail leaderDetail) {
    return leaderDetail == null ? null : String.format("%s:%s", leaderDetail.getHost(), leaderDetail.getPort());
  }

  /**
   * Creates a new PolicyDTO versionedPolicyDetails from the contents of the recordingPolicy. Since this versionedPolicyDetails
   * is translated from a subset of information, other values are populated with some defaults, e.g. version will be -1
   *
   * @param recordingPolicy BackendDTO recordingPolicy to be translated
   * @return newly created versionedPolicyDetails
   */
  public static PolicyDTO.VersionedPolicyDetails translateToPolicyVersionedPolicyDetails(BackendDTO.RecordingPolicy recordingPolicy) {
    String currentTime = ZonedDateTime.now().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
    PolicyDTO.Schedule.Builder policyDTOScheduleBuilder = PolicyDTO.Schedule.newBuilder().setDuration(recordingPolicy.getDuration()).setPgCovPct(recordingPolicy.getCoveragePct()).setAfter(DEFAULT_AFTER);

    if (recordingPolicy.hasMinHealthy()) {
      policyDTOScheduleBuilder.setMinHealthy(recordingPolicy.getMinHealthy());
    }

    return PolicyDTO.VersionedPolicyDetails.newBuilder()
        .setVersion(DEFAULT_VERSION)
        .setPolicyDetails(PolicyDTO.PolicyDetails.newBuilder()
            .setCreatedAt(currentTime)
            .setModifiedAt(currentTime)
            .setModifiedBy(DEFAULT_USER)
            .setPolicy(PolicyDTO.Policy.newBuilder()
                .setSchedule(policyDTOScheduleBuilder.build())
                .setDescription(DEFAULT_DESCRIPTION)
                .addAllWork(recordingPolicy.getWorkList().stream().map(BackendDTOProtoUtil::translateToPolicyDTOWork).collect(Collectors.toList()))
                .build()).build()).build();
  }

  private static PolicyDTO.Work translateToPolicyDTOWork(BackendDTO.Work work) {
    PolicyDTO.Work.Builder policyDTOWorkBuilder = PolicyDTO.Work.newBuilder().setWType(translateToPolicyDTOWType(work.getWType()));
    if (work.hasCpuSample()) {
      BackendDTO.CpuSampleWork backendDTOCPUSample = work.getCpuSample();
      policyDTOWorkBuilder.setCpuSample(PolicyDTO.CpuSampleWork.newBuilder().setFrequency(backendDTOCPUSample.getFrequency())
          .setMaxFrames(backendDTOCPUSample.getMaxFrames()).build());
    }
    if (work.hasThdSample()) {
      BackendDTO.ThreadSampleWork backendDTOThdSample = work.getThdSample();
      policyDTOWorkBuilder.setThdSample(PolicyDTO.ThreadSampleWork.newBuilder().setFrequency(backendDTOThdSample.getFrequency())
          .setMaxFrames(backendDTOThdSample.getMaxFrames()).build());
    }
    if (work.hasMonitorBlock()) {
      BackendDTO.MonitorContentionWork backendDTOMonitorBlock = work.getMonitorBlock();
      policyDTOWorkBuilder.setMonitorBlock(PolicyDTO.MonitorContentionWork.newBuilder().setMaxMonitors(backendDTOMonitorBlock.getMaxMonitors())
          .setMaxFrames(backendDTOMonitorBlock.getMaxFrames()).build());
    }
    if (work.hasMonitorWait()) {
      BackendDTO.MonitorWaitWork backendDTOMonitorWait = work.getMonitorWait();
      policyDTOWorkBuilder.setMonitorWait(PolicyDTO.MonitorWaitWork.newBuilder().setMaxMonitors(backendDTOMonitorWait.getMaxMonitors())
          .setMaxFrames(backendDTOMonitorWait.getMaxFrames()).build());
    }
    return policyDTOWorkBuilder.build();
  }

  private static PolicyDTO.WorkType translateToPolicyDTOWType(BackendDTO.WorkType wType) {
    switch (wType) {
      case cpu_sample_work:
        return PolicyDTO.WorkType.cpu_sample_work;
      case thread_sample_work:
        return PolicyDTO.WorkType.thread_sample_work;
      case monitor_contention_work:
        return PolicyDTO.WorkType.monitor_contention_work;
      case monitor_wait_work:
        return PolicyDTO.WorkType.monitor_wait_work;
      default:
        return null;
    }
  }
}
