package fk.prof.backend.model.assignment;

import com.codahale.metrics.Counter;
import com.codahale.metrics.Meter;
import com.codahale.metrics.MetricRegistry;
import com.codahale.metrics.SharedMetricRegistries;
import com.google.common.base.Preconditions;
import fk.prof.backend.ConfigManager;
import fk.prof.idl.WorkEntities;
import fk.prof.metrics.MetricName;
import fk.prof.metrics.ProcessGroupTag;
import io.vertx.core.logging.Logger;
import io.vertx.core.logging.LoggerFactory;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Collectors;

/**
 * vars prefixed with d are duration in seconds
 * vars prefixed with n are duration in nanos
 * vars prefixed with c are counters
 */
public class WorkAssignmentSchedule {
  private final static Logger logger = LoggerFactory.getLogger(WorkAssignmentSchedule.class);
  private final static long NANOS_IN_SEC = (long)Math.pow(10, 9);

  private final long nRef;
  private final int dMinDelay;
  private final int dMaxDelay;
  private final int cMaxParallel;

  private final Set<WorkEntities.WorkType> workTypes;
  private final PriorityQueue<ScheduleEntry> entries = new PriorityQueue<>();
  private Map<RecorderIdentifier, ScheduleEntry> assignedSchedule = new HashMap<>();
  private final ReentrantLock entriesLock = new ReentrantLock();

  private final MetricRegistry metricRegistry = SharedMetricRegistries.getOrCreate(ConfigManager.METRIC_REGISTRY);
  private final Counter ctrEntriesLockTimeout, ctrEntriesLockInterrupt, ctrAssignmentFetchFail;
  private final Meter mtrSchedulingMiss;

  public WorkAssignmentSchedule(WorkAssignmentScheduleBootstrapConfig bootstrapConfig,
                                WorkEntities.WorkAssignment.Builder[] workAssignmentBuilders,
                                int dProfileLen,
                                ProcessGroupTag processGroupTag) {
    this.workTypes = workAssignmentBuilders[0].getWorkList() == null
        ? new HashSet<>()
        : workAssignmentBuilders[0].getWorkList().stream().map(WorkEntities.Work::getWType).collect(Collectors.toSet());
    this.nRef = System.nanoTime();
    int cRequired = workAssignmentBuilders.length;

    // breathing space at the start, this will usually be a lower value than window tolerance
    int dWinStartPad = bootstrapConfig.getSchedulingBufferInSecs() * 2;
    //actual time span for which schedule is calculated
    int dEffectiveWinLen = bootstrapConfig.getWindowDurationInSecs() - bootstrapConfig.getWindowEndToleranceInSecs() - dWinStartPad;
    int dEffectiveProfileLen = dProfileLen + bootstrapConfig.getSchedulingBufferInSecs();
    int cMaxSerial = dEffectiveWinLen / dEffectiveProfileLen;

    if(cMaxSerial == 0) {
      Counter ctrImpossible = metricRegistry.counter(MetricRegistry.name(MetricName.WA_Scheduling_Impossible.get(), processGroupTag.toString()));
      ctrImpossible.inc();
      throw new IllegalArgumentException("Not possible to schedule any work assignment because effective length of single profile=" + dEffectiveProfileLen +
          " is greater than effective aggregation window length=" + dEffectiveWinLen);
    }

    this.dMinDelay = bootstrapConfig.getMinAcceptableDelayForWorkAssignmentInSecs();
    this.dMaxDelay = bootstrapConfig.getMaxAcceptableDelayForWorkAssignmentInSecs();
    this.cMaxParallel = (int)Math.ceil((double)cRequired / cMaxSerial);

    for(int i = 0; i < cRequired; i++) {
      long nEntryStartPad = (dWinStartPad + ((i % cMaxSerial) * dEffectiveProfileLen)) * NANOS_IN_SEC;
      this.entries.add(new ScheduleEntry(workAssignmentBuilders[i], nEntryStartPad));
    }

    String processGroupStr = processGroupTag.toString();
    this.ctrEntriesLockTimeout = metricRegistry.counter(MetricRegistry.name(MetricName.WA_Entries_Lock_Timeout.get(), processGroupStr));
    this.ctrEntriesLockInterrupt = metricRegistry.counter(MetricRegistry.name(MetricName.WA_Entries_Lock_Interrupt.get(), processGroupStr));
    this.ctrAssignmentFetchFail = metricRegistry.counter(MetricRegistry.name(MetricName.WA_Fetch_Failure.get(), processGroupStr));
    this.mtrSchedulingMiss = metricRegistry.meter(MetricRegistry.name(MetricName.WA_Scheduling_Miss.get(), processGroupStr));
  }

  public Set<WorkEntities.WorkType> getAssociatedWorkTypes() {
    return this.workTypes;
  }

  /**
   * Returns the maximum number of concurrently scheduled entries in this schedule
   * @return
   */
  public int getMaxOverlap() {
    return cMaxParallel;
  }

