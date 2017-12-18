package fk.prof.userapi.verticles;

import com.google.common.base.MoreObjects;
import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.util.JsonFormat;
import fk.prof.aggregation.AggregatedProfileNamingStrategy;
import fk.prof.aggregation.proto.AggregatedProfileModel;
import fk.prof.userapi.Configuration;
import fk.prof.userapi.api.ProfileStoreAPI;
import fk.prof.userapi.exception.UserapiHttpFailure;
import fk.prof.userapi.http.ProfHttpClient;
import fk.prof.userapi.http.UserapiHttpHelper;
import fk.prof.userapi.model.AggregatedSamplesPerTraceCtx;
import fk.prof.userapi.model.AggregationWindowSummary;
import fk.prof.userapi.model.ProfileViewType;
import fk.prof.userapi.model.TreeView;
import fk.prof.userapi.model.tree.IndexedTreeNode;
import fk.prof.userapi.model.tree.TreeViewResponse;
import fk.prof.userapi.util.HttpRequestUtil;
import fk.prof.userapi.util.Pair;
import fk.prof.userapi.util.ProtoUtil;
import io.vertx.core.AbstractVerticle;
import io.vertx.core.AsyncResult;
import io.vertx.core.CompositeFuture;
import io.vertx.core.Future;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.impl.CompositeFutureImpl;
import io.vertx.core.logging.Logger;
import io.vertx.core.logging.LoggerFactory;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import io.vertx.ext.web.handler.BodyHandler;
import io.vertx.ext.web.handler.LoggerHandler;
import io.vertx.ext.web.handler.TimeoutHandler;
import proto.PolicyDTO;

import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

import static fk.prof.userapi.http.UserapiApiPathConstants.*;
import static fk.prof.userapi.util.HttpRequestUtil.extractTypedParam;
import static fk.prof.userapi.util.HttpResponseUtil.setResponse;

/**
 * Routes requests to their respective handlers
 * Created by rohit.patiyal on 18/01/17.
 */
public class HttpVerticle extends AbstractVerticle {
    private static Logger logger = LoggerFactory.getLogger(HttpVerticle.class);
    private static int VERSION = 1;

    private String baseDir;
    private final int maxListProfilesDurationInSecs;
    private Configuration.HttpConfig httpConfig;
    private final Configuration.BackendConfig backendConfig;
    private ProfileStoreAPI profileStoreAPI;
    private ProfHttpClient httpClient;
    private Configuration.HttpClientConfig httpClientConfig;
    private Integer maxDepthForTreeExpand;

    public HttpVerticle(Configuration config, ProfileStoreAPI profileStoreAPI) {
        this.httpConfig = config.getHttpConfig();
        this.backendConfig = config.getBackendConfig();
        this.httpClientConfig = config.getHttpClientConfig();
        this.profileStoreAPI = profileStoreAPI;
        this.baseDir = config.getProfilesBaseDir();
        this.maxListProfilesDurationInSecs = config.getMaxListProfilesDurationInDays()*24*60*60;
        this.maxDepthForTreeExpand = config.getMaxDepthExpansion();
    }

