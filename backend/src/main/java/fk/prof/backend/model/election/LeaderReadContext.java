package fk.prof.backend.model.election;

import fk.prof.idl.Backend;

public interface LeaderReadContext {
  /**
   * Returns null if leader has not been set in the store
   *
   * @return
   */
  Backend.LeaderDetail getLeader();

  /**
   * Returns true if self is leader, false otherwise
   *
   * @return
   */
  boolean isLeader();
}
