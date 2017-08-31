package fk.prof.backend.model.policy;

import fk.prof.backend.exception.PolicyException;
import fk.prof.backend.proto.PolicyDTO;
import fk.prof.backend.util.PathNamingUtil;
import fk.prof.backend.util.ZookeeperUtil;
import fk.prof.backend.util.proto.PolicyProtoUtil;
import fk.prof.backend.util.proto.RecorderProtoUtil;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.logging.Logger;
import io.vertx.core.logging.LoggerFactory;
import org.apache.curator.framework.CuratorFramework;
import org.apache.curator.utils.ZKPaths;
import org.apache.zookeeper.CreateMode;
import recording.Recorder;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static fk.prof.backend.util.ZookeeperUtil.DELIMITER;

/**
 * Zookeeper based implementation of the policy store
 * Created by rohit.patiyal on 18/05/17.
 */
public class ZookeeperBasedPolicyStoreAPI implements PolicyStoreAPI {
    private static final Logger LOGGER = LoggerFactory.getLogger(ZookeeperBasedPolicyStoreAPI.class);

    private final CuratorFramework curatorClient;
    private final String policyPath;
    private final String policyVersion;
    private final Vertx vertx;
    private final Map<Recorder.ProcessGroup, PolicyDTO.VersionedPolicyDetails> policyCache = new ConcurrentHashMap<>();
    private final Map<String, Map<String, ConcurrentHashMap.KeySetView<String, Boolean>>> processGroupHierarchy = new ConcurrentHashMap<>();
    private boolean initialized;


    ZookeeperBasedPolicyStoreAPI(Vertx vertx, CuratorFramework curatorClient, String policyBaseDir, String policyVersion) {
        if (vertx == null) {
            throw new IllegalArgumentException("Vertx instance is required");
        }
        if (curatorClient == null) {
            throw new IllegalArgumentException("Curator client is required");
        }
        if (policyBaseDir == null) {
            throw new IllegalArgumentException("Policy baseDir in zookeeper hierarchy is required");
        }
        if (policyVersion == null) {
            throw new IllegalArgumentException("Policy version is required");
        }
        this.vertx = vertx;
        this.curatorClient = curatorClient;
        this.policyPath = DELIMITER + policyBaseDir;
        this.policyVersion = policyVersion;
        this.initialized = false;
    }

    private void populateCacheFromZK() throws Exception {
        CountDownLatch syncLatch = new CountDownLatch(1);
        RuntimeException syncException = new RuntimeException();

        //ZK Sync operation always happens async, since this is essential for us to proceed further, using a latch here
        ZookeeperUtil.sync(curatorClient, policyPath).setHandler(ar -> {
            if (ar.failed()) {
                syncException.initCause(ar.cause());
            }
            syncLatch.countDown();
        });

        boolean syncCompleted = syncLatch.await(10, TimeUnit.SECONDS);
        if (!syncCompleted) {
            throw new PolicyException("ZK sync operation taking longer than expected", true);
        }
        if (syncException.getCause() != null) {
            throw new PolicyException(syncException, true);
        }
        String rootNodePath = policyPath + DELIMITER + policyVersion;
        for (String appId : curatorClient.getChildren().forPath(rootNodePath)) {
            String appNodePath = rootNodePath + DELIMITER + appId;
            for (String clusterId : curatorClient.getChildren().forPath(appNodePath)) {
                String clusterNodePath = appNodePath + DELIMITER + clusterId;
                for (String procName : curatorClient.getChildren().forPath(clusterNodePath)) {
                    String procNamePath = clusterNodePath + DELIMITER + procName;
                    Recorder.ProcessGroup processGroup = Recorder.ProcessGroup.newBuilder().setAppId(PathNamingUtil.decode32(appId))
                            .setCluster(PathNamingUtil.decode32(clusterId))
                            .setProcName(PathNamingUtil.decode32(procName)).build();

                    Map.Entry<String, byte[]> policyNode = ZookeeperUtil.readLatestSeqZNodeChild(curatorClient, procNamePath);
                    PolicyDTO.PolicyDetails policyDetails = PolicyDTO.PolicyDetails.parseFrom(policyNode.getValue());
                    int version = Integer.parseInt(policyNode.getKey());
                    PolicyDTO.VersionedPolicyDetails versionedPolicyDetails = PolicyDTO.VersionedPolicyDetails.newBuilder().setPolicyDetails(policyDetails).setVersion(version).build();

                    policyCache.put(processGroup, versionedPolicyDetails);
                    updateProcessGroupHierarchy(processGroup);
                }
            }
        }
    }

    synchronized void init() throws Exception {
            if (!initialized) {
                populateCacheFromZK();
                initialized = true;
            }
    }

    public Set<String> getAppIds(String prefix) throws Exception {
        return processGroupHierarchy.keySet().stream().filter(appIds -> appIds.startsWith(prefix)).collect(Collectors.toSet());
    }

