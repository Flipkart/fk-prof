package fk.prof.userapi.api;

import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import com.google.common.io.BaseEncoding;
import fk.prof.aggregation.AggregatedProfileNamingStrategy;
import fk.prof.storage.AsyncStorage;
import fk.prof.userapi.Configuration;
import fk.prof.userapi.cache.CachedProfileNotFoundException;
import fk.prof.userapi.cache.ClusterAwareCache;
import fk.prof.userapi.model.*;
import fk.prof.userapi.util.Pair;
import io.vertx.core.*;

import java.nio.charset.Charset;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Collectors;

/**
 * Interacts with the {@link AsyncStorage} and the {@link ClusterAwareCache} based on invocations from controller.
 * Created by rohit.patiyal on 19/01/17.
 */
public class ProfileStoreAPIImpl implements ProfileStoreAPI {

    private static final String DELIMITER = "/";
    private static final String VERSION = "v0001";

    private final AsyncStorage asyncStorage;
    private final AggregatedProfileLoader profileLoader;

    private final WorkerExecutor workerExecutor;
    private final Vertx vertx;
    private final int loadTimeout;

    private Cache<String, AggregationWindowSummary> summaryCache;

    private final ClusterAwareCache clusterAwareCache;

    /* stores all requested futures that are waiting on file to be loaded from S3. If a file loading
    * is in progress, this map will contain its corresponding key */
    private final Map<String, FuturesList<AggregationWindowSummary>> filesBeingLoaded;

    public ProfileStoreAPIImpl(Vertx vertx, AsyncStorage asyncStorage, ClusterAwareCache clusterAwareCache, Configuration config) {
        this.asyncStorage = asyncStorage;
        this.profileLoader = new AggregatedProfileLoader(this.asyncStorage);

        this.vertx = vertx;
        this.workerExecutor = vertx.createSharedWorkerExecutor(config.getIoWorkerPool().getName(), config.getIoWorkerPool().getSize());
        this.loadTimeout = config.getProfileLoadTimeout();

        this.summaryCache = CacheBuilder.newBuilder()
            .weigher((k,v) -> 1)
            .maximumWeight(1000)
            .expireAfterAccess(config.getProfileRetentionDurationMin(), TimeUnit.MINUTES)
            .build();

        this.clusterAwareCache = clusterAwareCache;
        this.filesBeingLoaded = new ConcurrentHashMap<>();
    }

    private String getLastFromCommonPrefix(String commonPrefix) {
        String[] splits = commonPrefix.split(DELIMITER);
        return splits[splits.length - 1];
    }

    @Override
    public void getAppIdsWithPrefix(Future<Set<String>> appIds, String baseDir, String appIdPrefix) {
        /* TODO: move this prefix creation to {@link AggregatedProfileNamingStrategy} */
        String filterPrefix = (appIdPrefix == null) ? "" : appIdPrefix;
        getListingAtLevelWithPrefix(appIds, baseDir + DELIMITER + VERSION + DELIMITER, filterPrefix, true);
    }

    private void getListingAtLevelWithPrefix(Future<Set<String>> listings, String level, String objPrefix, boolean encoded) {
        CompletableFuture<Set<String>> commonPrefixesFuture = asyncStorage.listAsync(level, false);
        commonPrefixesFuture.thenApply(commonPrefixes -> {
            Set<String> objs = new HashSet<>();
            for (String commonPrefix : commonPrefixes) {
                String objName = getLastFromCommonPrefix(commonPrefix);
                objName = encoded ? decode(objName) : objName;

                if (objName.startsWith(objPrefix)) {
                    objs.add(objName);
                }
            }
            return objs;
        }).whenComplete((result, error) -> completeFuture(result, error, listings));
    }

    @Override
    public void getClusterIdsWithPrefix(Future<Set<String>> clusterIds, String baseDir, String appId, String clusterIdPrefix) {
        String filterPrefix = (clusterIdPrefix == null) ? "" : clusterIdPrefix;
        getListingAtLevelWithPrefix(clusterIds, baseDir + DELIMITER + VERSION + DELIMITER + encode(appId) + DELIMITER,
                filterPrefix, true);
    }

    @Override
    public void getProcNamesWithPrefix(Future<Set<String>> procNames, String baseDir, String appId, String clusterId, String procPrefix) {
        String filterPrefix = (procPrefix == null) ? "" : procPrefix;
        getListingAtLevelWithPrefix(procNames, baseDir + DELIMITER + VERSION + DELIMITER + encode(appId) + DELIMITER + encode(clusterId) + DELIMITER,
                filterPrefix, true);
    }

