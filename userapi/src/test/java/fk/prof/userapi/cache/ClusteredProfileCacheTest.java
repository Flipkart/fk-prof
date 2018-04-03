package fk.prof.userapi.cache;

import com.google.common.io.BaseEncoding;
import fk.prof.aggregation.AggregatedProfileNamingStrategy;
import fk.prof.aggregation.proto.AggregatedProfileModel;
import fk.prof.userapi.Configuration;
import fk.prof.userapi.model.ProfileViewType;
import fk.prof.userapi.model.tree.CallTreeView;
import fk.prof.userapi.model.tree.CalleesTreeView;
import fk.prof.userapi.util.Pair;
import fk.prof.userapi.UserapiConfigManager;
import fk.prof.userapi.api.StorageBackedProfileLoader;
import fk.prof.userapi.api.ProfileStoreAPIImpl;
import fk.prof.userapi.api.ProfileViewCreator;
import fk.prof.userapi.model.AggregatedProfileInfo;
import fk.prof.userapi.model.AggregatedSamplesPerTraceCtx;
import fk.prof.userapi.proto.LoadInfoEntities;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.WorkerExecutor;
import io.vertx.ext.unit.Async;
import io.vertx.ext.unit.TestContext;
import io.vertx.ext.unit.junit.VertxUnitRunner;
import org.apache.curator.framework.CuratorFramework;
import org.apache.curator.framework.CuratorFrameworkFactory;
import org.apache.curator.retry.ExponentialBackoffRetry;
import org.apache.curator.test.TestingServer;
import org.apache.zookeeper.CreateMode;
import org.junit.*;
import org.junit.runner.RunWith;

import java.io.IOException;
import java.nio.charset.Charset;
import java.time.Clock;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;

import static org.mockito.Mockito.*;

/**
 * Created by gaurav.ashok on 14/07/17.
 */
@RunWith(VertxUnitRunner.class)
public class ClusteredProfileCacheTest {
    private static final int zkPort = 2191;

    static {
        UserapiConfigManager.setDefaultSystemProperties();
    }

    private Vertx vertx;
    private static Configuration config;
    private static TestingServer zookeeper;
    private static CuratorFramework curatorClient;
    private WorkerExecutor executor;
    private ClusteredProfileCache cache;
    private LocalProfileCache localProfileCache;

    private static ZonedDateTime beginning = ZonedDateTime.now(Clock.systemUTC());

    @BeforeClass
    public static void beforeClass() throws Exception {
        config = UserapiConfigManager.loadConfig(ProfileStoreAPIImpl.class.getClassLoader().getResource("userapi-conf.json").getFile());

        zookeeper = new TestingServer(zkPort, true);

        Configuration.CuratorConfig curatorConfig = config.getCuratorConfig();
        curatorClient = CuratorFrameworkFactory.builder()
            .connectString("127.0.0.1:" + zkPort)
            .retryPolicy(new ExponentialBackoffRetry(1000, curatorConfig.getMaxRetries()))
            .connectionTimeoutMs(curatorConfig.getConnectionTimeoutMs())
            .sessionTimeoutMs(curatorConfig.getSessionTimeoutMs())
            .namespace(curatorConfig.getNamespace())
            .build();

        curatorClient.start();
        curatorClient.blockUntilConnected(config.getCuratorConfig().getConnectionTimeoutMs(), TimeUnit.MILLISECONDS);
    }

    @AfterClass
    public static void afterClass() throws IOException {
        zookeeper.close();
    }

    @Before
    public void beforeTest() throws Exception {
        vertx = Vertx.vertx();
        executor = vertx.createSharedWorkerExecutor(config.getBlockingWorkerPool().getName(), 3);
        cleanUpZookeeper();
    }

    private void setUpDefaultCache(TestContext context, StorageBackedProfileLoader profileLoader, ProfileViewCreator viewCreator) {
        setUpCache(context, new LocalProfileCache(config, viewCreator), profileLoader);
    }

