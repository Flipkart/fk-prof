package fk.prof.userapi.verticles;

import fk.prof.aggregation.AggregatedProfileNamingStrategy;
import fk.prof.aggregation.proto.AggregatedProfileModel;
import fk.prof.userapi.Configuration;
import fk.prof.userapi.UserapiConfigManager;
import fk.prof.userapi.api.ProfileStoreAPIImpl;
import fk.prof.userapi.deployer.VerticleDeployer;
import fk.prof.userapi.deployer.impl.UserapiHttpVerticleDeployer;
import fk.prof.userapi.model.AggregationWindowSummary;
import fk.prof.userapi.model.json.ProtoSerializers;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.vertx.core.CompositeFuture;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpClient;
import io.vertx.core.json.Json;
import io.vertx.ext.unit.Async;
import io.vertx.ext.unit.TestContext;
import io.vertx.ext.unit.junit.VertxUnitRunner;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentMatchers;
import org.mockito.Mock;
import org.mockito.internal.util.collections.Sets;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;

import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeoutException;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;

/**
 * Tests for {@link HttpVerticle} using mocked behaviour of ProfileStoreAPIImpl
 * Created by rohit.patiyal on 27/01/17.
 */
@RunWith(VertxUnitRunner.class)
public class RouterVerticleTest {
    private static final String URL_SEPARATOR = "/";
    private static final String P_APP_ID = "app1";
    private static final String NP_APP_ID = "foo";

    private static final String P_CLUSTER_ID = "cluster1";
    private static final String NP_CLUSTER_ID = "bar";
    private static final String P_PROC = "process1";
    private static final String NP_PROC = "main";

    private static final String P_ENCODED_TIME_STAMP = "2017-01-20T12:37:00.376%2B05:30";
    private static final String NP_ENCODED_TIME_STAMP = "2017-01-21T12:37:00.376%2B05:30";
    private static final ZonedDateTime P_TIME_STAMP = ZonedDateTime.parse("2017-01-20T12:37:00.376+05:30", DateTimeFormatter.ISO_ZONED_DATE_TIME);
    private static final ZonedDateTime NP_TIME_STAMP = ZonedDateTime.parse("2017-01-21T12:37:00.376+05:30", DateTimeFormatter.ISO_ZONED_DATE_TIME);

    private static final String P_WORKTYPE = "thread_sample_work";
    private static final String NP_WORKTYPE = "cpu_sample_work";


    private static final int DURATION = 1500;
    private static final long MORE_THAN_TIMEOUT = 3000;
    @Rule
    public MockitoRule rule = MockitoJUnit.rule();
    private Vertx vertx;
    private HttpClient client;
    private int port = 8082;

    @Mock
    private ProfileStoreAPIImpl profileDiscoveryAPI;

    @Before
    public void setUp(TestContext context) throws Exception {
        final Async async = context.async();
        ProtoSerializers.registerSerializers(Json.mapper);

        UserapiConfigManager.setDefaultSystemProperties();
        Configuration config = UserapiConfigManager.loadConfig(ProfileStoreAPIImpl.class.getClassLoader().getResource("userapi-conf.json").getFile());
        vertx = Vertx.vertx();
        port = config.getHttpConfig().getHttpPort();
        client = vertx.createHttpClient();

        VerticleDeployer verticleDeployer = new UserapiHttpVerticleDeployer(vertx, config, profileDiscoveryAPI);
        verticleDeployer.deploy().setHandler(ar -> {
            if(ar.failed()) {
                context.fail(ar.cause());
            }
            async.complete();
        });
    }

    @After
    public void tearDown(TestContext testContext) throws Exception {
      vertx.close(testContext.asyncAssertSuccess());
    }

