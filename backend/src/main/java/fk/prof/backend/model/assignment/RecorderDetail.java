package fk.prof.backend.model.assignment;

import com.codahale.metrics.Meter;
import com.codahale.metrics.MetricRegistry;
import com.codahale.metrics.SharedMetricRegistries;
import com.google.common.base.Preconditions;
import fk.prof.backend.ConfigManager;
import fk.prof.metrics.MetricName;
import fk.prof.metrics.RecorderTag;
import recording.Recorder;

public class RecorderDetail {
  private static final double NANOSECONDS_IN_SECOND = Math.pow(10, 9);

  private final RecorderIdentifier recorderIdentifier;
  private final long thresholdForDefunctRecorderInNanos;

  private long lastReportedTick = 0;
  private Long lastReportedTime = null;
  private Recorder.WorkResponse currentWorkResponse;

  private final MetricRegistry metricRegistry = SharedMetricRegistries.getOrCreate(ConfigManager.METRIC_REGISTRY);
  private final Meter mtrPollReset, mtrPollStale, mtrPollComplete;

  public RecorderDetail(RecorderIdentifier recorderIdentifier, int thresholdForDefunctRecorderInSecs) {
    this.recorderIdentifier = Preconditions.checkNotNull(recorderIdentifier);
    this.thresholdForDefunctRecorderInNanos = (long)(thresholdForDefunctRecorderInSecs * NANOSECONDS_IN_SECOND);

    String recorderStr = recorderIdentifier.metricTag().toString();
    this.mtrPollComplete = metricRegistry.meter(MetricRegistry.name(MetricName.Recorder_Poll_Complete.get(), recorderStr));
    this.mtrPollReset = metricRegistry.meter(MetricRegistry.name(MetricName.Recorder_Poll_Reset.get(), recorderStr));
    this.mtrPollStale = metricRegistry.meter(MetricRegistry.name(MetricName.Recorder_Poll_Stale.get(), recorderStr));
  }

  public synchronized boolean receivePoll(Recorder.PollReq pollReq) {
    long currTick = pollReq.getRecorderInfo().getRecorderTick();
    boolean timeUpdated = false;
    //NOTE: this is assuming that curr tick is always unsigned and does not wrap around.
    //here curr tick = 0 has a special meaning to reset the tick.
    if(currTick == 0 || this.lastReportedTick <= currTick) {
      this.lastReportedTick = currTick;
      if(currTick > 0) {
        this.lastReportedTime = System.nanoTime();
        timeUpdated = true;
      } else {
        mtrPollReset.mark();
      }
      this.currentWorkResponse = pollReq.getWorkLastIssued();
      mtrPollComplete.mark();
    } else {
      mtrPollStale.mark();
    }
    return timeUpdated;
  }

  public boolean isDefunct() {
    return lastReportedTime == null ||
        ((System.nanoTime() - lastReportedTime) > thresholdForDefunctRecorderInNanos);
  }

  public boolean canAcceptWork() {
    return !isDefunct() &&
        (currentWorkResponse.getWorkId() == 0 || Recorder.WorkResponse.WorkState.complete.equals(currentWorkResponse.getWorkState()));
  }

  @Override
  public boolean equals(Object o) {
    if (o == this) {
      return true;
    }
    if (!(o instanceof RecorderDetail)) {
      return false;
    }

    RecorderDetail other = (RecorderDetail) o;
    return this.recorderIdentifier.equals(other.recorderIdentifier);
  }

  @Override
  public int hashCode() {
    final int PRIME = 31;
    int result = 1;
    result = result * PRIME + this.recorderIdentifier.hashCode();
    return result;
  }
}