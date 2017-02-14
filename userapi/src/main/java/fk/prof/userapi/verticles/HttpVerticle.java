package fk.prof.userapi.verticles;

import fk.prof.aggregation.AggregatedProfileFileNamingStrategy;
import fk.prof.aggregation.proto.AggregatedProfileModel;
import fk.prof.userapi.api.ProfileStoreAPI;
import fk.prof.userapi.model.AggregatedProfileInfo;
import fk.prof.userapi.model.FilteredProfiles;
import io.vertx.core.AbstractVerticle;
import io.vertx.core.AsyncResult;
import io.vertx.core.Future;
import io.vertx.core.json.Json;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import io.vertx.ext.web.handler.LoggerHandler;
import io.vertx.ext.web.handler.TimeoutHandler;

import java.io.FileNotFoundException;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Set;

/**
 * Routes requests to their respective handlers
 * Created by rohit.patiyal on 18/01/17.
 */
public class HttpVerticle extends AbstractVerticle {

    public static final int AGGREGATION_DURATION = 30; // in min
    public static final String BASE_DIR = "profiles";

    private ProfileStoreAPI profileStoreAPI;

    HttpVerticle(ProfileStoreAPI profileStoreAPI) {
        this.profileStoreAPI = profileStoreAPI;
    }

    private Router configureRouter() {
        Router router = Router.router(vertx);
        router.route().handler(LoggerHandler.create());
        router.route().handler(TimeoutHandler.create(config().getInteger("req.timeout")));
        router.route("/").handler(routingContext -> routingContext.response()
                .putHeader("context-type", "text/html")
                .end("<h1>Welcome to UserAPI for FKProfiler"));
        router.get("/apps").handler(this::getAppIds);
        router.get("/cluster/:appId").handler(this::getClusterIds);
        router.get("/proc/:appId/:clusterId").handler(this::getProcs);
        router.get("/profiles/:appId/:clusterId/:proc").handler(this::getProfiles);
        router.get("/traces/:appId/:clusterId/:procId/:workType").handler(this::getTracesRequest);
        router.get("/profile/:appId/:clusterId/:procId/cpu-sampling/:traceName").handler(this::getCpuSamplingTraces);
        return router;
    }

    @Override
    public void start(Future<Void> startFuture) throws Exception {
        Router router = configureRouter();
        vertx.createHttpServer()
                .requestHandler(router::accept)
                .listen(config().getInteger("http.port", 8080), event -> {
                    if (event.succeeded()) {
                        startFuture.complete();
                    } else {
                        startFuture.fail(event.cause());
                    }
                });
    }

    public void getAppIds(RoutingContext routingContext) {
        String prefix = routingContext.request().getParam("prefix");
        if (prefix == null) {
            prefix = "";
        }
        Future<Set<String>> future = Future.future();
        profileStoreAPI.getAppIdsWithPrefix(future.setHandler(result -> setResponse(result, routingContext)),
                BASE_DIR, prefix);
    }

    private void getClusterIds(RoutingContext routingContext) {
        final String appId = routingContext.request().getParam("appId");
        String prefix = routingContext.request().getParam("prefix");
        if (prefix == null) {
            prefix = "";
        }
        Future<Set<String>> future = Future.future();
        profileStoreAPI.getClusterIdsWithPrefix(future.setHandler(result -> setResponse(result, routingContext)),
                BASE_DIR, appId, prefix);
    }

    private void getProcs(RoutingContext routingContext) {
        final String appId = routingContext.request().getParam("appId");
        final String clusterId = routingContext.request().getParam("clusterId");
        String prefix = routingContext.request().getParam("prefix");
        if (prefix == null) {
            prefix = "";
        }
        Future<Set<String>> future = Future.future();
        profileStoreAPI.getProcsWithPrefix(future.setHandler(result -> setResponse(result, routingContext)),
                BASE_DIR, appId, clusterId, prefix);
    }

