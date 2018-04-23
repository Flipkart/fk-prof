package fk.prof.userapi.cache;

import com.codahale.metrics.Meter;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.cache.RemovalCause;
import com.google.common.cache.RemovalNotification;
import fk.prof.aggregation.AggregatedProfileNamingStrategy;
import fk.prof.metrics.MetricName;
import fk.prof.metrics.Util;
import fk.prof.userapi.Configuration;
import fk.prof.userapi.api.DeserializationException;
import fk.prof.userapi.api.ProfileLoader;
import fk.prof.userapi.api.ProfileViewCreator;
import fk.prof.userapi.model.AggregatedProfileInfo;
import fk.prof.userapi.model.AggregatedSamplesPerTraceCtx;
import fk.prof.userapi.model.ProfileViewType;
import fk.prof.userapi.model.ProfileView;
import fk.prof.userapi.util.Pair;
import io.vertx.core.Future;
import io.vertx.core.WorkerExecutor;
import io.vertx.core.logging.Logger;
import io.vertx.core.logging.LoggerFactory;
import org.apache.curator.framework.CuratorFramework;

import java.util.Optional;

/**
 * Aggregated Profiles Cache that caches profiles after loading them and updates the mapping profile -> ip:port into the
 * shared store (zookeeper in this case).
 *
 * @see LocalProfileCache
 * @see ZKBasedCacheInfoRegistry
 *
 * Created by gaurav.ashok on 21/06/17.
 */
public class ClusteredProfileCache {
    private static final Logger logger = LoggerFactory.getLogger(ClusteredProfileCache.class);
    private final Meter profileLoadFailureCounter = Util.meter(MetricName.Profile_Load_Intermittent_Failures.get());

    private final LocalProfileCache cache;

    private final WorkerExecutor workerExecutor;
    private final ProfileLoader profileLoader;

    private final CacheInfoRegistry cacheInfoRegistry;
    private final String selfIp;
    private final int port;

    public ClusteredProfileCache(CuratorFramework curatorClient, ProfileLoader profileLoader,
                                 ProfileViewCreator viewCreator, WorkerExecutor workerExecutor, Configuration config) {
        this(curatorClient, profileLoader, workerExecutor, config, new LocalProfileCache(config, viewCreator));
    }

    @VisibleForTesting
    ClusteredProfileCache(CuratorFramework curatorClient, ProfileLoader profileLoader,
                          WorkerExecutor workerExecutor, Configuration config, LocalProfileCache localCache) {
        this.selfIp = config.getIpAddress();
        this.port = config.getHttpConfig().getHttpPort();

        this.workerExecutor = workerExecutor;
        this.profileLoader = profileLoader;

        this.cache = localCache;
        this.cache.setRemovalListener(this::doCleanUpOnEviction);

        this.cacheInfoRegistry = new ZKBasedCacheInfoRegistry(curatorClient, selfIp, port, this.cache::invalidateCache);
    }

    public Future<Void> onClusterJoin() {
        return doAsync(() -> {
            cacheInfoRegistry.onInit();
            return null;
        });
    }

    /**
     * Returns the aggregated profile if already cached. If the profile is not cached locally or remotely the loading is
     * initiated.
     * The returned future will fail with
     * - {@link CachedProfileNotFoundException} if found on other node.
     * - {@link ProfileLoadInProgressException} if the profile load is in progress.
     *
     * @param profileName name of the profile
     * @return Future of AggregatedProfileInfo
     *
     * leaving it at default access modifier for testing
     */
    Future<AggregatedProfileInfo> getAggregatedProfile(AggregatedProfileNamingStrategy profileName) {
        try {
            Optional<AggregatedProfileInfo> profile = getFromLocalCache(profileName);
            if(profile.isPresent()) {
                return Future.succeededFuture(profile.get());
            }
        } catch (Exception e) {
            return Future.failedFuture(e);
        }

        // check zookeeper if it is loaded somewhere else
        return doAsync(() -> {
            synchronized (this) {
                Optional<AggregatedProfileInfo> cachedProfile = getFromLocalCache(profileName);  //synchronized double check
                if (cachedProfile.isPresent()) {
                    return cachedProfile.get();
                }

                cacheInfoRegistry.claimOwnership(profileName);

                // able to claim the ownership. start loading it.
                Future<AggregatedProfileInfo> profile = loadProfile(profileName);
                if (profile.isComplete()) {
                    if(profile.succeeded()) return profile.result();
                    else throw new CachedProfileNotFoundException(profile.cause());
                }

                throw new ProfileLoadInProgressException(profileName);    //fail because loading is still not complete
            }
        });
    }

    /**
     * Main method to get the already cached view / create view for the cached profile. If the profile is not cached,
     * the return future will fail according to {@code getAggregatedProfile}.
     *
     * @param profileName name of the profile
     * @param traceName name of the trace
     * @param profileViewType type of profileView
     * @return Future containing a pair of trace specific aggregated samples and its view.
     */
    public <T extends ProfileView>
    Future<Pair<AggregatedSamplesPerTraceCtx, T>> getProfileView(AggregatedProfileNamingStrategy profileName,
                                                                 String traceName, ProfileViewType profileViewType) {
        Future<Pair<AggregatedSamplesPerTraceCtx, T >> viewFuture = Future.future();
        Pair<Future<AggregatedProfileInfo>, Cacheable<ProfileView>> profileViewPair = cache.getView(profileName, traceName, profileViewType);

        if (profileViewPair.first != null) {
            if (profileViewPair.second != null) {
                return Future.succeededFuture(
                    Pair.of(profileViewPair.first.result().getAggregatedSamples(traceName), (T) profileViewPair.second));
            }

            return doAsync(() -> getOrCreateView(profileName, traceName, profileViewType));
        }

        // profile not found, so initiate the profile loading and compute view after that
        getAggregatedProfile(profileName).setHandler(ar -> {
            if(ar.failed()) {
                viewFuture.fail(ar.cause());
                return;
            }

            try {
                viewFuture.complete(getOrCreateView(profileName, traceName, profileViewType));
            } catch (Exception e) {
                viewFuture.fail(e);
            }
        });

        return viewFuture;
    }

