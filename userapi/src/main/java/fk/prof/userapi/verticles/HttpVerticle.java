package fk.prof.userapi.verticles;

import fk.prof.aggregation.AggregatedProfileNamingStrategy;
import fk.prof.aggregation.proto.AggregatedProfileModel;
import fk.prof.storage.StreamTransformer;
import fk.prof.userapi.api.ProfileStoreAPI;
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

  public static final String BASE_DIR = "profiles";
  public int aggregationWindowDurationInSecs = 1800;

    private ProfileStoreAPI profileStoreAPI;

    HttpVerticle(ProfileStoreAPI profileStoreAPI) {
        this.profileStoreAPI = profileStoreAPI;
    }

    private Router configureRouter() {
        Router router = Router.router(vertx);
        router.route().handler(LoggerHandler.create());
        router.route().handler(TimeoutHandler.create(config().getInteger("req.timeout")));
        router.route("/healthcheck").handler(routingContext -> routingContext.response()
                .setStatusCode(200)
                .putHeader("context-type", "text/html")
                .end("<h1>Health: OK</h1>"));
        router.get("/apps").handler(this::getAppIds);
        router.get("/cluster/:appId").handler(this::getClusterIds);
        router.get("/proc/:appId/:clusterId").handler(this::getProcs);
        router.get("/profiles/:appId/:clusterId/:proc").handler(this::getProfiles);
        router.get("/profile/:appId/:clusterId/:procId/cpu-sampling/:traceName").handler(this::getCpuSamplingTraces);
        return router;
    }

    @Override
    public void start(Future<Void> startFuture) throws Exception {
      this.aggregationWindowDurationInSecs = config().getInteger("aggregation_window.duration.secs");

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

    private void getAppIds(RoutingContext routingContext) {
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

      Future<List<AggregatedProfileNamingStrategy>> foundProfiles = Future.future();
      foundProfiles.setHandler(result -> {
        List<Future> profileSummaries = new ArrayList<>();

        for (AggregatedProfileNamingStrategy filename : result.result()) {
          Future<AggregationWindowSummary> summary = Future.future();

          profileStoreAPI.loadSummary(summary, filename);
          profileSummaries.add(summary);
        }

        CompositeFuture.join(profileSummaries).setHandler(summaryResult -> {
          List<AggregationWindowSummary> succeeded = new ArrayList<>();
          List<ErroredGetSummaryResponse> failed = new ArrayList<>();

          // Can only get the underlying list of results of it is a CompositeFutureImpl
          if (summaryResult instanceof CompositeFutureImpl) {
            CompositeFutureImpl compositeFuture = (CompositeFutureImpl) summaryResult;
            for (int i = 0; i < compositeFuture.size(); ++i) {
              if (compositeFuture.succeeded(i)) {
                succeeded.add(compositeFuture.resultAt(i));
              } else {
                AggregatedProfileNamingStrategy failedFilename = result.result().get(i);
                failed.add(new ErroredGetSummaryResponse(failedFilename.startTime, failedFilename.duration, compositeFuture.cause(i).getMessage()));
              }
            }
          } else {
            if (summaryResult.succeeded()) {
              CompositeFuture compositeFuture = summaryResult.result();
              for (int i = 0; i < compositeFuture.size(); ++i) {
                succeeded.add(compositeFuture.resultAt(i));
              }
            } else {
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
          BASE_DIR, appId, clusterId, proc, startTime, duration);
    }

    private void getCpuSamplingTraces(RoutingContext routingContext) {
        String appId = routingContext.request().getParam("appId");
        String clusterId = routingContext.request().getParam("clusterId");
        String procId = routingContext.request().getParam("procId");
        AggregatedProfileModel.WorkType workType = AggregatedProfileModel.WorkType.cpu_sample_work;
        String traceName = routingContext.request().getParam("traceName");

        String startTime = routingContext.request().getParam("start");

        AggregatedProfileNamingStrategy filename;
        try {
            filename = buildFileName(appId, clusterId, procId, workType, startTime);
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

  private <T> void setResponse(AsyncResult<T> result, RoutingContext routingContext) {
    setResponse(result, routingContext, false);
  }

  private <T> void setResponse(AsyncResult<T> result, RoutingContext routingContext, boolean gzipped) {
    if (routingContext.response().ended()) {
      return;
    }
    if (result.failed()) {
      if (result.cause() instanceof FileNotFoundException) {
        routingContext.response().setStatusCode(404).end(result.cause().getMessage());
      } else if (result.cause() instanceof IllegalArgumentException) {
        routingContext.response().setStatusCode(400).end(result.cause().getMessage());
      } else {
        routingContext.response().setStatusCode(500).end(result.cause().getMessage());
      }
    } else {
      String encodedResponse = Json.encode(result.result());
      HttpServerResponse response = routingContext.response();

      response.putHeader("content-type", "application/json");
      if (gzipped && safeContains(routingContext.request().getHeader("Accept-Encoding"), "gzip")) {
        Buffer compressedBuf;
        try {
          compressedBuf = Buffer.buffer(StreamTransformer.compress(encodedResponse.getBytes(Charset.forName("utf-8"))));
        } catch (IOException e) {
          setResponse(Future.failedFuture(e), routingContext, false);
          return;
        }

        response.putHeader("Content-Encoding", "gzip");
        response.end(compressedBuf);
      } else {
        response.end(encodedResponse);
      }
    }
  }

    private AggregatedProfileNamingStrategy buildFileName(String appId, String clusterId, String procId,
                                                          AggregatedProfileModel.WorkType workType, String startTime) {
        ZonedDateTime zonedStartTime = ZonedDateTime.parse(startTime, DateTimeFormatter.ISO_ZONED_DATE_TIME);
      return new AggregatedProfileNamingStrategy(BASE_DIR, 1, appId, clusterId, procId, zonedStartTime, aggregationWindowDurationInSecs, workType);
    }

  private boolean safeContains(String str, String subStr) {
    if (str == null || subStr == null) {
      return false;
    }
    return str.toLowerCase().contains(subStr.toLowerCase());
  }

  public static class ErroredGetSummaryResponse {
    private final ZonedDateTime start;
    private final int duration;
    private final String error;

    public ErroredGetSummaryResponse(ZonedDateTime start, int duration, String errorMsg) {
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