    private void setUpCache(TestContext context, LocalProfileCache localCache, StorageBackedProfileLoader profileLoader) {
        localProfileCache = localCache;
        cache = new ClusteredProfileCache(curatorClient, profileLoader, executor, config, localProfileCache);
        Async async = context.async();
        cache.onClusterJoin().setHandler(ar -> {
            context.assertTrue(ar.succeeded());
            async.complete();
        });
        async.awaitSuccess(500);
    }

    @Test(timeout = 1000)
    public void testNodesInfoExistWhenCacheIsCreated(TestContext context) throws Exception {
        setUpDefaultCache(context, null, null);

        byte[] bytes = curatorClient.getData().forPath("/nodesInfo/127.0.0.1:" + config.getHttpConfig().getHttpPort());
        Assert.assertNotNull(bytes);
        Assert.assertNotNull(LoadInfoEntities.NodeLoadInfo.parseFrom(bytes));
    }

    @Test(timeout = 4000)
    public void testLoadProfile_shouldCallLoaderOnlyOnceOnMultipleInvocations(TestContext context) throws Exception {
        Async async = context.async(2);
        NameProfilePair npPair = npPair("proc1", dt(0));
        StorageBackedProfileLoader loader = mockedProfileLoader(npPair);

        setUpDefaultCache(context, loader, null);

        Future<AggregatedProfileInfo> profile1 = cache.getAggregatedProfile(npPair.name);
        Future<AggregatedProfileInfo> profile2 = cache.getAggregatedProfile(npPair.name);

        profile1.setHandler(ar -> {
            context.assertTrue(ar.failed());
            context.assertTrue(ar.cause() instanceof ProfileLoadInProgressException);
            async.countDown();
        });

        profile2.setHandler(ar -> {
            context.assertTrue(ar.failed());
            context.assertTrue(ar.cause() instanceof ProfileLoadInProgressException);
            async.countDown();
        });

        async.awaitSuccess(2000);
        Thread.sleep(500);
        verify(loader, times(1)).load(eq(npPair.name));

        // fetch it again after waiting for some time
        Thread.sleep(1000);
        Async async2 = context.async(1);
        profile1 = cache.getAggregatedProfile(npPair.name);

        profile1.setHandler(ar -> {
            context.assertTrue(ar.succeeded());
            context.assertEquals(ar.result(), npPair.profileInfo);
            async2.countDown();
        });
    }

    @Test(timeout =  2500)
    public void testLoadProfileAndView_shouldReturnWithTheExactCause(TestContext context) throws Exception {
        Async async = context.async(2);
        Exception e = new IOException();

        setUpDefaultCache(context, mockedProfileLoader(), null);

        AggregatedProfileNamingStrategy profileName = profileName("proc1", dt(0));
        localProfileCache.put(profileName, Future.failedFuture(e));

        Future<AggregatedProfileInfo> profile1 = cache.getAggregatedProfile(profileName);

        profile1.setHandler(ar -> {
            context.assertTrue(ar.failed());
            context.assertTrue(ar.cause() instanceof CachedProfileNotFoundException);
            context.assertEquals(ar.cause().getCause(), e);
            async.countDown();
        });

        Future<Pair<AggregatedSamplesPerTraceCtx, CallTreeView>> view = cache.getProfileView(profileName,"", ProfileViewType.CALLERS);
        view.setHandler(ar -> {
            context.assertTrue(ar.failed());
            context.assertTrue(ar.cause() instanceof CachedProfileNotFoundException);
            context.assertEquals(ar.cause().getCause(), e);
            async.countDown();
        });
    }

    @Test(timeout = 2500)
    public void testLoadCallersView_shouldReturnCallersViewForAlreadyLoadedProfile(TestContext context) throws Exception {
        Async async = context.async(2);

        NameProfilePair npPair = npPair("proc1", dt(0));
        ProfileViewCreator viewCreator = mockedViewCreator(npPair);

        setUpDefaultCache(context, null, viewCreator);
        localProfileCache.put(npPair.name, Future.succeededFuture(npPair.profileInfo));

        Future<Pair<AggregatedSamplesPerTraceCtx, CallTreeView>> view1 = cache.getProfileView(npPair.name,"t1", ProfileViewType.CALLERS);
        Future<Pair<AggregatedSamplesPerTraceCtx, CallTreeView>> view2 = cache.getProfileView(npPair.name,"t1", ProfileViewType.CALLERS);

        view1.setHandler(ar -> {
            context.assertTrue(ar.succeeded());
            context.assertEquals(ar.result().second, npPair.getCallTreeView(1));
            async.countDown();
        });

        view2.setHandler(ar -> {
            context.assertTrue(ar.succeeded());
            context.assertEquals(ar.result().second, npPair.getCallTreeView(1));
            async.countDown();
        });

        async.awaitSuccess(2000);
        verify(viewCreator, times(1)).buildCacheableView(same(npPair.profileInfo), eq("t1"), eq(ProfileViewType.CALLERS));
    }

