package fk.prof.backend.model.association.impl;

import fk.prof.backend.exception.AssociationException;
import fk.prof.backend.model.association.BackendAssociationStore;
import fk.prof.backend.model.association.BackendDetail;
import fk.prof.backend.proto.BackendDTO;
import fk.prof.backend.util.ProtoUtil;
import fk.prof.backend.util.ZookeeperUtil;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.logging.Logger;
import io.vertx.core.logging.LoggerFactory;
import org.apache.curator.framework.CuratorFramework;
import org.apache.zookeeper.CreateMode;
import recording.Recorder;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentSkipListSet;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

public class ZookeeperBasedBackendAssociationStore implements BackendAssociationStore {
  private static Logger logger = LoggerFactory.getLogger(ZookeeperBasedBackendAssociationStore.class);

  private final Vertx vertx;
  private final CuratorFramework curatorClient;
  private final String backendAssociationPath;
  private final int loadReportIntervalInSeconds;
  private final int loadMissTolerance;

  private final Map<String, BackendDetail> backendDetailLookup = new ConcurrentHashMap<>();
  private final SortedSet<BackendDetail> availableBackendsByPriority;

  private final Map<Recorder.ProcessGroup, String> processGroupToBackendLookup = new ConcurrentHashMap<>();
  private final Map<Recorder.ProcessGroup, String> processGroupToZNodePathLookup = new ConcurrentHashMap<>();

  private final ReentrantLock backendAssignmentLock = new ReentrantLock();

  public ZookeeperBasedBackendAssociationStore(Vertx vertx, CuratorFramework curatorClient, String backendAssociationPath,
                                                int loadReportIntervalInSeconds, int loadMissTolerance,
                                                Comparator<BackendDetail> backendPriorityComparator)
      throws Exception {
    if(vertx == null) {
      throw new IllegalStateException("Vertx instance is required");
    }
    if(curatorClient == null) {
      throw new IllegalStateException("Curator client is required");
    }
    if(backendAssociationPath == null) {
      throw new IllegalStateException("Backend association path in zookeeper hierarchy is required");
    }
    if(backendPriorityComparator == null) {
      throw new IllegalStateException("Priority comparator for backends is required");
    }

    this.vertx = vertx;
    this.curatorClient = curatorClient;
    this.backendAssociationPath = backendAssociationPath;
    this.loadReportIntervalInSeconds = loadReportIntervalInSeconds;
    this.loadMissTolerance = loadMissTolerance;
    this.availableBackendsByPriority = new ConcurrentSkipListSet<>(backendPriorityComparator);

    loadDataFromZookeeperInBackendLookup();
    for(BackendDetail backendDetail: this.backendDetailLookup.values()) {
      for(Recorder.ProcessGroup processGroup: backendDetail.getAssociatedProcessGroups()) {
        if (this.processGroupToBackendLookup.putIfAbsent(processGroup, backendDetail.getBackendIPAddress()) != null) {
          throw new IllegalStateException(String.format("Backend mapping already exists for process group=%s", processGroup));
        }
      }
    }
  }

