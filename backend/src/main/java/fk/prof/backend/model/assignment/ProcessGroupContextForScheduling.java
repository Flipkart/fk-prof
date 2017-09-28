package fk.prof.backend.model.assignment;

import recording.Recorder;

public interface ProcessGroupContextForScheduling {
  Recorder.ProcessGroup getProcessGroup();
  void updateWorkAssignmentSchedule(WorkAssignmentSchedule workAssignmentSchedule);
  int getRecorderTargetCountToMeetCoverage(int coveragePct);
}
