package fk.prof.backend.model.association;

import com.codahale.metrics.Meter;
import com.codahale.metrics.MetricRegistry;
import com.codahale.metrics.SharedMetricRegistries;
import fk.prof.backend.ConfigManager;
import fk.prof.metrics.BackendTag;
import fk.prof.metrics.MetricName;
import recording.Recorder;

import java.io.IOException;
import java.util.HashSet;
import java.util.Set;

public class BackendDetail {
  private static final double NANOSECONDS_IN_SECOND = Math.pow(10, 9);

  private final Recorder.AssignedBackend backend;
  private final long thresholdForDefunctInNanoSeconds;
  private final Set<Recorder.ProcessGroup> associatedProcessGroups;

  private long lastReportedTick = 0;
  //lastReportedTime is null unless backend reports stable load more than once (i.e. currTick reported by backend > 0, since in first load report currTick = 0)
  private volatile Long lastReportedTime;
  private float lastReportedLoad;

  private final MetricRegistry metricRegistry = SharedMetricRegistries.getOrCreate(ConfigManager.METRIC_REGISTRY);
  private final Meter mtrLoadReset, mtrLoadStale, mtrLoadComplete;

  public BackendDetail(Recorder.AssignedBackend backend, int loadReportIntervalInSeconds, int loadMissTolerance)
      throws IOException {
    this(backend, loadReportIntervalInSeconds, loadMissTolerance, new HashSet<>());
  }

  public BackendDetail(Recorder.AssignedBackend backend, int loadReportIntervalInSeconds, int loadMissTolerance, Set<Recorder.ProcessGroup> associatedProcessGroups)
      throws IOException {
    if(backend == null) {
      throw new IllegalArgumentException("Backend cannot be null");
    }
    this.backend = backend;
    this.thresholdForDefunctInNanoSeconds = (long)(loadReportIntervalInSeconds * (loadMissTolerance + 1) * NANOSECONDS_IN_SECOND);
    this.associatedProcessGroups = associatedProcessGroups == null ? new HashSet<>() : associatedProcessGroups;

    String backendTagStr = new BackendTag(backend.getHost(), backend.getPort()).toString();
    this.mtrLoadComplete = metricRegistry.meter(MetricRegistry.name(MetricName.Backend_LoadReport_Complete.get(), backendTagStr));
    this.mtrLoadReset = metricRegistry.meter(MetricRegistry.name(MetricName.Backend_LoadReport_Reset.get(), backendTagStr));
    this.mtrLoadStale = metricRegistry.meter(MetricRegistry.name(MetricName.Backend_LoadReport_Stale.get(), backendTagStr));
  }

  /**
   * Updates the load for this backend
   * NOTE: If backend dies and comes back up, it will send currTick=0 in the first request so as to override previous reported state, other than last reported time
   * @param load
   * @param currTick
   * @return true if successfully updated load report time, false otherwise
   */
  public synchronized boolean reportLoad(float load, long currTick) {
    boolean timeUpdated = false;
    if(currTick == 0 || this.lastReportedTick < currTick) {
      this.lastReportedTick = currTick;
      if(currTick > 0) {
        this.lastReportedTime = System.nanoTime();
        timeUpdated = true;
      } else {
        mtrLoadReset.mark();
      }
      this.lastReportedLoad = load;
      mtrLoadComplete.mark();
    } else {
      mtrLoadStale.mark();
    }
    return timeUpdated;
  }

  public void associateProcessGroup(Recorder.ProcessGroup processGroup) {
    this.associatedProcessGroups.add(processGroup);
  }

  public void deAssociateProcessGroup(Recorder.ProcessGroup processGroup) {
    this.associatedProcessGroups.remove(processGroup);
  }

  /**
   * Indicates backend is defunct if no load has been reported ever or the last load report time exceeds threshold
   * @return
   */
  public boolean isDefunct() {
    return lastReportedTime == null ||
        ((System.nanoTime() - lastReportedTime) > thresholdForDefunctInNanoSeconds);
  }

  public Recorder.AssignedBackend getBackend() {
    return this.backend;
  }

  public Set<Recorder.ProcessGroup> getAssociatedProcessGroups() {
    return this.associatedProcessGroups;
  }

  @Override
  public boolean equals(Object o) {
    if (o == this) {
      return true;
    }
    if (!(o instanceof BackendDetail)) {
      return false;
    }

    BackendDetail other = (BackendDetail) o;
    return this.backend.equals(other.backend);
  }

  @Override
  public int hashCode() {
    final int PRIME = 31;
    int result = 1;
    result = result * PRIME + this.backend.hashCode();
    return result;
  }

  public Recorder.ProcessGroups buildProcessGroupsProto() {
    return Recorder.ProcessGroups.newBuilder().addAllProcessGroup(associatedProcessGroups).build();
  }

}
