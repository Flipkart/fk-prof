package fk.prof.backend.model.policy;

import fk.prof.backend.exception.PolicyException;
import fk.prof.backend.util.PathNamingUtil;
import fk.prof.backend.util.ZookeeperUtil;
import fk.prof.backend.util.proto.PolicyEntitiesProtoUtil;
import fk.prof.backend.util.proto.RecorderProtoUtil;
import fk.prof.idl.Entities;
import fk.prof.idl.PolicyEntities;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.logging.Logger;
import io.vertx.core.logging.LoggerFactory;
import org.apache.curator.framework.CuratorFramework;
import org.apache.curator.utils.ZKPaths;
import org.apache.zookeeper.CreateMode;

import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashSet;
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
public class ZookeeperBasedPolicyStore implements PolicyStore {
    private static final Logger LOGGER = LoggerFactory.getLogger(ZookeeperBasedPolicyStore.class);

    private final CuratorFramework curatorClient;
    private final String policyPath;
    private final String policyVersion;
    private final Vertx vertx;
    private final Map<Entities.ProcessGroup, PolicyEntities.VersionedPolicyDetails> policyCache = new ConcurrentHashMap<>();
    private final Map<String, Map<String, ConcurrentHashMap.KeySetView<String, Boolean>>> processGroupHierarchy = new ConcurrentHashMap<>();
    private boolean initialized;