    @Test(timeout = 10000)
    public void TestRequestTimeout(TestContext testContext) throws Exception {
        final Async async = testContext.async();
        String pPrefixSet = "(^$|a|ap|app|app1)";
        doAnswer(invocation -> {
                    Future<Set<String>> future = invocation.getArgument(0);
                    CompletableFuture.supplyAsync(() -> {
                        try {
                            Thread.sleep(MORE_THAN_TIMEOUT);
                        } catch (InterruptedException e) {
                            e.printStackTrace();
                        }
                        return Sets.newSet(P_APP_ID);
                    }).whenComplete((result ,error) -> completeFuture(result, error, future));
            return null;
        }).when(profileDiscoveryAPI).getAppIdsWithPrefix(any(), any(), ArgumentMatchers.matches(pPrefixSet));

        client.getNow(port, "localhost", "/apps?prefix=a", httpClientResponse -> {
            testContext.assertEquals(httpClientResponse.statusCode(), HttpResponseStatus.SERVICE_UNAVAILABLE.code());
            httpClientResponse.bodyHandler(buffer -> {
                testContext.assertTrue(buffer.toString().contains("Service Unavailable"));
                async.complete();
            });
        });
    }

    @Test(timeout = 10000)
    public void TestGetAppsRoute(TestContext testContext) throws Exception {
        final Async async = testContext.async();
        String pPrefixSet = "(^$|a|ap|app|app1)";
        String npPrefixSet = "(f|fo|foo)";
        doAnswer(invocation -> {
            Future<Set<String>> future = invocation.getArgument(0);
            CompletableFuture.supplyAsync(() -> Sets.newSet(P_APP_ID)).whenComplete((res, error) -> completeFuture(res, error, future));
            return null;
        }).when(profileDiscoveryAPI).getAppIdsWithPrefix(any(), any(), ArgumentMatchers.matches(pPrefixSet));
        doAnswer(invocation -> {
            Future<Set<String>> future = invocation.getArgument(0);
            CompletableFuture.supplyAsync(Sets::<String>newSet).whenComplete((res, error) -> completeFuture(res, error, future));
            return null;
        }).when(profileDiscoveryAPI).getAppIdsWithPrefix(any(), any(), ArgumentMatchers.matches(npPrefixSet));

        Future<Void> pCorrectPrefix = Future.future();
        Future<Void> pIncorrectPrefix = Future.future();
        Future<Void> pNoPrefix = Future.future();

        client.getNow(port, "localhost", "/apps?prefix=" + P_APP_ID.substring(0, 1 + new Random().nextInt(P_APP_ID.length() - 1)), httpClientResponse -> {
            testContext.assertEquals(httpClientResponse.statusCode(), HttpResponseStatus.OK.code());
            httpClientResponse.bodyHandler(buffer -> {
                testContext.assertTrue(buffer.toString().contains(P_APP_ID));
                testContext.assertFalse(buffer.toString().contains(NP_APP_ID));
                pCorrectPrefix.complete();
            });
        });
        client.getNow(port, "localhost", "/apps?prefix=", httpClientResponse -> {
            testContext.assertEquals(httpClientResponse.statusCode(), HttpResponseStatus.OK.code());
            httpClientResponse.bodyHandler(buffer -> {
                testContext.assertTrue(buffer.toString().contains(P_APP_ID));
                testContext.assertFalse(buffer.toString().contains(NP_APP_ID));
                pIncorrectPrefix.complete();
            });
        });
        client.getNow(port, "localhost", "/apps?prefix=" + NP_APP_ID.substring(0, 1 + new Random().nextInt(NP_APP_ID.length() - 1)), httpClientResponse -> {
            testContext.assertEquals(httpClientResponse.statusCode(), HttpResponseStatus.OK.code());
            httpClientResponse.bodyHandler(buffer -> {
                testContext.assertFalse(buffer.toString().contains(P_APP_ID));
                testContext.assertFalse(buffer.toString().contains(NP_APP_ID));
                pNoPrefix.complete();
            });
        });

        CompositeFuture.all(pCorrectPrefix, pIncorrectPrefix, pNoPrefix).setHandler(compositeFutureAsyncResult -> async.complete());

    }