  @Override
  public Future<Recorder.ProcessGroups> reportBackendLoad(BackendDTO.LoadReportRequest payload) {
    String backendIPAddress = payload.getIp();
    Future<Recorder.ProcessGroups> result = Future.future();

    BackendDetail existingBackendDetail;
    if((existingBackendDetail = backendDetailLookup.get(backendIPAddress)) != null) {
      boolean timeUpdated = existingBackendDetail.reportLoad(payload.getLoad(), payload.getCurrTick());
      if(timeUpdated) {
        availableBackendsByPriority.add(existingBackendDetail);
      }
      result.complete(existingBackendDetail.buildProcessGroupsProto());
    } else {
      vertx.executeBlocking(future -> {
        try {
          /**TODO: Implement a cleanup job for backends which have been defunct for a long time, remove them from backenddetaillookup map
           * When implementing above, ensure cleanup operations in backenddetaillookup are consistent wrt get/iteration/update in
           * {@link #getAssociatedBackend(Recorder.ProcessGroup)} and {@link #reportBackendLoad(BackendDTO.LoadReportRequest)}
           */
          BackendDetail backendDetail = backendDetailLookup.computeIfAbsent(backendIPAddress, (key) -> {
            try {
              BackendDetail updatedBackendDetail = new BackendDetail(key, loadReportIntervalInSeconds, loadMissTolerance);
              String zNodePath = getZNodePathForBackend(key);
              ZookeeperUtil.writeZNode(curatorClient, zNodePath, new byte[0], true);
              return updatedBackendDetail;
            } catch (Exception ex) {
              throw new RuntimeException(ex);
            }
          });

          boolean timeUpdated = backendDetail.reportLoad(payload.getLoad(), payload.getCurrTick());
          if (timeUpdated) {
            availableBackendsByPriority.add(backendDetail);
          }

          /**
           * NOTE: There is a possible race condition above
           * t0(time) => request1: a recorder belonging to pg1(process group) calls /association which invokes {@link #getAssociatedBackend(Recorder.ProcessGroup)}
           *             pg1 is associated with b1(backend) which has become defunct so next operation to be executed is removal of b1 from available backends set
           * t1 => request2 b1 reports load and is added to available backend set (if not already present), response is returned
           * t2 => request1: {@link #getAssociatedBackend(Recorder.ProcessGroup)} attempts to remove b1 from available backends set since it assumes it to be defunct and returns response by associating recorder with some other backend b2
           * Above results in inconsistent state of availableBackends because even though b1 has reported load, it is not present in the set
           *
           * The above race-condition should not lead to a permanent inconsistent state because on subsequent load reports, b1 will get added to available backends set again
           */

          future.complete(backendDetail.buildProcessGroupsProto());
        } catch (Exception ex) {
          future.fail(ex);
        }

      }, result.completer());
    }
    return result;
  }