    public ZookeeperBasedPolicyStore(Vertx vertx, CuratorFramework curatorClient, String policyBaseDir, String policyVersion) {
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
                    Entities.ProcessGroup processGroup = Entities.ProcessGroup.newBuilder().setAppId(PathNamingUtil.decode32(appId))
                            .setCluster(PathNamingUtil.decode32(clusterId))
                            .setProcName(PathNamingUtil.decode32(procName)).build();

                    Map.Entry<String, byte[]> policyNode = ZookeeperUtil.readLatestSeqZNodeChild(curatorClient, procNamePath);
                    PolicyEntities.PolicyDetails policyDetails = PolicyEntities.PolicyDetails.parseFrom(policyNode.getValue());
                    int version = Integer.parseInt(policyNode.getKey());
                    PolicyEntities.VersionedPolicyDetails versionedPolicyDetails = PolicyEntities.VersionedPolicyDetails.newBuilder().setPolicyDetails(policyDetails).setVersion(version).build();

                    policyCache.put(processGroup, versionedPolicyDetails);
                    updateProcessGroupHierarchy(processGroup);
                }
            }
        }
    }

    public synchronized void init() throws Exception {
            if (!initialized) {
                populateCacheFromZK();
                initialized = true;
            }
    }

    public Set<String> getAppIds(String prefix) throws Exception {
        String filterPrefix = (prefix == null) ? "" : prefix;
        return processGroupHierarchy.keySet().stream().filter(appIds -> appIds.startsWith(filterPrefix)).collect(Collectors.toSet());
    }

    public Set<String> getClusterIds(String appId, String prefix) throws Exception {
        String filterPrefix = (prefix == null) ? "" : prefix;
        if(processGroupHierarchy.get(appId) != null) {
          return processGroupHierarchy.get(appId).keySet().stream().filter(clusterIds -> clusterIds.startsWith(filterPrefix)).collect(Collectors.toSet());
        }
        return new HashSet<>();
    }

    public Set<String> getProcNames(String appId, String clusterId, String prefix) throws Exception {
        String filterPrefix = (prefix == null) ? "" : prefix;
      if(processGroupHierarchy.get(appId) != null && processGroupHierarchy.get(appId).get(clusterId) != null) {
        return processGroupHierarchy.get(appId).get(clusterId).stream().filter(procNames -> procNames.startsWith(filterPrefix)).collect(Collectors.toSet());
      }
      return new HashSet<>();
    }

    @Override
    public PolicyEntities.VersionedPolicyDetails getVersionedPolicy(Entities.ProcessGroup processGroup) {
        if (processGroup == null) {
            throw new IllegalArgumentException("Process group is required");
        }
        return policyCache.get(processGroup);
    }

    @Override
    public Future<PolicyEntities.VersionedPolicyDetails> createVersionedPolicy(Entities.ProcessGroup processGroup, PolicyEntities.VersionedPolicyDetails versionedPolicyDetails) {
        return putVersionedPolicy(processGroup, versionedPolicyDetails, true);
    }

    @Override
    public Future<PolicyEntities.VersionedPolicyDetails> updateVersionedPolicy(Entities.ProcessGroup processGroup, PolicyEntities.VersionedPolicyDetails versionedPolicyDetails) {
        return putVersionedPolicy(processGroup, versionedPolicyDetails, false);
    }

    private Future<PolicyEntities.VersionedPolicyDetails> putVersionedPolicy(Entities.ProcessGroup processGroup, PolicyEntities.VersionedPolicyDetails requestedVersionedPolicyDetails, boolean create) {
        if (processGroup == null) {
            throw new IllegalArgumentException("Process group is required");
        }
        if (requestedVersionedPolicyDetails == null) {
            throw new IllegalArgumentException("PolicyDetails is required");
        }
        Future<PolicyEntities.VersionedPolicyDetails> future = Future.future();
        String policyNodePath = PathNamingUtil.getPolicyNodePath(processGroup, policyPath, policyVersion);

        vertx.executeBlocking(fut -> {
            try {
                PolicyEntities.VersionedPolicyDetails newVersionedPolicy = policyCache.compute(processGroup, (k, v) -> {
                    if (v != null && create) {
                        throw new PolicyException(String.format("Failing create of policy, Policy for ProcessGroup = %s already exists, policyDetails = %s", RecorderProtoUtil.processGroupCompactRepr(processGroup), PolicyEntitiesProtoUtil.versionedPolicyDetailsCompactRepr(getVersionedPolicy(processGroup))), true);
                    }
                    if (v == null && !create) {
                        throw new PolicyException(String.format("Failing update of policy, Policy for ProcessGroup = %s does not exist", RecorderProtoUtil.processGroupCompactRepr(processGroup)), true);
                    }
                    if (v != null && v.getVersion() != requestedVersionedPolicyDetails.getVersion()) {
                        throw new PolicyException("Failing update of policy, policy version mismatch, current version = " + v.getVersion() + ", your version = " + requestedVersionedPolicyDetails.getVersion() + ", for ProcessGroup = " + RecorderProtoUtil.processGroupCompactRepr(processGroup), true);
                    }
                    if (v == null && requestedVersionedPolicyDetails.getVersion() != -1) {
                        throw new PolicyException("Failing create of policy, initial version must be -1, requested version = " + requestedVersionedPolicyDetails.getVersion(), false);
                    }
                    try {
                        return putPolicyDetailsInZK(processGroup, requestedVersionedPolicyDetails, policyNodePath, create);
                    } catch (Exception e) {
                        throw new PolicyException("Exception thrown by ZK while writing policy for ProcessGroup = " + RecorderProtoUtil.processGroupCompactRepr(processGroup), e, true);
                    }
                });
                if (create) {
                    LOGGER.info("Policy : {} created for process group: {}", PolicyEntitiesProtoUtil.versionedPolicyDetailsCompactRepr(newVersionedPolicy), RecorderProtoUtil.processGroupCompactRepr(processGroup));
                } else {
                    LOGGER.info("Policy : {} updated for process group: {}", PolicyEntitiesProtoUtil.versionedPolicyDetailsCompactRepr(newVersionedPolicy), RecorderProtoUtil.processGroupCompactRepr(processGroup));
                }
                fut.complete(newVersionedPolicy);
            } catch (Exception e) {
                fut.fail(e);
            }
        }, false, future.completer());
        return future;
    }

    private PolicyEntities.VersionedPolicyDetails putPolicyDetailsInZK(Entities.ProcessGroup processGroup, PolicyEntities.VersionedPolicyDetails requestedVersionedPolicyDetails, String policyNodePath, boolean create) throws Exception {
        String currentTime = ZonedDateTime.now().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
        PolicyEntities.PolicyDetails.Builder policyDetailsBuilder = requestedVersionedPolicyDetails.getPolicyDetails().toBuilder().setModifiedAt(currentTime);
        if (create) {
          policyDetailsBuilder.setCreatedAt(currentTime);
        }
        PolicyEntities.PolicyDetails policyDetailsWithCurrentTime = policyDetailsBuilder.build();
        String policyNodePathWithNodeName = curatorClient.create().creatingParentsIfNeeded().withMode(CreateMode.PERSISTENT_SEQUENTIAL).
            forPath(policyNodePath, policyDetailsWithCurrentTime.toByteArray());

        PolicyEntities.VersionedPolicyDetails.Builder versionedPolicyDetailsBuilder = requestedVersionedPolicyDetails.toBuilder();
        Integer newVersion = Integer.parseInt(ZKPaths.getNodeFromPath(policyNodePathWithNodeName)); //Policy Node names are incrementing numbers (the versions)

        versionedPolicyDetailsBuilder.setVersion(newVersion).setPolicyDetails(policyDetailsWithCurrentTime);
        PolicyEntities.VersionedPolicyDetails updatedVersionedPolicyDetails = versionedPolicyDetailsBuilder.build();
        updateProcessGroupHierarchy(processGroup);
        return updatedVersionedPolicyDetails;
    }

    private void updateProcessGroupHierarchy(Entities.ProcessGroup processGroup) {
        processGroupHierarchy.putIfAbsent(processGroup.getAppId(), new ConcurrentHashMap<>());
        processGroupHierarchy.get(processGroup.getAppId()).putIfAbsent(processGroup.getCluster(), ConcurrentHashMap.newKeySet());
        processGroupHierarchy.get(processGroup.getAppId()).get(processGroup.getCluster()).add(processGroup.getProcName());
    }
}