    public Set<String> getClusterIds(String appId, String prefix) throws Exception {
        return processGroupHierarchy.get(appId).keySet().stream().filter(clusterIds -> clusterIds.startsWith(prefix)).collect(Collectors.toSet());
    }

    public Set<String> getProcNames(String appId, String clusterId, String prefix) throws Exception {
        return processGroupHierarchy.get(appId).get(clusterId).stream().filter(procNames -> procNames.startsWith(prefix)).collect(Collectors.toSet());
    }

    @Override
    public PolicyDTO.VersionedPolicyDetails getVersionedPolicy(Recorder.ProcessGroup processGroup) {
        if (processGroup == null) {
            throw new IllegalArgumentException("Process group is required");
        }
        return policyCache.get(processGroup);
    }

    @Override
    public Future<PolicyDTO.VersionedPolicyDetails> createVersionedPolicy(Recorder.ProcessGroup processGroup, PolicyDTO.VersionedPolicyDetails versionedPolicyDetails) {
        return putVersionedPolicy(processGroup, versionedPolicyDetails, true);
    }

    @Override
    public Future<PolicyDTO.VersionedPolicyDetails> updateVersionedPolicy(Recorder.ProcessGroup processGroup, PolicyDTO.VersionedPolicyDetails versionedPolicyDetails) {
        return putVersionedPolicy(processGroup, versionedPolicyDetails, false);
    }

    private Future<PolicyDTO.VersionedPolicyDetails> putVersionedPolicy(Recorder.ProcessGroup processGroup, PolicyDTO.VersionedPolicyDetails requestedVersionedPolicyDetails, boolean create) {
        if (processGroup == null) {
            throw new IllegalArgumentException("Process group is required");
        }
        if (requestedVersionedPolicyDetails == null) {
            throw new IllegalArgumentException("PolicyDetails is required");
        }
        Future<PolicyDTO.VersionedPolicyDetails> future = Future.future();
        String policyNodePath = PathNamingUtil.getPolicyNodePath(processGroup, policyPath, policyVersion);

        vertx.executeBlocking(fut -> {
            try {
                PolicyDTO.VersionedPolicyDetails newVersionedPolicy = policyCache.compute(processGroup, (k, v) -> {
                    if (v != null && create) {
                        throw new PolicyException(String.format("Failing create of policy, Policy for ProcessGroup = %s already exists, policyDetails = %s", RecorderProtoUtil.processGroupCompactRepr(processGroup), PolicyProtoUtil.versionedPolicyDetailsCompactRepr(getVersionedPolicy(processGroup))), true);
                    }
                    if (v == null && !create) {
                        throw new PolicyException(String.format("Failing update of policy, Policy for ProcessGroup = %s does not exist", RecorderProtoUtil.processGroupCompactRepr(processGroup)), true);
                    }
                    if (v != null && v.getVersion() != requestedVersionedPolicyDetails.getVersion()) {
                        throw new PolicyException("Failing update of policy, policy version mismatch, current version = " + v.getVersion() + ", your version = " + requestedVersionedPolicyDetails.getVersion() + ", for ProcessGroup = " + RecorderProtoUtil.processGroupCompactRepr(processGroup), true);
                    }
                    try {
                        String policyNodePathWithNodeName = curatorClient.create().creatingParentsIfNeeded().withMode(CreateMode.PERSISTENT_SEQUENTIAL).
                                forPath(policyNodePath, requestedVersionedPolicyDetails.getPolicyDetails().toByteArray());
                        String policyNodeName = ZKPaths.getNodeFromPath(policyNodePathWithNodeName);
                        Integer newVersion = Integer.parseInt(policyNodeName);      //Policy Node names are incrementing numbers (the versions)

                        PolicyDTO.VersionedPolicyDetails updated = requestedVersionedPolicyDetails.toBuilder().setVersion(newVersion).build();
                        updateProcessGroupHierarchy(processGroup);
                        return updated;
                    } catch (Exception e) {
                        throw new PolicyException("Exception thrown by ZK while writing policy for ProcessGroup = " + RecorderProtoUtil.processGroupCompactRepr(processGroup), e, true);
                    }
                });
                fut.complete(newVersionedPolicy);
            } catch (Exception e) {
                fut.fail(e);
            }
        }, false, future.completer());
        return future;
    }

    private void updateProcessGroupHierarchy(Recorder.ProcessGroup processGroup) {
        processGroupHierarchy.putIfAbsent(processGroup.getAppId(), new ConcurrentHashMap<>());
        processGroupHierarchy.get(processGroup.getAppId()).putIfAbsent(processGroup.getCluster(), ConcurrentHashMap.newKeySet());
        processGroupHierarchy.get(processGroup.getAppId()).get(processGroup.getCluster()).add(processGroup.getProcName());
    }
}
