package fk.prof.backend.model.association;

import fk.prof.backend.exception.BackendAssociationException;
import fk.prof.idl.Backend;
import fk.prof.idl.Entities;
import fk.prof.idl.Recorder;
import io.vertx.core.Future;

public interface BackendAssociationStore {

  Future<Entities.ProcessGroups> reportBackendLoad(Backend.LoadReportRequest payload);

  Future<Recorder.AssignedBackend> associateAndGetBackend(Entities.ProcessGroup processGroup);

  Recorder.AssignedBackend getAssociatedBackend(Entities.ProcessGroup processGroup);

  Backend.BackendAssociations getAssociations();

  Recorder.AssignedBackend removeAssociation(Entities.ProcessGroup processGroup) throws BackendAssociationException;

  /**
   * Method to allow delayed initialization. Calling other methods before init may result in undefined behaviour.
   */
  void init() throws Exception;
}
