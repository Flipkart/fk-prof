package fk.prof.backend.model.assignment;

import fk.prof.idl.Entities;
import fk.prof.idl.WorkEntities;

import java.util.Set;

public interface ProcessGroupContextForScheduling {

  Entities.ProcessGroup getProcessGroup();

  void updateWorkAssignmentSchedule(WorkAssignmentSchedule workAssignmentSchedule);

  /**
   * Returns count of known healthy recorders which can support all of the provided work types
   * @param workTypes
   * @return
   */
  int getHealthyRecordersCount(Set<WorkEntities.WorkType> workTypes);

  default int applyCoverage(int healthyRecorders, int coveragePct) {
    return (int)((coveragePct * healthyRecorders) / 100.0f);
  }
}
