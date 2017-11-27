package fk.prof.userapi.api;

import fk.prof.aggregation.AggregatedProfileNamingStrategy;
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
     * Returns completable future which returns set of appIds from the DataStore filtered by the specified prefix
     * Returns future with all appIds if prefix is null, else filters based on prefix
     *
     * @param appIdPrefix prefix to filter the appIds
     * @return completable future which returns set containing app ids
     */
    void getAppIdsWithPrefix(Future<Set<String>> appIds, String baseDir, String appIdPrefix);

    /**
     * Returns set of clusterIds of specified appId from the DataStore filtered by the specified prefix
     * Returns future with all clusterIds if prefix is null, else filters based on prefix
     *
     * @param appId           appId of which the clusterIds are required
     * @param clusterIdPrefix prefix to filter the clusterIds
     * @return completable future which returns set containing cluster ids
     */
    void getClusterIdsWithPrefix(Future<Set<String>> clusterIds, String baseDir, String appId, String clusterIdPrefix);

    /**
     * Returns set of procNames of specified appId and clusterId from the DataStore filtered by the specified prefix
     * Returns future with all procNames if prefix is null, else filters based on prefix
     *
     * @param appId      appId of which the processes are required
     * @param clusterId  clusterId of which the processes are required
     * @param procPrefix prefix to filter the processes
     * @return completable future which returns set containing process names
     */
    void getProcNamesWithPrefix(Future<Set<String>> procNames, String baseDir, String appId, String clusterId, String procPrefix);

    /**
     * Returns set of profiles of specified appId, clusterId and process from the DataStore filtered by the specified time interval and duration
     *
     * @param appId             appId of which the profiles are required
     * @param clusterId         clusterId of which the profiles are required
     * @param proc              process of which the profiles are required
     * @param startTime         startTime to filter the profiles
     * @param durationInSeconds duration from startTime to filter the profiles
     * @return completable future which returns set containing profiles
     */
    void getProfilesInTimeWindow(Future<List<AggregatedProfileNamingStrategy>> profiles, String baseDir, String appId, String clusterId, String proc, ZonedDateTime startTime, int durationInSeconds);

    /**
     * Returns aggregated profile info for the provided filename in a future
     *
     * @param future    future containing aggregated profile info for the provided filename
     * @param filename  name of the profile file constructed from a header
     */
    void load(Future<AggregatedProfileInfo> future, AggregatedProfileNamingStrategy filename);

    /**
     * Returns aggregation window summary for the provided filename in a future
     *
     * @param future    future containing aggregation window summary of the provided filename
     * @param filename  name of the profile file constructed from a header
     */
    void loadSummary(Future<AggregationWindowSummary> future, AggregatedProfileNamingStrategy filename);

    /**
     * Get/Create a TreeView for the provided profile name, trace name of a specific type
     *
     * @param profileName       name of the profile file
     * @param traceName         name of the trace context
     * @param profileViewType   type of the profile view intended to be get
     * @param <T>               type implementing the TreeView interface
     * @return Future containing pair of aggregated samples and TreeView of type T
     */
    <T extends TreeView> Future<Pair<AggregatedSamplesPerTraceCtx,T>> getProfileView(AggregatedProfileNamingStrategy profileName, String traceName, ProfileViewType profileViewType);
}
