package fk.prof.backend.http;

import com.codahale.metrics.Meter;
import com.codahale.metrics.MetricRegistry;
import com.codahale.metrics.SharedMetricRegistries;
import fk.prof.backend.ConfigManager;
import fk.prof.backend.exception.HttpFailure;
import fk.prof.backend.model.association.BackendAssociationStore;
import fk.prof.backend.model.policy.PolicyStore;
import fk.prof.backend.model.policy.impl.PolicyApiResponse;
import fk.prof.backend.model.policy.json.PolicyProtobufSerializers;
import fk.prof.backend.proto.BackendDTO;
import fk.prof.backend.util.ProtoUtil;
import fk.prof.backend.util.proto.RecorderProtoUtil;
import fk.prof.metrics.BackendTag;
import fk.prof.metrics.MetricName;
import fk.prof.metrics.ProcessGroupTag;
import io.vertx.core.AbstractVerticle;
import io.vertx.core.AsyncResult;
import io.vertx.core.Future;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.HttpServer;
import io.vertx.core.json.Json;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import io.vertx.ext.web.handler.BodyHandler;
import io.vertx.ext.web.handler.LoggerHandler;
import policy.PolicyDetails;
import recording.Recorder;

import java.io.IOException;

public class LeaderHttpVerticle extends AbstractVerticle {
  private final ConfigManager configManager;
  private final BackendAssociationStore backendAssociationStore;
  private final PolicyStore policyStore;

  private final MetricRegistry metricRegistry = SharedMetricRegistries.getOrCreate(ConfigManager.METRIC_REGISTRY);

  public LeaderHttpVerticle(ConfigManager configManager,
                            BackendAssociationStore backendAssociationStore,
                            PolicyStore policyStore) {
    this.configManager = configManager;
    this.backendAssociationStore = backendAssociationStore;
    this.policyStore = policyStore;
  }

  @Override
  public void start(Future<Void> fut) {
    Router router = setupRouting();
    PolicyProtobufSerializers.registerSerializer(Json.mapper);
    PolicyProtobufSerializers.registerSerializer(Json.prettyMapper);
    vertx.createHttpServer(HttpHelper.getHttpServerOptions(configManager.getLeaderHttpServerConfig()))
        .requestHandler(router::accept)
        .listen(configManager.getLeaderHttpPort(),
            http -> completeStartup(http, fut));
  }

  private Router setupRouting() {
    Router router = Router.router(vertx);
    router.route().handler(LoggerHandler.create());

    HttpHelper.attachHandlersToRoute(router, HttpMethod.POST, ApiPathConstants.LEADER_POST_LOAD,
        BodyHandler.create().setBodyLimit(64), this::handlePostLoad);

    HttpHelper.attachHandlersToRoute(router, HttpMethod.POST, ApiPathConstants.LEADER_POST_ASSOCIATION,
        BodyHandler.create().setBodyLimit(1024 * 10), this::handlePostAssociation);

    String apiPathForGetWork = ApiPathConstants.LEADER_GET_WORK + "/:appId/:clusterId/:procName";
    HttpHelper.attachHandlersToRoute(router, HttpMethod.GET, apiPathForGetWork,
        BodyHandler.create().setBodyLimit(1024 * 100), this::handleGetWork);

    HttpHelper.attachHandlersToRoute(router, HttpMethod.GET, ApiPathConstants.LEADER_GET_POLICIES_GIVEN_APPID, this::handleGetPoliciesGivenAppId);
    HttpHelper.attachHandlersToRoute(router, HttpMethod.GET, ApiPathConstants.LEADER_GET_POLICIES_GIVEN_APPID_CLUSTERID, this::handleGetPoliciesGivenAppIdClusterId);
    HttpHelper.attachHandlersToRoute(router, HttpMethod.GET, ApiPathConstants.LEADER_GET_POLICIES_GIVEN_APPID_CLUSTERID_PROCESS, this::handleGetPolicyGivenAppIdClusterIdProcess);

    HttpHelper.attachHandlersToRoute(router, HttpMethod.PUT, ApiPathConstants.LEADER_PUT_POLICY_GIVEN_APPID_CLUSTERID_PROCESS, BodyHandler.create().setBodyLimit(1024), this::handlePutPolicy);
    HttpHelper.attachHandlersToRoute(router, HttpMethod.DELETE, ApiPathConstants.LEADER_DELETE_POLICY_GIVEN_APPID_CLUSTERID_PROCESS, BodyHandler.create().setBodyLimit(1024), this::handleDeletePolicy);

    return router;
  }

