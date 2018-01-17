package fk.prof.aggregation.model;

import fk.prof.idl.Profile.*;
import fk.prof.idl.Profile.ProfileWorkInfo.*;
import fk.prof.aggregation.state.AggregationState;
import fk.prof.idl.WorkEntities;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.Set;

public class FinalizedProfileWorkInfo {
  private final int recorderVersion;
  private final RecorderDetails recorderDetails;
  private final AggregationState state;
  private final LocalDateTime startedAt;
  private final LocalDateTime endedAt;
  private final int durationInSec;
  private final Map<String, Integer> traceCoverages;
  private final Map<WorkEntities.WorkType, Integer> samples;

  public FinalizedProfileWorkInfo(int recorderVersion,
                                  RecorderDetails recorderDetails,
                                  AggregationState state,
                                  LocalDateTime startedAt,
                                  LocalDateTime endedAt,
                                  int durationInSec,
                                  Map<String, Integer> traceCoverages,
                                  Map<WorkEntities.WorkType, Integer> samples) {
    this.recorderVersion = recorderVersion;
    this.recorderDetails = recorderDetails;
    this.state = state;
    this.startedAt = startedAt;
    this.endedAt = endedAt;
    this.durationInSec = durationInSec;
    this.traceCoverages = traceCoverages;
    this.samples = samples;
  }

  //NOTE: Exposing this to make the class more testable since startedAt has generated value
  public LocalDateTime getStartedAt() {
    return startedAt;
  }

  //NOTE: Exposing this to make the class more testable since endedAt has generated value
  public LocalDateTime getEndedAt() {
    return endedAt;
  }

  public AggregationState getState() {
    return state;
  }

  @Override
  public boolean equals(Object o) {
    if (o == this) {
      return true;
    }
    if (!(o instanceof FinalizedProfileWorkInfo)) {
      return false;
    }

    FinalizedProfileWorkInfo other = (FinalizedProfileWorkInfo) o;
    return this.recorderVersion == other.recorderVersion
        && this.state.equals(other.state)
        && this.durationInSec == other.durationInSec
        && (this.startedAt == null ? other.startedAt == null : this.startedAt.equals(other.startedAt))
        && (this.endedAt == null ? other.endedAt == null : this.endedAt.equals(other.endedAt))
        && (this.traceCoverages == null ? other.traceCoverages == null : this.traceCoverages.equals(other.traceCoverages))
        && (this.samples == null ? other.samples == null : this.samples.equals(other.samples))
        && (this.recorderDetails == null ? other.recorderDetails == null : this.recorderDetails.equals(other.recorderDetails));
  }

  protected Set<String> getRecordedTraces() {
    return traceCoverages.keySet();
  }

  protected ProfileWorkInfo buildProfileWorkInfoProto(WorkEntities.WorkType workType, LocalDateTime aggregationStartTime, TraceCtxNames traces) {
    if(workType == null || (samples != null && samples.containsKey(workType))) {
      ProfileWorkInfo.Builder builder = ProfileWorkInfo.newBuilder()
              .setRecorderVersion(recorderVersion)
              .setStatus(toAggregationStatusProto(state))
              .setDuration(durationInSec);

      if(startedAt != null) {
        builder.setStartOffset((int) aggregationStartTime.until(startedAt, ChronoUnit.SECONDS));
      }

      if(recorderDetails != null) {
        builder.setRecorderDetails(recorderDetails);
      }

      if(workType != null) {
        builder.addSampleCount(ProfileWorkInfo.SampleCount.newBuilder().setWorkType(workType).setSampleCount(samples.getOrDefault(workType, 0)));
      }
      else {
        // add all the sample counts
        for(Map.Entry<WorkEntities.WorkType, Integer> wsSample : samples.entrySet()) {
          builder.addSampleCount(ProfileWorkInfo.SampleCount.newBuilder().setWorkType(wsSample.getKey()).setSampleCount(wsSample.getValue()));
        }
      }

      if(traceCoverages != null) {
        int index = 0;
        for (String traceName : traces.getNameList()) {
          Integer cvrg = traceCoverages.getOrDefault(traceName, null);
          if (cvrg != null) {
            builder.addTraceCoverageMap(TraceCtxToCoveragePctMap.newBuilder().setTraceCtxIdx(index).setCoveragePct(cvrg));
          }
          ++index;
        }
      }

      return builder.build();
    }
    return null;
  }

  protected ProfileWorkInfo buildProfileWorkInfoProto(LocalDateTime aggregationStartTime, TraceCtxNames traces) {
    return buildProfileWorkInfoProto(null, aggregationStartTime, traces);
  }

  private AggregationStatus toAggregationStatusProto(AggregationState status) {
    switch (status) {
      case ABORTED: return AggregationStatus.Aborted;
      case COMPLETED: return AggregationStatus.Completed;
      case ONGOING: throw new IllegalArgumentException("ONGOING state is not a terminal state");
      case ONGOING_PARTIAL: throw new IllegalArgumentException("ONGOING_PARTIAL state is not a terminal state");
      case CORRUPT: return AggregationStatus.Corrupt;
      case INCOMPLETE: return AggregationStatus.Incomplete;
      case RETRIED: return AggregationStatus.Retried;
      case SCHEDULED: return AggregationStatus.Scheduled;
      default: throw new IllegalArgumentException(state.name() + " state is not supported");
    }
  }
}