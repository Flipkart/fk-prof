package fk.prof.userapi.api;

import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import com.google.common.io.BaseEncoding;
import fk.prof.aggregation.AggregatedProfileNamingStrategy;
import fk.prof.storage.AsyncStorage;
import fk.prof.userapi.Configuration;
import fk.prof.userapi.cache.ClusteredProfileCache;
import fk.prof.userapi.model.ProfileView;
import fk.prof.userapi.model.*;
import fk.prof.userapi.util.Pair;
import io.vertx.core.*;

import java.nio.charset.Charset;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Collectors;

/**
 * Interacts with the {@link AsyncStorage} and the {@link ClusteredProfileCache} based on invocations from controller.
 * Created by rohit.patiyal on 19/01/17.
 */
public class ProfileStoreAPIImpl implements ProfileStoreAPI {

    private static final String DELIMITER = "/";
    private static final String VERSION = "v0001";

    private final AsyncStorage asyncStorage;
    private final ProfileLoader profileLoader;

    private final WorkerExecutor workerExecutor;
    private final Vertx vertx;
    private final int loadTimeout;

    private final Cache<String, AggregationWindowSummary> summaryCache;

    private final ClusteredProfileCache profileCache;

    /* stores all requested futures that are waiting on file to be loaded from S3. If a file loading
    * is in progress, this map will contain its corresponding key */
    private final ConcurrentHashMap<String, FuturesList<AggregationWindowSummary>> filesBeingLoaded;

    public ProfileStoreAPIImpl(Vertx vertx, AsyncStorage asyncStorage, ProfileLoader profileLoader,
                               ClusteredProfileCache profileCache, WorkerExecutor workerExecutor,
                               Configuration config) {
        this.asyncStorage = asyncStorage;
        this.profileLoader = profileLoader;

        this.vertx = vertx;
        this.workerExecutor = workerExecutor;
        this.loadTimeout = config.getProfileLoadTimeout();

        this.summaryCache = CacheBuilder.newBuilder()
            .weigher((k,v) -> 1)
            .maximumWeight(config.getMaxProfileSummaryCacheWeight())
            .expireAfterAccess(config.getProfileRetentionDurationMin(), TimeUnit.MINUTES)
            .build();

        this.profileCache = profileCache;
        this.filesBeingLoaded = new ConcurrentHashMap<>();
    }

    private String getLastFromCommonPrefix(String commonPrefix) {
        String[] splits = commonPrefix.split(DELIMITER);
        return splits[splits.length - 1];
    }

    @Override
    public Future<Set<String>> getAppIdsWithPrefix(String baseDir, String appIdPrefix) {
        /* TODO: move this prefix creation to {@link AggregatedProfileNamingStrategy} */
        String filterPrefix = (appIdPrefix == null) ? "" : appIdPrefix;
        return getListingAtLevelWithPrefix(baseDir + DELIMITER + VERSION + DELIMITER, filterPrefix, true);
    }

