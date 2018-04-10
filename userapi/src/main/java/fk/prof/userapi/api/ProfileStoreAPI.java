package fk.prof.userapi.api;

import fk.prof.aggregation.AggregatedProfileNamingStrategy;
import fk.prof.userapi.model.ProfileView;
import fk.prof.userapi.model.*;
import fk.prof.userapi.util.Pair;
import io.vertx.core.Future;

import java.time.ZonedDateTime;
import java.util.List;
import java.util.Set;

/**
 * Interface for DataStores containing aggregated profile data
 * Created by rohit.patiyal on 23/01/17.
 */
public interface ProfileStoreAPI {
    /**
     * Returns future for the set of appIds filtered by the specified prefix. Returns all appIds if prefix is null,
     * else filter is applied based on prefix
     *
     * @param baseDir
     * @param appIdPrefix
     * @return completable future which returns set containing appids
     */
    Future<Set<String>> getAppIdsWithPrefix(String baseDir, String appIdPrefix);

    /**
     * Returns set of clusterIds of specified appId from the DataStore filtered by the specified prefix
     * Returns future with all clusterIds if prefix is null, else filters based on prefix
     *
     * @param baseDir
     * @param appId           appId of which the clusterIds are required
     * @param clusterIdPrefix prefix to filter the clusterIds
     * @return completable future which returns set containing cluster ids
     */
    Future<Set<String>> getClusterIdsWithPrefix(String baseDir, String appId, String clusterIdPrefix);

    /**
     * Returns set of procNames of specified appId and clusterId from the DataStore filtered by the specified prefix
     * Returns future with all procNames if prefix is null, else filters based on prefix
     *
     * @param baseDir
     * @param appId      appId of which the processes are required
     * @param clusterId  clusterId of which the processes are required
     * @param procPrefix prefix to filter the processes
     * @return completable future which returns set containing process names
     */
    Future<Set<String>> getProcNamesWithPrefix(String baseDir, String appId, String clusterId, String procPrefix);

    /**
     * Returns set of profiles of specified appId, clusterId and process from the DataStore filtered by the specified
     * time interval and duration
     *
     * @param baseDir
     * @param appId             appId of which the profiles are required
     * @param clusterId         clusterId of which the profiles are required
     * @param proc              process of which the profiles are required
     * @param startTime         startTime to filter the profiles
     * @param durationInSeconds duration from startTime to filter the profiles
     * @return completable future which returns set containing profiles
     */
    Future<List<AggregatedProfileNamingStrategy>> getProfilesInTimeWindow(String baseDir, String appId,
                                                                          String clusterId, String proc,
                                                                          ZonedDateTime startTime, int durationInSeconds);

    /**
     * Returns {@link AggregationWindowSummary} for the provided filename in a future
     *
     * @param filename  name of the profile.
     */
    Future<AggregationWindowSummary> loadSummary(AggregatedProfileNamingStrategy filename);

    /**
     * Get/Create a {@link ProfileView} for the provided profile name, trace name and of a specific type
     *
     * @param profileName       name of the profile file
     * @param traceName         name of the trace context
     * @param profileViewType   type of the profile view intended to be fetched
     * @param <T>               type implementing the {@link ProfileView} interface
     * @return Future containing pair of aggregated samples and corresponding view.
     */
    <T extends ProfileView> Future<Pair<AggregatedSamplesPerTraceCtx,T>>
    getProfileView(AggregatedProfileNamingStrategy profileName, String traceName, ProfileViewType profileViewType);
}
