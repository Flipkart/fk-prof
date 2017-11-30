package fk.prof.userapi.cache;

import fk.prof.aggregation.AggregatedProfileNamingStrategy;
import fk.prof.userapi.proto.LoadInfoEntities;

/**
 * Interface for a shared storage which stores the mapping profile -> ip:port and maintains resource utilization counters
 * for each node in cluster.
 * It is used by {@link ClusterAwareCache}.
 *
 * @see LoadInfoEntities.NodeLoadInfo
 * @see LoadInfoEntities.ProfileResidencyInfo
 *
 */
public interface LoadInfoStore {
  void init() throws Exception;

  boolean profileResidencyInfoExists(AggregatedProfileNamingStrategy profileName) throws Exception;

  boolean nodeLoadInfoExists() throws Exception;

  LoadInfoEntities.ProfileResidencyInfo readProfileResidencyInfo(AggregatedProfileNamingStrategy profileName) throws Exception;

  LoadInfoEntities.NodeLoadInfo readNodeLoadInfo() throws Exception;

  void updateProfileResidencyInfo(AggregatedProfileNamingStrategy profileName, boolean profileResidencyInfoExists) throws Exception;

  void removeProfileResidencyInfo(AggregatedProfileNamingStrategy profileName, boolean deleteProfileResidencyInfo) throws Exception;

  LoadInfoEntities.NodeLoadInfo buildNodeLoadInfo(int profileCount);

}
