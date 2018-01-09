package fk.prof.backend.aggregator;

import com.codahale.metrics.Meter;
import com.codahale.metrics.MetricRegistry;
import com.codahale.metrics.SharedMetricRegistries;
import fk.prof.aggregation.FinalizableBuilder;
import fk.prof.aggregation.model.FinalizedAggregationWindow;
import fk.prof.aggregation.model.FinalizedProfileWorkInfo;
import fk.prof.aggregation.state.AggregationState;
import fk.prof.backend.ConfigManager;
import fk.prof.backend.exception.AggregationFailure;
import fk.prof.backend.model.aggregation.ActiveAggregationWindows;
import fk.prof.backend.model.profile.RecordedProfileIndexes;
import fk.prof.metrics.MetricName;
import fk.prof.metrics.ProcessGroupTag;
import recording.Recorder;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

public class AggregationWindow extends FinalizableBuilder<FinalizedAggregationWindow> {
  private final String appId;
  private final String clusterId;
  private final String procId;
  private LocalDateTime start = null, endedAt = null;
  private final int durationInSecs;

  private final Map<Long, ProfileWorkInfo> workInfoLookup;
  private final CpuSamplingAggregationBucket cpuSamplingAggregationBucket = new CpuSamplingAggregationBucket();

  private final ProcessGroupTag processGroupTag;
  private MetricRegistry metricRegistry = SharedMetricRegistries.getOrCreate(ConfigManager.METRIC_REGISTRY);
  private final Meter mtrStateTransitionFailures, mtrCSAggrFailures;

  public AggregationWindow(String appId, String clusterId, String procId,
                           LocalDateTime start, int durationInSecs, long[] workIds, int workDurationInSec) {
    this.appId = appId;
    this.clusterId = clusterId;
    this.procId = procId;
    this.start = start;
    this.durationInSecs = durationInSecs;

    Map<Long, ProfileWorkInfo> workInfoModifiableLookup = new HashMap<>();
    for (int i = 0; i < workIds.length; i++) {
      workInfoModifiableLookup.put(workIds[i], new ProfileWorkInfo(workDurationInSec));
    }
    this.workInfoLookup = Collections.unmodifiableMap(workInfoModifiableLookup);

    this.processGroupTag = new ProcessGroupTag(appId, clusterId, procId);
    String processGroupTagStr = this.processGroupTag.toString();
    this.mtrStateTransitionFailures = metricRegistry.meter(MetricRegistry.name(MetricName.AW_State_Transition_Failure.get(), processGroupTagStr));
    this.mtrCSAggrFailures = metricRegistry.meter(MetricRegistry.name(MetricName.AW_CpuSampling_Aggregation_Failure.get(), processGroupTagStr));
  }

  public AggregationState startProfile(long workId, int recorderVersion, LocalDateTime startedAt) throws AggregationFailure {
    ensureEntityIsWriteable();

    try {
      ProfileWorkInfo workInfo = this.workInfoLookup.get(workId);
      return workInfo.startProfile(recorderVersion, startedAt);
    } catch (IllegalStateException ex) {
      mtrStateTransitionFailures.mark();
      throw new AggregationFailure(String.format("Error starting profile for work_id=%d, recorder_version=%d, startedAt=%s",
          workId, recorderVersion, startedAt.toString()), ex);
    }
  }

  public AggregationState completeProfile(long workId) throws AggregationFailure {
    ensureEntityIsWriteable();

    try {
      ProfileWorkInfo workInfo = this.workInfoLookup.get(workId);
      return workInfo.completeProfile();
    } catch (IllegalStateException ex) {
      mtrStateTransitionFailures.mark();
      throw new AggregationFailure(String.format("Error completing profile for work_id=%d", workId), ex);
    }
  }

  public AggregationState abandonProfileAsCorrupt(long workId) throws AggregationFailure {
    ensureEntityIsWriteable();

    try {
      ProfileWorkInfo workInfo = this.workInfoLookup.get(workId);
      return workInfo.abandonProfileAsCorrupt();
    } catch (IllegalStateException ex) {
      mtrStateTransitionFailures.mark();
      throw new AggregationFailure(String.format("Error abandoning corrupt profile for work_id=%d", workId), ex);
    }
  }

