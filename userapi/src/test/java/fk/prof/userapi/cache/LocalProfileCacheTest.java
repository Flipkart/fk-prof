package fk.prof.userapi.cache;

import com.google.common.cache.RemovalListener;
import fk.prof.aggregation.AggregatedProfileNamingStrategy;
import fk.prof.aggregation.proto.AggregatedProfileModel;
import fk.prof.userapi.Configuration;
import fk.prof.userapi.api.ProfileViewCreator;
import fk.prof.userapi.model.ProfileView;
import fk.prof.userapi.model.ProfileViewType;
import fk.prof.userapi.model.tree.CallTreeView;
import fk.prof.userapi.util.Pair;
import fk.prof.userapi.UserapiConfigManager;
import fk.prof.userapi.api.ProfileStoreAPIImpl;
import fk.prof.userapi.model.AggregatedProfileInfo;
import io.vertx.core.Future;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mockito;

import java.time.Clock;
import java.time.ZonedDateTime;
import java.util.concurrent.TimeUnit;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Created by gaurav.ashok on 11/08/17.
 */
public class LocalProfileCacheTest {

    static {
        UserapiConfigManager.setDefaultSystemProperties();
    }

    private static ZonedDateTime beginning = ZonedDateTime.now(Clock.systemUTC());

    private Configuration config;
    private LocalProfileCache cache;
    private TestTicker ticker;
    private ProfileViewCreator viewCreator;

    @Before
    public void beforeTest() throws Exception {
        config = UserapiConfigManager.loadConfig(ProfileStoreAPIImpl.class.getClassLoader().getResource("userapi-conf.json").getFile());
        ticker = new TestTicker();
        viewCreator = Mockito.mock(ProfileViewCreator.class);
        cache = new LocalProfileCache(config, viewCreator, ticker);
    }

    @Test
    public void testStateWhenCacheEmpty() {
        AggregatedProfileNamingStrategy profileName = pn("proc1", dt(0));
        Assert.assertNull(cache.get(profileName));

        Pair profileViewPair = cache.getView(profileName, "/trace1/callersView", ProfileViewType.CALLERS);
        Assert.assertNotNull(profileViewPair);
        Assert.assertNull(profileViewPair.first);
        Assert.assertNull(profileViewPair.second);

        Assert.assertTrue(cache.cachedProfiles().size() == 0);
    }

    @Test
    public void testPutGet() {
        AggregatedProfileNamingStrategy profileName = pn("proc1", dt(0));

        AggregatedProfileInfo profile = Mockito.mock(AggregatedProfileInfo.class);
        CallTreeView view = Mockito.mock(CallTreeView.class);

        doReturn(view).when(viewCreator).buildCacheableView(any(), anyString(), any());


        cache.put(profileName, Future.succeededFuture(profile));
        Assert.assertSame(profile, cache.get(profileName).result());

        cache.computeViewIfAbsent(profileName,  "view1", ProfileViewType.CALLERS);
        Pair<Future<AggregatedProfileInfo>, Cacheable<ProfileView>> profileViewPair = cache.getView(profileName, "view1", ProfileViewType.CALLERS);
        Assert.assertSame(view, profileViewPair.second);
        Assert.assertSame(profile, profileViewPair.first.result());

        profileViewPair = cache.getView(profileName, "view2", ProfileViewType.CALLERS);
        Assert.assertNull(profileViewPair.second);
        Assert.assertSame(profile, profileViewPair.first.result());
    }

    @Test
    public void testExpiry() {
        AggregatedProfileNamingStrategy profileName = pn("proc1", dt(0));

        AggregatedProfileInfo profile = Mockito.mock(AggregatedProfileInfo.class);
        CallTreeView view = Mockito.mock(CallTreeView.class);
        RemovalListener<AggregatedProfileNamingStrategy, Future<AggregatedProfileInfo>> listener = Mockito.mock(RemovalListener.class);
        doReturn(view).when(viewCreator).buildCacheableView(any(), anyString(), any());

        cache.setRemovalListener(listener);
        cache.put(profileName, Future.succeededFuture(profile));
        cache.computeViewIfAbsent(profileName,  "view1", ProfileViewType.CALLERS);

        ticker.advance(config.getProfileRetentionDurationMin() + 1, TimeUnit.MINUTES);
        Assert.assertNull(cache.get(profileName));
        Pair<Future<AggregatedProfileInfo>, Cacheable<ProfileView>> profileViewPair = cache.getView(profileName, "view1", ProfileViewType.CALLERS);
        Assert.assertNull(profileViewPair.first);
        Assert.assertNull(profileViewPair.second);

        // force the cleanup
        cache.cleanUp();

        verify(listener, times(1)).onRemoval(any());
    }

    @Test
    public void testExpiryDueToResourceUtilization() {
        config = spy(config);
        doReturn(1).when(config).getMaxProfilesToCache();
        ProfileViewCreator profileViewCreator = Mockito.mock(ProfileViewCreator.class);
        cache = new LocalProfileCache(config, profileViewCreator, ticker);

        AggregatedProfileNamingStrategy profileName = pn("proc1", dt(0));
        AggregatedProfileNamingStrategy profileName2 = pn("proc2", dt(0));

        AggregatedProfileInfo profile = Mockito.mock(AggregatedProfileInfo.class);
        CallTreeView view = Mockito.mock(CallTreeView.class);
        doReturn(view).when(profileViewCreator).buildCacheableView(any(), anyString(), any());

        RemovalListener<AggregatedProfileNamingStrategy, Future<AggregatedProfileInfo>> listener = Mockito.mock(RemovalListener.class);

        cache.setRemovalListener(listener);
        cache.put(profileName, Future.succeededFuture(profile));
        cache.computeViewIfAbsent(profileName, "view1", ProfileViewType.CALLERS);

        // let some time pass
        ticker.advance(config.getProfileRetentionDurationMin() - 1, TimeUnit.MINUTES);
        // and assert that it is still there
        Assert.assertNotNull(cache.get(profileName));

        // put another one
        cache.put(profileName2, Future.succeededFuture(profile));
        cache.computeViewIfAbsent(profileName2, "view2", ProfileViewType.CALLERS);

        // should evict the previous key
        Assert.assertNull(cache.get(profileName));
        Pair<Future<AggregatedProfileInfo>, Cacheable<ProfileView>> profileViewPair = cache.getView(profileName, "view1", ProfileViewType.CALLERS);
        Assert.assertNull(profileViewPair.first);
        Assert.assertNull(profileViewPair.second);

        Assert.assertSame(profile, cache.get(profileName2).result());
        profileViewPair = cache.getView(profileName2, "view2", ProfileViewType.CALLERS);
        Assert.assertSame(profile, profileViewPair.first.result());
        Assert.assertSame(view, profileViewPair.second);

        verify(listener, times(1)).onRemoval(any());
    }

    private ZonedDateTime dt(int offsetInSec) {
        return beginning.plusSeconds(offsetInSec);
    }

    private AggregatedProfileNamingStrategy pn(String procId, ZonedDateTime dt) {
        return new AggregatedProfileNamingStrategy(config.getProfilesBaseDir(), 1, "app1", "cluster1", procId, dt, 1200, AggregatedProfileModel.WorkType.cpu_sample_work);
    }
}
