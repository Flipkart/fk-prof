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
import fk.prof.userapi.proto.LoadInfoEntities.ProfileResidencyInfo;
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
 * @see ZkLoadInfoStore
 *
 * Created by gaurav.ashok on 21/06/17.
 */
public class ClusterAwareCache {
    private static final Logger logger = LoggerFactory.getLogger(ClusterAwareCache.class);

    private final LocalProfileCache cache;

    private final WorkerExecutor workerExecutor;
    private final AggregatedProfileLoader profileLoader;
    private final ProfileViewCreator viewCreator;

    private ZkLoadInfoStore zkLoadInfoStore;
    private final String myIp;
    private final int port;

    public ClusterAwareCache(CuratorFramework curatorClient, WorkerExecutor workerExecutor,
                             AggregatedProfileLoader profileLoader, ProfileViewCreator viewCreator, Configuration config) {
        this.myIp = config.getIpAddress();
        this.port = config.getHttpConfig().getHttpPort();

        this.cache = new LocalProfileCache(config);
        this.cache.setRemovalListener(this::doCleanUpOnEviction);

        this.profileLoader = profileLoader;
        this.viewCreator = viewCreator;
        this.zkLoadInfoStore = new ZkLoadInfoStore(curatorClient, myIp, port, this.cache::invalidateCache);

        this.workerExecutor = workerExecutor;
    }

    @VisibleForTesting
    ClusterAwareCache(CuratorFramework curatorClient, WorkerExecutor workerExecutor,
                             AggregatedProfileLoader profileLoader, ProfileViewCreator viewCreator, Configuration config, LocalProfileCache localCache) {
        this.myIp = config.getIpAddress();
        this.port = config.getHttpConfig().getHttpPort();

        this.cache = localCache;
        this.cache.setRemovalListener(this::doCleanUpOnEviction);

        this.profileLoader = profileLoader;
        this.viewCreator = viewCreator;
        this.zkLoadInfoStore = new ZkLoadInfoStore(curatorClient, myIp, port, this.cache::cachedProfiles);

        this.workerExecutor = workerExecutor;
    }

