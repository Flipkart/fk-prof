package fk.prof.backend.leader.election;

import com.codahale.metrics.Counter;
import com.codahale.metrics.MetricRegistry;
import com.codahale.metrics.SharedMetricRegistries;
import fk.prof.backend.ConfigManager;
import fk.prof.backend.model.election.LeaderWriteContext;
import fk.prof.idl.Backend;
import fk.prof.metrics.MetricName;
import io.vertx.core.AbstractVerticle;
import io.vertx.core.logging.Logger;
import io.vertx.core.logging.LoggerFactory;
import org.apache.curator.framework.CuratorFramework;
import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.Watcher;

import java.util.ArrayList;
import java.util.List;

public class LeaderElectionWatcher extends AbstractVerticle {
  private static Logger logger = LoggerFactory.getLogger(LeaderElectionWatcher.class);

  private final CuratorFramework curatorClient;
  private final LeaderWriteContext leaderWriteContext;
  private final Watcher childWatcher;
  private String leaderWatchingPath;

  private final MetricRegistry metricRegistry = SharedMetricRegistries.getOrCreate(ConfigManager.METRIC_REGISTRY);
  private final Counter ctrZKWatchFailure = metricRegistry.counter(MetricName.Election_Watch_Failure.get());

  public LeaderElectionWatcher(CuratorFramework curatorClient, LeaderWriteContext leaderWriteContext) {
    this.curatorClient = curatorClient;
    this.leaderWriteContext = leaderWriteContext;
    this.childWatcher = createChildWatcher();
  }

  @Override
  public void start() {
    leaderWatchingPath = config().getString("leader.watching.path", "/backends");

    try {
      try {
        curatorClient.create().forPath(leaderWatchingPath);
      } catch (KeeperException.NodeExistsException ex) {
        //Ignore exception if node already exists
      }
      leaderUpdated(getChildrenAndSetWatch());
    } catch (Exception ex) {
      ctrZKWatchFailure.inc();
      logger.error(ex);
    }
  }

  @Override
  public void stop() {
    //This ensures that if this worker verticle is undeployed for whatever reason, leader is set as null and all other components dependent on leader will fail
    try {
      curatorClient.clearWatcherReferences(childWatcher);
    } catch (Exception ex) {
      ctrZKWatchFailure.inc();
      logger.error(ex);
    } finally {
      leaderWriteContext.setLeader(null);
    }
  }

  //TODO: Figure out when zk conn failure occurs, does this throw exception. In that case, we might want to re-add the watch after some delay
  private List<String> getChildrenAndSetWatch() {
    try {
      return curatorClient.getChildren().usingWatcher(childWatcher).forPath(leaderWatchingPath);
    } catch (Exception ex) {
      ctrZKWatchFailure.inc();
      logger.error(ex);
      // If there is an error getting leader info from zookeeper, unset leader for this backend node.
      // Sending an empty list will take care of that
      return new ArrayList<>();
    }
  }

  private void leaderUpdated(List<String> childNodesList) {
    if (childNodesList.size() == 1) {
      try {
        byte[] leaderDetailBytes = curatorClient.getData().forPath(leaderWatchingPath + "/" + childNodesList.get(0));
        leaderWriteContext.setLeader(Backend.LeaderDetail.parseFrom(leaderDetailBytes));
        return;
      } catch (Exception ex) {
        ctrZKWatchFailure.inc();
        logger.error("Error encountered while fetching leader information", ex);
      }
    }

    if (childNodesList.size() > 1) {
      ctrZKWatchFailure.inc();
      logger.error("More than one leader observed, this is an unexpected scenario");
    }
    leaderWriteContext.setLeader(null);
  }

  private Watcher createChildWatcher() {
    return event -> {
      if(event.getState().equals(Watcher.Event.KeeperState.ConnectedReadOnly) || event.getState().equals(Watcher.Event.KeeperState.SyncConnected)) {
        if (event.getType().equals(Watcher.Event.EventType.NodeChildrenChanged)) {
          leaderUpdated(getChildrenAndSetWatch());
        } else {
          getChildrenAndSetWatch();
        }
      } else {
        ctrZKWatchFailure.inc();
        logger.error("Not connected to ZK, current state: {}", event.getState().name());
        getChildrenAndSetWatch();
      }
    };
  }
}
