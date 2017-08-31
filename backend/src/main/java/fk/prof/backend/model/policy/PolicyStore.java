package fk.prof.backend.model.policy;

import fk.prof.backend.proto.BackendDTO;
import fk.prof.backend.util.ZookeeperUtil;
import io.vertx.core.logging.Logger;
import io.vertx.core.logging.LoggerFactory;
import org.apache.curator.framework.CuratorFramework;
import org.apache.zookeeper.KeeperException;
import recording.Recorder;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * TODO: Liable for refactoring. Hackish impl of policy store for now
 */
public class PolicyStore {
  private static Logger logger = LoggerFactory.getLogger(PolicyStore.class);

  public static String policyStorePath = "/policy_store_temp";
  private final Map<Recorder.ProcessGroup, BackendDTO.RecordingPolicy> store = new ConcurrentHashMap<>();
  private final CuratorFramework curator;
  private boolean initialized;

  public PolicyStore(CuratorFramework curator) throws Exception {
    this.curator = curator;
    this.initialized = false;
    ensurePolicyStorePath();
  }

  /**
   * Method to allow delayed initialization. Calling other method before init may result in undefined behaviour.
   */
  public void init() throws Exception {
    synchronized (this) {
      if (!initialized) {
        // populate all existing policies
        try {
          loadAllExistingPolicies();
        }
        catch (Exception e) {
          logger.error("Failed to load existing policies", e);
          throw e;
        }
        initialized = true;
      }
    }
  }

  public void put(Recorder.ProcessGroup processGroup, BackendDTO.RecordingPolicy recordingPolicy) throws Exception {
    updateToZk(processGroup, recordingPolicy);
    this.store.put(processGroup, recordingPolicy);
  }

  public BackendDTO.RecordingPolicy get(Recorder.ProcessGroup processGroup) {
    return this.store.get(processGroup);
  }

  private void updateToZk(Recorder.ProcessGroup processGroup, BackendDTO.RecordingPolicy recordingPolicy) throws Exception {
    ByteArrayOutputStream bout = new ByteArrayOutputStream();

    Map<Recorder.ProcessGroup, BackendDTO.RecordingPolicy> tempStore = new HashMap<>();
    tempStore.putAll(store);
    tempStore.put(processGroup, recordingPolicy);

    for(Map.Entry<Recorder.ProcessGroup, BackendDTO.RecordingPolicy> entry : tempStore.entrySet()) {
      entry.getKey().writeDelimitedTo(bout);
      entry.getValue().writeDelimitedTo(bout);
    }

    byte[] data = bout.toByteArray();

    // write to zk
    curator.setData().forPath(policyStorePath, data);
  }

  private void loadAllExistingPolicies() throws Exception {
    CountDownLatch syncLatch = new CountDownLatch(1);
    RuntimeException syncException = new RuntimeException();

    //ZK Sync operation always happens async, since this is essential for us to proceed further, using a latch here
    ZookeeperUtil.sync(curator, policyStorePath).setHandler(ar -> {
      if(ar.failed()) {
        syncException.initCause(ar.cause());
      }
      syncLatch.countDown();
    });

    boolean syncCompleted = syncLatch.await(5, TimeUnit.SECONDS);
    if(!syncCompleted) {
      throw new RuntimeException("ZK sync operation taking longer than expected");
    }
    if(syncException.getCause() != null) {
      throw new RuntimeException(syncException);
    }

    byte[] data = ZookeeperUtil.readZNode(curator, policyStorePath);
    if(data == null || data.length == 0) {
      return;
    }

    ByteArrayInputStream bin = new ByteArrayInputStream(data);

    // read policies for all process groups
    while(bin.available() > 0) {
      Recorder.ProcessGroup pg = Recorder.ProcessGroup.parser().parseDelimitedFrom(bin);
      BackendDTO.RecordingPolicy policy = BackendDTO.RecordingPolicy.parser().parseDelimitedFrom(bin);
      store.put(pg, policy);
    }

    logger.info("Read " + store.size() + " policies from zk");
  }

  private void ensurePolicyStorePath() throws Exception{
    try {
      curator.create().forPath(policyStorePath, new byte[0]);
    } catch (KeeperException.NodeExistsException ex) {
      logger.warn(ex);
    }
  }
}
