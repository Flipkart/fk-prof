package fk.prof.backend.model.election;

public interface LeaderDiscoveryStore {
  /**
   * Accepts null argument as well, which is treated as removing leader mapping from the underlying store
   *
   * @param ipAddress
   */
  void setLeaderIPAddress(String ipAddress);

  /**
   * Returns null if leader has not been set in the store
   *
   * @return
   */
  String getLeaderIPAddress();

  /**
   * Returns true if self is leader, false otherwise
   *
   * @return
   */
  boolean isLeader();
}
