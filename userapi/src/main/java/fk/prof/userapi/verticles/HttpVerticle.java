package fk.prof.userapi.verticles;

import fk.prof.aggregation.AggregatedProfileNamingStrategy;
import fk.prof.aggregation.proto.AggregatedProfileModel;
import fk.prof.storage.StreamTransformer;
import fk.prof.userapi.Configuration;
import fk.prof.userapi.api.ProfileStoreAPI;
import fk.prof.userapi.http.UserapiApiPathConstants;
import fk.prof.userapi.model.AggregatedProfileInfo;
import fk.prof.userapi.model.AggregationWindowSummary;
import io.vertx.core.AbstractVerticle;
import io.vertx.core.AsyncResult;
import io.vertx.core.CompositeFuture;
import io.vertx.core.Future;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpServerResponse;
import io.vertx.core.impl.CompositeFutureImpl;
import io.vertx.core.json.Json;
import io.vertx.core.json.JsonObject;
import io.vertx.core.logging.Logger;
import io.vertx.core.logging.LoggerFactory;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import io.vertx.ext.web.handler.LoggerHandler;
import io.vertx.ext.web.handler.TimeoutHandler;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.charset.Charset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * Routes requests to their respective handlers
 * Created by rohit.patiyal on 18/01/17.
 */
public class HttpVerticle extends AbstractVerticle {

    private static Logger LOGGER = LoggerFactory.getLogger(HttpVerticle.class);

    private String baseDir;
    private final int maxListProfilesDurationInSecs;
    private Configuration.HttpConfig httpConfig;
    private ProfileStoreAPI profileStoreAPI;

    public HttpVerticle(Configuration.HttpConfig httpConfig, ProfileStoreAPI profileStoreAPI, String baseDir, int maxListProfilesDurationInDays) {
        this.httpConfig = httpConfig;
        this.profileStoreAPI = profileStoreAPI;
        this.baseDir = baseDir;
        this.maxListProfilesDurationInSecs = maxListProfilesDurationInDays*24*60*60;
    }

    private Router configureRouter() {
        Router router = Router.router(vertx);
        router.route().handler(TimeoutHandler.create(httpConfig.getRequestTimeout()));
        router.route().handler(LoggerHandler.create());

        router.get(UserapiApiPathConstants.APPS).handler(this::getAppIds);
        router.get(UserapiApiPathConstants.CLUSTER_GIVEN_APPID).handler(this::getClusterIds);
        router.get(UserapiApiPathConstants.PROC_GIVEN_APPID_CLUSTERID).handler(this::getProcId);
        router.get(UserapiApiPathConstants.PROFILES_GIVEN_APPID_CLUSTERID_PROCID).handler(this::getProfiles);
        router.get(UserapiApiPathConstants.PROFILE_GIVEN_APPID_CLUSTERID_PROCID_WORKTYPE_TRACENAME).handler(this::getCpuSamplingTraces);
        router.get(UserapiApiPathConstants.HEALTHCHECK).handler(this::handleGetHealth);

        return router;
    }

    @Override
    public void start(Future<Void> startFuture) throws Exception {
        httpConfig = config().mapTo(Configuration.HttpConfig.class);

        Router router = configureRouter();
        vertx.createHttpServer()
                .requestHandler(router::accept)
                .listen(httpConfig.getHttpPort(), event -> {
                    if (event.succeeded()) {
                        startFuture.complete();
                    } else {
                        startFuture.fail(event.cause());
                    }
                });
    }

    private void getAppIds(RoutingContext routingContext) {
        String prefix = routingContext.request().getParam("prefix");
        if (prefix == null) {
            prefix = "";
        }
        Future<Set<String>> future = Future.future();
        profileStoreAPI.getAppIdsWithPrefix(future.setHandler(result -> setResponse(result, routingContext)),
            baseDir, prefix);
    }

    private void getClusterIds(RoutingContext routingContext) {
        final String appId = routingContext.request().getParam("appId");
        String prefix = routingContext.request().getParam("prefix");
        if (prefix == null) {
            prefix = "";
        }
        Future<Set<String>> future = Future.future();
        profileStoreAPI.getClusterIdsWithPrefix(future.setHandler(result -> setResponse(result, routingContext)),
            baseDir, appId, prefix);
    }