    @Test(timeout = 2500)
    public void testLoadCallersViewForDifferentTraces_shouldReturnCallersViewForAlreadyLoadedProfile(TestContext context) throws Exception {
        Async async = context.async(2);

        NameProfilePair npPair = npPair("proc1", dt(0), 2);
        ProfileViewCreator viewCreator = mockedViewCreator(npPair);

        setUpDefaultCache(context, null, viewCreator);
        localProfileCache.put(npPair.name, Future.succeededFuture(npPair.profileInfo));

        Future<Pair<AggregatedSamplesPerTraceCtx, CallTreeView>> view1 = cache.getProfileView(npPair.name,"t1", ProfileViewType.CALLERS);
        Future<Pair<AggregatedSamplesPerTraceCtx, CallTreeView>> view2 = cache.getProfileView(npPair.name,"t2", ProfileViewType.CALLERS);

        view1.setHandler(ar -> {
            context.assertTrue(ar.succeeded());
            context.assertEquals(ar.result().second, npPair.getCallTreeView(1));
            async.countDown();
        });

        view2.setHandler(ar -> {
            context.assertTrue(ar.succeeded());
            context.assertEquals(ar.result().second, npPair.getCallTreeView(2));
            async.countDown();
        });

        async.awaitSuccess(2000);
        verify(viewCreator, times(1)).buildCacheableView(same(npPair.profileInfo), eq("t1"), eq(ProfileViewType.CALLERS));
        verify(viewCreator, times(1)).buildCacheableView(same(npPair.profileInfo), eq("t2"), eq(ProfileViewType.CALLERS));
    }

    @Test(timeout = 3000)
    public void testLoadCallersAndThenCalleesView(TestContext context) throws Exception {
        Async async = context.async(2);

        NameProfilePair npPair = npPair("proc1", dt(0), 1);
        ProfileViewCreator viewCreator = mockedViewCreator(npPair);

        setUpDefaultCache(context, null, viewCreator);
        localProfileCache.put(npPair.name, Future.succeededFuture(npPair.profileInfo));

        Future<Pair<AggregatedSamplesPerTraceCtx, CallTreeView>> view1 = cache.getProfileView(npPair.name,"t1", ProfileViewType.CALLERS);
        Future<Pair<AggregatedSamplesPerTraceCtx, CalleesTreeView>> view2 = cache.getProfileView(npPair.name, "t1", ProfileViewType.CALLEES);

        view1.setHandler(ar -> {
            context.assertTrue(ar.succeeded());
            context.assertEquals(ar.result().second, npPair.getCallTreeView(1));
            async.countDown();
        });

        view2.setHandler(ar -> {
            context.assertTrue(ar.succeeded());
            context.assertEquals(ar.result().second, npPair.getCalleesTreeView(1));
            async.countDown();
        });

        async.awaitSuccess(2000);
        verify(viewCreator, times(1)).buildCacheableView(same(npPair.profileInfo), eq("t1"), eq(ProfileViewType.CALLEES));
        verify(viewCreator, times(1)).buildCacheableView(same(npPair.profileInfo), eq("t1"), eq(ProfileViewType.CALLERS));
    }


