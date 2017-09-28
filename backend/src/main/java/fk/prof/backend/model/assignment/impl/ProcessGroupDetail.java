package fk.prof.backend.model.assignment.impl;

import com.google.common.base.Preconditions;
import fk.prof.backend.model.assignment.*;
import io.vertx.core.logging.Logger;
import io.vertx.core.logging.LoggerFactory;
import recording.Recorder;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

public class ProcessGroupDetail implements ProcessGroupContextForScheduling, ProcessGroupContextForPolling {
  private static final Logger logger = LoggerFactory.getLogger(ProcessGroupDetail.class);

  private final Recorder.ProcessGroup processGroup;
  private final int thresholdForDefunctRecorderInSecs;
  private final Map<RecorderIdentifier, RecorderDetail> recorderLookup = new ConcurrentHashMap<>();
  private volatile WorkAssignmentSchedule workAssignmentSchedule = null;

  public ProcessGroupDetail(Recorder.ProcessGroup processGroup, int thresholdForDefunctRecorderInSecs) {
    this.processGroup = Preconditions.checkNotNull(processGroup);
    this.thresholdForDefunctRecorderInSecs = thresholdForDefunctRecorderInSecs;
  }

  @Override
  public Recorder.ProcessGroup getProcessGroup() {
    return processGroup;
  }

  /**
   * Called on http event loop
   * TODO: Liable for refactoring to implement stickiness behaviour in future
   * Returns null if poll is refused by recorder detail (if the poll request is obsolete)
   * Returns null if no work assignment schedule is present
   * Returns null if recorder cannot accept work because it is defunct or already doing some other work
   * Returns null if no work assignments are left or none is ready yet to be handed out
   * Returns null if recorder has been assigned work already
   * Returns work assignment otherwise
   * @return
   */
  @Override
  public Recorder.WorkAssignment getWorkAssignment(Recorder.PollReq pollReq) {
    RecorderIdentifier recorderIdentifier = RecorderIdentifier.from(pollReq.getRecorderInfo());
    //TODO: clean-up job to remove recorders from lookup which have been defunct for a long long time
    //The above is only a problem if the same backend stays associated with recorder for a long time, otherwise ProcessGroupDetail will be GC-eligible on de-association
    RecorderDetail recorderDetail = this.recorderLookup.computeIfAbsent(recorderIdentifier,
        key -> new RecorderDetail(key, thresholdForDefunctRecorderInSecs));

    boolean pollReceived = recorderDetail.receivePoll(pollReq);
    boolean canAcceptWork = recorderDetail.canAcceptWork();
    if (pollReceived
        && workAssignmentSchedule != null
        && canAcceptWork) {

      try {
        return workAssignmentSchedule.getNextWorkAssignment(recorderIdentifier);
      } catch (NullPointerException ex) {
        // Do nothing in this scenario. This exception can happen when:
        // t0=above "if conditional" is evaluated successfully
        // t1=aggregation window start timer called in backend daemon but by that time work prototype was not fetched from leader,
        // or some other error occurred in aggregation window switcher causing reset to be invoked in aggregation window planner
        // t2=workAssignmentSchedule set to null because of events at t1
        // t3=above "try block" executed resulting in null pointer exception
      }

    } else {
      logger.info("Recorder " + recorderIdentifier + " not sent assignment, updated=" + pollReceived + ", accept_work=" + canAcceptWork);
    }
    return null;
  }

  //Called by backend daemon thread
  @Override
  public void updateWorkAssignmentSchedule(WorkAssignmentSchedule workAssignmentSchedule) {
    this.workAssignmentSchedule = workAssignmentSchedule;
  }

  //Called by backend daemon thread
  @Override
  public int getRecorderTargetCountToMeetCoverage(int coveragePct) {
    final List<RecorderDetail> availableRecorders = this.recorderLookup.values().stream()
        .filter(recorderDetail -> !recorderDetail.isDefunct())
        .collect(Collectors.toList());
    return (int)((coveragePct * availableRecorders.size()) / 100.0f);
  }

  @Override
  public boolean equals(Object o) {
    if (o == this) {
      return true;
    }
    if (!(o instanceof ProcessGroupDetail)) {
      return false;
    }

    ProcessGroupDetail other = (ProcessGroupDetail) o;
    return this.processGroup.equals(other.processGroup);
  }

  @Override
  public int hashCode() {
    final int PRIME = 31;
    int result = 1;
    result = result * PRIME + this.processGroup.hashCode();
    return result;
  }
}
