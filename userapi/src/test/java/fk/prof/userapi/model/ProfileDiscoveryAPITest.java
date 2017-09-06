package fk.prof.userapi.model;

import fk.prof.aggregation.AggregatedProfileNamingStrategy;
import fk.prof.storage.AsyncStorage;
import fk.prof.userapi.Configuration;
import fk.prof.userapi.UserapiConfigManager;
import fk.prof.userapi.api.ProfileStoreAPI;
import fk.prof.userapi.api.ProfileStoreAPIImpl;
import fk.prof.userapi.model.json.ProtoSerializers;
import io.vertx.core.AsyncResult;
import io.vertx.core.CompositeFuture;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.json.Json;
import io.vertx.ext.unit.Async;
import io.vertx.ext.unit.TestContext;
import io.vertx.ext.unit.junit.VertxUnitRunner;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.internal.util.collections.Sets;

import java.time.ZonedDateTime;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Stream;

import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;


/**
 * Tests for {@link ProfileStoreAPIImpl} using mocked behaviour of listAysnc {@link AsyncStorage} API
 * Created by rohit.patiyal on 24/01/17.
 */

@RunWith(VertxUnitRunner.class)
public class ProfileDiscoveryAPITest {

    private static final String DELIMITER = "/";
    private static final String BASE_DIR = "profiles";

    private ProfileStoreAPI profileDiscoveryAPI;
    private AsyncStorage asyncStorage;
    private Vertx vertx;

    String[] objects = {
            "profiles/v0001/MZXW6===/MJQXE===/NVQWS3Q=/2017-01-20T12:37:20.551+05:30/1500/thread_sample_work/0001",
            "profiles/v0001/MZXW6===/MJQXE===/NVQWS3Q=/2017-01-20T12:37:20.551+05:30/1500/cpu_sample_work/0001",
            "profiles/v0001/MZXW6===/MJQXE===/NVQWS3Q=/2017-01-20T12:37:20.551+05:30/1500/summary/0001",
            "profiles/v0001/MFYHAMI=/MNWHK43UMVZDC===/OBZG6Y3FONZTC===/2017-01-20T12:37:20.551+05:30/1500/monitor_contention_work/0001",
            "profiles/v0001/MFYHAMI=/MNWHK43UMVZDC===/OBZG6Y3FONZTC===/2017-01-20T12:37:20.551+05:30/1500/summary/0001",
            "profiles/v0001/MFYHAMI=/MNWHK43UMVZDC===/OBZG6Y3FONZTC===/2017-01-20T12:37:20.551+05:30/1800/monitor_wait_work/0001",
            "profiles/v0001/MFYHAMI=/MNWHK43UMVZDC===/OBZG6Y3FONZTC===/2017-01-20T12:37:20.551+05:30/1800/summary/0001",
    };

    AggregatedProfileNamingStrategy[] filenames = Stream.of(objects).map(AggregatedProfileNamingStrategy::fromFileName).toArray(AggregatedProfileNamingStrategy[]::new);
    private Configuration config;

    private Set<String> getObjList(String prefix, boolean recursive) {

        Set<String> resultObjects = new HashSet<>();
        for (String obj : objects) {
            if (obj.indexOf(prefix) == 0) {
                if (recursive) {
                    resultObjects.add(obj);
                } else {
                    resultObjects.add(obj.substring(0, prefix.length() + obj.substring(prefix.length()).indexOf(DELIMITER)));
                }
            }
        }
        return resultObjects;
    }

    @BeforeClass
    public static void setup() {
        ProtoSerializers.registerSerializers(Json.mapper);
    }

    @Before
    public void setUp() throws Exception {
        vertx = Vertx.vertx();
        asyncStorage = mock(AsyncStorage.class);
        config = UserapiConfigManager.loadConfig(ParseProfileTest.class.getClassLoader().getResource("userapi-conf.json").getFile());
        profileDiscoveryAPI = new ProfileStoreAPIImpl(vertx, asyncStorage, 30, config.getProfileLoadTimeout(), config.getVertxWorkerPoolSize());

        when(asyncStorage.listAsync(anyString(), anyBoolean())).thenAnswer(invocation -> {
            String path1 = invocation.getArgument(0);
            Boolean recursive = invocation.getArgument(1);
            return CompletableFuture.supplyAsync(() -> getObjList(path1, recursive));
        });
    }

    @Test(timeout = 10000)
    public void TestGetAppIdsWithPrefix(TestContext context) throws Exception {
        Async async = context.async();
        Map<String, Collection<Object>> appIdTestPairs = new HashMap<String, Collection<Object>>() {
            {
                put("app", Sets.newSet("app1"));
                put("", Sets.newSet("app1", "foo"));
            }
        };

        List<Future> futures = new ArrayList<>();
        for (Map.Entry<String, Collection<Object>> entry : appIdTestPairs.entrySet()) {
            Future<Set<String>> f = Future.future();
            futures.add(f);

            f.setHandler(res -> context.assertEquals(entry.getValue(), res.result()));
            profileDiscoveryAPI.getAppIdsWithPrefix(f, BASE_DIR, entry.getKey());
        }

        CompositeFuture f = CompositeFuture.all(futures);
        f.setHandler(res -> completeTest(res, context, async));
    }

