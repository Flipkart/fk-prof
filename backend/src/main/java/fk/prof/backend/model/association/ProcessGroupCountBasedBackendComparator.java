package fk.prof.backend.model.association;

import java.util.Comparator;

public class ProcessGroupCountBasedBackendComparator implements Comparator<BackendDetail> {

  @Override
  public int compare(BackendDetail b1, BackendDetail b2) {
    int b1Score = b1.getAssociatedProcessGroups().size();
    int b2Score = b2.getAssociatedProcessGroups().size();
    return b1Score - b2Score;
  }

}
