package fk.prof.backend.http;

import com.codahale.metrics.Meter;
import com.codahale.metrics.MetricRegistry;
import com.codahale.metrics.SharedMetricRegistries;
import com.google.protobuf.AbstractMessage;
import com.google.protobuf.util.JsonFormat;
import fk.prof.backend.ConfigManager;
import fk.prof.backend.Configuration;
import fk.prof.backend.exception.HttpFailure;
import fk.prof.backend.model.association.BackendAssociationStore;
import fk.prof.backend.model.policy.PolicyStore;
import fk.prof.backend.util.ProtoUtil;
import fk.prof.backend.util.proto.PolicyEntitiesProtoUtil;
import fk.prof.backend.util.proto.RecorderProtoUtil;
import fk.prof.idl.*;
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

import java.io.IOException;

public class LeaderHttpVerticle extends AbstractVerticle {
  private final Configuration config;
  private final BackendAssociationStore backendAssociationStore;
  private final MetricRegistry metricRegistry = SharedMetricRegistries.getOrCreate(ConfigManager.METRIC_REGISTRY);
  private final PolicyStore policyStore;

  public LeaderHttpVerticle(Configuration config,
                            BackendAssociationStore backendAssociationStore,
                            PolicyStore policyStore) {
    this.config = config;
    this.backendAssociationStore = backendAssociationStore;
    this.policyStore = policyStore;
  }

  @Override
  public void start(Future<Void> fut) {
    Router router = setupRouting();
    vertx.createHttpServer(config.getLeaderHttpServerOpts())
        .requestHandler(router::accept)
        .listen(config.getLeaderHttpServerOpts().getPort(),
            http -> completeStartup(http, fut));
  }

  private Router setupRouting() {
    Router router = Router.router(vertx);
    router.route().handler(LoggerHandler.create());

    HttpHelper.attachHandlersToRoute(router, HttpMethod.POST, ApiPathConstants.LEADER_POST_LOAD,
        BodyHandler.create().setBodyLimit(64), this::handlePostLoad);

    HttpHelper.attachHandlersToRoute(router, HttpMethod.POST, ApiPathConstants.LEADER_POST_ASSOCIATION,
        BodyHandler.create().setBodyLimit(1024 * 10), this::handlePostAssociation);

    HttpHelper.attachHandlersToRoute(router, HttpMethod.GET, ApiPathConstants.LEADER_GET_ASSOCIATIONS,
        this::handleGetAssociations);

    String apiPathForGetWork = ApiPathConstants.LEADER_GET_WORK + "/:appId/:clusterId/:procName";
    HttpHelper.attachHandlersToRoute(router, HttpMethod.GET, apiPathForGetWork,
        BodyHandler.create().setBodyLimit(1024 * 100), this::handleGetWork);

    HttpHelper.attachHandlersToRoute(router, HttpMethod.GET, ApiPathConstants.LEADER_GET_APPS, this::handleGetAppIds);
    HttpHelper.attachHandlersToRoute(router, HttpMethod.GET, ApiPathConstants.LEADER_GET_CLUSTERS_FOR_APP, this::handleGetClusterIds);
    HttpHelper.attachHandlersToRoute(router, HttpMethod.GET, ApiPathConstants.LEADER_GET_PROCS_FOR_APP_CLUSTER, this::handleGetProcNames);

    HttpHelper.attachHandlersToRoute(router, HttpMethod.GET, ApiPathConstants.LEADER_GET_POLICY_FOR_APP_CLUSTER_PROC, this::handleGetPolicy);
    HttpHelper.attachHandlersToRoute(router, HttpMethod.PUT, ApiPathConstants.LEADER_PUT_POLICY_FOR_APP_CLUSTER_PROC,
        BodyHandler.create().setBodyLimit(1024 * 10), this::handleUpdatePolicy);
    HttpHelper.attachHandlersToRoute(router, HttpMethod.POST, ApiPathConstants.LEADER_POST_POLICY_FOR_APP_CLUSTER_PROC,
        BodyHandler.create().setBodyLimit(1024 * 10), this::handleCreatePolicy);

    String apiPathForDeleteAssociation = ApiPathConstants.LEADER_ADMIN_DELETE_ASSOCIATION + "/:appId/:clusterId/:procName";
    HttpHelper.attachHandlersToRoute(router, HttpMethod.DELETE, apiPathForDeleteAssociation,
        BodyHandler.create().setBodyLimit(1024 * 10), this::handleDeleteAssociation);

    return router;
  }

  private void handleGetAppIds(RoutingContext context) {
    try {
      final String prefix = context.request().getParam("prefix");
      context.response().end(Json.encode(policyStore.getAppIds(prefix)));
    } catch (Exception ex) {
      HttpFailure httpFailure = HttpFailure.failure(ex);
      HttpHelper.handleFailure(context, httpFailure);
    }
  }

  private void handleGetClusterIds(RoutingContext context) {
    try {
      final String appId = context.request().getParam("appId");
      final String prefix = context.request().getParam("prefix");
      context.response().end(Json.encode(policyStore.getClusterIds(appId, prefix)));
    } catch (Exception ex) {
      HttpFailure httpFailure = HttpFailure.failure(ex);
      HttpHelper.handleFailure(context, httpFailure);
    }
  }