    @Test(timeout = 10000)
    public void TestGetClusterIdsWithPrefix(TestContext context) throws Exception {
        Async async = context.async();
        Map<List<String>, Collection<?>> appIdTestPairs = new HashMap<List<String>, Collection<?>>() {
            {
                put(Arrays.asList("app1", "cl"), Sets.newSet("cluster1"));
                put(Arrays.asList("app1", ""), Sets.newSet("cluster1"));
                put(Arrays.asList("foo", "b"), Sets.newSet("bar"));
                put(Arrays.asList("np", "np"), Sets.newSet());
                put(Arrays.asList("app1", "b"), Sets.newSet());
                put(Arrays.asList("", ""), Sets.newSet());
            }
        };

        List<Future> futures = new ArrayList<>();
        for (Map.Entry<List<String>, Collection<?>> entry : appIdTestPairs.entrySet()) {
            Future<Set<String>> f = Future.future();
            futures.add(f);

            f.setHandler(res -> context.assertEquals(entry.getValue(), res.result()));
            profileDiscoveryAPI.getClusterIdsWithPrefix(f, BASE_DIR, entry.getKey().get(0), entry.getKey().get(1));
        }

        CompositeFuture f = CompositeFuture.all(futures);
        f.setHandler(res -> completeTest(res, context, async));
    }

    @Test(timeout = 10000)
    public void TestGetProcsWithPrefix(TestContext context) throws Exception {
        Async async = context.async();
        Map<List<String>, Collection<?>> appIdTestPairs = new HashMap<List<String>, Collection<?>>() {
            {
                put(Arrays.asList("app1", "cluster1", "pr"), Sets.newSet("process1"));
                put(Arrays.asList("app1", "", ""), Sets.newSet());
                put(Arrays.asList("foo", "bar", ""), Sets.newSet("main"));
                put(Arrays.asList("", "", ""), Sets.newSet());
            }
        };

        List<Future> futures = new ArrayList<>();
        for (Map.Entry<List<String>, Collection<?>> entry : appIdTestPairs.entrySet()) {
            Future<Set<String>> f = Future.future();
            futures.add(f);

            f.setHandler(res -> {
                context.assertEquals(entry.getValue(), res.result());
            });
            profileDiscoveryAPI.getProcsWithPrefix(f, BASE_DIR, entry.getKey().get(0), entry.getKey().get(1), entry.getKey().get(2));
        }

        CompositeFuture f = CompositeFuture.all(futures);
        f.setHandler(res -> completeTest(res, context, async));
    }

    @Test(timeout = 10000)
    public void TestGetProfilesInTimeWindow(TestContext context) throws Exception {
        Async async = context.async();
        FilteredProfiles profile1 = new FilteredProfiles(ZonedDateTime.parse("2017-01-20T12:37:20.551+05:30"), ZonedDateTime.parse("2017-01-20T12:37:20.551+05:30").plusSeconds(1500), Sets.newSet("monitor_contention_work"));
        FilteredProfiles profile2 = new FilteredProfiles(ZonedDateTime.parse("2017-01-20T12:37:20.551+05:30"), ZonedDateTime.parse("2017-01-20T12:37:20.551+05:30").plusSeconds(1800), Sets.newSet("monitor_wait_work"));
        FilteredProfiles profile3 = new FilteredProfiles(ZonedDateTime.parse("2017-01-20T12:37:20.551+05:30"), ZonedDateTime.parse("2017-01-20T12:37:20.551+05:30").plusSeconds(1500), Sets.newSet("thread_sample_work", "cpu_sample_work"));

        Map<List<Object>, Collection<?>> appIdTestPairs = new HashMap<List<Object>, Collection<?>>() {
            {
                put(Arrays.asList("app1", "cluster1", "process1", ZonedDateTime.parse("2017-01-20T12:37:20.551+05:30"), 1600),
                        Sets.newSet(filenames[4], filenames[6]));
                put(Arrays.asList("app1", "cluster1", "process1", ZonedDateTime.parse("2017-01-20T12:37:20.551+05:30"), 1900),
                        Sets.newSet(filenames[4], filenames[6]));
                put(Arrays.asList("foo", "bar", "main", ZonedDateTime.parse("2017-01-20T12:37:20.551+05:30"), 1900),
                        Sets.newSet(filenames[2]));
            }
        };

        List<Future> futures = new ArrayList<>();
        for (Map.Entry<List<Object>, Collection<?>> entry : appIdTestPairs.entrySet()) {
            Future<List<AggregatedProfileNamingStrategy>> f = Future.future();
            futures.add(f);

            f.setHandler(res -> context.assertEquals(entry.getValue(), Sets.newSet(res.result().toArray())));
            profileDiscoveryAPI.getProfilesInTimeWindow(f, BASE_DIR,
                    (String)entry.getKey().get(0), (String)entry.getKey().get(1), (String)entry.getKey().get(2), (ZonedDateTime)entry.getKey().get(3), (Integer)entry.getKey().get(4));
        }

        CompositeFuture f = CompositeFuture.all(futures);
        f.setHandler(res -> completeTest(res, context, async));
    }

    private void completeTest(AsyncResult result, TestContext context, Async async) {
        if(result.failed()) {
            context.fail(result.cause());
        }
        async.complete();
    }
}