  @Override
  public Future<String> getAssociatedBackend(Recorder.ProcessGroup processGroup) {
    Future<String> result = Future.future();
    String backendAssociation = processGroupToBackendLookup.get(processGroup);

    if(backendAssociation != null
        && !backendDetailLookup.get(backendAssociation).isDefunct()) {
      // Returning the existing assignment if some backend is something already assigned to this process group and it is not defunct
      if(logger.isDebugEnabled()) {
        logger.debug(String.format("process_group=%s, existing backend=%s, available",
            ProtoUtil.processGroupCompactRepr(processGroup), backendAssociation));
      }
      result.complete(backendAssociation);
    } else {
      vertx.executeBlocking(future -> {
        /**
         * Since a backend assignment and possible de-assignment is required, acquiring a lock to avoid race conditions. Examples:
         * => two recorders with same process group getting different backends assigned
         * => available backend set seen as empty by some of the requests because assignments of all available backends were being updated in other requests.
         *    this can arise because during assignment, backend is removed from available backend set and added again post assignment
         * => competing zookeeper requests to de-assign a defunct backend
         */
        if(logger.isDebugEnabled()) {
          logger.debug(String.format("process_group=%s, existing backend=%s, defunct or null",
              ProtoUtil.processGroupCompactRepr(processGroup), backendAssociation));
        }
        try {
          boolean acquired = backendAssignmentLock.tryLock(2, TimeUnit.SECONDS);
          if (acquired) {
            try {
              //Lookup existing backend association again after acquiring lock to avoid race conditions
              String existingBackendAssociation = processGroupToBackendLookup.get(processGroup);
              if (existingBackendAssociation == null) {
                //This is a new process group and no backend has been assigned to this yet
                BackendDetail availableBackend = getAvailableBackendFromPrioritySet();
                if (availableBackend == null) {
                  //TODO: some metric to indicate assignment failure in this scenario
                  future.fail("No available backends are known to leader, cannot assign one to process_group=" + processGroup);
                } else {
                  try {
                    associateBackendWithProcessGroup(availableBackend, processGroup);
                    if (logger.isDebugEnabled()) {
                      logger.debug(String.format("process_group=%s, new backend=%s",
                          ProtoUtil.processGroupCompactRepr(processGroup), availableBackend.getBackendIPAddress()));
                    }
                    future.complete(availableBackend.getBackendIPAddress());
                  } catch (Exception ex) {
                    future.fail(String.format("Cannot persist association of backend=%s with process_group=%s in zookeeper",
                        availableBackend.getBackendIPAddress(), processGroup));
                  }

                  try {
                    availableBackendsByPriority.add(availableBackend);
                  } catch (Exception ex) {
                    //TODO: Some metric to indicate add failure here
                    logger.error("Error adding backend=" + availableBackend.getBackendIPAddress() + " back in the set");
                  }
                }
              } else {
                BackendDetail existingBackend = backendDetailLookup.get(existingBackendAssociation);
                if (!existingBackend.isDefunct()) {
                  /**
                   * Backend is not defunct, proceed with returning this assignment
                   * This is a double check, this condition is checked before acquiring the lock as well to avoid locking cost in majority of the cases
                   */
                  if (logger.isDebugEnabled()) {
                    logger.debug(String.format("process_group=%s, existing backend=%s, available",
                        ProtoUtil.processGroupCompactRepr(processGroup), existingBackend.getBackendIPAddress()));
                  }
                  future.complete(existingBackendAssociation);
                } else {
                  /**
                   * Presently assigned backend is defunct, find an available backend and if found, de-associate current backend and assign the new one to process group
                   * Defunct backend is not de-associated eagerly because if no available backend is found, its better to wait for current backend to come back alive
                   */
                  availableBackendsByPriority.remove(existingBackend);
                  BackendDetail newBackend = getAvailableBackendFromPrioritySet();
                  if (newBackend == null) {
                    logger.warn(String.format("Presently assigned backend=%s for process_group=%s is defunct but cannot find any available backend so keeping assignment unchanged",
                        existingBackendAssociation, ProtoUtil.processGroupCompactRepr(processGroup)));
                    future.complete(existingBackendAssociation);
                  } else {
                    try {
                      /**
                       * Race condition is described in {@link #reportBackendLoad(BackendDTO.LoadReportRequest)} which can result in new backend to be same as existing backend
                       * Basically, existingBackend can be defunct, but before a new backend is determined by this method, the existing backend reports load and gets added back to the available backend set
                       * It is possible that we get the existing backend again as the available backend by {@link #getAvailableBackendFromPrioritySet()}
                       * In this case, below conditional serves as optimization to do ZK operations only if we truly have a new backend
                       */
                      if(!newBackend.equals(existingBackend)) {
                        deAssociateBackendWithProcessGroup(existingBackend, processGroup);
                        associateBackendWithProcessGroup(newBackend, processGroup);
                        if (logger.isDebugEnabled()) {
                          logger.debug(String.format("process_group=%s, de-associating existing backend=%s, associating new backend=%s",
                              ProtoUtil.processGroupCompactRepr(processGroup), existingBackend.getBackendIPAddress(), newBackend.getBackendIPAddress()));
                        }
                      }
                      future.complete(newBackend.getBackendIPAddress());
                    } catch (Exception ex) {
                      future.fail(String.format("Cannot persist association of backend=%s with process_group=%s in zookeeper",
                          newBackend.getBackendIPAddress(), processGroup));
                    }

                    try {
                      availableBackendsByPriority.add(newBackend);
                    } catch (Exception ex) {
                      //TODO: Some metric to indicate add failure here
                      logger.error("Error adding backend=" + newBackend.getBackendIPAddress() + " back in the set");
                    }
                  }
                }
              }
            } finally {
              backendAssignmentLock.unlock();
            }
          } else {
            future.fail("Timeout while acquiring lock for backend assignment for process_group=" + processGroup);
          }
        } catch (InterruptedException ex) {
          future.fail(new RuntimeException("Interrupted while acquiring lock for backend assignment for process_group=" + processGroup, ex));
        } catch (Exception ex) {
          future.fail(new RuntimeException("Unexpected error while retrieving backend assignment for process_group=" + processGroup, ex));
        }
      }, result.completer());
    }

    return result;
  }

  /**
   * Finds an available backend from the available backends set. Removes all backends which have become defunct since they were added
   * @return available backend or null if none found
   */
  private BackendDetail getAvailableBackendFromPrioritySet() {
    while(availableBackendsByPriority.size() > 0) {
      BackendDetail availableBackend = availableBackendsByPriority.first();
      availableBackendsByPriority.remove(availableBackend);
      if(!availableBackend.isDefunct()) {
        return availableBackend;
      }
    }
    return null;
  }

