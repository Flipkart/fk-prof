package fk.prof.backend.model.slot;

import fk.prof.idl.Profile;
import fk.prof.idl.WorkEntities;

import java.util.HashMap;
import java.util.Map;

public class WorkSlotWeightCalculator {
  private final static Map<WorkEntities.WorkType, Integer> weights = new HashMap<>();

  //TODO: Determine if weights for work type need to be picked from configuration?
  // In that case slotweightcalculator becomes a dependency to be injected everywhere
  static {
    weights.put(WorkEntities.WorkType.cpu_sample_work, 1);
    weights.put(WorkEntities.WorkType.io_trace_work, 1);
  }

  public static int weight(Profile.RecordingPolicy recordingPolicy) {
    int baseSlots = 0;
    if (recordingPolicy.getWorkCount() > 0) {
      for(WorkEntities.Work work: recordingPolicy.getWorkList()) {
        baseSlots += weights.get(work.getWType());
      }
    }
    return baseSlots;
  }
}
