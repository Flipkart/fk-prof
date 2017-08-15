package fk.prof.userapi.api.cache;

import com.google.common.io.BaseEncoding;
import fk.prof.aggregation.AggregatedProfileNamingStrategy;
import fk.prof.aggregation.proto.AggregatedProfileModel;
import fk.prof.userapi.Configuration;
import fk.prof.userapi.Pair;
import fk.prof.userapi.UserapiConfigManager;
import fk.prof.userapi.api.AggregatedProfileLoader;
import fk.prof.userapi.api.ProfileStoreAPIImpl;
import fk.prof.userapi.api.ProfileViewCreator;
import fk.prof.userapi.model.AggregatedProfileInfo;
import fk.prof.userapi.model.AggregatedSamplesPerTraceCtx;
import fk.prof.userapi.model.tree.CallTreeView;
import fk.prof.userapi.model.tree.CalleesTreeView;
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

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Created by gaurav.ashok on 14/07/17.
 */
@RunWith(VertxUnitRunner.class)
public class CacheTest {
    private static final int zkPort = 2191;

    static {
        UserapiConfigManager.setDefaultSystemProperties();
    }

    private Vertx vertx;
    private static Configuration config;
    private static TestingServer zookeeper;
    private static CuratorFramework curator;
    private WorkerExecutor executor;
    private ClusterAwareCache cache;
    private LocalProfileCache localProfileCache;

    private static ZonedDateTime beginning = ZonedDateTime.now(Clock.systemUTC());