    private Router configureRouter() {
        Router router = Router.router(vertx);
        router.route().handler(TimeoutHandler.create(httpConfig.getRequestTimeout()));
        router.route().handler(LoggerHandler.create());

        router.get(PROFILES_APPS).handler(this::getAppIds);
        router.get(PROFILES_CLUSTERS_FOR_APP).handler(this::getClusterIds);
        router.get(PROFILES_PROCS_FOR_APP_CLUSTER).handler(this::getProcName);
        router.get(PROFILES_FOR_APP_CLUSTER_PROC).handler(this::getProfiles);
        router.get(HEALTH_CHECK).handler(this::handleGetHealth);

        UserapiHttpHelper.attachHandlersToRoute(router, HttpMethod.GET, POLICIES_APPS, this::proxyListAPIToBackend);
        UserapiHttpHelper.attachHandlersToRoute(router, HttpMethod.GET, POLICIES_CLUSTERS_FOR_APP, this::proxyListAPIToBackend);
        UserapiHttpHelper.attachHandlersToRoute(router, HttpMethod.GET, POLICIES_PROCS_FOR_APP_CLUSTER, this::proxyListAPIToBackend);

        UserapiHttpHelper.attachHandlersToRoute(router, HttpMethod.GET, GET_POLICY_FOR_APP_CLUSTER_PROC, this::proxyGetPolicyToBackend);
        UserapiHttpHelper.attachHandlersToRoute(router, HttpMethod.PUT, PUT_POLICY_FOR_APP_CLUSTER_PROC,
                BodyHandler.create().setBodyLimit(1024 * 10), this::proxyPutPostPolicyToBackend);
        UserapiHttpHelper.attachHandlersToRoute(router, HttpMethod.POST, POST_POLICY_FOR_APP_CLUSTER_PROC,
                BodyHandler.create().setBodyLimit(1024 * 10), this::proxyPutPostPolicyToBackend);

        UserapiHttpHelper.attachHandlersToRoute(router, HttpMethod.POST, CALLERS_VIEW_FOR_CPU_SAMPLING,
            BodyHandler.create().setBodyLimit(1024 * 1024), this::getCallersViewForCpuSampling);
        UserapiHttpHelper.attachHandlersToRoute(router, HttpMethod.POST, CALLEES_VIEW_FOR_CPU_SAMPLING,
            BodyHandler.create().setBodyLimit(1024 * 1024), this::getCalleesViewForCpuSampling);

        return router;
    }

    @Override
    public void start(Future<Void> startFuture) throws Exception {
        httpConfig = config().mapTo(Configuration.HttpConfig.class);
        httpClient = ProfHttpClient.newBuilder().setConfig(httpClientConfig).build(vertx);

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
        final String prefix = routingContext.request().getParam("prefix");
        Future<Set<String>> future = Future.future();
        profileStoreAPI.getAppIdsWithPrefix(future.setHandler(result -> setResponse(result, routingContext)),
            baseDir, prefix);
    }

    private void getClusterIds(RoutingContext routingContext) {
        final String appId = routingContext.request().getParam("appId");
        final String prefix = routingContext.request().getParam("prefix");
        Future<Set<String>> future = Future.future();
        profileStoreAPI.getClusterIdsWithPrefix(future.setHandler(result -> setResponse(result, routingContext)),
            baseDir, appId, prefix);
    }

    private void getProcName(RoutingContext routingContext) {
        final String appId = routingContext.request().getParam("appId");
        final String clusterId = routingContext.request().getParam("clusterId");
        final String prefix = routingContext.request().getParam("prefix");
        Future<Set<String>> future = Future.future();
        profileStoreAPI.getProcNamesWithPrefix(future.setHandler(result -> setResponse(result, routingContext)),
            baseDir, appId, clusterId, prefix);
    }