    private void getProcId(RoutingContext routingContext) {
        final String appId = routingContext.request().getParam("appId");
        final String clusterId = routingContext.request().getParam("clusterId");
        String prefix = routingContext.request().getParam("prefix");
        if (prefix == null) {
            prefix = "";
        }
        Future<Set<String>> future = Future.future();
        profileStoreAPI.getProcsWithPrefix(future.setHandler(result -> setResponse(result, routingContext)),
            baseDir, appId, clusterId, prefix);
    }

    private void getProfiles(RoutingContext routingContext) {
        final String appId = routingContext.request().getParam("appId");
        final String clusterId = routingContext.request().getParam("clusterId");
        final String proc = routingContext.request().getParam("procId");

        ZonedDateTime startTime;
        int duration;

        try {
            startTime = ZonedDateTime.parse(routingContext.request().getParam("start"), DateTimeFormatter.ISO_ZONED_DATE_TIME);
            duration = Integer.parseInt(routingContext.request().getParam("duration"));
        } catch (Exception e) {
            setResponse(Future.failedFuture(new IllegalArgumentException(e)), routingContext);
            return;
        }
        if (duration > maxListProfilesDurationInSecs) {
            setResponse(Future.failedFuture(new IllegalArgumentException("Max window size supported = " + maxListProfilesDurationInSecs + " seconds, requested window size = " + duration + " seconds")), routingContext);
            return;
        }

        Future<List<AggregatedProfileNamingStrategy>> foundProfiles = Future.future();
        foundProfiles.setHandler(result -> {
            List<Future> profileSummaries = new ArrayList<>();
            for (AggregatedProfileNamingStrategy filename: result.result()) {
                Future<AggregationWindowSummary> summary = Future.future();

                profileStoreAPI.loadSummary(summary, filename);
                profileSummaries.add(summary);
            }

            CompositeFuture.join(profileSummaries).setHandler(summaryResult -> {
                List<AggregationWindowSummary> succeeded = new ArrayList<>();
                List<ErroredGetSummaryResponse> failed = new ArrayList<>();

                // Can only get the underlying list of results of it is a CompositeFutureImpl
                if(summaryResult instanceof CompositeFutureImpl) {
                    CompositeFutureImpl compositeFuture = (CompositeFutureImpl) summaryResult;
                    for (int i = 0; i < compositeFuture.size(); ++i) {
                        if(compositeFuture.succeeded(i)) {
                            succeeded.add(compositeFuture.resultAt(i));
                        }
                        else {
                            AggregatedProfileNamingStrategy failedFilename = result.result().get(i);
                            failed.add(new ErroredGetSummaryResponse(failedFilename.startTime, failedFilename.duration, compositeFuture.cause(i).getMessage()));
                        }
                    }
                }
                else {
                    if(summaryResult.succeeded()) {
                        CompositeFuture compositeFuture = summaryResult.result();
                        for (int i = 0; i < compositeFuture.size(); ++i) {
                            succeeded.add(compositeFuture.resultAt(i));
                        }
                    }
                    else {
                        // composite future failed so set error in response.
                        setResponse(Future.failedFuture(summaryResult.cause()), routingContext);
                        return;
                    }
                }

                Map<String, Object> response = new HashMap<>();
                response.put("succeeded", succeeded);
                response.put("failed", failed);

                setResponse(Future.succeededFuture(response), routingContext, true);
            });
        });

        profileStoreAPI.getProfilesInTimeWindow(foundProfiles,
            baseDir, appId, clusterId, proc, startTime, duration);
    }