    @Override
    public void getProfilesInTimeWindow(Future<List<AggregatedProfileNamingStrategy>> profiles, String baseDir, String appId, String clusterId, String proc, ZonedDateTime startTime, int durationInSeconds) {
        LocalDate startDate = ZonedDateTime.ofInstant(startTime.toInstant(), ZoneId.of("UTC")).toLocalDate();
        LocalDate endDate = ZonedDateTime.ofInstant(startTime.plusSeconds(durationInSeconds).toInstant(), ZoneId.of("UTC")).toLocalDate();
        LocalDate currentDate = startDate;
        String prefix = baseDir + DELIMITER + VERSION + DELIMITER + encode(appId)
                + DELIMITER + encode(clusterId) + DELIMITER + encode(proc) + DELIMITER;

        List<Future> allResults = new ArrayList<>();
        while (!currentDate.isAfter(endDate)) {
            String prefixWithDate = prefix + currentDate.toString();
            Future<List<AggregatedProfileNamingStrategy>> currResult = Future.future();
            allResults.add(currResult);
            asyncStorage.listAsync(prefixWithDate, true).thenApply(allObjects ->
                    allObjects.stream()
                            .map(AggregatedProfileNamingStrategy::fromFileName)
                            // filter by time and isSummary
                            .filter(s -> s.isSummaryFile &&
                                    s.startTime.isAfter(startTime.minusSeconds(1)) &&
                                    s.startTime.isBefore(startTime.plusSeconds(durationInSeconds))).collect(Collectors.toList())
            ).whenComplete((result, error) -> completeFuture(result, error, currResult));
            currentDate = currentDate.plus(1, ChronoUnit.DAYS);
        }

        CompositeFuture.all(allResults).setHandler(ar -> {
            if (ar.succeeded()) {
                List<List<AggregatedProfileNamingStrategy>> allProfiles = ar.result().list();
                List<AggregatedProfileNamingStrategy> flattenedProfiles = allProfiles.stream().reduce(new ArrayList<>(), (list1, list2) -> {
                    list1.addAll(list2);
                    return list1;
                });
                flattenedProfiles.sort(Comparator.comparing(agg -> agg.startTime));
                profiles.complete(flattenedProfiles);
            } else {
                profiles.fail(ar.cause());
            }
        });
    }

    public void load(Future<AggregatedProfileInfo> future, AggregatedProfileNamingStrategy filename) {
        clusterAwareCache.getAggregatedProfile(filename).setHandler(future.completer());
    }

    public void loadSummary(Future<AggregationWindowSummary> future, AggregatedProfileNamingStrategy filename) {
        if(!filename.isSummaryFile) {
            future.fail(new IllegalArgumentException(filename.getFileName(0) + " is not a summaryFile"));
            return;
        }

        String fileNameKey = filename.getFileName(0);
        AggregationWindowSummary cachedProfileInfo = summaryCache.getIfPresent(fileNameKey);
        if(cachedProfileInfo == null) {
            boolean fileLoadInProgress = filesBeingLoaded.containsKey(fileNameKey);
            saveRequestedFutureInMap(fileNameKey, future, filesBeingLoaded);

            // set the timeout for this future
            vertx.setTimer(loadTimeout, timerId -> timeoutRequestedFutureInMap(fileNameKey, future, filesBeingLoaded));

            if(!fileLoadInProgress) {
                workerExecutor.executeBlocking((Future<AggregationWindowSummary> f) -> profileLoader.loadSummary(f, filename),
                    true,
                    result -> {
                        if(result.succeeded()) {
                            summaryCache.put(fileNameKey, result.result());
                        }
                        filesBeingLoaded.remove(fileNameKey).complete(result.map(r -> r));
                    });
            }
        } else {
            future.complete(cachedProfileInfo);
        }
    }