    public Future<Void> onClusterJoin() {
        return doAsync(f -> {
            try {
                zkLoadInfoStore.init();
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
     * @param profileName
     * @return Future of AggregatedProfileInfo
     *
     */
    public Future<AggregatedProfileInfo> getAggregatedProfile(AggregatedProfileNamingStrategy profileName) {
        Future<AggregatedProfileInfo> profileFuture = Future.future();

        Future<AggregatedProfileInfo> cachedProfileInfo = cache.get(profileName);
        if (cachedProfileInfo != null) {
            completeFuture(profileName, cachedProfileInfo, profileFuture);
        }
        else {
            // check zookeeper if it is loaded somewhere else
            doAsync((Future<AggregatedProfileInfo> f) -> {
                    try (AutoCloseable ignored = zkLoadInfoStore.getLock()) {
                    Future<AggregatedProfileInfo> _cachedProfileInfo = cache.get(profileName);
                    if (_cachedProfileInfo != null) {
                        completeFuture(profileName, _cachedProfileInfo, f);
                        return;
                    }

                    // still no cached profile. read zookeeper for remotely cached profile
                    ProfileResidencyInfo residencyInfo = zkLoadInfoStore.readProfileResidencyInfo(profileName);
                    // stale node exists. will update instead of create
                    boolean staleNodeExists = false;
                    if(residencyInfo != null) {
                        if(residencyInfo.getIp().equals(myIp) && residencyInfo.getPort() == port) {
                            staleNodeExists = true;
                        }
                        else {
                            f.fail(new CachedProfileNotFoundException(residencyInfo.getIp(), residencyInfo.getPort()));
                            return;
                        }
                    }

                    // profile not cached anywhere
                    // start the loading process
                    Future<AggregatedProfileInfo> loadProfileFuture = Future.future();
                    workerExecutor.executeBlocking(f2 -> profileLoader.load(f2, profileName), loadProfileFuture.completer());

                    // update LOADING status in zookeeper
                    zkLoadInfoStore.updateProfileResidencyInfo(profileName, staleNodeExists);
                    cache.put(profileName, loadProfileFuture);

                    loadProfileFuture.setHandler(ar -> {
                        logger.info("Profile load complete. file: {}", profileName);
                        // load_profile might fail, regardless reinsert to take the new utilization into account.
                        cache.put(profileName, loadProfileFuture);
                    });

                    if(loadProfileFuture.isComplete()) {
                        f.complete(loadProfileFuture.result());
                    }
                    f.fail(new ProfileLoadInProgressException(profileName));
                }
            }, "Error while interacting with zookeeper for file: {}", profileName).setHandler(profileFuture.completer());
        }
        return profileFuture;
    }

    /**
     * Event handler for evicted profiles. Deletes the profile -> ip:port mapping fom shared store.
     * @param onRemoval
     */
    private void doCleanUpOnEviction(RemovalNotification<AggregatedProfileNamingStrategy, Future<AggregatedProfileInfo>> onRemoval) {
        if(!RemovalCause.REPLACED.equals(onRemoval.getCause()) && onRemoval.wasEvicted()) {
            AggregatedProfileNamingStrategy profileName = onRemoval.getKey();
            doAsync(f -> {
                try(AutoCloseable ignored = zkLoadInfoStore.getLock()) {
                    ProfileResidencyInfo residencyInfo = zkLoadInfoStore.readProfileResidencyInfo(profileName);
                    boolean deleteProfileNode = false;
                    if(residencyInfo != null && (myIp.equals(residencyInfo.getIp()) && port == residencyInfo.getPort()) && cache.get(profileName) == null) {
                        deleteProfileNode = true;
                    }
                    zkLoadInfoStore.removeProfileResidencyInfo(profileName, deleteProfileNode);
                }
                f.complete();
            }, "Error while cleaning for file: {}", profileName.toString());
        }
    }

    /**
     * Helper method to complete the future depending upon the state of the cached profile.
     * @param profileName
     * @param cachedProfileInfo
     * @param profileFuture
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
     * Main method to get the already cached view / create view for the cached profile. If the profile is not cached,
     * the return future will fail according to {@code getAggregatedProfile}.
     *
     * @param profileName
     * @param traceName
     * @param profileViewType
     * @return Future containing a pair of trace specific aggregated samples and its view.
     */
    public <T extends ProfileView> Future<Pair<AggregatedSamplesPerTraceCtx, T>> getProfileView(AggregatedProfileNamingStrategy profileName, String traceName, ProfileViewType profileViewType) {
        Future<Pair<AggregatedSamplesPerTraceCtx, T >> viewFuture = Future.future();
        Pair<Future<AggregatedProfileInfo>, T> profileViewPair = cache.getView(profileName, traceName, profileViewType);

        if(profileViewPair.first != null) {
            if(profileViewPair.second != null) {
                return Future.succeededFuture(Pair.of(profileViewPair.first.result().getAggregatedSamples(traceName), profileViewPair.second));
            }
            else {
                workerExecutor.executeBlocking(f -> getOrCreateView(profileName, traceName, f, profileViewType), viewFuture.completer());
            }
        }
        else {
            // else check into zookeeper if this is cached in another node
            doAsync((Future<Pair<AggregatedSamplesPerTraceCtx, T>> f) -> {
                ProfileResidencyInfo residencyInfo;
                try(AutoCloseable ignored = zkLoadInfoStore.getLock()) {
                    residencyInfo = zkLoadInfoStore.readProfileResidencyInfo(profileName);
                }

                if(residencyInfo != null) {
                    // by the time we got the lock, it is possible a profile load has started
                    if (residencyInfo.getIp().equals(myIp) && residencyInfo.getPort() == port) {
                        getOrCreateView(profileName, traceName, f, profileViewType);
                    }
                    else {
                        // cached in another node
                        f.fail(new CachedProfileNotFoundException(residencyInfo.getIp(), residencyInfo.getPort()));
                    }
                }
                else {
                    f.fail(new CachedProfileNotFoundException());
                }
            }).setHandler(viewFuture.completer());
        }

        return viewFuture;
    }

    /**
     * Helper method to get/create the view once it has been established that the profile is cached locally.
     * @param profileName
     * @param traceName
     * @param f
     * @param profileViewType
     */
    private <T extends ProfileView> void getOrCreateView(AggregatedProfileNamingStrategy profileName, String traceName, Future<Pair<AggregatedSamplesPerTraceCtx, T>> f, ProfileViewType profileViewType) {
        Pair<Future<AggregatedProfileInfo>, T> profileViewPair = cache.getView(profileName, traceName, profileViewType);
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
                f.complete(Pair.of(samplesPerTraceCtx, profileViewPair.second));
            }
            else {
                // no cached view, so create a new one
                switch (profileName.workType) {
                    case cpu_sample_work:
                        profileViewPair = cache.computeViewIfAbsent(profileName, traceName, profileViewType, p -> buildView(p, traceName, profileViewType));
                        if(profileViewPair.second == null) {
                            f.fail(new CachedProfileNotFoundException());
                        }
                        else {
                            f.complete(Pair.of(profileViewPair.first.result().getAggregatedSamples(traceName), profileViewPair.second));
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

    private <T extends ProfileView> T buildView(AggregatedProfileInfo profile, String traceName, ProfileViewType profileViewType) {
        if (ProfileViewType.CALLERS.equals(profileViewType)) {
            return (T)viewCreator.buildCallTreeView(profile, traceName);
        }
        else {
            return (T)viewCreator.buildCalleesTreeView(profile, traceName);
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