  private void completeStartup(AsyncResult<HttpServer> http, Future<Void> fut) {
    if (http.succeeded()) {
      fut.complete();
    } else {
      fut.fail(http.cause());
    }
  }

  private void handlePostLoad(RoutingContext context) {
    try {
      BackendDTO.LoadReportRequest payload = ProtoUtil.buildProtoFromBuffer(BackendDTO.LoadReportRequest.parser(), context.getBody());
      String backendStr = new BackendTag(payload.getIp(), payload.getPort()).toString();
      Meter mtrFailure = metricRegistry.meter(MetricRegistry.name(MetricName.Leader_LoadReport_Failure.get(), backendStr));
      Meter mtrSuccess = metricRegistry.meter(MetricRegistry.name(MetricName.Leader_LoadReport_Success.get(), backendStr));

      backendAssociationStore.reportBackendLoad(payload).setHandler(ar -> {
        mtrSuccess.mark();
        if(ar.succeeded()) {
          try {
            Buffer responseBuffer = ProtoUtil.buildBufferFromProto(ar.result());
            context.response().end(responseBuffer);
          } catch (IOException ex) {
            HttpFailure httpFailure = HttpFailure.failure(ar.cause());
            HttpHelper.handleFailure(context, httpFailure);
          }
        } else {
          mtrFailure.mark();
          HttpFailure httpFailure = HttpFailure.failure(ar.cause());
          HttpHelper.handleFailure(context, httpFailure);
        }
      });
    } catch (Exception ex) {
      HttpFailure httpFailure = HttpFailure.failure(ex);
      HttpHelper.handleFailure(context, httpFailure);
    }
  }

  private void handlePostAssociation(RoutingContext context) {
    try {
      Recorder.RecorderInfo recorderInfo = ProtoUtil.buildProtoFromBuffer(Recorder.RecorderInfo.parser(), context.getBody());
      Recorder.ProcessGroup processGroup = RecorderProtoUtil.mapRecorderInfoToProcessGroup(recorderInfo);

      String processGroupStr = new ProcessGroupTag(processGroup.getAppId(), processGroup.getCluster(), processGroup.getProcName()).toString();
      Meter mtrFailure = metricRegistry.meter(MetricRegistry.name(MetricName.Leader_Assoc_Failure.get(), processGroupStr));
      Meter mtrSuccess = metricRegistry.meter(MetricRegistry.name(MetricName.Leader_Assoc_Success.get(), processGroupStr));

      backendAssociationStore.associateAndGetBackend(processGroup).setHandler(ar -> {
        //TODO: Evaluate if this lambda can be extracted out as a static variable/function if this is repetitive across the codebase
        if(ar.succeeded()) {
          mtrSuccess.mark();
          try {
            context.response().end(ProtoUtil.buildBufferFromProto(ar.result()));
          } catch (Exception ex) {
            HttpFailure httpFailure = HttpFailure.failure(ex);
            HttpHelper.handleFailure(context, httpFailure);
          }
        } else {
          mtrFailure.mark();
          HttpFailure httpFailure = HttpFailure.failure(ar.cause());
          HttpHelper.handleFailure(context, httpFailure);
        }
      });
    } catch (Exception ex) {
      HttpFailure httpFailure = HttpFailure.failure(ex);
      HttpHelper.handleFailure(context, httpFailure);
    }
  }