    @Test(timeout = 2500)
    public void testLoadProfileAndViewWhenRemotelyCached_shouldThrowExceptionWithRemoteIp(TestContext context) throws Exception {
        Async async = context.async(1);

        AggregatedProfileNamingStrategy profileName = profileName("proc1", dt(0));
        // update zookeeper that tells that profile is loaded somewhere else
        createProfileNode(profileName, "127.0.0.1", 3456);
        setUpDefaultCache(context, mockedProfileLoader(), null);

        Future<AggregatedProfileInfo> profile1 = cache.getAggregatedProfile(profileName);

        profile1.setHandler(ar -> {
            context.assertTrue(ar.failed());
            context.assertTrue(ar.cause() instanceof CachedProfileNotFoundException);
            CachedProfileNotFoundException ex = (CachedProfileNotFoundException) ar.cause();
            context.assertTrue(ex.isCachedRemotely());
            context.assertEquals(ex.getIp(), "127.0.0.1");
            context.assertEquals(ex.getPort(), 3456);
            async.countDown();
        });

        async.awaitSuccess(2000);

        Async async2 = context.async(1);
        Future<?> f = cache.getProfileView(profileName, "t1", ProfileViewType.CALLEES);
        f.setHandler(ar -> {
            context.assertTrue(ar.failed());
            context.assertTrue(ar.cause() instanceof CachedProfileNotFoundException);
            CachedProfileNotFoundException ex = (CachedProfileNotFoundException) ar.cause();
            context.assertTrue(ex.isCachedRemotely());
            context.assertEquals(ex.getIp(), "127.0.0.1");
            context.assertEquals(ex.getPort(), 3456);
            async2.countDown();
        });
    }

    @Test(timeout = 10000)
    public void testProfileExpiry_cacheShouldGetInvalidated_EntryShouldBeRemovedFromZookeeper(TestContext context) throws Exception {
        TestTicker ticker = new TestTicker();

        NameProfilePair npPair = npPair("proc2", dt(0));
        StorageBackedProfileLoader loader = mockedProfileLoader(npPair);
        ProfileViewCreator viewCreator = mockedViewCreator(npPair);

        setUpCache(context, new LocalProfileCache(config, viewCreator, ticker), loader);

        Async async1 = context.async();
        cache.getAggregatedProfile(npPair.name);
        Thread.sleep(1200);

        Future<?> profile1 = cache.getAggregatedProfile(npPair.name);
        profile1.setHandler(ar -> {
            context.assertTrue(ar.succeeded());
            async1.countDown();
        });
        async1.awaitSuccess(2000);

        Async async2 = context.async();
        Future<?> view1 = cache.getProfileView(npPair.name,"t1", ProfileViewType.CALLERS);
        view1.setHandler(ar -> {
            context.assertTrue(ar.succeeded());
            async2.countDown();
        });
        async2.awaitSuccess(5000);

        // all objects are loaded. advance time
        ticker.advance(config.getProfileRetentionDurationMin() + 1, TimeUnit.MINUTES);

        // retry fetching
        Async async3 = context.async(1);
        view1 = cache.getProfileView(npPair.name,"t1", ProfileViewType.CALLERS);
        view1.setHandler(ar -> {
            context.assertTrue(ar.failed());
            // earlier getView used to fail in case the profile was evicted. Now it will initiate the profile load.
            context.assertTrue(ar.cause() instanceof ProfileLoadInProgressException);
            async3.countDown();
        });
        async3.awaitSuccess(1000);

        verify(viewCreator, times(1)).buildCacheableView(same(npPair.profileInfo), eq("t1"), eq(ProfileViewType.CALLERS));
        // 1 in the starting and 1 after the eviction
        verify(loader, times(2)).load(same(npPair.name));
    }

    private StorageBackedProfileLoader mockedProfileLoader(NameProfilePair... npPairs) throws Exception {
        StorageBackedProfileLoader loader = mock(StorageBackedProfileLoader.class);
        for(NameProfilePair npPair : npPairs) {
            doAnswer(invocation -> getDelayedResponse(500, npPair.profileInfo)).when(loader).load(eq(npPair.name));
        }
        return loader;
    }