    private Future<Set<String>> getListingAtLevelWithPrefix(String level, String objPrefix, boolean encoded) {
        Future<Set<String>> listings = Future.future();

        asyncStorage.listAsync(level, false)
            .thenApply(commonPrefixes -> {
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

        return listings;
    }

    @Override
    public Future<Set<String>> getClusterIdsWithPrefix(String baseDir, String appId, String clusterIdPrefix) {
        String filterPrefix = (clusterIdPrefix == null) ? "" : clusterIdPrefix;
        return getListingAtLevelWithPrefix(baseDir + DELIMITER + VERSION + DELIMITER + encode(appId) + DELIMITER,
                filterPrefix, true);
    }

    @Override
    public Future<Set<String>> getProcNamesWithPrefix(String baseDir, String appId, String clusterId, String procPrefix) {
        String filterPrefix = (procPrefix == null) ? "" : procPrefix;
        return getListingAtLevelWithPrefix(baseDir + DELIMITER + VERSION + DELIMITER + encode(appId) + DELIMITER + encode(clusterId) + DELIMITER,
                filterPrefix, true);
    }

    @Override
    public Future<List<AggregatedProfileNamingStrategy>> getProfilesInTimeWindow(String baseDir, String appId,
                                                                                 String clusterId, String proc,
                                                                                 ZonedDateTime startTime,
                                                                                 int durationInSeconds) {
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

        Future<List<AggregatedProfileNamingStrategy>> profiles = Future.future();
        CompositeFuture.all(allResults).setHandler(ar -> {
            if (ar.succeeded()) {
                List<List<AggregatedProfileNamingStrategy>> allProfiles = ar.result().list();
                profiles.complete(
                    allProfiles.stream()
                        .flatMap(List::stream)
                        .sorted(Comparator.comparing(agg -> agg.startTime))
                        .collect(Collectors.toList()));
            } else {
                profiles.fail(ar.cause());
            }
        });

        return profiles;
    }

    public Future<AggregationWindowSummary> loadSummary(AggregatedProfileNamingStrategy filename) {
        String fileNameKey = filename.getFileName(0);
        if(!filename.isSummaryFile) {
            return Future.failedFuture(new IllegalArgumentException(fileNameKey + " is not a summaryFile"));
        }

        AggregationWindowSummary cachedProfileInfo = summaryCache.getIfPresent(fileNameKey);
        if(cachedProfileInfo == null) {
            return loadAndCacheSummary(filename);
        }

        return Future.succeededFuture(cachedProfileInfo);
    }

    @Override
    public <T extends ProfileView> Future<Pair<AggregatedSamplesPerTraceCtx, T>>
    getProfileView(AggregatedProfileNamingStrategy profileName, String traceName, ProfileViewType profileViewType) {
        return profileCache.getProfileView(profileName, traceName, profileViewType);
    }

    /**
     * Loads aggregated profile summary and caches the result for subsequent loads
     * @param filename
     * @return AggregationWindowSummary Future
     */
    private Future<AggregationWindowSummary> loadAndCacheSummary(AggregatedProfileNamingStrategy filename) {
        String cacheKey = filename.getFileName(0);
        Future<AggregationWindowSummary> future = Future.future();

        filesBeingLoaded.compute(cacheKey, (k,v) -> {
            AggregationWindowSummary summary = summaryCache.getIfPresent(cacheKey);
            // able to get the value, just complete it
            if(summary != null) {
                future.complete(summary);
                return v;
            }

            boolean firstRequest = (v == null || v.size() == 0);
            FuturesList<AggregationWindowSummary> futuresList = (v == null ? new FuturesList<>() : v);
            futuresList.addFuture(future);

            vertx.setTimer(loadTimeout, timerId -> {
                futuresList.removeFuture(future);
                timeoutFuture(filename, future);
            });

            if(firstRequest) {
                startLoadingSummary(filename).setHandler(ar -> finishLoadingSummary(cacheKey, futuresList, ar));
            }

            return futuresList;
        });

        return future;
    }

    private Future<AggregationWindowSummary> startLoadingSummary(AggregatedProfileNamingStrategy filename) {
        Future<AggregationWindowSummary> future = Future.future();
        workerExecutor.executeBlocking((Future<AggregationWindowSummary> f) -> {
                try {
                    f.complete(profileLoader.loadSummary(filename));
                } catch (Exception e) {
                    f.fail(e);
                }
            },
            future);
        return future;
    }

    private void finishLoadingSummary(String cacheKey, FuturesList<AggregationWindowSummary> futures,
                                      AsyncResult<AggregationWindowSummary> result) {
        if(result.succeeded()) {
            summaryCache.put(cacheKey, result.result());
        }
        futures.complete(result);
    }

    private void timeoutFuture(AggregatedProfileNamingStrategy filename, Future<AggregationWindowSummary> future) {
        if (future.isComplete()) {
            return;
        }

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

        synchronized void complete(AsyncResult<T> result) {
            futures.forEach(f -> {
                if (result.succeeded()) {
                    f.complete(result.result());
                }
                else {
                    f.fail(result.cause());
                }
            });

            futures.clear();
        }

        synchronized int size() {
            return futures.size();
        }

        private boolean exists(Future<T> future) {
            return futures.stream().anyMatch(f -> f == future);
        }
    }
}
