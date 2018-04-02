package fk.prof.userapi.cache;

import fk.prof.aggregation.AggregatedProfileNamingStrategy;

/**
 * Interface for a shared storage which stores the mapping profile -> ip:port and maintains resource utilization counters
 * for each node in cluster.
 * It is used by {@link ClusterAwareCache}.
 */
public interface CacheInfoRegistry {

    void onInit() throws Exception;

    void claimOwnership(AggregatedProfileNamingStrategy profileName) throws Exception;

    void releaseOwnership(AggregatedProfileNamingStrategy profileName) throws Exception;
}