  private void handleGetWork(RoutingContext context) {
    try {
      String appId = context.request().getParam("appId");
      String clusterId = context.request().getParam("clusterId");
      String procName = context.request().getParam("procName");
      Recorder.ProcessGroup processGroup = Recorder.ProcessGroup.newBuilder().setAppId(appId).setCluster(clusterId).setProcName(procName).build();

      String processGroupStr = new ProcessGroupTag(appId, clusterId, procName).toString();
      Meter mtrAssocMiss = metricRegistry.meter(MetricRegistry.name(MetricName.Leader_Work_Assoc_Miss.get(), processGroupStr));
      Meter mtrPolicyMiss = metricRegistry.meter(MetricRegistry.name(MetricName.Leader_Work_Policy_Miss.get(), processGroupStr));

      String backendIP = context.request().getParam("ip");
      int backendPort = Integer.valueOf(context.request().getParam("port"));
      Recorder.AssignedBackend callingBackend = Recorder.AssignedBackend.newBuilder().setHost(backendIP).setPort(backendPort).build();

      if(!callingBackend.equals(backendAssociationStore.getAssociatedBackend(processGroup))) {
        mtrAssocMiss.mark();
        context.response().setStatusCode(400);
        context.response().end("Calling backend=" + RecorderProtoUtil.assignedBackendCompactRepr(callingBackend) + " not assigned to process_group=" + RecorderProtoUtil.processGroupCompactRepr(processGroup));
      } else {
        BackendDTO.RecordingPolicy recordingPolicy = this.policyStore.getRecordingPolicy(processGroup);
        if (recordingPolicy == null) {
          mtrPolicyMiss.mark();
          context.response().setStatusCode(400);
          context.response().end("Policy not found for process_group" + RecorderProtoUtil.processGroupCompactRepr(processGroup));
        } else {
          context.response().end(ProtoUtil.buildBufferFromProto(recordingPolicy));
        }
      }
    } catch (Exception ex) {
      HttpFailure httpFailure = HttpFailure.failure(ex);
      HttpHelper.handleFailure(context, httpFailure);
    }
  }

  private void handleGetPoliciesGivenAppId(RoutingContext context) {
    final String appId = context.request().getParam("appId");
    String response = Json.encode(PolicyApiResponse.getNewInstance(policyStore.getUserPolicies(appId)));
    context.response().putHeader("content-type", "application/json").end(response);
  }

  private void handleGetPoliciesGivenAppIdClusterId(RoutingContext context) {
    final String appId = context.request().getParam("appId");
    final String clusterId = context.request().getParam("clusterId");
    String response = Json.encode(PolicyApiResponse.getNewInstance(policyStore.getUserPolicies(appId, clusterId)));
    context.response().putHeader("content-type", "application/json").end(response);
  }

  private void handleGetPolicyGivenAppIdClusterIdProcess(RoutingContext context) {
    final String appId = context.request().getParam("appId");
    final String clusterId = context.request().getParam("clusterId");
    final String process = context.request().getParam("process");
    String response = Json.encode(PolicyApiResponse.getNewInstance(policyStore.getUserPolicies(appId, clusterId, process)));
    context.response().putHeader("content-type", "application/json").end(response);
  }

  private void handlePutPolicy(RoutingContext context) {
    final String appId = context.request().getParam("appId");
    final String clusterId = context.request().getParam("clusterId");
    final String process = context.request().getParam("process");
    try {
      Recorder.ProcessGroup processGroup = Recorder.ProcessGroup.newBuilder().setAppId(appId).setCluster(clusterId).setProcName(process).build();
      PolicyDetails policyDetails = PolicyDetails.parseFrom(context.getBody().getBytes());
      policyStore.setUserPolicy(processGroup, policyDetails).whenComplete((aVoid, throwable) -> {
        if (throwable == null) {
          context.response().setStatusCode(200).end();
        } else {
          HttpFailure httpFailure = HttpFailure.failure(throwable);
          HttpHelper.handleFailure(context, httpFailure);
        }
      });
    } catch (Exception ex) {
      HttpFailure httpFailure = HttpFailure.failure(ex);
      HttpHelper.handleFailure(context, httpFailure);
    }
  }

  private void handleDeletePolicy(RoutingContext context) {
    final String appId = context.request().getParam("appId");
    final String clusterId = context.request().getParam("clusterId");
    final String process = context.request().getParam("process");
    try {
      Recorder.ProcessGroup processGroup = Recorder.ProcessGroup.newBuilder().setAppId(appId).setCluster(clusterId).setProcName(process).build();
      String admin = context.getBodyAsJson().getString("administrator");
      policyStore.removeUserPolicy(processGroup, admin).whenComplete((aVoid, throwable) -> {
        if (throwable == null) {
          context.response().setStatusCode(200).end();
        } else {
          HttpFailure httpFailure = HttpFailure.failure(throwable);
          HttpHelper.handleFailure(context, httpFailure);
        }
      });
    } catch (Exception ex) {
      HttpFailure httpFailure = HttpFailure.failure(ex);
      HttpHelper.handleFailure(context, httpFailure);
    }
  }
}