  /**
   * Associates a backend with process group. Writes to in-memory store and zookeeper
   * NOTE: Ensure that the backend detail instance being modified has been removed from available backend set prior to calling this method
   * @param backendDetail
   * @param processGroup
   * @throws Exception
   */
  private void associateBackendWithProcessGroup(BackendDetail backendDetail, Recorder.ProcessGroup processGroup)
      throws Exception {
    String processGroupZNodeBasePath = getZNodePathForBackend(backendDetail.getBackendIPAddress()) + "/pg_";
    String processGroupZNodeCreatedPath = curatorClient
        .create()
        .withMode(CreateMode.PERSISTENT_SEQUENTIAL)
        .forPath(processGroupZNodeBasePath, processGroup.toByteArray());

    backendDetail.associateProcessGroup(processGroup);
    processGroupToBackendLookup.put(processGroup, backendDetail.getBackendIPAddress());
    processGroupToZNodePathLookup.put(processGroup, processGroupZNodeCreatedPath);
  }

  /**
   * De associates a backend from process group. Writes to in-memory store and zookeeper
   * NOTE: Ensure that the backend detail instance being modified has been removed from available backend set prior to calling this method
   * @param backendDetail
   * @param processGroup
   * @throws Exception
   */
  private void deAssociateBackendWithProcessGroup(BackendDetail backendDetail, Recorder.ProcessGroup processGroup)
      throws Exception {
    String processGroupZNodePath = processGroupToZNodePathLookup.get(processGroup);
    if(processGroupZNodePath == null) {
      throw new IllegalStateException("Cannot find znode path of already loaded process group=" + ProtoUtil.processGroupCompactRepr(processGroup));
    }
    curatorClient.delete().forPath(processGroupZNodePath);

    backendDetail.deAssociateProcessGroup(processGroup);
    processGroupToBackendLookup.remove(processGroup);
    processGroupToZNodePathLookup.remove(processGroup);
  }

  private void loadDataFromZookeeperInBackendLookup() throws Exception {
    CountDownLatch syncLatch = new CountDownLatch(1);
    AssociationException syncException = new AssociationException();

    //ZK Sync operation always happens async, since this is essential for us to proceed further, using a latch here
    ZookeeperUtil.sync(curatorClient, backendAssociationPath).setHandler(ar -> {
      if(ar.failed()) {
        syncException.initCause(ar.cause());
      }
      syncLatch.countDown();
    });

    boolean syncCompleted = syncLatch.await(5, TimeUnit.SECONDS);
    if(!syncCompleted) {
      throw new AssociationException("ZK sync operation taking longer than expected");
    }
    if(syncException.getCause() != null) {
      throw syncException;
    }

    List<String> backendIPAddresses = curatorClient.getChildren().forPath(backendAssociationPath);
    for(String backendIPAddress: backendIPAddresses) {
      String backendZNodePath = getZNodePathForBackend(backendIPAddress);
      List<String> processGroupNodes = curatorClient.getChildren().forPath(backendZNodePath);
      Set<Recorder.ProcessGroup> processGroups = new HashSet<>();

      for(String processGroupZNodeName: processGroupNodes) {
        String processGroupZNodePath = getZNodePathForProcessGroup(backendIPAddress, processGroupZNodeName);
        Recorder.ProcessGroup processGroup = Recorder.ProcessGroup.parseFrom(ZookeeperUtil.readZNode(curatorClient, processGroupZNodePath));
        if(processGroupToZNodePathLookup.get(processGroup) != null) {
          throw new AssociationException("Found multiple nodes in zookeeper backend association tree for same process group, aborting load from ZK. Process group=" +
              ProtoUtil.processGroupCompactRepr(processGroup));
        }
        processGroupToZNodePathLookup.put(processGroup, processGroupZNodePath);
        processGroups.add(processGroup);
      }
      this.backendDetailLookup.put(backendIPAddress, new BackendDetail(backendIPAddress, loadReportIntervalInSeconds, loadMissTolerance, processGroups));
    }
  }

  private String getZNodePathForBackend(String backendIPAddress) {
    return backendAssociationPath + "/" + backendIPAddress;
  }

  private String getZNodePathForProcessGroup(String backendIPAddress, String processGroupZNodeName) {
    return getZNodePathForBackend(backendIPAddress) + "/" + processGroupZNodeName;
  }
}