    @Test(timeout = 10000)
    public void TestHealthcheckRoute(TestContext testContext) throws Exception {
        final Async async = testContext.async();
        client.getNow(port, "localhost", "/health", httpClientResponse -> {
            testContext.assertEquals(httpClientResponse.statusCode(), 200);
            async.complete();
        });
    }

    @Test(timeout = 10000)
    public void TestGetClustersRoute(TestContext testContext) throws Exception {
        final Async async = testContext.async();
        String pPrefixSet = "(^$|c|cl|clu|clus|clust|cluste|cluster|cluster1)";
        String npPrefixSet = "(b|ba|bar)";
        doAnswer(invocation -> {
            CompletableFuture.supplyAsync(() -> Sets.newSet(P_CLUSTER_ID)).whenComplete((res, error) -> completeFuture(res, error, invocation.getArgument(0)));
            return null;
        }).when(profileDiscoveryAPI).getClusterIdsWithPrefix(any(), any(), eq(P_APP_ID), ArgumentMatchers.matches(pPrefixSet));
        doAnswer(invocation -> {
            CompletableFuture.supplyAsync(Sets::<String>newSet).whenComplete((res, error) -> completeFuture(res, error, invocation.getArgument(0)));
            return null;
        }).when(profileDiscoveryAPI).getClusterIdsWithPrefix(any(), any(), eq(P_APP_ID), ArgumentMatchers.matches(npPrefixSet));
        doAnswer(invocation -> {
            CompletableFuture.supplyAsync(Sets::<String>newSet).whenComplete((res, error) -> completeFuture(res, error, invocation.getArgument(0)));
            return null;
        }).when(profileDiscoveryAPI).getClusterIdsWithPrefix(any(), any(), eq(NP_APP_ID), ArgumentMatchers.matches(pPrefixSet));
        doAnswer(invocation -> {
            CompletableFuture.supplyAsync(Sets::<String>newSet).whenComplete((res, error) -> completeFuture(res, error, invocation.getArgument(0)));
            return null;
        }).when(profileDiscoveryAPI).getClusterIdsWithPrefix(any(), any(), eq(NP_APP_ID), ArgumentMatchers.matches(npPrefixSet));

        Future<Void> pAndCorrectPrefix = Future.future();
        Future<Void> pAndIncorrectPrefix = Future.future();
        Future<Void> pAndNoPrefix = Future.future();

        Future<Void> npAndPPrefix = Future.future();
        Future<Void> npAndNpPrefix = Future.future();
        Future<Void> npAndNoPrefix = Future.future();

        client.getNow(port, "localhost", "/cluster/" + P_APP_ID + "?prefix=" + P_CLUSTER_ID.substring(0, 1 + new Random().nextInt(P_CLUSTER_ID.length() - 1)), httpClientResponse -> {
            testContext.assertEquals(httpClientResponse.statusCode(), HttpResponseStatus.OK.code());
            httpClientResponse.bodyHandler(buffer -> {
                testContext.assertTrue(buffer.toString().contains(P_CLUSTER_ID));
                testContext.assertFalse(buffer.toString().contains(NP_CLUSTER_ID));
                pAndCorrectPrefix.complete();
            });
        });
        client.getNow(port, "localhost", "/cluster/" + P_APP_ID + "?prefix=", httpClientResponse -> {
            testContext.assertEquals(httpClientResponse.statusCode(), HttpResponseStatus.OK.code());
            httpClientResponse.bodyHandler(buffer -> {
                testContext.assertTrue(buffer.toString().contains(P_CLUSTER_ID));
                testContext.assertFalse(buffer.toString().contains(NP_CLUSTER_ID));
                pAndNoPrefix.complete();
            });
        });
        client.getNow(port, "localhost", "/cluster/" + P_APP_ID + "?prefix=" + NP_CLUSTER_ID.substring(0, 1 + new Random().nextInt(NP_CLUSTER_ID.length() - 1)), httpClientResponse -> {
            testContext.assertEquals(httpClientResponse.statusCode(), HttpResponseStatus.OK.code());
            httpClientResponse.bodyHandler(buffer -> {
                testContext.assertFalse(buffer.toString().contains(P_CLUSTER_ID));
                testContext.assertFalse(buffer.toString().contains(NP_CLUSTER_ID));
                pAndIncorrectPrefix.complete();
            });
        });

        client.getNow(port, "localhost", "/cluster/" + NP_APP_ID + "?prefix=" + P_CLUSTER_ID.substring(0, 1 + new Random().nextInt(P_CLUSTER_ID.length() - 1)), httpClientResponse -> {
            testContext.assertEquals(httpClientResponse.statusCode(), HttpResponseStatus.OK.code());
            httpClientResponse.bodyHandler(buffer -> {
                testContext.assertFalse(buffer.toString().contains(P_CLUSTER_ID));
                testContext.assertFalse(buffer.toString().contains(NP_CLUSTER_ID));
                npAndPPrefix.complete();
            });
        });
        client.getNow(port, "localhost", "/cluster/" + NP_APP_ID + "?prefix=" + NP_CLUSTER_ID.substring(0, 1 + new Random().nextInt(NP_CLUSTER_ID.length() - 1)), httpClientResponse -> {
            testContext.assertEquals(httpClientResponse.statusCode(), HttpResponseStatus.OK.code());
            httpClientResponse.bodyHandler(buffer -> {
                testContext.assertFalse(buffer.toString().contains(P_CLUSTER_ID));
                testContext.assertFalse(buffer.toString().contains(NP_CLUSTER_ID));
                npAndNpPrefix.complete();
            });
        });
        client.getNow(port, "localhost", "/cluster/" + NP_APP_ID + "?prefix=", httpClientResponse -> {
            testContext.assertEquals(httpClientResponse.statusCode(), HttpResponseStatus.OK.code());
            httpClientResponse.bodyHandler(buffer -> {
                testContext.assertFalse(buffer.toString().contains(P_CLUSTER_ID));
                testContext.assertFalse(buffer.toString().contains(NP_CLUSTER_ID));
                npAndNoPrefix.complete();
            });
        });

        CompositeFuture.all(pAndCorrectPrefix, pAndIncorrectPrefix, pAndNoPrefix, npAndPPrefix, npAndNpPrefix, npAndNoPrefix).setHandler(compositeFutureAsyncResult -> async.complete());
    }

