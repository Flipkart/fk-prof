package fk.prof.userapi.cache;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.cache.RemovalCause;
import com.google.common.cache.RemovalNotification;
import fk.prof.aggregation.AggregatedProfileNamingStrategy;
import fk.prof.userapi.Configuration;
import fk.prof.userapi.api.AggregatedProfileLoader;
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

/**
 * Aggregated Profiles Cache that caches profiles after loading them and updates the mapping profile -> ip:port into the
 * shared store (zookeeper in this case).
 *
 * @see LocalProfileCache
 * @see ZKBasedCacheInfoRegistry
 *
 * Created by gaurav.ashok on 21/06/17.
 */
public class ClusterAwareCache {
    private static final Logger logger = LoggerFactory.getLogger(ClusterAwareCache.class);

    private final LocalProfileCache cache;

    private final WorkerExecutor workerExecutor;
    private final AggregatedProfileLoader profileLoader;

    private CacheInfoRegistry cacheInfoRegistry;
    private final String myIp;
    private final int port;

    public ClusterAwareCache(CuratorFramework curatorClient, WorkerExecutor workerExecutor,
                             AggregatedProfileLoader profileLoader, ProfileViewCreator viewCreator, Configuration config) {
        this(curatorClient, workerExecutor, profileLoader, config, new LocalProfileCache(config, viewCreator));
    }

    @VisibleForTesting
    ClusterAwareCache(CuratorFramework curatorClient, WorkerExecutor workerExecutor,
                             AggregatedProfileLoader profileLoader, Configuration config, LocalProfileCache localCache) {
        this.myIp = config.getIpAddress();
        this.port = config.getHttpConfig().getHttpPort();

        this.workerExecutor = workerExecutor;
        this.profileLoader = profileLoader;

        this.cache = localCache;
        this.cache.setRemovalListener(this::doCleanUpOnEviction);

        this.cacheInfoRegistry = new ZKBasedCacheInfoRegistry(curatorClient, myIp, port, this.cache::invalidateCache);
    }

