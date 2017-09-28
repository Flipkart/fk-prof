package fk.prof.backend.leader.election;

import com.codahale.metrics.Counter;
import com.codahale.metrics.MetricRegistry;
import com.codahale.metrics.SharedMetricRegistries;
import com.google.common.base.Preconditions;
import fk.prof.backend.ConfigManager;
import fk.prof.backend.proto.BackendDTO;
import fk.prof.backend.util.proto.BackendProtoUtil;
import fk.prof.metrics.MetricName;
import io.vertx.core.logging.Logger;
import io.vertx.core.logging.LoggerFactory;
import org.apache.curator.framework.CuratorFramework;
import org.apache.curator.framework.recipes.leader.CancelLeadershipException;
import org.apache.curator.framework.recipes.leader.LeaderSelectorListener;
import org.apache.curator.framework.recipes.leader.LeaderSelectorListenerAdapter;
import org.apache.curator.framework.state.ConnectionState;
import org.apache.zookeeper.CreateMode;

public class LeaderSelectorListenerImpl extends LeaderSelectorListenerAdapter {
  private static Logger logger = LoggerFactory.getLogger(LeaderSelectorListenerImpl.class);

  private final String leaderWatchingPath;
  private KillBehavior killBehavior;
  private final Runnable leaderElectedTask;
  private final BackendDTO.LeaderDetail selfLeaderDetail;

  private MetricRegistry metricRegistry = SharedMetricRegistries.getOrCreate(ConfigManager.METRIC_REGISTRY);
  private Counter ctrLeaderElect = metricRegistry.counter(MetricName.Election_Completed.get());
  private Counter ctrLeaderRelinquish = metricRegistry.counter(MetricName.Election_Relinquished.get());
  private Counter ctrLeaderInterrupt = metricRegistry.counter(MetricName.Election_Interrupted.get());
  private Counter ctrLeaderSuicideFailure = metricRegistry.counter(MetricName.Election_Suicide_Failure.get());


  public LeaderSelectorListenerImpl(String ipAddress, int leaderHttpPort, String leaderWatchingPath, KillBehavior killBehavior, Runnable leaderElectedTask) {
    this.selfLeaderDetail = BackendDTO.LeaderDetail.newBuilder().setHost(ipAddress).setPort(leaderHttpPort).build();
    this.leaderWatchingPath = Preconditions.checkNotNull(leaderWatchingPath);
    this.killBehavior = Preconditions.checkNotNull(killBehavior);
    this.leaderElectedTask = leaderElectedTask;
  }

  @Override
  public void takeLeadership(CuratorFramework curatorClient) throws Exception {
    ctrLeaderElect.inc();
    logger.info("Elected as leader");
    curatorClient
        .create()
        .creatingParentsIfNeeded()
        .withMode(CreateMode.EPHEMERAL)
        .forPath(leaderWatchingPath + "/" + BackendProtoUtil.leaderDetailCompactRepr(selfLeaderDetail),
            selfLeaderDetail.toByteArray());

    // NOTE: There is a race here. Other backend nodes can be communicated about the new leader before leaderElectedTask has run
    // If backend nodes talk to the new leader before leader has been primed and setup, its possible for leader to not respond.
    // We are OK with this race issue as backend nodes can simply retry
    if (leaderElectedTask != null) {
      leaderElectedTask.run();
    }

    while (true) {
      try {
        Thread.sleep(Long.MAX_VALUE);
      } catch (InterruptedException ex) {
        ctrLeaderInterrupt.inc();
        logger.warn("Thread interrupted, sleeping again");
      }
    }
  }

  @Override
  public void stateChanged(CuratorFramework curatorClient, ConnectionState newState) {
    if(logger.isDebugEnabled()) {
      logger.debug("Connection state changed to {}", newState.toString());
    }
    try {
      super.stateChanged(curatorClient, newState);
    } catch (CancelLeadershipException ex) {
      ctrLeaderRelinquish.inc();
      if(logger.isDebugEnabled()) {
        logger.debug("Relinquishing leadership, suicide!");
      }
      cleanup();
    }
  }

  /**
   * Kill own process
   */
  private void cleanup() {
    try {
      killBehavior.process();
    } catch (Exception ex) {
      ctrLeaderSuicideFailure.inc();
      logger.error(ex);
    }
  }

}
