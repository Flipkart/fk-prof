package fk.prof.userapi.api;


import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import com.google.common.io.BaseEncoding;
import fk.prof.aggregation.AggregatedProfileFileNamingStrategy;
import fk.prof.storage.AsyncStorage;
import fk.prof.userapi.model.AggregatedProfileInfo;
import fk.prof.userapi.model.FilteredProfiles;
import io.vertx.core.AsyncResult;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.WorkerExecutor;
import io.vertx.core.logging.Logger;
import io.vertx.core.logging.LoggerFactory;

import java.nio.charset.Charset;
import java.time.ZonedDateTime;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Collectors;

/**
 * Interacts with the {@link AsyncStorage} based on invocations from controller
 * Created by rohit.patiyal on 19/01/17.
 */
public class ProfileStoreAPIImpl implements ProfileStoreAPI {

    public static final String WORKER_POOL_NAME = "aggregation.loader.pool";
    public static final int WORKER_POOL_SIZE = 10;
    public static final int DEFAULT_LOAD_TIMEOUT = 10000;   // in ms
    private static final String VERSION = "v0001";
    private static final String DELIMITER = "/";
    private static Logger LOGGER = LoggerFactory.getLogger(ProfileStoreAPIImpl.class);
    private int loadTimeout = DEFAULT_LOAD_TIMEOUT;
    private Vertx vertx;
    private AsyncStorage asyncStorage;
    private AggregatedProfileLoader profileLoader;
    private int maxIdleRetentionInMin;

    private WorkerExecutor workerExecutor;

    private Cache<String, AggregatedProfileInfo> cache;

    /* stores all requested futures that are waiting on file to be loaded from S3. If a file load
    * is in progress, this map will contain its corresponding key */
    private Map<String, FuturesList<AggregatedProfileInfo>> futuresForLoadingFiles;

    public ProfileStoreAPIImpl(Vertx vertx, AsyncStorage asyncStorage, int maxIdleRetentionInMin) {
        this.vertx = vertx;
        this.asyncStorage = asyncStorage;
        this.profileLoader = new AggregatedProfileLoader(this.asyncStorage);
        this.maxIdleRetentionInMin = maxIdleRetentionInMin;

        this.workerExecutor = vertx.createSharedWorkerExecutor(WORKER_POOL_NAME, WORKER_POOL_SIZE);

        this.cache = CacheBuilder.newBuilder()
                .maximumSize(50)
                .expireAfterAccess(this.maxIdleRetentionInMin, TimeUnit.MINUTES)
                .build();

        this.futuresForLoadingFiles = new HashMap<>();
    }

    private static Pair<ZonedDateTime, ZonedDateTime> getInterval(AggregatedProfileFileNamingStrategy fileName) {
        return new Pair(fileName.startTime, fileName.startTime.plusSeconds(fileName.duration));
    }

