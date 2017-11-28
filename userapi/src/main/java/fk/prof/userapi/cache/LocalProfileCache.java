package fk.prof.userapi.cache;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Ticker;
import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.RemovalListener;
import com.google.common.cache.RemovalNotification;
import fk.prof.aggregation.AggregatedProfileNamingStrategy;
import fk.prof.metrics.Util;
import fk.prof.userapi.Configuration;
import fk.prof.userapi.model.AggregatedProfileInfo;
import fk.prof.userapi.model.TreeView;
import fk.prof.userapi.model.ProfileViewType;
import fk.prof.userapi.util.Pair;
import io.vertx.core.Future;
import io.vertx.core.logging.Logger;
import io.vertx.core.logging.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

/**
 * A wrapper over {@link Cache} to cache aggregated profiles and created views.
 * Provides usual get, put semantics.
 * It is used by {@link ClusterAwareCache} as a local cache. {@code put} for a {@link AggregatedProfileNamingStrategy}
 * will only be called 2 times, once the profile loading is initiated and the next when loading is finished.
 *
 * Created by gaurav.ashok on 17/07/17.
 */
class LocalProfileCache {
    private static final Logger logger = LoggerFactory.getLogger(LocalProfileCache.class);

    private final AtomicInteger uidGenerator;
    private final Cache<AggregatedProfileNamingStrategy, CacheableProfile> cache;
    private final Cache<String, CacheableProfileView> viewCache;

    private RemovalListener<AggregatedProfileNamingStrategy, Future<AggregatedProfileInfo>> removalListener;

    LocalProfileCache(Configuration config) {
        this(config, Ticker.systemTicker());
    }

    @VisibleForTesting
    LocalProfileCache(Configuration config, Ticker ticker) {
        this.viewCache = CacheBuilder.newBuilder()
            .ticker(ticker)
            .weigher((k, v) -> 1)        // default weight of 1, effectively counting the cached objects.
            .maximumWeight(config.getMaxProfileViewsToCache())
            .expireAfterAccess(config.getProfileViewRetentionDurationMin(), TimeUnit.MINUTES)
            .build();

        this.cache = CacheBuilder.newBuilder()
            .ticker(ticker)
            .weigher((k, v) -> 1)        // default weight of 1, effectively counting the cached objects.
            .maximumWeight(config.getMaxProfilesToCache())
            .expireAfterAccess(config.getProfileRetentionDurationMin(), TimeUnit.MINUTES)
            .removalListener(this::doCleanupOnEviction)
            .build();

        this.removalListener = null;
        this.uidGenerator = new AtomicInteger(0);

        Util.gauge("localcache.profiles.count", cache::size);
        Util.gauge("localcache.views.count", viewCache::size);
    }

    void setRemovalListener(RemovalListener<AggregatedProfileNamingStrategy, Future<AggregatedProfileInfo>> removalListener) {
        this.removalListener = removalListener;
    }

    Future<AggregatedProfileInfo> get(AggregatedProfileNamingStrategy profileName) {
        CacheableProfile cacheableProfile = cache.getIfPresent(profileName);
        return cacheableProfile != null ? cacheableProfile.profile : null;
    }

    void put(AggregatedProfileNamingStrategy key, Future<AggregatedProfileInfo> profileFuture) {
        cache.put(key, new CacheableProfile(key, uidGenerator.incrementAndGet(), profileFuture));
    }

    <T extends TreeView> Pair<Future<AggregatedProfileInfo>, T> getView(AggregatedProfileNamingStrategy profileName, String traceName, ProfileViewType profileViewType) {
        CacheableProfile cacheableProfile = cache.getIfPresent(profileName);
        if (cacheableProfile != null) {
            String viewKey = toViewKey(profileName, traceName, profileViewType, cacheableProfile.uid);
            return Pair.of(cacheableProfile.profile, (T)viewCache.getIfPresent(viewKey));
        }
        return Pair.of(null, null);
    }

    <T extends TreeView> Pair<Future<AggregatedProfileInfo>, T> computeViewIfAbsent(AggregatedProfileNamingStrategy profileName, String traceName, ProfileViewType profileViewType, Function<AggregatedProfileInfo, T> viewProvider) {
        // dont cache it if dependent profile is not there.
        CacheableProfile cacheableProfile = cache.getIfPresent(profileName);
        if(cacheableProfile == null) {
            return Pair.of(null, null);
        }
        String viewKey = toViewKey(profileName, traceName, profileViewType, cacheableProfile.uid);
        T cachedView = cacheableProfile.computeAndAddViewIfAbsent(viewKey, viewProvider);
        return cachedView == null ? Pair.of(null, null) : Pair.of(cacheableProfile.profile, cachedView);
    }

    List<AggregatedProfileNamingStrategy> cachedProfiles() {
        return new ArrayList<>(cache.asMap().keySet());
    }

    void cleanUp() {
        cache.cleanUp();
        viewCache.cleanUp();
    }

    private void doCleanupOnEviction(RemovalNotification<AggregatedProfileNamingStrategy, CacheableProfile> evt) {
        evt.getValue().markEvicted();
        if(evt.wasEvicted()) {
            logger.info("Profile evicted. file: {}", evt.getKey());
        }

        evt.getValue().clearViews();

        if(removalListener != null) {
            removalListener.onRemoval(RemovalNotification.create(evt.getKey(), evt.getValue().profile, evt.getCause()));
        }
    }

    /**
     * A wrapper over cached profile object {@code Future<AggregatedProfileInfo>} to store list of keys for loaded views
     * and provide thread-safe getOrCompute semantic for the view. The list of keys is also used to invalidate views when
     * profile itself gets evicted.
     */
    private class CacheableProfile implements Cacheable<AggregatedProfileInfo> {
        int uid;
        AggregatedProfileNamingStrategy profileName;
        Future<AggregatedProfileInfo> profile;
        List<String> cachedViews;
        boolean evicted;

        CacheableProfile(AggregatedProfileNamingStrategy profileName, int uid, Future<AggregatedProfileInfo> profile) {
            this.uid = uid;
            this.profileName = profileName;
            this.profile = profile;
            this.cachedViews = new ArrayList<>();
            this.evicted = false;
        }

        void markEvicted() {
            synchronized (this) {
                evicted = true;
            }
        }

        <T extends TreeView> T computeAndAddViewIfAbsent(String viewKey, Function<AggregatedProfileInfo, T> viewProvider) {
            synchronized (this) {
                if(!evicted) {
                    T prev = (T)viewCache.getIfPresent(viewKey);
                    if(prev != null) {
                        return prev;
                    }
                    else {
                        cachedViews.add(viewKey);
                        T view = viewProvider.apply(profile.result());
                        viewCache.put(viewKey, (CacheableProfileView) view);
                        return view;
                    }
                }
                return null;
            }
        }

        void clearViews() {
            synchronized (this) {
                viewCache.invalidateAll(cachedViews);
                cachedViews.clear();
            }
        }
    }

    private static String toViewKey(AggregatedProfileNamingStrategy profileName, String traceName, ProfileViewType profileViewType, int uid) {
        return profileName.toString() + "/" + traceName + "/" + profileViewType.name() + "/" + uid;
    }

    private class CacheableProfileView implements Cacheable<TreeView>  {
    }
}