    @Override
    public <T extends TreeView> Future<Pair<AggregatedSamplesPerTraceCtx, T>> getCPUSamplingTreeView(AggregatedProfileNamingStrategy profileName, String traceName, StacktraceTreeViewType viewType) {
        Future<Pair<AggregatedSamplesPerTraceCtx, T>> result = Future.future();

        Future<Pair<AggregatedSamplesPerTraceCtx, T>> f = clusterAwareCache.getView(profileName, traceName, viewType);
        f.setHandler(ar -> {
            if (ar.failed() && ar.cause() instanceof CachedProfileNotFoundException && !((CachedProfileNotFoundException) ar.cause()).isCachedRemotely()) {
                // initiate the load locally
                Future<AggregatedProfileInfo> profileLoad = Future.future();
                load(profileLoad, profileName);

                profileLoad.setHandler(ar2 -> {
                    Future<Pair<AggregatedSamplesPerTraceCtx, T>> f2 = clusterAwareCache.getView(profileName, traceName, viewType);
                    f2.setHandler(result.completer());
                });
            } else {

                result.handle(ar);
            }
        });
        return result;
    }

//    /**
//     * GetOrCreate a callTree view for the loaded profile. In case the profile is not loaded, it will be loaded locally
//     * first and then the view will be created.
//     * @param profileName
//     * @param traceName
//     * @return Future containing calltree view and the associated aggregated samples
//     */
//    @Override
//    public Future<Pair<AggregatedSamplesPerTraceCtx, CallTreeView>> getCpuSamplingCallersTreeView(AggregatedProfileNamingStrategy profileName, String traceName) {
//        Future<Pair<AggregatedSamplesPerTraceCtx, CallTreeView>> result = Future.future();
//        getTreeViewFromClusterAwareCache(profileName, traceName, result, StacktraceTreeViewType.CALLERS);
//
//    }

//    @Override
//    public  <T extends Cacheable<TreeView> & TreeView> Future<Pair<AggregatedSamplesPerTraceCtx, T>> getTreeViewFromClusterAwareCache(AggregatedProfileNamingStrategy profileName, String traceName,
//                                                                                                                           StacktraceTreeViewType viewType) {
//        Future<Pair<AggregatedSamplesPerTraceCtx, T>> result = Future.future();
//
//        Future<Pair<AggregatedSamplesPerTraceCtx, T>> f = clusterAwareCache.getView(profileName, traceName, viewType);
//        f.setHandler(ar -> {
//            if (ar.failed() && ar.cause() instanceof CachedProfileNotFoundException && !((CachedProfileNotFoundException) ar.cause()).isCachedRemotely()) {
//                // initiate the load locally
//                Future<AggregatedProfileInfo> profileLoad = Future.future();
//                load(profileLoad, profileName);
//
//                profileLoad.setHandler(ar2 -> {
//                    Future<Pair<AggregatedSamplesPerTraceCtx, T>> f2 = clusterAwareCache.getView(profileName, traceName, viewType);
//                    f2.setHandler(result.completer());
//                });
//            } else {
//
//                result.handle(ar);
//            }
//        });
//        return result;
//    }

//    /**
//     * GetOrCreate a calleeTree view for the loaded profile. In case the profile is not loaded, it will be loaded locally
//     * first and then the view will be created.
//     * @param profileName
//     * @param traceName
//     * @return Future containing calleetree view and the associated aggregated samples
//     */
//    @Override
//    public Future<Pair<AggregatedSamplesPerTraceCtx, CalleesTreeView>> getCpuSamplingCalleesTreeView(AggregatedProfileNamingStrategy profileName, String traceName) {
//        Future<Pair<AggregatedSamplesPerTraceCtx, CalleesTreeView>> result = Future.future();
//        getTreeViewFromClusterAwareCache(profileName, traceName, result, StacktraceTreeViewType.CALLEES);
//        return result;
//    }

    private <T> void saveRequestedFutureInMap(String filename, Future<T> future, Map<String, FuturesList<T>> futuresListMap) {
        FuturesList<T> futures = futuresListMap.get(filename);
        if (futures == null) {
            futures = new FuturesList<>();
            futuresListMap.put(filename, futures);
        }
        futures.addFuture(future);
    }

    private <T> void timeoutRequestedFutureInMap(String filename, Future<T> future, Map<String, FuturesList<T>> filesBeingLoaded) {
        if (future.isComplete()) {
            return;
        }
        FuturesList<T> futures = filesBeingLoaded.get(filename);
        futures.removeFuture(future);
        future.fail(new TimeoutException("timeout while waiting for file to loadFromInputStream from store: " + filename));
    }

    private <T> void completeFuture(T result, Throwable error, Future<T> future) {
        if (error == null) {
            future.complete(result);
        } else {
            future.fail(error);
        }
    }

    private String encode(String str) {
        return BaseEncoding.base32().encode(str.getBytes(Charset.forName("utf-8")));
    }

    private String decode(String str) {
        return new String(BaseEncoding.base32().decode(str), Charset.forName("utf-8"));
    }

    private static class FuturesList<T> {
        List<Future<T>> futures = new ArrayList<>(2);

        synchronized void addFuture(Future<T> future) {
            if (!exists(future)) {
                futures.add(future);
            }
        }

        synchronized void removeFuture(Future<T> future) {
            futures.removeIf(f -> f == future);
        }

        synchronized public void complete(AsyncResult<T> result) {
            if (result.succeeded()) {
                futures.forEach(f -> f.complete(result.result()));
            } else {
                futures.forEach(f -> f.fail(result.cause()));
            }

            futures.clear();
        }

        private boolean exists(Future<T> future) {
            return futures.stream().anyMatch(f -> f == future);
        }
    }
}
