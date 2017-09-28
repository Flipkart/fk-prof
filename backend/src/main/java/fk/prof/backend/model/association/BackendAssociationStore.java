package fk.prof.backend.model.association;

import fk.prof.backend.proto.BackendDTO;
import io.vertx.core.Future;
import recording.Recorder;

public interface BackendAssociationStore {
  Future<Recorder.ProcessGroups> reportBackendLoad(BackendDTO.LoadReportRequest payload);
  Future<Recorder.AssignedBackend> associateAndGetBackend(Recorder.ProcessGroup processGroup);
  Recorder.AssignedBackend getAssociatedBackend(Recorder.ProcessGroup processGroup);
  Recorder.BackendAssociations getAssociations();

  /**
   * Method to allow delayed initialization. Calling other methods before init may result in undefined behaviour.
   */
  void init() throws Exception;
}