    @Test(timeout = 10000)
    public void TestGetProcRoute(TestContext testContext) throws Exception {
        final Async async = testContext.async();
        String pPrefixSet = "(^$|p|pr|pro|proc|proce|proces|process|process1)";
        String npPrefixSet = "(m|ma|mai|main)";

        doAnswer(invocation -> {
            CompletableFuture.supplyAsync(() -> Sets.newSet(P_PROC)).whenComplete((res, error) -> completeFuture(res, error, invocation.getArgument(0)));
            return null;
        }).when(profileDiscoveryAPI).getProcsWithPrefix(any(), any(), eq(P_APP_ID), eq(P_CLUSTER_ID), ArgumentMatchers.matches(pPrefixSet));
        doAnswer(invocation -> {
            CompletableFuture.supplyAsync(Sets::<String>newSet).whenComplete((res, error) -> completeFuture(res, error, invocation.getArgument(0)));
            return null;
        }).when(profileDiscoveryAPI).getProcsWithPrefix(any(), any(), eq(P_APP_ID), eq(P_CLUSTER_ID), ArgumentMatchers.matches(npPrefixSet));
        doAnswer(invocation -> {
            CompletableFuture.supplyAsync(Sets::<String>newSet).whenComplete((res, error) -> completeFuture(res, error, invocation.getArgument(0)));
            return null;
        }).when(profileDiscoveryAPI).getProcsWithPrefix(any(), any(), eq(NP_APP_ID), eq(NP_CLUSTER_ID), ArgumentMatchers.matches(pPrefixSet));
        doAnswer(invocation -> {
            CompletableFuture.supplyAsync(Sets::<String>newSet).whenComplete((res, error) -> completeFuture(res, error, invocation.getArgument(0)));
            return null;
        }).when(profileDiscoveryAPI).getProcsWithPrefix(any(), any(), eq(NP_APP_ID), eq(NP_CLUSTER_ID), ArgumentMatchers.matches(npPrefixSet));

        Future<Void> pAndCorrectPrefix = Future.future();
        Future<Void> pAndIncorrectPrefix = Future.future();
        Future<Void> pAndNoPrefix = Future.future();

        Future<Void> npAndPPrefix = Future.future();
        Future<Void> npAndNpPrefix = Future.future();
        Future<Void> npAndNoPrefix = Future.future();

        client.getNow(port, "localhost", "/proc/" + P_APP_ID + URL_SEPARATOR + P_CLUSTER_ID + "?prefix=" + P_PROC.substring(0, 1 + new Random().nextInt(P_PROC.length() - 1)), httpClientResponse -> {
            testContext.assertEquals(httpClientResponse.statusCode(), HttpResponseStatus.OK.code());
            httpClientResponse.bodyHandler(buffer -> {
                testContext.assertTrue(buffer.toString().contains(P_PROC));
                testContext.assertFalse(buffer.toString().contains(NP_PROC));
                pAndCorrectPrefix.complete();
            });

        });
        client.getNow(port, "localhost", "/proc/" + P_APP_ID + URL_SEPARATOR + P_CLUSTER_ID + "?prefix=" + NP_PROC.substring(0, 1 + new Random().nextInt(NP_PROC.length() - 1)), httpClientResponse -> {
            testContext.assertEquals(httpClientResponse.statusCode(), HttpResponseStatus.OK.code());
            httpClientResponse.bodyHandler(buffer -> {
                testContext.assertFalse(buffer.toString().contains(P_PROC));
                testContext.assertFalse(buffer.toString().contains(NP_PROC));
                pAndIncorrectPrefix.complete();
            });

        });
        client.getNow(port, "localhost", "/proc/" + P_APP_ID + URL_SEPARATOR + P_CLUSTER_ID + "?prefix=", httpClientResponse -> {
            testContext.assertEquals(httpClientResponse.statusCode(), HttpResponseStatus.OK.code());
            httpClientResponse.bodyHandler(buffer -> {
                testContext.assertTrue(buffer.toString().contains(P_PROC));
                testContext.assertFalse(buffer.toString().contains(NP_PROC));
                pAndNoPrefix.complete();
            });

        });
        client.getNow(port, "localhost", "/proc/" + NP_APP_ID + URL_SEPARATOR + NP_CLUSTER_ID + "?prefix=" + P_PROC.substring(0, 1 + new Random().nextInt(P_PROC.length() - 1)), httpClientResponse -> {
            testContext.assertEquals(httpClientResponse.statusCode(), HttpResponseStatus.OK.code());
            httpClientResponse.bodyHandler(buffer -> {
                testContext.assertFalse(buffer.toString().contains(P_PROC));
                testContext.assertFalse(buffer.toString().contains(NP_PROC));
                npAndPPrefix.complete();
            });

        });
        client.getNow(port, "localhost", "/proc/" + NP_APP_ID + URL_SEPARATOR + NP_CLUSTER_ID + "?prefix=" + NP_PROC.substring(0, 1 + new Random().nextInt(NP_PROC.length() - 1)), httpClientResponse -> {
            testContext.assertEquals(httpClientResponse.statusCode(), HttpResponseStatus.OK.code());
            httpClientResponse.bodyHandler(buffer -> {
                testContext.assertFalse(buffer.toString().contains(P_PROC));
                testContext.assertFalse(buffer.toString().contains(NP_PROC));
                npAndNpPrefix.complete();
            });

        });
        client.getNow(port, "localhost", "/proc/" + NP_APP_ID + URL_SEPARATOR + NP_CLUSTER_ID + "?prefix=", httpClientResponse -> {
            testContext.assertEquals(httpClientResponse.statusCode(), HttpResponseStatus.OK.code());
            httpClientResponse.bodyHandler(buffer -> {
                testContext.assertFalse(buffer.toString().contains(P_PROC));
                testContext.assertFalse(buffer.toString().contains(NP_PROC));
                npAndNoPrefix.complete();
            });

        });

        CompositeFuture.all(pAndCorrectPrefix, pAndIncorrectPrefix, pAndNoPrefix, npAndPPrefix, npAndNpPrefix, npAndNoPrefix).setHandler(compositeFutureAsyncResult -> async.complete());

    }

