package fk.prof.backend.model.assignment;


import fk.prof.idl.Entities;
import fk.prof.idl.Recorder;
import fk.prof.idl.WorkEntities;

public interface ProcessGroupContextForPolling {
  Entities.ProcessGroup getProcessGroup();
  WorkEntities.WorkAssignment getWorkAssignment(Recorder.PollReq pollReq);
}