    private void getProfiles(RoutingContext routingContext) {
        final String appId = routingContext.request().getParam("appId");
        final String clusterId = routingContext.request().getParam("clusterId");
        final String proc = routingContext.request().getParam("proc");

        ZonedDateTime startTime;
        int duration;

        try {
            startTime = ZonedDateTime.parse(routingContext.request().getParam("start"), DateTimeFormatter.ISO_ZONED_DATE_TIME);
            duration = Integer.parseInt(routingContext.request().getParam("duration"));
        }
        catch (Exception e) {
            setResponse(Future.failedFuture(new IllegalArgumentException(e)), routingContext);
            return;
        }

        Future<Set<FilteredProfiles>> future = Future.future();
        profileStoreAPI.getProfilesInTimeWindow(future.setHandler(result -> setResponse(result, routingContext)),
                BASE_DIR, appId, clusterId, proc, startTime, duration);
    }

    public void getTracesRequest(RoutingContext routingContext) {
        String appId = routingContext.request().getParam("appId");
        String clusterId = routingContext.request().getParam("clusterId");
        String procId = routingContext.request().getParam("procId");
        String workType = routingContext.request().getParam("workType");

        String startTime = routingContext.request().getParam("start");

        AggregatedProfileFileNamingStrategy filename;
        try {
            filename = buildFileName(appId, clusterId, procId, AggregatedProfileModel.WorkType.valueOf(workType), startTime);
        }
        catch (Exception e) {
            setResponse(Future.failedFuture(new IllegalArgumentException(e)), routingContext);
            return;
        }

        Future<AggregatedProfileInfo> future = Future.future();
        future.setHandler((AsyncResult<AggregatedProfileInfo> result) -> {
            if(result.succeeded()) {
                setResponse(Future.succeededFuture(result.result().getProfileSummary().getTraces()), routingContext);
            }
            else {
                setResponse(Future.failedFuture(result.cause()), routingContext);
            }
        });
        profileStoreAPI.load(future, filename);
    }

    public void getCpuSamplingTraces(RoutingContext routingContext) {
        String appId = routingContext.request().getParam("appId");
        String clusterId = routingContext.request().getParam("clusterId");
        String procId = routingContext.request().getParam("procId");
        AggregatedProfileModel.WorkType workType = AggregatedProfileModel.WorkType.cpu_sample_work;
        String traceName = routingContext.request().getParam("traceName");

        String startTime = routingContext.request().getParam("start");

        AggregatedProfileFileNamingStrategy filename;
        try {
            filename = buildFileName(appId, clusterId, procId, workType, startTime);
        } catch (Exception e) {
            setResponse(Future.failedFuture(new IllegalArgumentException(e)), routingContext);
            return;
        }

        Future<AggregatedProfileInfo> future = Future.future();
        future.setHandler((AsyncResult<AggregatedProfileInfo> result) -> {
            if (result.succeeded()) {
                setResponse(Future.succeededFuture(result.result().getAggregatedSamples(traceName)), routingContext);
            } else {
                setResponse(result, routingContext);
            }
        });
        profileStoreAPI.load(future, filename);
    }

    private <T> void setResponse(AsyncResult<T> result, RoutingContext routingContext) {
        if(routingContext.response().ended()) {
            return;
        }
        if(result.failed()) {
            if(result.cause() instanceof FileNotFoundException) {
                routingContext.response().setStatusCode(404).end();
            }
            else if(result.cause() instanceof IllegalArgumentException) {
                routingContext.response().setStatusCode(400).setStatusMessage(result.cause().getMessage()).end();
            }
            else {
                routingContext.response().setStatusCode(500).setStatusMessage(result.cause().getMessage()).end();
            }
        }
        else {
            String response = Json.encode(result.result());
            routingContext.response().putHeader("content-type", "application/json").end(response);
        }
    }

    private AggregatedProfileFileNamingStrategy buildFileName(String appId, String clusterId, String procId,
                                                              AggregatedProfileModel.WorkType workType, String startTime) {
        ZonedDateTime zonedStartTime = ZonedDateTime.parse(startTime, DateTimeFormatter.ISO_ZONED_DATE_TIME);
        return new AggregatedProfileFileNamingStrategy(BASE_DIR, 1, appId, clusterId, procId, zonedStartTime, AGGREGATION_DURATION * 60, workType);
    }
}