    private Future<AggregatedProfileInfo> loadProfile(AggregatedProfileNamingStrategy profileName) {
        Future<AggregatedProfileInfo> future = doAsync(() -> profileLoader.load(profileName));

        cache.put(profileName, future);
        future.setHandler(ar -> {
            if(ar.failed()) {
                logger.error("Profile load complete in failure: {}", profileName, ar.cause());
            }
            else {
                logger.info("Profile load complete: {}", profileName);
            }

            // load_profile might fail, regardless reinsert to take the new utilization into account.

            // ignore in case if intermittent issues
            if(ar.failed() && !(ar.cause() instanceof DeserializationException)) {
                profileLoadFailureCounter.mark();
                doAsync(() -> {
                    try {
                        cacheInfoRegistry.releaseOwnership(profileName);
                    } finally {
                        // invalidate the key
                        cache.remove(profileName);
                    }
                    return null;
                });
            }
            else {
                cache.put(profileName, future);
            }
        });
        return future;
    }

    /**
     * Helper method to get the value from local cache. If the profile and corresponding future exists, then
     * @param profileName
     * @return Optional<AggregatedProfileInfo> if loaded profile is present.
     *         Optional.empty() if the cache has no information.
     * @throws ProfileLoadInProgressException if last request is still in progress
     * @throws CachedProfileNotFoundException if last request failed to load
     */
    private Optional<AggregatedProfileInfo> getFromLocalCache(AggregatedProfileNamingStrategy profileName)
        throws ProfileLoadInProgressException, CachedProfileNotFoundException {

        Future<AggregatedProfileInfo> cachedProfileInfo = cache.get(profileName);

        if (cachedProfileInfo == null) {
            return Optional.empty();
        }

        if(!cachedProfileInfo.isComplete()) {
            throw new ProfileLoadInProgressException(profileName);
        }
        else if (!cachedProfileInfo.succeeded()) {
            throw new CachedProfileNotFoundException(cachedProfileInfo.cause());
        }

        return Optional.of(cachedProfileInfo.result());
    }

    /**
     * Event handler for evicted profiles. Deletes the profile -> ip:port mapping fom shared store.
     * @param onRemoval the on removal notification received on an entry removal
     */
    private void doCleanUpOnEviction(RemovalNotification<AggregatedProfileNamingStrategy, Future<AggregatedProfileInfo>> onRemoval) {
        if(!RemovalCause.REPLACED.equals(onRemoval.getCause()) && onRemoval.wasEvicted()) {
            AggregatedProfileNamingStrategy profileName = onRemoval.getKey();
            doAsync(() -> {
                cacheInfoRegistry.releaseOwnership(profileName);
                return null;
            }, "Error while cleaning for file: {}", profileName.toString());
        }
    }

    /**
     * Helper method to get/create the view once it has been established that the profile is cached locally.
     * @param profileName name of the profile
     * @param traceName name of the trace
     * @param profileViewType type of the profileView to be get/created
     */
    private <T extends ProfileView> Pair<AggregatedSamplesPerTraceCtx, T> getOrCreateView(
        AggregatedProfileNamingStrategy profileName, String traceName, ProfileViewType profileViewType) throws Exception {

        Pair<Future<AggregatedProfileInfo>, Cacheable<ProfileView>> profileViewPair = cache.getView(profileName, traceName, profileViewType);
        Future<AggregatedProfileInfo> cachedProfileInfo = profileViewPair.first;

        if (cachedProfileInfo != null) {
            if (!cachedProfileInfo.isComplete()) {
                throw new ProfileLoadInProgressException(profileName);
            }

            // unlikely case where profile load failed
            if (!cachedProfileInfo.succeeded()) {
                throw new CachedProfileNotFoundException(cachedProfileInfo.cause());
            }

            AggregatedSamplesPerTraceCtx samplesPerTraceCtx = cachedProfileInfo.result().getAggregatedSamples(traceName);
            if (profileViewPair.second != null) {
                return Pair.of(samplesPerTraceCtx, (T)profileViewPair.second);
            }

            // no cached view, so create a new one
            switch (profileName.workType) {
                case cpu_sample_work:
                    profileViewPair = cache.computeViewIfAbsent(profileName, traceName, profileViewType);
                    if(profileViewPair.second == null) {
                        throw new CachedProfileNotFoundException();
                    }
                    return Pair.of(profileViewPair.first.result().getAggregatedSamples(traceName), (T)profileViewPair.second);

                default:
                    throw new IllegalArgumentException("Unsupported workType: " + profileName.workType);
            }
        }

        throw new CachedProfileNotFoundException();
    }

    interface BlockingTask<T> {
        T getResult() throws Exception;
    }

    private <T> Future<T> doAsync(BlockingTask<T> t) {
        return doAsync(t, false, "");
    }

    private <T> Future<T> doAsync(BlockingTask<T> t, String failMsg, Object... objects) {
        return doAsync(t, true, failMsg, objects);
    }

    private <T> Future<T> doAsync(BlockingTask<T> t, boolean logError, String failMsg, Object... objects) {
        Future<T> result = Future.future();
        workerExecutor.executeBlocking(f -> {
            try {
                f.complete(t.getResult());
            }
            catch (Exception e) {
                if(logError) {
                    logger.error(failMsg, e, objects);
                }
                f.fail(e);
            }
        }, result.completer());
        return result;
    }
}