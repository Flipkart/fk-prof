package fk.prof.backend.leader.election;

import fk.prof.backend.model.election.LeaderDiscoveryStore;
import io.vertx.core.AbstractVerticle;
import io.vertx.core.logging.Logger;
import io.vertx.core.logging.LoggerFactory;
import org.apache.curator.framework.CuratorFramework;
import org.apache.curator.framework.api.CuratorWatcher;
import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.Watcher;

import java.net.InetAddress;
import java.util.ArrayList;
import java.util.List;

public class LeaderElectionWatcher extends AbstractVerticle {
  private static Logger logger = LoggerFactory.getLogger(LeaderElectionWatcher.class);

  private final CuratorFramework curatorClient;
  private final LeaderDiscoveryStore leaderDiscoveryStore;
  private String leaderWatchingPath;

  public LeaderElectionWatcher(CuratorFramework curatorClient, LeaderDiscoveryStore leaderDiscoveryStore) {
    this.curatorClient = curatorClient;
    this.leaderDiscoveryStore = leaderDiscoveryStore;
  }

  @Override
  public void start() {
    leaderWatchingPath = config().getString("leader.watching.path");

    try {
      curatorClient.create().forPath(leaderWatchingPath);
    } catch (KeeperException.NodeExistsException ex) {
      //Ignore exception if node already exists
    } catch (Exception ex) {
      logger.error(ex);
    }

    try {
      leaderUpdated(getChildrenAndSetWatch());
    } catch (Exception ex) {
      logger.error(ex);
    }
  }

  @Override
  public void stop() {
    //This ensures that if this worker verticle is undeployed for whatever reason, leader is set as null and all other components dependent on leader will fail
    leaderDiscoveryStore.setLeaderIPAddress(null);
  }

  private List<String> getChildrenAndSetWatch() {
    try {
      return curatorClient.getChildren().usingWatcher(getChildWatcher()).forPath(leaderWatchingPath);
    } catch (Exception ex) {
      logger.error(ex);
      // If there is an error getting leader info from zookeeper, unset leader for this backend node.
      // Sending an empty list will take care of that
      return new ArrayList<>();
    }
  }

  private CuratorWatcher getChildWatcher() {
    return event -> {
      if (event.getType().equals(Watcher.Event.EventType.NodeChildrenChanged)) {
        leaderUpdated(getChildrenAndSetWatch());
      } else {
        getChildrenAndSetWatch();
      }
    };
  }

  private void leaderUpdated(List<String> childNodesList) {
    if (childNodesList.size() == 1) {
      try {
        byte[] ipAddressBytes = curatorClient.getData().forPath(leaderWatchingPath + "/" + childNodesList.get(0));
        String leaderIPAddress = InetAddress.getByAddress(ipAddressBytes).getHostAddress();
        leaderDiscoveryStore.setLeaderIPAddress(leaderIPAddress);
        return;
      } catch (Exception ex) {
        logger.error("Error encountered while fetching leader information", ex);
      }
    }

    if (childNodesList.size() > 1) {
      logger.error("More than one leader observed, this is an unexpected scenario");
    }
    leaderDiscoveryStore.setLeaderIPAddress(null);
  }
}
