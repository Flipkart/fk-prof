package fk.prof.backend.model.association.impl;

import fk.prof.idl.Entities;

public class ProcessGroupZNodeDetail {
  private final String zNodePath;
  private final Entities.ProcessGroup processGroup;

  public ProcessGroupZNodeDetail(String zNodePath, Entities.ProcessGroup processGroup) {
    this.zNodePath = zNodePath;
    this.processGroup = processGroup;
  }

  public String getzNodePath() {
    return zNodePath;
  }

  public Entities.ProcessGroup getProcessGroup() {
    return processGroup;
  }
}