  /**
   * Supplies with a work assignment
   * Returns null if:
   * > no work assignments are pending
   * > no work assignment ready to be handed out
   * > recorder already assigned work (tied to aggregation window)
   * > timeout while acquiring lock over queue
   * > interrupted while waiting to acquire lock over queue
   * > exception occurred while processing queue entries
   * @return WorkAssignment or null
   */
  public WorkEntities.WorkAssignment getNextWorkAssignment(RecorderIdentifier recorderIdentifier) {
    Counter ctrAssignment = metricRegistry.counter(MetricRegistry.name(MetricName.Recorder_Assignment_Available.get(), recorderIdentifier.metricTag().toString()));
    try {
      boolean acquired = entriesLock.tryLock(100, TimeUnit.MILLISECONDS);
      if(acquired) {
        try {
          ScheduleEntry scheduleEntry = this.assignedSchedule.get(recorderIdentifier);
          if(scheduleEntry != null) {
            ScheduleEntry.ScheduleEntryValue value = scheduleEntry.getValue((System.nanoTime() - nRef), dMinDelay, dMaxDelay);
            if(value.isValid()) {
              ctrAssignment.inc();
              return value.workAssignment;
            } else {
              // Work was already assigned to recorder, so this will not be a scenario when recorder is too early
              // We will be here when scheduling miss has occurred (tooLate = true). This can happen in two scenarios:
              // > poll request is received after recorder has completed the same assigned work
              // > unable to receive work assignment earlier
              // We cannot distinguish between above two over here, anyways, need to return null because of scheduling miss
              return null;
            }
          }

          while((scheduleEntry = this.entries.peek()) != null) {
            ScheduleEntry.ScheduleEntryValue value = scheduleEntry.getValue((System.nanoTime() - nRef), dMinDelay, dMaxDelay);
            if(value.tooEarly) {
              logger.debug(String.format("Too early to hand over work assignment for work_id=%d, remaining delay=%d", value.workAssignment.getWorkId(), value.workAssignment.getDelay()));
              return null; //Since this is a priority queue, no point checking subsequent entries if current entry indicates its too early
            } else {
              this.entries.poll(); //dequeue the entry. no point in keeping the entry around whether fetch was done on right time or it was a scheduling miss
              if (value.tooLate) {
                mtrSchedulingMiss.mark();
                logger.error(String.format("Scheduling miss for work_id=%d, remaining delay=%d", value.workAssignment.getWorkId(), value.workAssignment.getDelay()));
              }
              else {
                ctrAssignment.inc();
                this.assignedSchedule.put(recorderIdentifier, scheduleEntry);
                return value.workAssignment;
              }
            }
          }
        } catch (Exception ex) {
          ctrAssignmentFetchFail.inc();
          logger.error("Unexpected error when getting fetching work assignment from schedule", ex);
        } finally {
          entriesLock.unlock();
        }
      } else {
        ctrEntriesLockTimeout.inc();
        logger.warn("Timeout while acquiring lock for fetching work assignment from schedule");
      }
    } catch (InterruptedException ex) {
      ctrEntriesLockInterrupt.inc();
      logger.warn("Interrupted while acquiring lock for fetching work assignment from schedule");
    }
    return null;
  }

  public static class ScheduleEntry implements Comparable<ScheduleEntry> {
    private final static Logger logger = LoggerFactory.getLogger(ScheduleEntry.class);

    private final WorkEntities.WorkAssignment.Builder workAssignmentBuilder;
    private final long nStartPad;

    public ScheduleEntry(WorkEntities.WorkAssignment.Builder workAssignmentBuilder, long nStartPad) {
      this.workAssignmentBuilder = Preconditions.checkNotNull(workAssignmentBuilder);
      this.nStartPad = nStartPad;
    }

    /**
     * @param nElapsed
     * @param dMinDelay
     * @param dMaxDelay
     * @return value of schedule entry with appropriate flags set to indicate if its too early or too late to receive work assignment
     */
    ScheduleEntryValue getValue(long nElapsed, int dMinDelay, int dMaxDelay) {
      int dRemainingDelay = (int)((nStartPad - nElapsed) / NANOS_IN_SEC);
      boolean tooLate = dRemainingDelay < dMinDelay;
      boolean tooEarly = dRemainingDelay > dMaxDelay;
      return new ScheduleEntryValue(
          workAssignmentBuilder
              .clone()
              .setDelay(dRemainingDelay)
              .setIssueTime(LocalDateTime.now(Clock.systemUTC()).format(DateTimeFormatter.ISO_LOCAL_DATE_TIME))
              .build(),
          tooEarly, tooLate);
    }

    @Override
    public int compareTo(ScheduleEntry other) {
      long diff = this.nStartPad - other.nStartPad;
      return diff > 0 ? 1 : (diff < 0 ? -1 : 0);
    }

    static class ScheduleEntryValue {
      private final WorkEntities.WorkAssignment workAssignment;
      private final boolean tooEarly;
      private final boolean tooLate;

      ScheduleEntryValue(final WorkEntities.WorkAssignment workAssignment, final boolean tooEarly, final boolean tooLate) {
        if(tooEarly && tooLate) {
          throw new IllegalArgumentException("Fetch of scheduling entry cannot be too early and too late simultaneously. Make up your mind!");
        }
        if(workAssignment == null) {
          throw new IllegalArgumentException("Valid work assignment should be returned if the fetch is done at correct time");
        }

        this.workAssignment = workAssignment;
        this.tooEarly = tooEarly;
        this.tooLate = tooLate;
      }

      boolean isValid() {
        return !tooEarly && !tooLate;
      }
    }

  }

}
