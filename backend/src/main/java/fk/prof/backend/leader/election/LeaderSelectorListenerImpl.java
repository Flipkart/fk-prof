package fk.prof.backend.leader.election;

import fk.prof.backend.util.IPAddressUtil;
import io.vertx.core.logging.Logger;
import io.vertx.core.logging.LoggerFactory;
import org.apache.curator.framework.CuratorFramework;
import org.apache.curator.framework.recipes.leader.LeaderSelectorListenerAdapter;
import org.apache.curator.framework.state.ConnectionState;
import org.apache.zookeeper.CreateMode;

public class LeaderSelectorListenerImpl extends LeaderSelectorListenerAdapter {
  private static Logger logger = LoggerFactory.getLogger(LeaderSelectorListenerImpl.class);

  private final int sleepDurationInMs;
  private final String leaderWatchingPath;
  private KillBehavior killBehavior;
  private final Runnable leaderElectedTask;

  public LeaderSelectorListenerImpl(int sleepDurationInMs, String leaderWatchingPath, KillBehavior killBehavior, Runnable leaderElectedTask) {
    if (leaderWatchingPath == null) {
      throw new IllegalArgumentException("Leader Watching path in zookeeper cannot be null");
    }
    if (killBehavior == null) {
      throw new IllegalArgumentException("Kill behavior cannot be null");
    }

    this.sleepDurationInMs = sleepDurationInMs;
    this.leaderWatchingPath = leaderWatchingPath;
    this.killBehavior = killBehavior;
    this.leaderElectedTask = leaderElectedTask;
  }

  @Override
  public void takeLeadership(CuratorFramework curatorClient) throws Exception {
    logger.info("Elected as leader");
    try {
      curatorClient
          .create()
          .creatingParentsIfNeeded()
          .withMode(CreateMode.EPHEMERAL_SEQUENTIAL)
          .forPath(leaderWatchingPath + "/child_", IPAddressUtil.getIPAddressAsBytes());

      // NOTE: There is a race here. Other backend nodes can be communicated about the new leader before leaderElectedTask has run
      // If backend nodes talk to the new leader before leader has been primed and setup, its possible for leader to not respond.
      // We are OK with this race issue as backend nodes can simply retry
      if (leaderElectedTask != null) {
        leaderElectedTask.run();
      }

      while (true) {
        Thread.sleep(sleepDurationInMs);
      }
    } catch (InterruptedException ex) {
      logger.warn("Thread interrupted");
      Thread.currentThread().interrupt();
    } finally {
      cleanup();
    }
  }

  @Override
  public void stateChanged(CuratorFramework curatorClient, ConnectionState newState) {
    logger.debug("Connection state changed to {}", newState.toString());
    super.stateChanged(curatorClient, newState);
  }

  /**
   * Kill own process
   */
  private void cleanup() {
    try {
      killBehavior.process();
    } catch (Exception ex) {
      logger.error(ex);
    }
  }

}