  public AggregationState abandonProfileAsIncomplete(long workId) throws AggregationFailure {
    ensureEntityIsWriteable();

    try {
      ProfileWorkInfo workInfo = this.workInfoLookup.get(workId);
      return workInfo.abandonProfileAsIncomplete();
    } catch (IllegalStateException ex) {
      mtrStateTransitionFailures.mark();
      throw new AggregationFailure(String.format("Error abandoning incomplete profile for work_id=%d", workId), ex);
    }
  }

  /**
   * Called by backend daemon thread
   * Does following tasks required to expire aggregation window:
   * > Marks status of ongoing profiles as aborted
   * > De associates assigned work with this aggregation window
   * > Updates ended at time for aggregation window
   * > Finalizes the window
   * @param activeAggregationWindows
   * @return finalized aggregation window
   */
  public FinalizedAggregationWindow expireWindow(ActiveAggregationWindows activeAggregationWindows) {
    ensureEntityIsWriteable();

    abortOngoingProfiles();
    long[] workIds = this.workInfoLookup.keySet().stream().mapToLong(Long::longValue).toArray();
    activeAggregationWindows.deAssociateAggregationWindow(workIds);
    this.endedAt = LocalDateTime.now(Clock.systemUTC());
    return finalizeEntity();
  }

  /**
   * Aborts all in-flight profiles. Should be called when aggregation window expires
   */
  private void abortOngoingProfiles() {
    ensureEntityIsWriteable();
    try {
      for (Map.Entry<Long, ProfileWorkInfo> entry : workInfoLookup.entrySet()) {
        entry.getValue().abortProfile();
      }
    } catch (IllegalStateException ex) {
      //Ignore when not able to mark profiles as aborted. This is because they are already in a terminal state
    }
  }

  public void aggregate(Recorder.Wse wse, RecordedProfileIndexes indexes) throws AggregationFailure {
    ensureEntityIsWriteable();

    switch (wse.getWType()) {
      case cpu_sample_work:
        Recorder.StackSampleWse stackSampleWse = wse.getCpuSampleEntry();
        if (stackSampleWse == null) {
          throw new AggregationFailure(String.format("work type=%s did not have associated samples", wse.getWType()));
        }
        cpuSamplingAggregationBucket.aggregate(stackSampleWse, indexes, mtrCSAggrFailures);
        break;
      default:
        throw new AggregationFailure(String.format("Aggregation not supported for work type=%s", wse.getWType()));
    }
  }

  public void updateWorkInfo(long workId, Recorder.RecordingChunk recordingChunk) {
    ensureEntityIsWriteable();

    ProfileWorkInfo workInfo = workInfoLookup.get(workId);
    if (workInfo == null) {
      throw new AggregationFailure(String.format("Cannot find work id=%d association in the aggregation window", workId), true);
    }

    workInfo.updateWSEDetails(recordingChunk);
  }

  public void updateRecorderInfo(long workId, Recorder.RecorderInfo recorderInfo) {
    ensureEntityIsWriteable();

    ProfileWorkInfo workInfo = workInfoLookup.get(workId);
    if (workInfo == null) {
      throw new AggregationFailure(String.format("Cannot find work id=%d association in the aggregation window", workId), true);
    }
    workInfo.updateRecorderInfo(recorderInfo);
  }

  public ProcessGroupTag getProcessGroupTag() {
    return processGroupTag;
  }


  @Override
  public String toString() {
    return "app=" + appId +
        ", cluster=" + clusterId +
        ", proc=" + procId +
        ", start=" + start +
        ", end=" + endedAt;
  }

  @Override
  protected FinalizedAggregationWindow buildFinalizedEntity() {
    Map<Long, FinalizedProfileWorkInfo> finalizedWorkInfoLookup = workInfoLookup.entrySet()
        .stream()
        .collect(Collectors.toMap(Map.Entry::getKey,
            entry -> entry.getValue().finalizeEntity()));

    return new FinalizedAggregationWindow(
        appId, clusterId, procId, start, endedAt, durationInSecs,
        finalizedWorkInfoLookup,
        cpuSamplingAggregationBucket.finalizeEntity()
    );
  }
}
