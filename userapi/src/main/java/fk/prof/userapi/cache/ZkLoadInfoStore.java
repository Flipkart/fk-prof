package fk.prof.userapi.cache;

import com.codahale.metrics.Counter;
import com.google.common.io.BaseEncoding;
import com.google.common.util.concurrent.ThreadFactoryBuilder;
import com.google.protobuf.AbstractMessage;
import com.google.protobuf.Parser;
import fk.prof.aggregation.AggregatedProfileNamingStrategy;
import fk.prof.metrics.Util;
import fk.prof.userapi.proto.LoadInfoEntities.NodeLoadInfo;
import fk.prof.userapi.proto.LoadInfoEntities.ProfileResidencyInfo;
import io.vertx.core.logging.Logger;
import io.vertx.core.logging.LoggerFactory;
import org.apache.curator.framework.CuratorFramework;
import org.apache.curator.framework.api.transaction.CuratorTransactionFinal;
import org.apache.curator.framework.recipes.locks.InterProcessLock;
import org.apache.curator.framework.recipes.locks.InterProcessMutex;
import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.KeeperException;

import java.nio.charset.Charset;
import java.time.LocalDateTime;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Shared storage based on zookeeper to store the mapping profile -> ip:port and maintain resource utilization counters
 * for each node in cluster.
 * It is used by {@link ClusterAwareCache}.
 *
 * zookeeper node structure:
 * /fkprof-userapi                                                                    (session namespace)
 * /fkprof-userapi/nodesInfo/{ip:port} -> {@link NodeLoadInfo}                                (ephemeral)
 * /fkprof-userapi/profilesLoadStatus/{profileName} -> {@link ProfileResidencyInfo}           (ephemeral)
 *
 * For resource utilization counter, currently only cached profile count is stored
 *
 * @see NodeLoadInfo
 * @see ProfileResidencyInfo
 *
 * Created by gaurav.ashok on 01/08/17.
 */
class ZkLoadInfoStore implements LoadInfoStore {
    private static final Logger logger = LoggerFactory.getLogger(ZkLoadInfoStore.class);
    private static final String distributedLockPath = "/global_mutex";

    private final String zkNodesInfoPath;
    private final CuratorFramework curatorClient;
    private final InterProcessLock interProcessLock;

    private final AtomicReference<ConnectionState> connectionState;
    private final AtomicReference<LocalDateTime> lastZkLostTime;
    private final AtomicBoolean recentlyZkConnectionLost;

    private final byte[] myResidencyInfo;
    private Runnable cacheInvalidator;

    private Counter lockAcquireTimeoutCounter = Util.counter("zk.lock.timeouts");
    private Counter onReconnectFailures = Util.counter("zk.onreconnect.failures");

    ZkLoadInfoStore(CuratorFramework curatorClient, String myIp, int port, Runnable cacheInvalidator) {
        this.curatorClient = curatorClient;

        this.zkNodesInfoPath = "/nodesInfo/" + myIp + ":" + port;
        this.interProcessLock = new InterProcessMutex(curatorClient, distributedLockPath);
        this.lastZkLostTime = new AtomicReference<>(LocalDateTime.MIN);
        this.recentlyZkConnectionLost = new AtomicBoolean(false);
        this.myResidencyInfo = ProfileResidencyInfo.newBuilder().setIp(myIp).setPort(port).build().toByteArray();

        this.curatorClient.getConnectionStateListenable().addListener(this::zkStateChangeListener,
            Executors.newSingleThreadExecutor(new ThreadFactoryBuilder().setNameFormat("curator-state-listener").build()));

        this.cacheInvalidator = cacheInvalidator;

        this.connectionState = new AtomicReference<>(
            curatorClient.getZookeeperClient().isConnected() ? ConnectionState.Connected : ConnectionState.Disconnected);
    }

    @Override
    public void init() throws Exception {
        ensureConnected();
        ensureRequiredZkNodesPresent();
    }

    @Override
    public boolean profileResidencyInfoExists(AggregatedProfileNamingStrategy profileName) throws Exception {
        ensureConnected();
        return pathExists(zkPathForProfile(profileName));
    }

    @Override
    public boolean nodeLoadInfoExists() throws Exception {
        ensureConnected();
        return pathExists(zkNodesInfoPath);
    }

    @Override
    public ProfileResidencyInfo readProfileResidencyInfo(AggregatedProfileNamingStrategy profileName) throws Exception {
        ensureConnected();
        try {
            return readFrom(zkPathForProfile(profileName), ProfileResidencyInfo.parser());
        } catch (KeeperException.NoNodeException e) {
            return null;
        }
    }

    @Override
    public NodeLoadInfo readNodeLoadInfo() throws Exception {
        ensureConnected();
        try {
            return readFrom(zkNodesInfoPath, NodeLoadInfo.parser());
        } catch (KeeperException.NoNodeException e) {
            return null;
        }
    }

