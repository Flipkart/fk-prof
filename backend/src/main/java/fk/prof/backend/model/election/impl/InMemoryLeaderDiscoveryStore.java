package fk.prof.backend.model.election.impl;

import fk.prof.backend.model.election.LeaderDiscoveryStore;
import fk.prof.backend.util.IPAddressUtil;
import io.vertx.core.logging.Logger;
import io.vertx.core.logging.LoggerFactory;

/**
 * This implementation is susceptible to race conditions on backend nodes when
 * concurrently setLeaderIPAddress() and getLeaderIPAddress() / isLeader() methods are called
 * despite volatile variables being used to hold state
 * We should be OK despite this race condition as no permanent inconsistency will be introduced
 * and we avoid the cost associated with synchronized/locks
 */
public class InMemoryLeaderDiscoveryStore implements LeaderDiscoveryStore {
  private static Logger logger = LoggerFactory.getLogger(InMemoryLeaderDiscoveryStore.class);
  private volatile String leaderIPAddress = null;
  private volatile boolean leader = false;

  @Override
  public void setLeaderIPAddress(String ipAddress) {
    if (ipAddress == null) {
      logger.info(String.format("Removed backend node as leader. Node IP = %s",
          this.leaderIPAddress == null ? "" : this.leaderIPAddress));
    } else {
      logger.info(String.format("Set backend leader. Node IP = %s", ipAddress));
    }
    this.leaderIPAddress = ipAddress;
    this.leader = this.leaderIPAddress != null && this.leaderIPAddress.equals(IPAddressUtil.getIPAddressAsString());
  }

  @Override
  public String getLeaderIPAddress() {
    return this.leaderIPAddress;
  }

  @Override
  public boolean isLeader() {
    return this.leader;
  }
}