    private void getProfiles(RoutingContext routingContext) {
        final String appId = routingContext.request().getParam("appId");
        final String clusterId = routingContext.request().getParam("clusterId");
        final String procName = routingContext.request().getParam("procName");

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
            if(result.failed()) {
                setResponse(result, routingContext);
                return;
            }

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
            baseDir, appId, clusterId, procName, startTime, duration);
    }

    private void getCallersViewForCpuSampling(RoutingContext routingContext) {
      getViewForCpuSampling(routingContext, ProfileViewType.CALLERS);
    }

    private void getCalleesViewForCpuSampling(RoutingContext routingContext) {
      getViewForCpuSampling(routingContext, ProfileViewType.CALLEES);
    }

    private void getViewForCpuSampling(RoutingContext routingContext, ProfileViewType profileViewType) {
      HttpServerRequest req = routingContext.request();

      String appId, clusterId, procId, traceName;
      Boolean forceExpand;
      Integer maxDepth, duration;
      ZonedDateTime startTime;
      List<Integer> nodeIds;
      try {
        appId = HttpRequestUtil.extractStringParam(req, "appId");
        clusterId = HttpRequestUtil.extractStringParam(req, "clusterId");
        procId = HttpRequestUtil.extractStringParam(req, "procId");
        traceName = HttpRequestUtil.extractStringParam(req, "traceName");
        startTime = HttpRequestUtil.extractTypedParam(req, "start", ZonedDateTime.class);
        duration = HttpRequestUtil.extractTypedParam(req, "duration", Integer.class);
        forceExpand = MoreObjects.firstNonNull(HttpRequestUtil.extractTypedParam(req, "forceExpand", Boolean.class, false), false);
        maxDepth = Math.min(maxDepthForTreeExpand, MoreObjects.firstNonNull(HttpRequestUtil.extractTypedParam(req, "maxDepth", Integer.class, false), maxDepthForTreeExpand));
        nodeIds = routingContext.getBodyAsJsonArray().getList();
      }
      catch (IllegalArgumentException e) {
        setResponse(Future.failedFuture(e), routingContext);
        return;
      }
      catch (Exception e) {
        setResponse(Future.failedFuture(new IllegalArgumentException(e)), routingContext);
        return;
      }

      AggregatedProfileNamingStrategy profileName = new AggregatedProfileNamingStrategy(baseDir, VERSION, appId, clusterId, procId, startTime, duration, AggregatedProfileModel.WorkType.cpu_sample_work);

      getTreeViewForCpuSampling(routingContext, profileName, traceName, nodeIds, forceExpand, maxDepth, profileViewType);
    }

    private <T extends TreeView<IndexedTreeNode<AggregatedProfileModel.FrameNode>>>void getTreeViewForCpuSampling(RoutingContext routingContext, AggregatedProfileNamingStrategy profileName, String traceName,
                                                                                                                  List<Integer> nodeIds, boolean forceExpand, int maxDepth, ProfileViewType profileViewType) {
      Future<Pair<AggregatedSamplesPerTraceCtx, T>> treeViewPair = profileStoreAPI.getProfileView(profileName, traceName, profileViewType);

      treeViewPair.setHandler(ar -> {
        if(ar.failed()) {
          setResponse(ar, routingContext);
        }
        else {
          AggregatedSamplesPerTraceCtx samplesPerTraceCtx = ar.result().first;
          T treeView = ar.result().second;
          List<Integer> originIds = nodeIds;
          if(originIds == null || originIds.isEmpty()) {
            originIds = treeView.getRoots();
          }

          List<IndexedTreeNode<AggregatedProfileModel.FrameNode>> subTree = treeView.getSubTrees(originIds, maxDepth, forceExpand);
          Map<Integer, String> methodLookup = new HashMap<>();

          subTree.forEach(e -> e.visit((i, node) -> methodLookup.put(node.getData().getMethodId(), samplesPerTraceCtx.getMethodLookup().get(node.getData().getMethodId()))));

          setResponse(Future.succeededFuture(new TreeViewResponse<>(subTree, methodLookup)), routingContext, true);
        }
      });
    }

    private void proxyPutPostPolicyToBackend(RoutingContext routingContext) {
        String payloadVersionedPolicyDetailsJsonString = routingContext.getBodyAsString("utf-8");
        try {
            PolicyDTO.VersionedPolicyDetails.Builder payloadVersionedPolicyDetailsBuilder = PolicyDTO.VersionedPolicyDetails.newBuilder();
            JsonFormat.parser().merge(payloadVersionedPolicyDetailsJsonString, payloadVersionedPolicyDetailsBuilder);
            PolicyDTO.VersionedPolicyDetails versionedPolicyDetails = payloadVersionedPolicyDetailsBuilder.build();
            makeRequestToBackend(routingContext.request().method(), routingContext.normalisedPath(), ProtoUtil.buildBufferFromProto(versionedPolicyDetails), false)
                    .setHandler(ar -> proxyBufferedPolicyResponseFromBackend(routingContext, ar));
        } catch (Exception ex) {
            UserapiHttpFailure httpFailure = UserapiHttpFailure.failure(ex);
            UserapiHttpHelper.handleFailure(routingContext, httpFailure);
        }
    }

    private void proxyGetPolicyToBackend(RoutingContext routingContext) {
        try {
            makeRequestToBackend(routingContext.request().method(), routingContext.normalisedPath(), null, false)
                    .setHandler(ar -> proxyBufferedPolicyResponseFromBackend(routingContext, ar));
        } catch (Exception ex) {
            UserapiHttpFailure httpFailure = UserapiHttpFailure.failure(ex);
            UserapiHttpHelper.handleFailure(routingContext, httpFailure);
        }
    }

    private void proxyListAPIToBackend(RoutingContext routingContext) {
        final String path = routingContext.normalisedPath().substring((META_PREFIX + POLICIES_PREFIX).length()) + ((routingContext.request().query() != null)? "?" + routingContext.request().query(): "");
        try {
            makeRequestToBackend(routingContext.request().method(), path, routingContext.getBody(), false)
                    .setHandler(ar -> proxyResponseFromBackend(routingContext, ar));
        } catch (Exception ex) {
            UserapiHttpFailure httpFailure = UserapiHttpFailure.failure(ex);
            UserapiHttpHelper.handleFailure(routingContext, httpFailure);
        }
    }

    private Future<ProfHttpClient.ResponseWithStatusTuple> makeRequestToBackend(HttpMethod method, String path, Buffer payloadAsBuffer, boolean withRetry) {
        if (withRetry) {
            return httpClient.requestAsyncWithRetry(method, backendConfig.getIp(), backendConfig.getPort(), path, payloadAsBuffer);
        } else {
            return httpClient.requestAsync(method, backendConfig.getIp(), backendConfig.getPort(), path, payloadAsBuffer);
        }
    }

    private void proxyBufferedPolicyResponseFromBackend(RoutingContext context, AsyncResult<ProfHttpClient.ResponseWithStatusTuple> ar) {
        if (ar.succeeded()) {
            context.response().setStatusCode(ar.result().getStatusCode());
            if (ar.result().getStatusCode() == 200 || ar.result().getStatusCode() == 201) {
                try {
                    PolicyDTO.VersionedPolicyDetails responseVersionedPolicyDetails = ProtoUtil.buildProtoFromBuffer(PolicyDTO.VersionedPolicyDetails.parser(), ar.result().getResponse());
                    String jsonStr = JsonFormat.printer().print(responseVersionedPolicyDetails);
                    context.response().end(jsonStr);
                } catch (InvalidProtocolBufferException e) {
                    UserapiHttpFailure httpFailure = UserapiHttpFailure.failure(e);
                    UserapiHttpHelper.handleFailure(context, httpFailure);
                }
            } else {
                context.response().end(ar.result().getResponse());
            }
        } else {
            UserapiHttpFailure httpFailure = UserapiHttpFailure.failure(ar.cause());
            UserapiHttpHelper.handleFailure(context, httpFailure);
        }
    }

    private void proxyResponseFromBackend(RoutingContext context, AsyncResult<ProfHttpClient.ResponseWithStatusTuple> ar) {
        if (ar.succeeded()) {
            context.response().setStatusCode(ar.result().getStatusCode());
            context.response().end(ar.result().getResponse());
        } else {
            UserapiHttpFailure httpFailure = UserapiHttpFailure.failure(ar.cause());
            UserapiHttpHelper.handleFailure(context, httpFailure);
        }
    }

    private void handleGetHealth(RoutingContext routingContext) {
      routingContext.response().setStatusCode(200).end();
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
