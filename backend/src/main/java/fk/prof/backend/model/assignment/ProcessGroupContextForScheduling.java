package fk.prof.backend.model.assignment;


import fk.prof.idl.Entities;

public interface ProcessGroupContextForScheduling {
  Entities.ProcessGroup getProcessGroup();
  void updateWorkAssignmentSchedule(WorkAssignmentSchedule workAssignmentSchedule);
  int getHealthyRecordersCount();

  default int applyCoverage(int healthyRecorders, int coveragePct) {
    return (int)((coveragePct * healthyRecorders) / 100.0f);
  }
}
