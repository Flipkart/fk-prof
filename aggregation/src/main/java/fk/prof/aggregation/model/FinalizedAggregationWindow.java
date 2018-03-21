package fk.prof.aggregation.model;

import fk.prof.idl.Profile.*;
import fk.prof.idl.WorkEntities;
import fk.prof.metrics.ProcessGroupTag;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.*;

public class FinalizedAggregationWindow {
  protected final String appId;
  protected final String clusterId;
  protected final String procId;
  protected final LocalDateTime start;
  protected final LocalDateTime endedAt;
  protected final int durationInSecs;
  protected final Map<Long, FinalizedProfileWorkInfo> workInfoLookup;
  protected final RecordingPolicy policy;
  protected final Map<WorkEntities.WorkType, FinalizedWorkSpecificAggregationBucket> workSpecificBuckets;

  private final ProcessGroupTag processGroupTag;

  public FinalizedAggregationWindow(String appId,
                                    String clusterId,
                                    String procId,
                                    LocalDateTime start,
                                    LocalDateTime endedAt,
                                    int durationInSecs,
                                    Map<Long, FinalizedProfileWorkInfo> workInfoLookup,
                                    RecordingPolicy policy,
                                    Map<WorkEntities.WorkType, FinalizedWorkSpecificAggregationBucket> workSpecificBuckets) {
    this.appId = appId;
    this.clusterId = clusterId;
    this.procId = procId;
    this.start = start;
    this.endedAt = endedAt;
    this.durationInSecs = durationInSecs;
    this.workInfoLookup = workInfoLookup;
    this.policy = policy;
    this.workSpecificBuckets = workSpecificBuckets;

    this.processGroupTag = new ProcessGroupTag(appId, clusterId, procId);
  }

  public ProcessGroupTag getProcessGroupTag() {
    return processGroupTag;
  }

  public FinalizedProfileWorkInfo getDetailsForWorkId(long workId) {
    return this.workInfoLookup.get(workId);
  }

  //NOTE: This is computed on expiry of aggregation window, null otherwise. Having a getter here to make this testable
  public LocalDateTime getEndedAt() {
    return this.endedAt;
  }

  @Override
  public String toString() {
    return "app_id=" + appId +
        ", cluster_id=" + clusterId +
        ", proc_id=" + procId +
        ", start=" + start +
        ", end=" + endedAt +
        ", duration=" + durationInSecs;
  }

  @Override
  public boolean equals(Object o) {
    if (o == this) {
      return true;
    }
    if (!(o instanceof FinalizedAggregationWindow)) {
      return false;
    }

    FinalizedAggregationWindow other = (FinalizedAggregationWindow) o;
    return this.appId.equals(other.appId)
        && this.clusterId.equals(other.clusterId)
        && this.procId.equals(other.procId)
        && this.start.equals(other.start)
        && this.durationInSecs == other.durationInSecs
        && (this.endedAt == null ? other.endedAt == null : this.endedAt.equals(other.endedAt))
        && this.workInfoLookup.equals(other.workInfoLookup)
        && this.policy.equals(other.policy)
        && this.workSpecificBuckets.equals(other.workSpecificBuckets);
  }

  protected Header buildHeaderProto(int version, WorkEntities.WorkType workType) {
    Header.Builder builder = Header.newBuilder()
        .setFormatVersion(version)
        .setAggregationEndTime(endedAt == null ? null : endedAt.atOffset(ZoneOffset.UTC).format(DateTimeFormatter.ISO_ZONED_DATE_TIME))
        .setAggregationStartTime(start.atOffset(ZoneOffset.UTC).format(DateTimeFormatter.ISO_ZONED_DATE_TIME))
        .setWindowDuration(durationInSecs)
        .setAppId(appId)
        .setClusterId(clusterId)
        .setProcId(procId)
        .setPolicy(policy);

    if(workType != null) {
      builder.setWorkType(workType);
    }

    return builder.build();
  }

