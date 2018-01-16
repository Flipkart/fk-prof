package fk.prof.backend.model.assignment;


import fk.prof.idl.Entities;

public interface ProcessGroupDiscoveryContext {
  ProcessGroupContextForPolling getProcessGroupContextForPolling(Entities.ProcessGroup processGroup);
}