    @BeforeClass
    public static void beforeClass() throws Exception {
        config = UserapiConfigManager.loadConfig(ProfileStoreAPIImpl.class.getClassLoader().getResource("userapi-conf.json").getFile());

        zookeeper = new TestingServer(zkPort, true);

        Configuration.CuratorConfig curatorConfig = config.getCuratorConfig();
        curator = CuratorFrameworkFactory.builder()
            .connectString("127.0.0.1:" + zkPort)
            .retryPolicy(new ExponentialBackoffRetry(1000, curatorConfig.getMaxRetries()))
            .connectionTimeoutMs(curatorConfig.getConnectionTimeoutMs())
            .sessionTimeoutMs(curatorConfig.getSessionTimeoutMs())
            .namespace(curatorConfig.getNamespace())
            .build();

        curator.start();
        curator.blockUntilConnected(config.getCuratorConfig().getConnectionTimeoutMs(), TimeUnit.MILLISECONDS);
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

    private void setUpDefaultCache(TestContext context, AggregatedProfileLoader profileLoader, ProfileViewCreator viewCreator) {
        setUpCache(context, new LocalProfileCache(config), profileLoader, viewCreator);
    }

    private void setUpCache(TestContext context, LocalProfileCache localCache, AggregatedProfileLoader profileLoader, ProfileViewCreator viewCreator) {
        localProfileCache = localCache;
        cache = new ClusterAwareCache(curator, executor, profileLoader, viewCreator, config, localProfileCache);
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

        byte[] bytes = curator.getData().forPath("/nodesInfo/127.0.0.1:" + config.getHttpConfig().getHttpPort());
        Assert.assertNotNull(bytes);
        Assert.assertNotNull(LoadInfoEntities.NodeLoadInfo.parseFrom(bytes));
    }

    @Test(timeout = 3500)
    public void testLoadProfile_shouldCallLoaderOnlyOnceOnMultipleInvocations(TestContext context) throws Exception {
        Async async = context.async(2);
        NameProfilePair npPair = npPair("proc1", dt(0));
        AggregatedProfileLoader loader = mockedProfileLoader(npPair);

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
        verify(loader, times(1)).load(any(), eq(npPair.name));

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

        Future<Pair<AggregatedSamplesPerTraceCtx, CallTreeView>> view = cache.getCallTreeView(profileName, "");
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
        ProfileViewCreator viewCreator = mockedViewCreator(CallTreeView.class, npPair);

        setUpDefaultCache(context, null, viewCreator);
        localProfileCache.put(npPair.name, Future.succeededFuture(npPair.profileInfo));

        Future<Pair<AggregatedSamplesPerTraceCtx, CallTreeView>> view1 = cache.getCallTreeView(npPair.name, "t1");
        Future<Pair<AggregatedSamplesPerTraceCtx, CallTreeView>> view2 = cache.getCallTreeView(npPair.name, "t1");

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
        verify(viewCreator, times(1)).buildCallTreeView(same(npPair.profileInfo), eq("t1"));
    }

    @Test(timeout = 2500)
    public void testLoadCallersViewForDifferentTraces_shouldReturnCallersViewForAlreadyLoadedProfile(TestContext context) throws Exception {
        Async async = context.async(2);

        NameProfilePair npPair = npPair("proc1", dt(0), 2);
        ProfileViewCreator viewCreator = mockedViewCreator(CallTreeView.class, npPair);

        setUpDefaultCache(context, null, viewCreator);
        localProfileCache.put(npPair.name, Future.succeededFuture(npPair.profileInfo));

        Future<Pair<AggregatedSamplesPerTraceCtx, CallTreeView>> view1 = cache.getCallTreeView(npPair.name, "t1");
        Future<Pair<AggregatedSamplesPerTraceCtx, CallTreeView>> view2 = cache.getCallTreeView(npPair.name, "t2");

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
        verify(viewCreator, times(1)).buildCallTreeView(same(npPair.profileInfo), eq("t1"));
        verify(viewCreator, times(1)).buildCallTreeView(same(npPair.profileInfo), eq("t2"));
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
        Future<?> f = cache.getCalleesTreeView(profileName, "t1");
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

    @Test(timeout = 4000)
    public void testProfileExpiry_cacheShouldGetInvalidated_EntryShouldBeRemovedFromZookeeper(TestContext context) throws Exception {
        TestTicker ticker = new TestTicker();

        NameProfilePair npPair = npPair("proc2", dt(0));
        AggregatedProfileLoader loader = mockedProfileLoader(npPair);
        ProfileViewCreator viewCreator = mockedViewCreator(CallTreeView.class, npPair);

        setUpCache(context, new LocalProfileCache(config, ticker), loader, viewCreator);

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
        Future<?> view1 = cache.getCallTreeView(npPair.name, "t1");
        view1.setHandler(ar -> {
            context.assertTrue(ar.succeeded());
            async2.countDown();
        });
        async2.awaitSuccess(500);

        // all objects are loaded. advance time
        ticker.advance(config.getProfileRetentionDurationMin() + 1, TimeUnit.MINUTES);

        // retry fetching
        Async async3 = context.async(1);
        view1 = cache.getCallTreeView(npPair.name, "t1");
        view1.setHandler(ar -> {
            context.assertTrue(ar.failed());
            context.assertTrue(ar.cause() instanceof CachedProfileNotFoundException);
            async3.countDown();
        });
        async3.awaitSuccess(1000);

        verify(viewCreator, times(1)).buildCallTreeView(same(npPair.profileInfo), eq("t1"));
        verify(loader, times(1)).load(any(), same(npPair.name));
    }

    private AggregatedProfileLoader mockedProfileLoader(NameProfilePair... npPairs) {
        AggregatedProfileLoader loader = mock(AggregatedProfileLoader.class);
        for(NameProfilePair npPair : npPairs) {
            doAnswer(invocation -> {
                getDelayedFuture(invocation.getArgument(0), 500, npPair.profileInfo);
                return null;
            }).when(loader).load(any(), eq(npPair.name));
        }
        return loader;
    }

    private <T> ProfileViewCreator mockedViewCreator(Class<T> clazz, NameProfilePair... npPairs) {
        ProfileViewCreator foo = mock(ProfileViewCreator.class);
        for(NameProfilePair npPair : npPairs) {
            if(CallTreeView.class.equals(clazz)) {
                IntStream.range(0, npPair.callTreeView.size()).forEach(i ->
                    doReturn(npPair.callTreeView.get(i)).when(foo).buildCallTreeView(same(npPair.profileInfo), eq("t" + (i+1))));
            }
            else {
                IntStream.range(0, npPair.calleesTreeView.size()).forEach(i ->
                    doReturn(npPair.calleesTreeView.get(i)).when(foo).buildCalleesTreeView(same(npPair.profileInfo), eq("t" + (i+1))));
            }
        }

        return foo;
    }

    private static AggregatedProfileNamingStrategy profileName(String procId, ZonedDateTime dt) {
        return new AggregatedProfileNamingStrategy(config.getProfilesBaseDir(), 1, "app1", "cluster1", procId, dt, 1200, AggregatedProfileModel.WorkType.cpu_sample_work);
    }

    private <T> void getDelayedFuture(Future<T> f, int ms, T obj) {
        vertx.setTimer(ms, h -> {
            if(obj instanceof Throwable) {
                f.fail((Throwable) obj);
            }
            else {
                f.complete(obj);
            }
        });
    }

    private ZonedDateTime dt(int offsetInSec) {
        return beginning.plusSeconds(offsetInSec);
    }

    private void createProfileNode(AggregatedProfileNamingStrategy profileName, String ip, int port) throws Exception {
        curator.create().creatingParentsIfNeeded().withMode(CreateMode.EPHEMERAL).forPath(zkPathForProfile(profileName),
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
        if(curator.checkExists().forPath("/profilesLoadStatus") != null) {
            profileNodes.addAll(curator.getChildren().forPath("/profilesLoadStatus"));
        }
        if(curator.checkExists().forPath("/nodesInfo") != null) {
            nodes.addAll(curator.getChildren().forPath("/nodesInfo"));
        }

        for(String path : profileNodes) {
            curator.delete().forPath("/profilesLoadStatus/" + path);
        }
        for(String path : nodes) {
            curator.delete().forPath("/nodesInfo/" + path);
        }
    }

    private String zkPathForProfile(AggregatedProfileNamingStrategy profileName) {
        return "/profilesLoadStatus/" +  BaseEncoding.base32().encode(profileName.toString().getBytes(Charset.forName("utf-8")));
    }
}