    private String getLastFromCommonPrefix(String commonPrefix) {
        String[] splits = commonPrefix.split(DELIMITER);
        return splits[splits.length - 1];
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
    public void getAppIdsWithPrefix(Future<Set<String>> appIds, String baseDir, String appIdPrefix) {
        getListingAtLevelWithPrefix(appIds, baseDir + DELIMITER + VERSION + DELIMITER, appIdPrefix, true);
    }

    @Override
    public void getClusterIdsWithPrefix(Future<Set<String>> clusterIds, String baseDir, String appId, String clusterIdPrefix) {
        getListingAtLevelWithPrefix(clusterIds, baseDir + DELIMITER + VERSION + DELIMITER + encode(appId) + DELIMITER,
                clusterIdPrefix, true);
    }

    @Override
    public void getProcsWithPrefix(Future<Set<String>> procIds, String baseDir, String appId, String clusterId, String procPrefix) {
        getListingAtLevelWithPrefix(procIds, baseDir + DELIMITER + VERSION + DELIMITER + encode(appId) + DELIMITER + encode(clusterId) + DELIMITER,
                procPrefix, false);
    }

    @Override
    public void getProfilesInTimeWindow(Future<Set<FilteredProfiles>> profiles, String baseDir, String appId, String clusterId, String proc, ZonedDateTime startTime, int durationInSeconds) {
        String prefix = baseDir + DELIMITER + VERSION + DELIMITER + encode(appId)
                + DELIMITER + encode(clusterId) + DELIMITER + proc + DELIMITER;

        asyncStorage.listAsync(prefix, true).thenApply(allObjects -> {
            ZonedDateTime start = startTime.minusSeconds(1);
            ZonedDateTime end = startTime.plusSeconds(durationInSeconds + 1);

            return allObjects.stream()
                    .map(AggregatedProfileFileNamingStrategy::fromFileName)
                    // filter by time
                    .filter(s -> s.startTime.isAfter(start) && s.startTime.isBefore(end))
                    //Groups all strings by their time interval (as profiles) and collects the corresponding worktypes in a set as its value.
                    .collect(Collectors.groupingBy(ProfileStoreAPIImpl::getInterval, Collectors.mapping(s -> s.workType.name(), Collectors.toSet())))
                    // convert the result to set of found Profiles
                    .entrySet().stream()
                    .map(kv -> new FilteredProfiles(kv.getKey().first, kv.getKey().second, kv.getValue())).collect(Collectors.toSet());
        }).whenComplete((result, error) -> completeFuture(result, error, profiles));
    }

    @Override
    synchronized public void load(Future<AggregatedProfileInfo> future, AggregatedProfileFileNamingStrategy filename) {
        String fileNameKey = filename.getFileName(0);

        AggregatedProfileInfo cachedProfileInfo = cache.getIfPresent(fileNameKey);
        if(cachedProfileInfo == null) {
            boolean fileLoadInProgress = futuresForLoadingFiles.containsKey(fileNameKey);
            // save the future, so that it can be notified when the loading process finishes
            saveRequestedFuture(fileNameKey, future);
            // set the timeout for this future
            vertx.setTimer(loadTimeout, timerId -> timeoutRequestedFuture(fileNameKey, future));

            if(!fileLoadInProgress) {
                workerExecutor.executeBlocking((Future<AggregatedProfileInfo> f) -> profileLoader.load(f, filename),
                        true,
                        result -> completeAggregatedProfileLoading(result, fileNameKey));
            }
        }
        else {
            future.complete(cachedProfileInfo);
        }
    }

    synchronized private void saveRequestedFuture(String filename, Future<AggregatedProfileInfo> future) {
        FuturesList futures = futuresForLoadingFiles.get(filename);
        if(futures == null) {
            futures = new FuturesList();
            futuresForLoadingFiles.put(filename, futures);
        }
        futures.addFuture(future);
    }

    synchronized private void timeoutRequestedFuture(String filename, Future<AggregatedProfileInfo> future) {
        if(future.isComplete()) {
            return;
        }
        FuturesList futures = futuresForLoadingFiles.get(filename);
        futures.removeFuture(future);

        future.fail(new TimeoutException("timeout for: " + filename));
    }

    synchronized private void completeAggregatedProfileLoading(AsyncResult<AggregatedProfileInfo> result, String filename) {
        if(result.succeeded()) {
            cache.put(filename, result.result());
        }

        // complete all dependent futures
        FuturesList futures = futuresForLoadingFiles.get(filename);
        futures.complete(result);

        futuresForLoadingFiles.remove(filename);
    }

    private <T> void completeFuture(T result, Throwable error, Future<T> future) {
        if(error == null) {
            future.complete(result);
        }
        else {
            future.fail(error);
        }
    }

    private String encode(String str) {
        return BaseEncoding.base32().encode(str.getBytes(Charset.forName("utf-8")));
    }

    private String decode(String str) {
        return new String(BaseEncoding.base32().decode(str), Charset.forName("utf-8"));
    }

    private static class Pair<T,U> {
        public T first;
        public U second;
        Pair(T first, U second) {
            this.first = first;
            this.second = second;
        }

        @Override
        public int hashCode() {
            int hash = 59;
            if (first != null) {
                hash = 31 * hash + first.hashCode();
            }
            if(second != null) {
                hash = 31 * hash + second.hashCode();
            }
            return hash;
        }

        @Override
        public boolean equals(Object that) {
            if(that == null || !(that instanceof Pair)) {
                return false;
            }

            return Objects.equals(this.first, ((Pair) that).first) && Objects.equals(this.second, ((Pair) that).second);
        }
    }

    private static class FuturesList<T> {
        List<Future<T>> futures = new ArrayList<>(2);

        synchronized public void addFuture(Future<T> future) {
            if(!exists(future)) {
                futures.add(future);
            }
        }

        synchronized public void removeFuture(Future<T> future) {
            futures.removeIf(f -> f == future);
        }

        synchronized public void complete(AsyncResult<T> result) {
            if(result.succeeded()) {
                futures.forEach(f -> f.complete(result.result()));
            }
            else {
                futures.forEach(f -> f.fail(result.cause()));
            }

            futures.clear();
        }

        private boolean exists(Future<T> future) {
            return futures.stream().anyMatch(f -> f == future);
        }
    }
}