    private void getCpuSamplingTraces(RoutingContext routingContext) {
        String appId = routingContext.request().getParam("appId");
        String clusterId = routingContext.request().getParam("clusterId");
        String procId = routingContext.request().getParam("procId");
        AggregatedProfileModel.WorkType workType = AggregatedProfileModel.WorkType.cpu_sample_work;
        String traceName = routingContext.request().getParam("traceName");

        ZonedDateTime startTime;
        int duration;

        try {
            startTime = ZonedDateTime.parse(routingContext.request().getParam("start"), DateTimeFormatter.ISO_ZONED_DATE_TIME);
            duration = Integer.parseInt(routingContext.request().getParam("duration"));
        } catch (Exception e) {
            setResponse(Future.failedFuture(new IllegalArgumentException(e)), routingContext);
            return;
        }

        AggregatedProfileNamingStrategy filename;
        try {
            filename = new AggregatedProfileNamingStrategy(baseDir, 1, appId, clusterId, procId, startTime, duration, workType);
        } catch (Exception e) {
            setResponse(Future.failedFuture(new IllegalArgumentException(e)), routingContext);
            return;
        }

        Future<AggregatedProfileInfo> future = Future.future();
        future.setHandler((AsyncResult<AggregatedProfileInfo> result) -> {
            if (result.succeeded()) {
                setResponse(Future.succeededFuture(result.result().getAggregatedSamples(traceName)), routingContext, true);
            } else {
                setResponse(result, routingContext);
            }
        });
        profileStoreAPI.load(future, filename);
    }

    private void handleGetHealth(RoutingContext routingContext) {
        routingContext.response().setStatusCode(200).end();
    }

    private <T> void setResponse(AsyncResult<T> result, RoutingContext routingContext) {
        setResponse(result, routingContext, false);
    }

    private <T> void setResponse(AsyncResult<T> result, RoutingContext routingContext, boolean gzipped) {
        if(routingContext.response().ended()) {
            return;
        }
        if(result.failed()) {
            LOGGER.error(routingContext.request().uri(), result.cause());

            if(result.cause() instanceof FileNotFoundException) {
                endResponseWithError(routingContext.response(), result.cause(), 404);
            }
            else if(result.cause() instanceof IllegalArgumentException) {
                endResponseWithError(routingContext.response(), result.cause(), 400);
            }
            else {
                endResponseWithError(routingContext.response(), result.cause(), 500);
            }
        }
        else {
            String encodedResponse = Json.encode(result.result());
            HttpServerResponse response = routingContext.response();

            response.putHeader("content-type", "application/json");
            if(gzipped && safeContains(routingContext.request().getHeader("Accept-Encoding"), "gzip")) {
                Buffer compressedBuf;
                try {
                    compressedBuf = Buffer.buffer(StreamTransformer.compress(encodedResponse.getBytes(Charset.forName("utf-8"))));
                }
                catch(IOException e) {
                    setResponse(Future.failedFuture(e), routingContext, false);
                    return;
                }

                response.putHeader("Content-Encoding", "gzip");
                response.end(compressedBuf);
            }
            else {
                response.end(encodedResponse);
            }
        }
    }

    private boolean safeContains(String str, String subStr) {
        if(str == null || subStr == null) {
            return false;
        }
        return str.toLowerCase().contains(subStr.toLowerCase());
    }

    private void endResponseWithError(HttpServerResponse response, Throwable error, int statusCode) {
        response.setStatusCode(statusCode).end(buildHttpErrorObject(error.getMessage(), statusCode).encode());
    }

    private JsonObject buildHttpErrorObject(String msg, int statusCode) {
        final JsonObject error = new JsonObject()
                .put("timestamp", System.currentTimeMillis())
                .put("status", statusCode);

        switch (statusCode) {
            case 400: error.put("error", "BAD_REQUEST");
                break;
            case 404: error.put("error", "NOT_FOUND");
                break;
            case 500: error.put("error", "INTERNAL_SERVER_ERROR");
                break;
            default:  error.put("error", "SOMETHING_WENT_WRONG");
        }

        if (msg != null) {
            error.put("message", msg);
        }
        return error;
    }

    public static class ErroredGetSummaryResponse {
        private final ZonedDateTime start;
        private final int duration;
        private final String error;

        ErroredGetSummaryResponse(ZonedDateTime start, int duration, String errorMsg) {
            this.start = start;
            this.duration = duration;
            this.error = errorMsg;
        }

        public ZonedDateTime getStart() {
            return start;
        }

        public int getDuration() {
            return duration;
        }

        public String getError() {
            return error;
        }
    }
}
