package fk.prof.backend.model.association.impl;

import fk.prof.backend.model.association.BackendAssociationStore;
import fk.prof.backend.model.association.BackendDetail;
import fk.prof.backend.model.association.ProcessGroupCountBasedBackendComparator;
import fk.prof.backend.proto.BackendDTO;
import fk.prof.backend.util.ProtoUtil;
import fk.prof.backend.util.ZookeeperUtil;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.logging.Logger;
import io.vertx.core.logging.LoggerFactory;
import org.apache.curator.framework.CuratorFramework;
import recording.Recorder;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

public class ZookeeperBasedBackendAssociationStore implements BackendAssociationStore {
  private static Logger logger = LoggerFactory.getLogger(ZookeeperBasedBackendAssociationStore.class);

  private final Vertx vertx;
  private final CuratorFramework curatorClient;
  private final String backendAssociationPath;
  private final int reportingFrequencyInSeconds;
  private final int maxAllowedSkips;

  private final Map<String, BackendDetail> backendDetailLookup = new ConcurrentHashMap<>();
  private final Map<Recorder.ProcessGroup, String> processGroupToBackendLookup = new ConcurrentHashMap<>();
  private final PriorityQueue<BackendDetail> availableBackendsByPriority;
  private final ReentrantLock backendAssignmentLock = new ReentrantLock();

