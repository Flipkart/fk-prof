package fk.prof.backend.leader.election;

import io.vertx.core.AbstractVerticle;
import io.vertx.core.logging.Logger;
import io.vertx.core.logging.LoggerFactory;
import org.apache.curator.framework.CuratorFramework;
import org.apache.curator.framework.recipes.leader.LeaderSelector;
import org.apache.curator.framework.recipes.leader.LeaderSelectorListener;

public class LeaderElectionParticipator extends AbstractVerticle {
  private static Logger logger = LoggerFactory.getLogger(LeaderElectionParticipator.class);
  private CuratorFramework curatorClient;
  private Runnable leaderElectedTask;

  private LeaderSelector leaderSelector;

  public LeaderElectionParticipator(CuratorFramework curatorClient, Runnable leaderElectedTask) {
    this.curatorClient = curatorClient;
    this.leaderElectedTask = leaderElectedTask;
  }

  @Override
  public void start() {
    LeaderSelectorListener leaderSelectorListener = createLeaderSelectorListener();
    leaderSelector = createLeaderSelector(curatorClient, leaderSelectorListener);
    logger.debug("Starting leader selector");
    leaderSelector.start();
  }

  @Override
  public void stop() {
    logger.debug("Closing leader selector");
    leaderSelector.close();
  }

  private LeaderSelectorListener createLeaderSelectorListener() {
    return new LeaderSelectorListenerImpl(
        config().getInteger("leader.sleep.ms"),
        config().getString("leader.watching.path"),
        KillBehavior.valueOf(config().getString("kill.behavior", "DO_NOTHING")),
        leaderElectedTask);
  }

  private LeaderSelector createLeaderSelector(CuratorFramework curatorClient, LeaderSelectorListener leaderSelectorListener) {
    return new LeaderSelector(curatorClient, config().getString("leader.mutex.path"), leaderSelectorListener);
  }

}