    private ProfileViewCreator mockedViewCreator(NameProfilePair... npPairs) {
        ProfileViewCreator foo = mock(ProfileViewCreator.class);
        for(NameProfilePair npPair : npPairs) {
            IntStream.range(0, npPair.callTreeView.size()).forEach(i ->
                doReturn(npPair.callTreeView.get(i)).when(foo).buildCacheableView(same(npPair.profileInfo), eq("t" + (i+1)), eq(ProfileViewType.CALLERS)));
            IntStream.range(0, npPair.calleesTreeView.size()).forEach(i ->
                doReturn(npPair.calleesTreeView.get(i)).when(foo).buildCacheableView(same(npPair.profileInfo), eq("t" + (i+1)), eq(ProfileViewType.CALLEES)));

        }
        return foo;
    }

    private static AggregatedProfileNamingStrategy profileName(String procId, ZonedDateTime dt) {
        return new AggregatedProfileNamingStrategy(config.getProfilesBaseDir(), 1, "app1", "cluster1", procId, dt, 1200, AggregatedProfileModel.WorkType.cpu_sample_work);
    }

    private <T> Future<T> getDelayedFuture(int ms, T obj) {
        Future<T> f = Future.future();
        vertx.setTimer(ms, h -> {
            if(obj instanceof Throwable) {
                f.fail((Throwable) obj);
            }
            else {
                f.complete(obj);
            }
        });
        return f;
    }

    private <T> T getDelayedResponse(int ms, T obj) {
        try {
            Thread.sleep(ms);
        } catch (Exception e) {
            // ignore
        }
        return obj;
    }

    private ZonedDateTime dt(int offsetInSec) {
        return beginning.plusSeconds(offsetInSec);
    }

    private void createProfileNode(AggregatedProfileNamingStrategy profileName, String ip, int port) throws Exception {
        curatorClient.create().creatingParentsIfNeeded().withMode(CreateMode.EPHEMERAL).forPath(zkPathForProfile(profileName),
            LoadInfoEntities.ProfileResidencyInfo.newBuilder().setIp(ip).setPort(port).build().toByteArray());
    }

    private static class NameProfilePair {
        final AggregatedProfileNamingStrategy name;
        final AggregatedProfileInfo profileInfo;
        final List<CallTreeView> callTreeView = new ArrayList<>();
        final List<CalleesTreeView> calleesTreeView = new ArrayList<>();

        NameProfilePair(String procId, ZonedDateTime dt, int traceCount) {
            this.name = profileName(procId, dt);
            this.profileInfo = mock(AggregatedProfileInfo.class);
            IntStream.range(0, traceCount).forEach(i -> {
                callTreeView.add(mock(CallTreeView.class));
                calleesTreeView.add(mock(CalleesTreeView.class));
            });
        }

        NameProfilePair(String procId, ZonedDateTime dt) {
            this(procId, dt, 1);
        }

        CallTreeView getCallTreeView(int i) {
            return callTreeView.get(i - 1);
        }

        CalleesTreeView getCalleesTreeView(int i) {
            return calleesTreeView.get(i - 1);
        }
    }

    private static NameProfilePair npPair(String procId, ZonedDateTime dt) {
        return new NameProfilePair(procId, dt);
    }

    private static NameProfilePair npPair(String procId, ZonedDateTime dt, int traceCount) {
        return new NameProfilePair(procId, dt, traceCount);
    }

    private void cleanUpZookeeper() throws Exception {
        List<String> profileNodes = new ArrayList<>();
        List<String> nodes = new ArrayList<>();
        if(curatorClient.checkExists().forPath("/profilesLoadStatus") != null) {
            profileNodes.addAll(curatorClient.getChildren().forPath("/profilesLoadStatus"));
        }
        if(curatorClient.checkExists().forPath("/nodesInfo") != null) {
            nodes.addAll(curatorClient.getChildren().forPath("/nodesInfo"));
        }

        for(String path : profileNodes) {
            curatorClient.delete().forPath("/profilesLoadStatus/" + path);
        }
        for(String path : nodes) {
            curatorClient.delete().forPath("/nodesInfo/" + path);
        }
    }

    private String zkPathForProfile(AggregatedProfileNamingStrategy profileName) {
        return "/profilesLoadStatus/" +  BaseEncoding.base32().encode(profileName.toString().getBytes(Charset.forName("utf-8")));
    }
}
