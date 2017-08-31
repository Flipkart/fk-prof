package fk.prof.backend.model.assignment;

import com.google.common.base.Preconditions;
import fk.prof.aggregation.model.FinalizedAggregationWindow;
import fk.prof.backend.model.aggregation.ActiveAggregationWindows;
import fk.prof.backend.model.slot.WorkSlotPool;
import fk.prof.backend.proto.BackendDTO;
import fk.prof.backend.util.proto.RecorderProtoUtil;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import recording.Recorder;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Function;

public class AggregationWindowPlannerStore {
  private final Map<Recorder.ProcessGroup, AggregationWindowPlanner> lookup = new HashMap<>();

  private final Vertx vertx;
  private final int backendId;
  private final ActiveAggregationWindows activeAggregationWindows;
  private final WorkSlotPool workSlotPool;
  private final PolicyRequestor policyForBackendRequestor;
  private final Consumer<FinalizedAggregationWindow> aggregationWindowWriter;
  private final WorkAssignmentScheduleBootstrapConfig workAssignmentScheduleBootstrapConfig;
  private final int aggregationWindowDurationInSecs;
  private final int policyRefreshBufferInSecs;
  private final int thresholdForDefunctRecorderInSecs;

  public AggregationWindowPlannerStore(Vertx vertx,
                                       int backendId,
                                       int windowDurationInSecs,
                                       int windowEndToleranceInSecs,
                                       int policyRefreshBufferInSecs,
                                       int thresholdForDefunctRecorderInSecs,
                                       int schedulingBufferInSecs,
                                       int maxAcceptableDelayForWorkAssignmentInSecs,
                                       WorkSlotPool workSlotPool,
                                       ActiveAggregationWindows activeAggregationWindows,
                                       PolicyRequestor policyForBackendRequestor,
                                       Consumer<FinalizedAggregationWindow> aggregationWindowWriter) {
    this.vertx = Preconditions.checkNotNull(vertx);
    this.backendId = backendId;
    this.policyForBackendRequestor = Preconditions.checkNotNull(policyForBackendRequestor);
    this.aggregationWindowWriter = Preconditions.checkNotNull(aggregationWindowWriter);
    this.activeAggregationWindows = Preconditions.checkNotNull(activeAggregationWindows);
    this.workSlotPool = Preconditions.checkNotNull(workSlotPool);
    this.workAssignmentScheduleBootstrapConfig = new WorkAssignmentScheduleBootstrapConfig(windowDurationInSecs,
        windowEndToleranceInSecs,
        schedulingBufferInSecs,
        maxAcceptableDelayForWorkAssignmentInSecs);
    this.aggregationWindowDurationInSecs = windowDurationInSecs;
    this.policyRefreshBufferInSecs = policyRefreshBufferInSecs;
    this.thresholdForDefunctRecorderInSecs = thresholdForDefunctRecorderInSecs;
  }

  /**
   * @param processGroupContextForScheduling
   * @return true if aggregation window planner was not associated earlier. false otherwise
   */
  public boolean associateAggregationWindowPlannerIfAbsent(ProcessGroupContextForScheduling processGroupContextForScheduling) {
    if (!this.lookup.containsKey(processGroupContextForScheduling.getProcessGroup())) {
      AggregationWindowPlanner aggregationWindowPlanner = new AggregationWindowPlanner(
          vertx,
          backendId,
          aggregationWindowDurationInSecs,
          policyRefreshBufferInSecs,
          thresholdForDefunctRecorderInSecs,
          workAssignmentScheduleBootstrapConfig,
          workSlotPool,
          processGroupContextForScheduling,
          activeAggregationWindows,
          policyForBackendRequestor,
          aggregationWindowWriter);
      this.lookup.put(processGroupContextForScheduling.getProcessGroup(), aggregationWindowPlanner);
      return true;
    }
    return false;
  }

  public void deAssociateAggregationWindowPlanner(Recorder.ProcessGroup processGroup)
    throws IllegalStateException {
    AggregationWindowPlanner aggregationWindowPlanner = this.lookup.remove(processGroup);
    if(aggregationWindowPlanner == null) {
      throw new IllegalStateException("Unexpected state. No aggregation window planner associated with process_group=" + RecorderProtoUtil.processGroupCompactRepr(processGroup));
    }
    aggregationWindowPlanner.close();
  }
}