    @Test(timeout = 10000)
    public void TestGetProfilesRoute(TestContext testContext) throws Exception {
        final Async async = testContext.async();

        AggregatedProfileNamingStrategy pProfile = new AggregatedProfileNamingStrategy("profiles", 1, P_APP_ID, P_CLUSTER_ID, P_PROC, P_TIME_STAMP, 1800);
        AggregationWindowSummary dummySummary = new AggregationWindowSummary(
                AggregatedProfileModel.Header.newBuilder().setFormatVersion(1).setAggregationStartTime(P_TIME_STAMP.format(DateTimeFormatter.ISO_ZONED_DATE_TIME)).setAggregationEndTime(P_TIME_STAMP.plusSeconds(1800).format(DateTimeFormatter.ISO_ZONED_DATE_TIME)).build(),
                AggregatedProfileModel.TraceCtxNames.newBuilder().addAllName(Arrays.asList("trace1", "trace2")).build(), null, null);

        String encodedSummary = Json.encode(dummySummary);

        doAnswer(invocation -> {
            CompletableFuture.supplyAsync(() -> Arrays.asList(pProfile)).whenComplete((res, error) -> completeFuture(res, error, invocation.getArgument(0)));
            return null;
        }).when(profileDiscoveryAPI).getProfilesInTimeWindow(any(), any(), eq(P_APP_ID), eq(P_CLUSTER_ID), eq(P_PROC), eq(P_TIME_STAMP), eq(DURATION));
        doAnswer(invocation -> {
            CompletableFuture.supplyAsync(() -> Collections.EMPTY_LIST).whenComplete((res, error) -> completeFuture(res, error, invocation.getArgument(0)));
            return null;
        }).when(profileDiscoveryAPI).getProfilesInTimeWindow(any(), any(), eq(P_APP_ID), eq(P_CLUSTER_ID), eq(P_PROC), eq(NP_TIME_STAMP), eq(DURATION));
        doAnswer(invocation -> {
            Future<AggregationWindowSummary> summary = invocation.getArgument(0);
            summary.complete(dummySummary);
            return null;
        }).when(profileDiscoveryAPI).loadSummary(any(), eq(pProfile));

        Future<Void> pAndCorrectPrefix = Future.future();
        Future<Void> pAndIncorrectPrefix = Future.future();
        Future<Void> pAndNoPrefix = Future.future();

        client.getNow(port, "localhost", "/profiles/" + P_APP_ID + URL_SEPARATOR + P_CLUSTER_ID + URL_SEPARATOR + P_PROC + "?start=" + P_ENCODED_TIME_STAMP + "&duration=" + DURATION, httpClientResponse -> {
            testContext.assertEquals(httpClientResponse.statusCode(), HttpResponseStatus.OK.code());
            httpClientResponse.bodyHandler(buffer -> {
                testContext.assertTrue(buffer.toString().equals("{\"failed\":[],\"succeeded\":[" + encodedSummary + "]}"));
                pAndCorrectPrefix.complete();
            });
        });
        client.getNow(port, "localhost", "/profiles/" + P_APP_ID + URL_SEPARATOR + P_CLUSTER_ID + URL_SEPARATOR + P_PROC + "?start=" + NP_ENCODED_TIME_STAMP + "&duration=" + DURATION, httpClientResponse -> {
            testContext.assertEquals(httpClientResponse.statusCode(), HttpResponseStatus.OK.code());
            httpClientResponse.bodyHandler(buffer -> {
                testContext.assertFalse(buffer.toString().contains(P_WORKTYPE));
                testContext.assertFalse(buffer.toString().contains(NP_WORKTYPE));
                pAndIncorrectPrefix.complete();
            });

        });
        client.getNow(port, "localhost", "/profiles/" + P_APP_ID + URL_SEPARATOR + P_CLUSTER_ID + URL_SEPARATOR + P_PROC, httpClientResponse -> {
            testContext.assertEquals(httpClientResponse.statusCode(), HttpResponseStatus.BAD_REQUEST.code());
            pAndNoPrefix.complete();
            });

        CompositeFuture.all(pAndCorrectPrefix, pAndIncorrectPrefix, pAndNoPrefix).setHandler(compositeFutureAsyncResult -> async.complete());
    }