    @Override
    public void updateProfileResidencyInfo(AggregatedProfileNamingStrategy profileName, boolean profileResidencyInfoNodeExists) throws Exception {
        ensureConnected();
        NodeLoadInfo nodeLoadInfo = readFrom(zkNodesInfoPath, NodeLoadInfo.parser());
        int profileLoadedCount = nodeLoadInfo.getProfilesLoaded() + (profileResidencyInfoNodeExists ? 0 : 1);
        CuratorTransactionFinal transaction = curatorClient.inTransaction().setData().forPath(zkNodesInfoPath, buildNodeLoadInfo(profileLoadedCount).toByteArray()).and();
        if(profileResidencyInfoNodeExists) {
            transaction = transaction.setData().forPath(zkPathForProfile(profileName), myResidencyInfo).and();
        }
        else {
            transaction = transaction.create().withMode(CreateMode.EPHEMERAL).forPath(zkPathForProfile(profileName), myResidencyInfo).and();
        }
        transaction.commit();
    }

    @Override
    public void removeProfileResidencyInfo(AggregatedProfileNamingStrategy profileName, boolean deleteProfileResidencyInfoNode) throws Exception {
        ensureConnected();
        NodeLoadInfo nodeLoadInfo = readFrom(zkNodesInfoPath, NodeLoadInfo.parser());
        byte[] newData = buildNodeLoadInfo(nodeLoadInfo.getProfilesLoaded() - 1).toByteArray();
        if(deleteProfileResidencyInfoNode) {
            curatorClient.inTransaction().setData().forPath(zkNodesInfoPath, newData)
                .and().delete().forPath(zkPathForProfile(profileName))
                .and().commit();
        }
        else {
            curatorClient.setData().forPath(zkNodesInfoPath, newData);
        }
    }

    AutoCloseable getLock() throws Exception {
        ensureConnected();
        return new CloseableSharedLock();
    }

    ConnectionState getState() {
        return connectionState.get();
    }

    public enum ConnectionState {
        Disconnected,
        Connected
    }

    private void ensureConnected() throws ZkStoreUnavailableException {
        if(!ConnectionState.Connected.equals(connectionState.get())) {
            throw new ZkStoreUnavailableException("connection lost recently: " + recentlyZkConnectionLost.get() + ", lastTimeOfLostConnection: " + lastZkLostTime.get().toString());
        }
    }

    private boolean pathExists(String path) throws  Exception {
        return curatorClient.checkExists().forPath(path) != null;
    }

    private class CloseableSharedLock implements AutoCloseable {

        CloseableSharedLock() throws Exception {
            if(!interProcessLock.acquire(5, TimeUnit.SECONDS)) {
                lockAcquireTimeoutCounter.inc();
                throw new ZkStoreUnavailableException("Could not acquire lock in the given time.");
            }
        }

        @Override
        public void close() throws Exception {
            interProcessLock.release();
        }
    }

    @Override
    public NodeLoadInfo buildNodeLoadInfo(int profileCount) {
        return NodeLoadInfo.newBuilder().setProfilesLoaded(profileCount).build();
    }

    private <T extends AbstractMessage> T readFrom(String path, Parser<T> parser) throws Exception {
        byte[] bytes = curatorClient.getData().forPath(path);
        return parser.parseFrom(bytes);
    }


    private String zkPathForProfile(AggregatedProfileNamingStrategy profileName) {
        return "/profilesLoadStatus/" +  BaseEncoding.base32().encode(profileName.toString().getBytes(Charset.forName("utf-8")));
    }

    private void zkStateChangeListener(CuratorFramework client, org.apache.curator.framework.state.ConnectionState newState) {
        if(org.apache.curator.framework.state.ConnectionState.RECONNECTED.equals(newState)) {
            try {
                if(recentlyZkConnectionLost.get()) {
                    reInit();
                    recentlyZkConnectionLost.set(false);
                }
                else {
                    if(!pathExists(zkNodesInfoPath)) {
                        // Assuming this should not happen. throwing an error here so that we can be notified if this assumption breaks any time.
                        throw new RuntimeException("After zookeeper reconnection, my node was not found");
                    }
                }
                connectionState.set(ConnectionState.Connected);
            }
            catch (Exception e) {
                onReconnectFailures.inc();
                logger.error("Error while reinitializing zookeeper after reconnection.", e);
            }
        }
        else if(org.apache.curator.framework.state.ConnectionState.LOST.equals(newState)) {
            connectionState.set(ConnectionState.Disconnected);
            lastZkLostTime.set(LocalDateTime.now());
            recentlyZkConnectionLost.set(true);
        }
        else if(org.apache.curator.framework.state.ConnectionState.SUSPENDED.equals(newState) || org.apache.curator.framework.state.ConnectionState.READ_ONLY.equals(newState)) {
            connectionState.set(ConnectionState.Disconnected);
        }
        logger.info("zookeeper state changed to \"{}\"", newState.name());
    }

    private void ensureRequiredZkNodesPresent() throws Exception {
        try {
            curatorClient.delete().forPath(zkNodesInfoPath);

        } catch (KeeperException.NoNodeException e){
            logger.info("Node does not exist while deleting zkNodeInfoPath Node, exception: ", e);
        }
        curatorClient.create().creatingParentsIfNeeded().withMode(CreateMode.EPHEMERAL).forPath(zkNodesInfoPath, buildNodeLoadInfo(0).toByteArray());

        if (curatorClient.checkExists().forPath("/profilesLoadStatus") == null) {
            curatorClient.create().withMode(CreateMode.PERSISTENT).forPath("/profilesLoadStatus");
        }
    }

    private void reInit() throws Exception {
        cacheInvalidator.run();
        ensureRequiredZkNodesPresent();
    }

}