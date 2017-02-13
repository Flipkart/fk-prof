package fk.prof.backend.aggregator;

import com.koloboke.collect.map.hash.HashObjIntMap;
import com.koloboke.collect.map.hash.HashObjIntMaps;
import fk.prof.aggregation.proto.AggregatedProfileModel;
import fk.prof.aggregation.state.AggregationState;
import fk.prof.aggregation.state.AggregationStateEvent;
import fk.prof.aggregation.FinalizableBuilder;
import fk.prof.aggregation.finalized.FinalizedProfileWorkInfo;
import fk.prof.backend.util.ProtoUtil;
import fk.prof.backend.exception.AggregationFailure;
import recording.Recorder;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

/**
 * Non getter methods in this class are not thread-safe
 * But, this should not be a concern because instances of this class are maintained for every work id
 * Updates to state associated with a work id happens in context of a request (single thread)
 */
public class ProfileWorkInfo extends FinalizableBuilder<FinalizedProfileWorkInfo> {
  private int recorderVersion;
  private AggregationState state = AggregationState.SCHEDULED;
  private LocalDateTime startedAt = null, endedAt = null;
  private final HashObjIntMap<String> traceCoverages = HashObjIntMaps.newUpdatableMap();
  private final HashObjIntMap<Recorder.WorkType> workTypeSamples = HashObjIntMaps.newUpdatableMap();

  public void updateWSESpecificDetails(Recorder.Wse wse) {
    for (Recorder.TraceContext trace : wse.getIndexedData().getTraceCtxList()) {
      traceCoverages.put(trace.getTraceName(), trace.getCoveragePct());
    }
    workTypeSamples.put(wse.getWType(), workTypeSamples.getOrDefault(wse.getWType(), 0) + getSampleCount(wse));
  }

  public AggregationState startProfile(int recorderVersion, LocalDateTime startedAt) {
    if(!processStateEvent(AggregationStateEvent.START_PROFILE)) {
      throw new IllegalStateException(String.format("Invalid event %s for current state %s",
          AggregationStateEvent.START_PROFILE, state));
    }
    this.recorderVersion = recorderVersion;
    this.startedAt = startedAt;
    return state;
  }

  public AggregationState completeProfile() {
    if(!processStateEvent(AggregationStateEvent.COMPLETE_PROFILE)) {
      throw new IllegalStateException(String.format("Invalid event %s for current state %s",
          AggregationStateEvent.COMPLETE_PROFILE, state));
    }
    this.endedAt = LocalDateTime.now(Clock.systemUTC());
    return state;
  }

  public AggregationState abandonProfile() {
    if(!processStateEvent(AggregationStateEvent.ABANDON_PROFILE)) {
      throw new IllegalStateException(String.format("Invalid event %s for current state %s",
          AggregationStateEvent.ABANDON_PROFILE, state));
    }
    this.endedAt = LocalDateTime.now(Clock.systemUTC());
    return state;
  }

  public AggregationState abortProfile() {
    if(!processStateEvent(AggregationStateEvent.ABORT_PROFILE)) {
      throw new IllegalStateException(String.format("Invalid event %s for current state %s",
          AggregationStateEvent.ABORT_PROFILE, state));
    }
    return state;
  }

  public boolean hasProfileBeenStarted() {
    return state.isOngoing();
  }

  private boolean processStateEvent(AggregationStateEvent stateEvent) {
    AggregationState newState = state.process(stateEvent);
    if(newState.equals(state)) {
      return false;
    } else {
      state = newState;
      return true;
    }
  }

  private int getSampleCount(Recorder.Wse wse) {
    switch(wse.getWType()) {
      case cpu_sample_work:
        return wse.getCpuSampleEntry().getStackSampleCount();
      default:
        throw new AggregationFailure(String.format("Not supported worktype=%s for fetching sample count", wse.getWType()));
    }
  }

  @Override
  protected FinalizedProfileWorkInfo buildFinalizedEntity() {
    Map<AggregatedProfileModel.WorkType, Integer> mappedWorkTypeSamples = new HashMap<>();
    for(Map.Entry<Recorder.WorkType, Integer> entry: workTypeSamples.entrySet()) {
      AggregatedProfileModel.WorkType mappedWorkType = ProtoUtil.mapRecorderToAggregatorWorkType(entry.getKey());
      if(mappedWorkType == null) {
        throw new AggregationFailure(String.format("Unable to map recorder work_type=%s to corresponding aggregation work_type", entry.getKey()), true);
      }
      mappedWorkTypeSamples.put(mappedWorkType, entry.getValue());
    }

    return new FinalizedProfileWorkInfo(
        recorderVersion,
        state,
        startedAt,
        endedAt,
        traceCoverages,
        mappedWorkTypeSamples
    );
  }
}