    @Test(timeout = 10000)
    public void TestGetProfilesRoute_shouldReturnFailedProfilesIfThereIsExceptionWhenLoadingFiles(TestContext testContext) throws Exception {
        final Async async = testContext.async();

        String errorMsg = "took too long";

        // file to fetch
        AggregatedProfileNamingStrategy pProfile = new AggregatedProfileNamingStrategy("profiles", 1, P_APP_ID, P_CLUSTER_ID, P_PROC, P_TIME_STAMP, 1800);

        // expected response
        Map<String, Object> expectedMap = new HashMap<>();
        expectedMap.put("failed", Arrays.asList(new HttpVerticle.ErroredGetSummaryResponse(pProfile.startTime, pProfile.duration, errorMsg)));
        expectedMap.put("succeeded", Collections.EMPTY_LIST);

        String expectedResponse = Json.encode(expectedMap);

        // found the file
        doAnswer(invocation -> {
            CompletableFuture.supplyAsync(() -> Arrays.asList(pProfile)).whenComplete((res, error) -> completeFuture(res, error, invocation.getArgument(0)));
            return null;
        }).when(profileDiscoveryAPI).getProfilesInTimeWindow(any(), any(), eq(P_APP_ID), eq(P_CLUSTER_ID), eq(P_PROC), eq(P_TIME_STAMP), eq(DURATION));

        // but throw timeout exception when loading it
        doAnswer(invocation -> {
            Future<AggregationWindowSummary> summary = invocation.getArgument(0);
            summary.fail(new TimeoutException(errorMsg));
            return null;
        }).when(profileDiscoveryAPI).loadSummary(any(), eq(pProfile));

        // make the request
        Future<Void> f1 = Future.future();
        client.getNow(port, "localhost", "/profiles/" + P_APP_ID + URL_SEPARATOR + P_CLUSTER_ID + URL_SEPARATOR + P_PROC + "?start=" + P_ENCODED_TIME_STAMP + "&duration=" + DURATION, httpClientResponse -> {
            testContext.assertEquals(httpClientResponse.statusCode(), HttpResponseStatus.OK.code());
            httpClientResponse.bodyHandler(buffer -> {
                testContext.assertTrue(buffer.toString().equals(expectedResponse));
                f1.complete();
            });
        });

        f1.setHandler(res -> async.complete());
    }

    private <T> void completeFuture(T result, Throwable error, Future<T> future) {
        if(error == null) {
            future.complete(result);
        }
        else {
            future.fail(error);
        }
    }
}