  private void handleGetProcNames(RoutingContext context) {
    try {
      final String appId = context.request().getParam("appId");
      final String clusterId = context.request().getParam("clusterId");
      final String prefix = context.request().getParam("prefix");
      context.response().end(Json.encode(policyStore.getProcNames(appId, clusterId, prefix)));
    } catch (Exception ex) {
      HttpFailure httpFailure = HttpFailure.failure(ex);
      HttpHelper.handleFailure(context, httpFailure);
    }
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
      Backend.LoadReportRequest payload = ProtoUtil.buildProtoFromBuffer(Backend.LoadReportRequest.parser(), context.getBody());
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
      Entities.ProcessGroup processGroup = RecorderProtoUtil.mapRecorderInfoToProcessGroup(recorderInfo);

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
      Entities.ProcessGroup processGroup = Entities.ProcessGroup.newBuilder().setAppId(appId).setCluster(clusterId).setProcName(procName).build();

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
        Profile.RecordingPolicy recordingPolicy = PolicyEntitiesProtoUtil.translateToRecordingPolicy(policyStore.getVersionedPolicy(processGroup));
        if (recordingPolicy == null) {
          mtrPolicyMiss.mark();
          context.response().setStatusCode(400);
          context.response().end("Policy not found for process group " + RecorderProtoUtil.processGroupCompactRepr(processGroup));
        } else {
          context.response().end(ProtoUtil.buildBufferFromProto(recordingPolicy));
        }
      }
    } catch (Exception ex) {
      HttpFailure httpFailure = HttpFailure.failure(ex);
      HttpHelper.handleFailure(context, httpFailure);
    }
  }

  private void handleGetPolicy(RoutingContext context) {
    try {
      Entities.ProcessGroup pg = parseProcessGroup(context);
      PolicyEntities.VersionedPolicyDetails versionedPolicyDetails = policyStore.getVersionedPolicy(pg);
      if (versionedPolicyDetails == null) {
        throw new HttpFailure("Policy not found for process group " + RecorderProtoUtil.processGroupCompactRepr(pg), 404);
      } else {
        context.response().end(ProtoUtil.buildBufferFromProto(versionedPolicyDetails));
      }
    } catch (Exception ex) {
      HttpFailure httpFailure = HttpFailure.failure(ex);
      HttpHelper.handleFailure(context, httpFailure);
    }
  }

  private void handleCreatePolicy(RoutingContext context) {
    try {
      Entities.ProcessGroup pg = parseProcessGroup(context);
      PolicyEntities.VersionedPolicyDetails versionedPolicyDetails = parseVersionedPolicyDetailsFromPayload(context);
      policyStore.createVersionedPolicy(pg, versionedPolicyDetails).setHandler(ar -> setResponse(ar, context, 201));
    } catch (Exception ex) {
      HttpFailure httpFailure = HttpFailure.failure(ex);
      HttpHelper.handleFailure(context, httpFailure);
    }
  }

  private void handleUpdatePolicy(RoutingContext context) {
    try {
      Entities.ProcessGroup pg = parseProcessGroup(context);
      PolicyEntities.VersionedPolicyDetails versionedPolicyDetails = parseVersionedPolicyDetailsFromPayload(context);
      policyStore.updateVersionedPolicy(pg, versionedPolicyDetails).setHandler(ar -> setResponse(ar, context, 200));
    } catch (Exception ex) {
      HttpFailure httpFailure = HttpFailure.failure(ex);
      HttpHelper.handleFailure(context, httpFailure);
    }
  }

  private void setResponse(AsyncResult<? extends AbstractMessage> ar, RoutingContext context, int statusCode){
    if (ar.succeeded()) {
      try {
        context.response().setStatusCode(statusCode).end(ProtoUtil.buildBufferFromProto(ar.result()));
      } catch (IOException ex) {
        HttpFailure httpFailure = HttpFailure.failure(ex);
        HttpHelper.handleFailure(context, httpFailure);
      }
    } else {
      HttpFailure httpFailure = HttpFailure.failure(ar.cause());
      HttpHelper.handleFailure(context, httpFailure);
    }
  }

  private Entities.ProcessGroup parseProcessGroup(RoutingContext context) {
    final String appId = context.request().getParam("appId");
    final String clusterId = context.request().getParam("clusterId");
    final String procName = context.request().getParam("procName");

    return Entities.ProcessGroup.newBuilder().setAppId(appId).setCluster(clusterId).setProcName(procName).build();
  }

  private PolicyEntities.VersionedPolicyDetails parseVersionedPolicyDetailsFromPayload(RoutingContext context) throws Exception {
    PolicyEntities.VersionedPolicyDetails versionedPolicyDetails = ProtoUtil.buildProtoFromBuffer(PolicyEntities.VersionedPolicyDetails.parser(), context.getBody());
    PolicyEntitiesProtoUtil.validatePolicyValues(versionedPolicyDetails);
    return versionedPolicyDetails;
  }

  private void handleGetAssociations(RoutingContext context) {
    try {
      context.response().end(ProtoUtil.buildBufferFromProto(backendAssociationStore.getAssociations()));
    } catch (Exception ex) {
      HttpFailure httpFailure = HttpFailure.failure(ex);
      HttpHelper.handleFailure(context, httpFailure);
    }
  }

  private void handleDeleteAssociation(RoutingContext context) {
    try {
      String appId = context.request().getParam("appId");
      String clusterId = context.request().getParam("clusterId");
      String procName = context.request().getParam("procName");
      Entities.ProcessGroup processGroup = Entities.ProcessGroup.newBuilder().setAppId(appId).setCluster(clusterId).setProcName(procName).build();

      Recorder.AssignedBackend assignedBackend = backendAssociationStore.removeAssociation(processGroup);
      if(assignedBackend != null) {
        context.response().putHeader("Content-Type", "application/json");
        context.response().end(JsonFormat.printer().print(assignedBackend));
      } else {
        context.response().end();
      }
    } catch (Exception ex) {
      HttpFailure httpFailure = HttpFailure.failure(ex);
      HttpHelper.handleFailure(context, httpFailure);
    }
  }
}
