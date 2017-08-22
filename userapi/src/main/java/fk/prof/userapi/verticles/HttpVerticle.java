package fk.prof.userapi.verticles;

import com.google.common.base.MoreObjects;
import fk.prof.aggregation.AggregatedProfileNamingStrategy;
import fk.prof.aggregation.proto.AggregatedProfileModel.FrameNode;
import fk.prof.aggregation.proto.AggregatedProfileModel.WorkType;
import fk.prof.userapi.Configuration;
import fk.prof.userapi.Pair;
import fk.prof.userapi.api.ProfileStoreAPI;
import fk.prof.userapi.http.UserapiApiPathConstants;
import fk.prof.userapi.model.AggregatedSamplesPerTraceCtx;
import fk.prof.userapi.model.AggregationWindowSummary;
import fk.prof.userapi.model.StacktraceTreeViewType;
import fk.prof.userapi.model.tree.CallTreeView;
import fk.prof.userapi.model.tree.CalleesTreeView;
import fk.prof.userapi.model.tree.IndexedTreeNode;
import fk.prof.userapi.model.tree.TreeViewResponse.CpuSampleCalleesTreeViewResponse;
import fk.prof.userapi.model.tree.TreeViewResponse.CpuSampleCallersTreeViewResponse;
import io.vertx.core.AbstractVerticle;
import io.vertx.core.CompositeFuture;
import io.vertx.core.Future;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.impl.CompositeFutureImpl;
import io.vertx.core.logging.Logger;
import io.vertx.core.logging.LoggerFactory;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import io.vertx.ext.web.handler.BodyHandler;
import io.vertx.ext.web.handler.LoggerHandler;
import io.vertx.ext.web.handler.TimeoutHandler;

import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