  private ZookeeperBasedBackendAssociationStore(Vertx vertx, CuratorFramework curatorClient, String backendAssociationPath,
                                                int reportingFrequencyInSeconds, int maxAllowedSkips,
                                                Comparator<BackendDetail> backendPriorityComparator)
      throws Exception {
    this.vertx = vertx;
    this.curatorClient = curatorClient;
    this.backendAssociationPath = backendAssociationPath;
    this.reportingFrequencyInSeconds = reportingFrequencyInSeconds;
    this.maxAllowedSkips = maxAllowedSkips;

    List<BackendDetail> existingBackendsInZookeeper = loadDataFromZookeeper();
    this.availableBackendsByPriority = new PriorityQueue<>(backendPriorityComparator);
    this.availableBackendsByPriority.addAll(existingBackendsInZookeeper);

    for(BackendDetail backendDetail: existingBackendsInZookeeper) {
      this.backendDetailLookup.put(backendDetail.getBackendIPAddress(), backendDetail);
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
    vertx.executeBlocking(future -> {
      try {
        BackendDetail backendDetail = backendDetailLookup.computeIfAbsent(backendIPAddress, (key) -> {
          try {
            BackendDetail updatedBackendDetail = new BackendDetail(key, reportingFrequencyInSeconds, maxAllowedSkips);
            String zNodePath = getZNodePathForBackend(key);
            ZookeeperUtil.writeZNode(curatorClient, zNodePath, BackendDetail.serializeProcessGroups(updatedBackendDetail.getAssociatedProcessGroups()), true);
            return updatedBackendDetail;
          } catch (Exception ex) {
            throw new RuntimeException(ex);
          }
        });

        /**
         * If backend was defunct earlier, acquire lock on available backend queue and add this backend there along with reporting load
         * If backend was available, don't incur cost of acquiring lock since backend will already be in queue, which is the case mostly. Report load and get out
         */
        boolean wasDefunct = backendDetail.isDefunct();
        if(wasDefunct) {
          try {
            boolean acquired = backendAssignmentLock.tryLock(2, TimeUnit.SECONDS);
            if (acquired) {
              backendDetail.reportLoad(payload.getLoad());
              availableBackendsByPriority.offer(backendDetail);
            } else {
              logger.warn("Timeout while acquiring lock on backend queue for reporting backend=" + backendIPAddress);
            }
          } catch (InterruptedException ex) {
            logger.warn("Interrupted while acquiring lock on backend queue for reporting backend=" + backendIPAddress, ex);
          } finally {
            backendAssignmentLock.unlock();
          }
        } else {
          backendDetail.reportLoad(payload.getLoad());
        }
        future.complete(BackendDetail.buildProcessGroupsProto(backendDetail.getAssociatedProcessGroups()));
      } catch (Exception ex) {
        future.fail(ex);
      }

    }, result.completer());
    return result;
  }

  @Override
  public Future<String> getAssociatedBackend(Recorder.ProcessGroup processGroup) {
    Future<String> result = Future.future();
    String existingBackendIPAddress = processGroupToBackendLookup.get(processGroup);
    vertx.executeBlocking(future -> {
      if(existingBackendIPAddress != null
          && !backendDetailLookup.get(existingBackendIPAddress).isDefunct()) {
        // Returning the existing assignment if some backend is something already assigned to this process group and it is not defunct
        logger.debug(String.format("process_group=%s, existing backend=%s, available",
            ProtoUtil.processGroupCompactRepr(processGroup), existingBackendIPAddress));
        future.complete(existingBackendIPAddress);
      } else {
        /**
         * Since a backend assignment and possible de-assignment is required, acquiring a lock to avoid race conditions. Examples:
         * => two recorders with same process group getting different backends assigned
         * => available backend queue seen as empty by some of the requests because assignments of all available backends were being updated in other requests.
         *    this can arise because during assignment, backend is dequeued from available backend queue and enqueued again post assignment
         * => competing zookeeper requests to de-assign a defunct backend
         */
        logger.debug(String.format("process_group=%s, existing backend=%s, defunct or null",
            ProtoUtil.processGroupCompactRepr(processGroup), existingBackendIPAddress));
        try {
          boolean acquired = backendAssignmentLock.tryLock(2, TimeUnit.SECONDS);
          if (acquired) {
            if (existingBackendIPAddress == null) {
              //This is a new process group and no backend has been assigned to this yet
              BackendDetail availableBackend = getAvailableBackendFromPriorityQueue();
              try {
                if (availableBackend == null) {
                  future.fail("No available backends are known to leader, cannot assign one to process_group=" + processGroup);
                } else {
                  try {
                    associateBackendWithProcessGroup(availableBackend, processGroup);
                    logger.debug(String.format("process_group=%s, new backend=%s",
                        ProtoUtil.processGroupCompactRepr(processGroup), availableBackend.getBackendIPAddress()));
                    future.complete(availableBackend.getBackendIPAddress());
                  } catch (Exception ex) {
                    future.fail(String.format("Cannot persist association of backend=%s with process_group=%s in zookeeper",
                        availableBackend.getBackendIPAddress(), processGroup));
                  }
                }
              } finally {
                if(availableBackend != null) {
                  availableBackendsByPriority.offer(availableBackend);
                }
              }
            } else {
              BackendDetail existingBackend = backendDetailLookup.get(existingBackendIPAddress);
              if(!existingBackend.isDefunct()) {
                /**
                 * Backend is not defunct, proceed with returning this assignment
                 * This is a double check, this condition is checked before acquiring the lock as well to avoid locking cost in majority of the cases
                 */
                logger.debug(String.format("process_group=%s, existing backend=%s, available",
                    ProtoUtil.processGroupCompactRepr(processGroup), existingBackend.getBackendIPAddress()));
                future.complete(existingBackendIPAddress);
              } else {
                /**
                 * Presently assigned backend is defunct, find an available backend and if found, de-associate current backend and assign the new one to process group
                 * Defunct backend is not de-associated eagerly because if no available backend is found, its better to wait for current backend to come back alive
                 */
                availableBackendsByPriority.remove(existingBackend);
                BackendDetail newBackend = getAvailableBackendFromPriorityQueue();
                try {
                  if (newBackend == null) {
                    logger.warn(String.format("Presently assigned backend=%s for process_group=%s is defunct but cannot find any available backend so keeping assignment unchanged",
                        existingBackendIPAddress, ProtoUtil.processGroupCompactRepr(processGroup)));
                    future.complete(existingBackendIPAddress);
                  } else {
                    try {
                      deAssociateBackendWithProcessGroup(existingBackend, processGroup);
                      associateBackendWithProcessGroup(newBackend, processGroup);
                      logger.debug(String.format("process_group=%s, de-associating existing backend=%s, associating new backend=%s",
                          ProtoUtil.processGroupCompactRepr(processGroup), existingBackend.getBackendIPAddress(), newBackend.getBackendIPAddress()));
                      future.complete(newBackend.getBackendIPAddress());
                    } catch (Exception ex) {
                      future.fail(String.format("Cannot persist association of backend=%s with process_group=%s in zookeeper",
                          newBackend.getBackendIPAddress(), processGroup));
                    }
                  }
                } finally {
                  if(newBackend != null) {
                    availableBackendsByPriority.offer(newBackend);
                  }
                }
              }
            }
          } else {
            future.fail("Timeout while acquiring lock on backend queue for process_group=" + processGroup);
          }
        } catch (InterruptedException ex) {
          future.fail(new RuntimeException("Interrupted while acquiring lock on backend queue for process_group=" + processGroup, ex));
        } finally {
          backendAssignmentLock.unlock();
        }
      }
    }, result.completer());

    return result;
  }

  /**
   * Finds an available backend from the queue. De-queues all backends which have become defunct since they were added
   * @return available backend or null if none found
   */
  private BackendDetail getAvailableBackendFromPriorityQueue() {
    BackendDetail availableBackend;
    while((availableBackend = availableBackendsByPriority.poll()) != null) {
      if(!availableBackend.isDefunct()) {
        return availableBackend;
      }
    }
    return (availableBackend == null || availableBackend.isDefunct()) ? null : availableBackend;
  }

  /**
   * Associates a backend with process group. Writes to in-memory store and zookeeper
   * NOTE: Ensure that the backend detail instance being modified has been removed from available backend queue prior to calling this method
   * @param backendDetail
   * @param processGroup
   * @throws Exception
   */
  private void associateBackendWithProcessGroup(BackendDetail backendDetail, Recorder.ProcessGroup processGroup)
      throws Exception {
    Set<Recorder.ProcessGroup> associatedProcessGroups = new HashSet<>(backendDetail.getAssociatedProcessGroups());
    associatedProcessGroups.add(processGroup);

    String zNodePath = getZNodePathForBackend(backendDetail.getBackendIPAddress());
    ZookeeperUtil.writeZNode(curatorClient, zNodePath, BackendDetail.serializeProcessGroups(associatedProcessGroups), false);

    backendDetail.associateProcessGroup(processGroup);
    processGroupToBackendLookup.put(processGroup, backendDetail.getBackendIPAddress());
  }

  /**
   * De associates a backend from process group. Writes to in-memory store and zookeeper
   * NOTE: Ensure that the backend detail instance being modified has been removed from available backend queue prior to calling this method
   * @param backendDetail
   * @param processGroup
   * @throws Exception
   */
  private void deAssociateBackendWithProcessGroup(BackendDetail backendDetail, Recorder.ProcessGroup processGroup)
      throws Exception {
    Set<Recorder.ProcessGroup> associatedProcessGroups = new HashSet<>(backendDetail.getAssociatedProcessGroups());
    associatedProcessGroups.remove(processGroup);

    String zNodePath = getZNodePathForBackend(backendDetail.getBackendIPAddress());
    ZookeeperUtil.writeZNode(curatorClient, zNodePath, BackendDetail.serializeProcessGroups(associatedProcessGroups), false);

    backendDetail.deAssociateProcessGroup(processGroup);
    processGroupToBackendLookup.remove(processGroup);
  }

  private List<BackendDetail> loadDataFromZookeeper() throws Exception {
    List<BackendDetail> backends = new ArrayList<>();
    List<String> backendIPAddresses = curatorClient.getChildren().forPath(backendAssociationPath);
    for(String backendIPAddress: backendIPAddresses) {
      String zNodePath = getZNodePathForBackend(backendIPAddress);
      backends.add(new BackendDetail(backendIPAddress, reportingFrequencyInSeconds, maxAllowedSkips, ZookeeperUtil.readZNode(curatorClient, zNodePath)));
    }

    return backends;
  }

  private String getZNodePathForBackend(String backendIPAddress) {
    return backendAssociationPath + "/" + backendIPAddress;
  }

  public static Builder newBuilder() {
    return new Builder();
  }

  public static class Builder {
    private CuratorFramework curatorClient;
    private String backendAssociationPath;
    private Integer reportingFrequencyInSeconds;
    private Integer maxAllowedSkips = 2;
    private Comparator<BackendDetail> backendPriorityComparator = new ProcessGroupCountBasedBackendComparator();

    public Builder setCuratorClient(CuratorFramework curatorClient) {
      this.curatorClient = curatorClient;
      return this;
    }

    public Builder setBackendAssociationPath(String backendAssociationPath) {
      this.backendAssociationPath = backendAssociationPath;
      return this;
    }

    public Builder setReportingFrequencyInSeconds(int reportingFrequencyInSeconds) {
      this.reportingFrequencyInSeconds = reportingFrequencyInSeconds;
      return this;
    }

    public Builder setMaxAllowedSkips(int maxAllowedSkips) {
      this.maxAllowedSkips = maxAllowedSkips;
      return this;
    }

    public Builder setBackedPriorityComparator(Comparator<BackendDetail> backedPriorityComparator) {
      this.backendPriorityComparator = backedPriorityComparator;
      return this;
    }

    public ZookeeperBasedBackendAssociationStore build(Vertx vertx) throws Exception {
      if(vertx == null) {
        throw new IllegalStateException("Vertx instance is required");
      }
      if(curatorClient == null) {
        throw new IllegalStateException("Curator client is required");
      }
      if(backendAssociationPath == null) {
        throw new IllegalStateException("Backend association path in zookeeper hierarchy is required");
      }
      if(reportingFrequencyInSeconds == null) {
        throw new IllegalStateException("Frequency of reporting of load to leader is required");
      }
      if(maxAllowedSkips == null) {
        throw new IllegalStateException("Max allowed skips of load reporting needs to be specified");
      }
      if(backendPriorityComparator == null) {
        throw new IllegalStateException("Priority comparator for backends is required");
      }

      return new ZookeeperBasedBackendAssociationStore(vertx, curatorClient, backendAssociationPath,
          reportingFrequencyInSeconds, maxAllowedSkips, backendPriorityComparator);
    }

  }
}