  protected Header buildHeaderProto(int version) {
    return buildHeaderProto(version, null);
  }

  /**
   * Builds an iterable of {@link} PerRecorderProfileSummary for a workType.
   * @param workType
   * @param traces
   * @return {@link ProfileWorkInfo} iterable
   */
  protected Iterable<ProfileWorkInfo> buildProfileWorkInfoProto(WorkEntities.WorkType workType, TraceCtxNames traces) {
    return workInfoLookup.entrySet().stream().map(e -> e.getValue().buildProfileWorkInfoProto(workType, start, traces))::iterator;
  }

  /**
   * Builds an iterable of {@link ProfileWorkInfo}
   * @return {@link ProfileWorkInfo} iterable
   */
  protected Iterable<ProfileWorkInfo> buildProfileWorkInfoProto(TraceCtxNames traces) {
    return workInfoLookup.entrySet().stream().map(e -> e.getValue().buildProfileWorkInfoProto(start, traces))::iterator;
  }

  /**
   * Builds a list of all traces present in all recorded profiles.
   * @return
   */
  protected TraceCtxNames buildTraceCtxNamesProto() {
    TraceCtxNames.Builder builder = TraceCtxNames.newBuilder();
    if(workInfoLookup.size() == 0) {
      return builder.build();
    }

    // using first profile.tracesCount * 2 as initial capacity to avoid array resize. All recorded profiles have almost same set of traces.
    int initialCapacity = workInfoLookup.values().iterator().next().getRecordedTraces().size() * 2;
    Set<String> traces = new HashSet<>(initialCapacity);

    workInfoLookup.values().stream().forEach(e -> traces.addAll(e.getRecordedTraces()));

    builder.addAllName(traces);

    return builder.build();
  }

  /**
   * Build a list of traces present in a specific workType
   * @param workType
   * @return
   */
  protected TraceCtxNames buildTraceCtxNamesProto(WorkEntities.WorkType workType) {
    if(workSpecificBuckets.get(workType) == null) {
      throw new IllegalArgumentException(String.format("Recording policy does not specify work type=%s", workType));
    }

    switch (workType) {
      case cpu_sample_work:
        FinalizedCpuSamplingAggregationBucket cpuSamplingAggregationBucket = (FinalizedCpuSamplingAggregationBucket)workSpecificBuckets.get(WorkEntities.WorkType.cpu_sample_work);
        return cpuSamplingAggregationBucket.buildTraceNamesProto();
      case io_trace_work:
        FinalizedIOTracingAggregationBucket ioTracingAggregationBucket = (FinalizedIOTracingAggregationBucket) workSpecificBuckets.get(WorkEntities.WorkType.io_trace_work);
        return ioTracingAggregationBucket.buildTraceNamesProto();
      default:
        throw new IllegalArgumentException(workType.name() + " not supported");
    }
  }

  protected TraceCtxDetailList buildTraceCtxDetailListProto(WorkEntities.WorkType workType, TraceCtxNames traces) {
    if(workSpecificBuckets.get(workType) == null) {
      throw new IllegalArgumentException(String.format("Recording policy does not specify work type=%s", workType));
    }

    switch (workType) {
      case cpu_sample_work:
        FinalizedCpuSamplingAggregationBucket cpuSamplingAggregationBucket = (FinalizedCpuSamplingAggregationBucket)workSpecificBuckets.get(WorkEntities.WorkType.cpu_sample_work);
        return cpuSamplingAggregationBucket.buildTraceCtxListProto(traces);
      case io_trace_work:
        FinalizedIOTracingAggregationBucket ioTracingAggregationBucket = (FinalizedIOTracingAggregationBucket) workSpecificBuckets.get(WorkEntities.WorkType.io_trace_work);
        return ioTracingAggregationBucket.buildTraceCtxListProto(traces);
      default:
        throw new IllegalArgumentException(workType.name() + " not supported");
    }
  }
}
