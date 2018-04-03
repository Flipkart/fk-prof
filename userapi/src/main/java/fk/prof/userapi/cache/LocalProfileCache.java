package fk.prof.userapi.cache;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Ticker;
import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.RemovalListener;
import com.google.common.cache.RemovalNotification;
import fk.prof.aggregation.AggregatedProfileNamingStrategy;
import fk.prof.metrics.MetricName;
import fk.prof.metrics.Util;
import fk.prof.userapi.Configuration;
import fk.prof.userapi.api.ProfileViewCreator;
import fk.prof.userapi.model.AggregatedProfileInfo;
import fk.prof.userapi.model.ProfileView;
import fk.prof.userapi.model.ProfileViewType;
import fk.prof.userapi.util.Pair;
import io.vertx.core.Future;
import io.vertx.core.logging.Logger;
import io.vertx.core.logging.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A wrapper over {@link Cache} to cache aggregated profiles and created views.
 * Provides usual get, put semantics.
 * It is used by {@link ClusteredProfileCache} as a local cache. {@code put} for a {@link AggregatedProfileNamingStrategy}
 * will only be called 2 times, once the profile loading is initiated and the next when loading is finished.
 *
 * Created by gaurav.ashok on 17/07/17.
 */
class LocalProfileCache {
    private static final Logger logger = LoggerFactory.getLogger(LocalProfileCache.class);

    private final AtomicInteger uidGenerator;
    private final Cache<AggregatedProfileNamingStrategy, CacheableProfile> profileCache;
    private final Cache<String, Cacheable<ProfileView>> viewCache;
    private final ProfileViewCreator viewCreator;

    private RemovalListener<AggregatedProfileNamingStrategy, Future<AggregatedProfileInfo>> removalListener;

    LocalProfileCache(Configuration config, ProfileViewCreator viewCreator) {
        this(config, viewCreator, Ticker.systemTicker());
    }

    @VisibleForTesting
    LocalProfileCache(Configuration config, ProfileViewCreator viewCreator, Ticker ticker) {
        this.viewCache = CacheBuilder.newBuilder()
            .ticker(ticker)
            .weigher((k, v) -> 1)        // default weight of 1, effectively counting the cached objects.
            .maximumWeight(config.getMaxProfileViewsToCache())
            .expireAfterAccess(config.getProfileViewRetentionDurationMin(), TimeUnit.MINUTES)
            .build();

        this.profileCache = CacheBuilder.newBuilder()
            .ticker(ticker)
            .weigher((k, v) -> 1)        // default weight of 1, effectively counting the cached objects.
            .maximumWeight(config.getMaxProfilesToCache())
            .expireAfterAccess(config.getProfileRetentionDurationMin(), TimeUnit.MINUTES)
            .removalListener(this::doCleanupOnEviction)
            .build();

        this.removalListener = null;
        this.uidGenerator = new AtomicInteger(0);
        this.viewCreator = viewCreator;
        Util.gauge(MetricName.Profile_Cache_Size.get(), profileCache::size);
        Util.gauge(MetricName.ProfileSummary_Cache_Size.get(), viewCache::size);
    }

    void setRemovalListener(RemovalListener<AggregatedProfileNamingStrategy, Future<AggregatedProfileInfo>> removalListener) {
        this.removalListener = removalListener;
    }

    Future<AggregatedProfileInfo> get(AggregatedProfileNamingStrategy profileName) {
        CacheableProfile cacheableProfile = profileCache.getIfPresent(profileName);
        return cacheableProfile != null ? cacheableProfile.profile : null;
    }

    void put(AggregatedProfileNamingStrategy key, Future<AggregatedProfileInfo> profileFuture) {
        profileCache.put(key, new CacheableProfile(key, uidGenerator.incrementAndGet(), profileFuture));
    }

    Pair<Future<AggregatedProfileInfo>, Cacheable<ProfileView>> getView(AggregatedProfileNamingStrategy profileName, String traceName, ProfileViewType profileViewType) {
        CacheableProfile cacheableProfile = profileCache.getIfPresent(profileName);
        if (cacheableProfile != null) {
            String viewKey = toViewKey(profileName, traceName, profileViewType, cacheableProfile.uid);
            return Pair.of(cacheableProfile.profile, viewCache.getIfPresent(viewKey));
        }
        return Pair.of(null, null);
    }

    Pair<Future<AggregatedProfileInfo>, Cacheable<ProfileView>> computeViewIfAbsent(AggregatedProfileNamingStrategy profileName, String traceName, ProfileViewType profileViewType) {
        // dont cache it if dependent profile is not there.
        CacheableProfile cacheableProfile = profileCache.getIfPresent(profileName);
        if(cacheableProfile == null) {
            return Pair.of(null, null);
        }
        String viewKey = toViewKey(profileName, traceName, profileViewType, cacheableProfile.uid);
        Cacheable<ProfileView> cachedView = cacheableProfile.computeAndAddViewIfAbsent(viewKey, traceName, profileViewType, viewCreator);
        return cachedView == null ? Pair.of(null, null) : Pair.of(cacheableProfile.profile, cachedView);
    }

    @VisibleForTesting
    void cleanUp() {
        profileCache.cleanUp();
        viewCache.cleanUp();
    }

    void invalidateCache() {
        profileCache.invalidateAll();
        viewCache.invalidateAll();
    }

    private void doCleanupOnEviction(RemovalNotification<AggregatedProfileNamingStrategy, CacheableProfile> evt) {
        evt.getValue().onEviction();
        if(evt.wasEvicted()) {
            logger.info("Profile evicted. file: {}", evt.getKey());
        }

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
        /**
         * Unique id to tie the generated {@code viewKey} to this particular profile. So that the views generated
         * from this profile are not reused alongside the different instance of the same profile.
         */
        final int uid;

        final AggregatedProfileNamingStrategy profileName;

        final Future<AggregatedProfileInfo> profile;

        /**
         * List of cached views. These view will be invalidated when the profile gets evicted from the cache.
         */
        final List<String> cachedViews;

        boolean evicted;

        CacheableProfile(AggregatedProfileNamingStrategy profileName, int uid, Future<AggregatedProfileInfo> profile) {
            this.uid = uid;
            this.profileName = profileName;
            this.profile = profile;
            this.cachedViews = new ArrayList<>();
            this.evicted = false;
        }

        synchronized Cacheable<ProfileView> computeAndAddViewIfAbsent(String viewKey, String traceName, ProfileViewType profileViewType, ProfileViewCreator viewCreator) {
            if(evicted) {
                return null;
            }

            Cacheable<ProfileView> prev = viewCache.getIfPresent(viewKey);
            if(prev != null) {
                return prev;
            }

            Cacheable<ProfileView> view = viewCreator.buildCacheableView(profile.result(), traceName, profileViewType);
            if(!cachedViews.contains(viewKey)) {
                cachedViews.add(viewKey);
            }
            viewCache.put(viewKey, view);
            return view;
        }

        synchronized void onEviction() {
            evicted = true;
            viewCache.invalidateAll(cachedViews);
            cachedViews.clear();
        }
    }

    private static String toViewKey(AggregatedProfileNamingStrategy profileName, String traceName, ProfileViewType profileViewType, int uid) {
        return profileName.toString() + "/" + traceName + "/" + profileViewType.name() + "/" + uid;
    }

}