    public Future<Void> onClusterJoin() {
        return doAsync(f -> {
            try {
                cacheInfoRegistry.init();
                f.complete();
            } catch (Exception e) {
                f.fail(e);
            }
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
     */
    public Future<AggregatedProfileInfo> getAggregatedProfile(AggregatedProfileNamingStrategy profileName) {
        Future<AggregatedProfileInfo> profileFuture = Future.future();
        getFromLocalCache(profileName, profileFuture);
        if(profileFuture.isComplete()) return profileFuture;
        else {
            // check zookeeper if it is loaded somewhere else
            doAsync((Future<AggregatedProfileInfo> f) -> {
                synchronized (this) {
                    getFromLocalCache(profileName, f);            //synchronized double check
                    if (f.isComplete()) return;

                    try {
                        cacheInfoRegistry.claimOwnership(profileName);
                    } catch (CachedProfileNotFoundException ex) {
                        f.fail(ex);
                        return;
                    }
                    Future<AggregatedProfileInfo> loadProfileFuture = Future.future();  // start the loading process
                    loadProfile(profileName, loadProfileFuture);
                    if (loadProfileFuture.isComplete()) f.complete(loadProfileFuture.result());

                    f.fail(new ProfileLoadInProgressException(profileName));    //fail because loading is still not complete
                }
            }, "Error while interacting with zookeeper for file: {}", profileName).setHandler(profileFuture.completer());
        }
        return profileFuture;
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
    public <T extends ProfileView> Future<Pair<AggregatedSamplesPerTraceCtx, T>> getProfileView(AggregatedProfileNamingStrategy profileName, String traceName, ProfileViewType profileViewType) {
        Future<Pair<AggregatedSamplesPerTraceCtx, T >> viewFuture = Future.future();
        Pair<Future<AggregatedProfileInfo>, Cacheable<ProfileView>> profileViewPair = cache.getView(profileName, traceName, profileViewType);

        if (profileViewPair.first != null) {
            if (profileViewPair.second != null) {
                return Future.succeededFuture(Pair.of(profileViewPair.first.result().getAggregatedSamples(traceName), (T) profileViewPair.second));
            } else {
                workerExecutor.executeBlocking(f -> getOrCreateView(profileName, traceName, f, profileViewType), viewFuture.completer());
            }
        } else {
            // else check into zookeeper if this is cached in another node
            doAsync((Future<Pair<AggregatedSamplesPerTraceCtx, T>> f) -> {
                synchronized (this) {
                    try {
                        cacheInfoRegistry.claimOwnership(profileName);
                    } catch (CachedProfileNotFoundException ex) {
                        f.fail(ex);
                        return;
                    }
                    getOrCreateView(profileName, traceName, f, profileViewType);
                }
            }).setHandler(viewFuture.completer());
        }
        return viewFuture;
    }

    private void loadProfile(AggregatedProfileNamingStrategy profileName, Future<AggregatedProfileInfo> loadProfileFuture) {
        workerExecutor.executeBlocking(f2 -> profileLoader.load(f2, profileName), loadProfileFuture.completer());
        cache.put(profileName, loadProfileFuture);
        loadProfileFuture.setHandler(ar -> {
            logger.info("Profile load complete. file: {}", profileName);
            // load_profile might fail, regardless reinsert to take the new utilization into account.
            cache.put(profileName, loadProfileFuture);
        });
    }

    private void getFromLocalCache(AggregatedProfileNamingStrategy profileName, Future<AggregatedProfileInfo> profileFuture) {
        Future<AggregatedProfileInfo> cachedProfileInfo = cache.get(profileName);
        if (cachedProfileInfo != null) {
            completeFuture(profileName, cachedProfileInfo, profileFuture);
        }
    }

    /**
     * Event handler for evicted profiles. Deletes the profile -> ip:port mapping fom shared store.
     * @param onRemoval the on removal notification received on an entry removal
     */
    private void doCleanUpOnEviction(RemovalNotification<AggregatedProfileNamingStrategy, Future<AggregatedProfileInfo>> onRemoval) {
        if(!RemovalCause.REPLACED.equals(onRemoval.getCause()) && onRemoval.wasEvicted()) {
            AggregatedProfileNamingStrategy profileName = onRemoval.getKey();
            doAsync(f -> {
                cacheInfoRegistry.releaseOwnership(profileName);
                f.complete();
            }, "Error while cleaning for file: {}", profileName.toString());
        }
    }

    /**
     * Helper method to complete the future depending upon the state of the cached profile.
     * @param profileName name of the profile
     * @param cachedProfileInfo future containing the profile
     * @param profileFuture resulting future to be completed/failed based on cachedProfileInfo's status
     */
    private void completeFuture(AggregatedProfileNamingStrategy profileName, Future<AggregatedProfileInfo> cachedProfileInfo, Future<AggregatedProfileInfo> profileFuture) {
        if (!cachedProfileInfo.isComplete()) {
            profileFuture.fail(new ProfileLoadInProgressException(profileName));
        }
        else if (!cachedProfileInfo.succeeded()) {
            profileFuture.fail(new CachedProfileNotFoundException(cachedProfileInfo.cause()));
        }
        else {
            profileFuture.complete(cachedProfileInfo.result());
        }
    }

    /**
     * Helper method to get/create the view once it has been established that the profile is cached locally.
     * @param profileName name of the profile
     * @param traceName name of the trace
     * @param f future containing the profile and its to be get/created view as a pair
     * @param profileViewType type of the profileView to be get/created
     */
    private <T extends ProfileView> void getOrCreateView(AggregatedProfileNamingStrategy profileName, String traceName, Future<Pair<AggregatedSamplesPerTraceCtx, T>> f, ProfileViewType profileViewType) {
        Pair<Future<AggregatedProfileInfo>, Cacheable<ProfileView>> profileViewPair = cache.getView(profileName, traceName, profileViewType);
        Future<AggregatedProfileInfo> cachedProfileInfo = profileViewPair.first;

        if (cachedProfileInfo != null) {
            if (!cachedProfileInfo.isComplete()) {
                f.fail(new ProfileLoadInProgressException(profileName));
                return;
            }

            // unlikely case where profile load failed
            if (!cachedProfileInfo.succeeded()) {
                f.fail(new CachedProfileNotFoundException(cachedProfileInfo.cause()));
                return;
            }

            AggregatedSamplesPerTraceCtx samplesPerTraceCtx = cachedProfileInfo.result().getAggregatedSamples(traceName);
            if (profileViewPair.second != null) {
                f.complete(Pair.of(samplesPerTraceCtx, (T)profileViewPair.second));
            }
            else {
                // no cached view, so create a new one
                switch (profileName.workType) {
                    case cpu_sample_work:
                        profileViewPair = cache.computeViewIfAbsent(profileName, traceName, profileViewType);
                        if(profileViewPair.second == null) {
                            f.fail(new CachedProfileNotFoundException());
                        }
                        else {
                            f.complete(Pair.of(profileViewPair.first.result().getAggregatedSamples(traceName), (T)profileViewPair.second));
                        }
                        break;
                    default:
                        f.fail(new IllegalArgumentException("Unsupported workType: " + profileName.workType));
                }
            }
        }
        else {
            f.fail(new CachedProfileNotFoundException());
        }
    }


    interface BlockingTask<T> {
        void getResult(Future<T> future) throws Exception;
    }

    private <T> Future<T> doAsync(BlockingTask<T> s) {
        return doAsync(s, "");
    }

    private <T> Future<T> doAsync(BlockingTask<T> s, String failMsg, Object... objects) {
        Future<T> result = Future.future();
        workerExecutor.executeBlocking(f -> {
            try {
                s.getResult(f);
            }
            catch (Exception e) {
                logger.error(failMsg, e, objects);
                if(!f.isComplete()) {
                    f.fail(e);
                }
            }
        }, result.completer());
        return result;
    }
}