import static fk.prof.userapi.util.HttpRequestUtil.getParam;
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
    private ProfileStoreAPI profileStoreAPI;
    private Integer maxDepthForTreeExpand;

    public HttpVerticle(Configuration config, ProfileStoreAPI profileStoreAPI) {
        this.httpConfig = config.getHttpConfig();
        this.profileStoreAPI = profileStoreAPI;
        this.baseDir = config.getProfilesBaseDir();
        this.maxListProfilesDurationInSecs = config.getMaxListProfilesDurationInDays()*24*60*60;
        this.maxDepthForTreeExpand = config.getMaxDepthExpansion();
    }

    private Router configureRouter() {
        Router router = Router.router(vertx);
        router.route().handler(TimeoutHandler.create(httpConfig.getRequestTimeout()));
        router.route().handler(LoggerHandler.create());

        router.get(UserapiApiPathConstants.APPS).handler(this::getAppIds);
        router.get(UserapiApiPathConstants.CLUSTER_GIVEN_APPID).handler(this::getClusterIds);
        router.get(UserapiApiPathConstants.PROC_GIVEN_APPID_CLUSTERID).handler(this::getProcId);
        router.get(UserapiApiPathConstants.PROFILES_GIVEN_APPID_CLUSTERID_PROCID).handler(this::getProfiles);

        router.post(UserapiApiPathConstants.CALLERS_VIEW_FOR_CPU_SAMPLING).handler(BodyHandler.create().setBodyLimit(1024 * 1024));
        router.post(UserapiApiPathConstants.CALLERS_VIEW_FOR_CPU_SAMPLING).handler(this::getCallersViewForCpuSampling);

        router.post(UserapiApiPathConstants.CALLEES_VIEW_FOR_CPU_SAMPLING).handler(BodyHandler.create().setBodyLimit(1024 * 1024));
        router.post(UserapiApiPathConstants.CALLEES_VIEW_FOR_CPU_SAMPLING).handler(this::getCalleesViewForCpuSampling);

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
            if(result.failed()) {
                setResponse(result, routingContext);
                return;
            }

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

    private void getCallersViewForCpuSampling(RoutingContext routingContext) {
        getViewForCpuSampling(routingContext, StacktraceTreeViewType.CALLERS);
    }

    private void getCalleesViewForCpuSampling(RoutingContext routingContext) {
        getViewForCpuSampling(routingContext, StacktraceTreeViewType.CALLEES);
    }

    private void getViewForCpuSampling(RoutingContext routingContext, StacktraceTreeViewType viewType) {
        HttpServerRequest req = routingContext.request();

        String appId, clusterId, procId, traceName;
        Boolean autoExpand;
        Integer maxDepth, duration;
        ZonedDateTime startTime;
        List<Integer> nodeIds;
        try {
            appId = getParam(req, "appId");
            clusterId = getParam(req, "clusterId");
            procId = getParam(req, "procId");
            traceName = getParam(req, "traceName");
            startTime = getParam(req, "start", ZonedDateTime.class);
            duration = getParam(req, "duration", Integer.class);
            autoExpand = MoreObjects.firstNonNull(getParam(req, "autoExpand", Boolean.class, false), false);
            maxDepth = Math.min(maxDepthForTreeExpand, MoreObjects.firstNonNull(getParam(req, "maxDepth", Integer.class, false), maxDepthForTreeExpand));
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

        AggregatedProfileNamingStrategy profileName = new AggregatedProfileNamingStrategy(baseDir, VERSION, appId, clusterId, procId, startTime, duration, WorkType.cpu_sample_work);

        if(StacktraceTreeViewType.CALLERS.equals(viewType)) {
            getCallersViewForCpuSampling(routingContext, profileName, traceName, nodeIds, autoExpand, maxDepth);
        }
        else {
            getCalleesViewForCpuSampling(routingContext, profileName, traceName, nodeIds, autoExpand, maxDepth);
        }
    }

    private void getCallersViewForCpuSampling(RoutingContext routingContext, AggregatedProfileNamingStrategy profileName, String traceName,
                                              List<Integer> nodeIds, boolean autoExpand, int maxDepth) {
        Future<Pair<AggregatedSamplesPerTraceCtx,CallTreeView>> callTreeView = profileStoreAPI.getCpuSamplingCallersTreeView(profileName, traceName);

        callTreeView.setHandler(ar -> {
            if(ar.failed()) {
                setResponse(ar, routingContext);
            }
            else {
                AggregatedSamplesPerTraceCtx samplesPerTraceCtx = ar.result().first;
                CallTreeView treeView = ar.result().second;

                List<Integer> originIds = nodeIds;
                if(originIds == null || originIds.isEmpty()) {
                    originIds = treeView.getRootNodes().stream().map(e -> e.getIdx()).collect(Collectors.toList());
                }

                List<IndexedTreeNode<FrameNode>> subTree = treeView.getSubTree(originIds, maxDepth, autoExpand);
                Map<Integer, String> methodLookup = new HashMap<>();

                subTree.forEach(e -> e.visit((i,data) -> methodLookup.put(data.getMethodId(), samplesPerTraceCtx.getMethodLookup().get(data.getMethodId()))));

                setResponse(Future.succeededFuture(new CpuSampleCallersTreeViewResponse(subTree, methodLookup)), routingContext, true);
            }
        });
    }

    private void getCalleesViewForCpuSampling(RoutingContext routingContext, AggregatedProfileNamingStrategy profileName, String traceName,
                                              List<Integer> nodeIds, boolean autoExpand, int maxDepth) {
        Future<Pair<AggregatedSamplesPerTraceCtx,CalleesTreeView>> calleesTreeView = profileStoreAPI.getCpuSamplingCalleesTreeView(profileName, traceName);

        calleesTreeView.setHandler(ar -> {
            if(ar.failed()) {
                setResponse(ar, routingContext);
            }
            else {
                AggregatedSamplesPerTraceCtx samplesPerTraceCtx = ar.result().first;
                CalleesTreeView treeView = ar.result().second;

                List<Integer> originIds = nodeIds;
                if(originIds == null || originIds.isEmpty()) {
                    originIds = treeView.getRootNodes().stream().map(e -> e.getIdx()).collect(Collectors.toList());
                }

                List<IndexedTreeNode<FrameNode>> subTree = treeView.getSubTree(originIds, maxDepth, autoExpand);
                Map<Integer, String> methodLookup = new HashMap<>();

                subTree.forEach(e -> e.visit((i,data) -> methodLookup.put(data.getMethodId(), samplesPerTraceCtx.getMethodLookup().get(data.getMethodId()))));

                setResponse(Future.succeededFuture(new CpuSampleCalleesTreeViewResponse(subTree, methodLookup)), routingContext, true);
            }
        });
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
