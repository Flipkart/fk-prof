package fk.prof.backend.model.assignment;


import fk.prof.idl.Entities;

import java.util.function.BiConsumer;

public interface AssociatedProcessGroups extends ProcessGroupDiscoveryContext {
  void updateProcessGroupAssociations(Entities.ProcessGroups processGroups, BiConsumer<ProcessGroupContextForScheduling, ProcessGroupAssociationResult> postUpdateAction);
}
