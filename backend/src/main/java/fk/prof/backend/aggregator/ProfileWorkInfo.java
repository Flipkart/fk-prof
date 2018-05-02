package fk.prof.backend.aggregator;

import com.koloboke.collect.map.hash.HashObjIntMap;
import com.koloboke.collect.map.hash.HashObjIntMaps;
import fk.prof.aggregation.state.AggregationState;
import fk.prof.aggregation.state.AggregationStateEvent;
import fk.prof.aggregation.FinalizableBuilder;
import fk.prof.aggregation.model.FinalizedProfileWorkInfo;
import fk.prof.backend.util.ProtoUtil;
import fk.prof.backend.exception.AggregationFailure;
import fk.prof.idl.Recorder;
import fk.prof.idl.Recording;
import fk.prof.idl.WorkEntities;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

/**
 * Non getter methods in this class are not thread-safe
 * But, this should not be a concern because instances of this class are maintained for every work id
 * Updates to state associated with a work id happens in context of a request (profile or poll request)
 * Since we do not retry /profile requests from recorder, there can not be competing threads trying to update (except <pre>updateRecorderInfo</pre> method which is accessed on /poll request which can be retried)
 * If /profile requests are retried, this class will not be able to guarantee consistent state of members and state mutating methods like abortprofile, completeprofile, startprofile etc will need special handling
 */
public class ProfileWorkInfo extends FinalizableBuilder<FinalizedProfileWorkInfo> {
  private int recorderVersion;
  private AggregationState state = AggregationState.SCHEDULED;
  private LocalDateTime startedAt = null, endedAt = null;
  private int durationInSec;
  private Recorder.RecorderInfo recorderInfo;
  private final HashObjIntMap<String> traceCoverages = HashObjIntMaps.newUpdatableMap();
  private final HashObjIntMap<WorkEntities.WorkType> workTypeSamples = HashObjIntMaps.newUpdatableMap();

  public ProfileWorkInfo(int durationInSec) {
    this.durationInSec = durationInSec;
  }

  public void updateRecorderInfo(Recorder.RecorderInfo recorderInfo) {
    this.recorderInfo = recorderInfo;
  }

  public void updateWSEDetails(Recording.RecordingChunk recordingChunk) {
    for (Recording.TraceContext trace : recordingChunk.getIndexedData().getTraceCtxList()) {
      traceCoverages.put(trace.getTraceName(), trace.getCoveragePct());
    }
    for(int i = 0; i < recordingChunk.getWseCount(); i++) {
      Recording.Wse wse = recordingChunk.getWse(i);
      workTypeSamples.put(wse.getWType(), workTypeSamples.getOrDefault(wse.getWType(), 0) + getSampleCount(wse));
    }
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

  public AggregationState abandonProfileAsCorrupt() {
    if(!processStateEvent(AggregationStateEvent.ABANDON_PROFILE_AS_CORRUPT)) {
      throw new IllegalStateException(String.format("Invalid event %s for current state %s",
          AggregationStateEvent.ABANDON_PROFILE_AS_CORRUPT, state));
    }
    this.endedAt = LocalDateTime.now(Clock.systemUTC());
    return state;
  }

  public AggregationState abandonProfileAsIncomplete() {
    if(!processStateEvent(AggregationStateEvent.ABANDON_PROFILE_AS_INCOMPLETE)) {
      throw new IllegalStateException(String.format("Invalid event %s for current state %s",
          AggregationStateEvent.ABANDON_PROFILE_AS_INCOMPLETE, state));
    }
    this.endedAt = LocalDateTime.now(Clock.systemUTC());
    return state;
  }

  public AggregationState abortProfile() {
    processStateEvent(AggregationStateEvent.ABORT_PROFILE);
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

  private int getSampleCount(Recording.Wse wse) {
    switch(wse.getWType()) {
      case cpu_sample_work:
        return wse.getCpuSampleEntry().getStackSampleCount();
      case io_trace_work:
        return wse.getIoTraceEntry().getTracesCount();
      default:
        throw new AggregationFailure(String.format("Not supported worktype=%s for fetching sample count", wse.getWType()));
    }
  }

  @Override
  protected FinalizedProfileWorkInfo buildFinalizedEntity() {
    Map<WorkEntities.WorkType, Integer> mappedWorkTypeSamples = new HashMap<>();
    for(Map.Entry<WorkEntities.WorkType, Integer> entry: workTypeSamples.entrySet()) {
      WorkEntities.WorkType mappedWorkType = ProtoUtil.mapRecorderToAggregatorWorkType(entry.getKey());
      if(mappedWorkType == null) {
        throw new AggregationFailure(String.format("Unable to map recorder work_type=%s to corresponding aggregation work_type", entry.getKey()), true);
      }
      mappedWorkTypeSamples.put(mappedWorkType, entry.getValue());
    }

    return new FinalizedProfileWorkInfo(
        recorderVersion,
        recorderInfo == null ? null : ProtoUtil.mapToAggregatorRecorderDetails(recorderInfo),
        state,
        startedAt,
        endedAt,
        durationInSec,
        traceCoverages,
        mappedWorkTypeSamples
    );
  }
}
