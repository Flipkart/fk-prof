package fk.prof.backend.worker;

import com.codahale.metrics.Counter;
import com.codahale.metrics.Meter;
import com.codahale.metrics.MetricRegistry;
import com.codahale.metrics.SharedMetricRegistries;
import fk.prof.aggregation.model.AggregationWindowStorage;
import fk.prof.aggregation.model.FinalizedAggregationWindow;
import fk.prof.backend.ConfigManager;
import fk.prof.backend.http.ApiPathConstants;
import fk.prof.backend.http.ProfHttpClient;
import fk.prof.backend.model.aggregation.ActiveAggregationWindows;
import fk.prof.backend.model.assignment.AggregationWindowPlannerStore;
import fk.prof.backend.model.assignment.AssociatedProcessGroups;
import fk.prof.backend.model.election.LeaderReadContext;
import fk.prof.backend.model.slot.WorkSlotPool;
import fk.prof.backend.proto.BackendDTO;
import fk.prof.backend.util.ProtoUtil;
import fk.prof.backend.util.URLUtil;
import fk.prof.backend.util.proto.RecorderProtoUtil;
import io.vertx.core.AbstractVerticle;
import io.vertx.core.Future;
import io.vertx.core.WorkerExecutor;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.json.JsonObject;
import io.vertx.core.logging.Logger;
import io.vertx.core.logging.LoggerFactory;
import recording.Recorder;

import java.io.UnsupportedEncodingException;
import java.util.HashMap;
import java.util.Map;

public class BackendDaemon extends AbstractVerticle {
  private static Logger logger = LoggerFactory.getLogger(BackendDaemon.class);

  private final ConfigManager configManager;
  private final LeaderReadContext leaderReadContext;
  private final AssociatedProcessGroups associatedProcessGroups;
  private final WorkSlotPool workSlotPool;
  private final ActiveAggregationWindows activeAggregationWindows;
  private final AggregationWindowStorage aggregationWindowStorage;
  private final String ipAddress;
  private final int backendHttpPort;

  private WorkerExecutor serializationWorkerExecutor;
  private AggregationWindowPlannerStore aggregationWindowPlannerStore;
  private ProfHttpClient httpClient;
  private int loadTickCounter = 0;

  private final MetricRegistry metricRegistry = SharedMetricRegistries.getOrCreate(ConfigManager.METRIC_REGISTRY);
  private final Meter mtrLoadReportSuccess = metricRegistry.meter(MetricRegistry.name(BackendDaemon.class, "load.report", "success"));
  private final Meter mtrLoadReportFailure = metricRegistry.meter(MetricRegistry.name(BackendDaemon.class, "load.report", "fail"));
  private final Counter ctrLeaderUnknownReq = metricRegistry.counter(MetricRegistry.name(BackendDaemon.class, "ldr.unknown", "req"));

  public BackendDaemon(ConfigManager configManager,
                       LeaderReadContext leaderReadContext,
                       AssociatedProcessGroups associatedProcessGroups,
                       ActiveAggregationWindows activeAggregationWindows,
                       WorkSlotPool workSlotPool,
                       AggregationWindowStorage aggregationWindowStorage) {
    this.configManager = configManager;
    this.ipAddress = configManager.getIPAddress();
    this.backendHttpPort = configManager.getBackendHttpPort();

    this.leaderReadContext = leaderReadContext;
    this.associatedProcessGroups = associatedProcessGroups;
    this.activeAggregationWindows = activeAggregationWindows;
    this.workSlotPool = workSlotPool;
    this.aggregationWindowStorage = aggregationWindowStorage;
  }

  @Override
  public void start() {
    httpClient = buildHttpClient();
    aggregationWindowPlannerStore = buildAggregationWindowPlannerStore();

    JsonObject poolConfig = configManager.getSerializationWorkerPoolConfig();
    serializationWorkerExecutor = vertx.createSharedWorkerExecutor("aggregation.window.serialization.threadpool",
            poolConfig.getInteger("size"), poolConfig.getInteger("timeout.secs") * 1000);
    postLoadToLeader();
  }

  private ProfHttpClient buildHttpClient() {
    JsonObject httpClientConfig = configManager.getHttpClientConfig();
    return ProfHttpClient.newBuilder().setConfig(httpClientConfig).build(vertx);
  }

  private AggregationWindowPlannerStore buildAggregationWindowPlannerStore() {
    return new AggregationWindowPlannerStore(
        vertx,
        configManager.getBackendId(),
        config().getInteger("aggregation.window.duration.secs", 1800),
        config().getInteger("aggregation.window.end.tolerance.secs", 120),
        config().getInteger("policy.refresh.offset.secs", 300),
        config().getInteger("scheduling.buffer.secs", 30),
        config().getInteger("work.assignment.max.delay.secs", 120),
        workSlotPool,
        activeAggregationWindows,
        this::getWorkFromLeader,
        this::serializeAndPersistAggregationWindow);
  }

  private void postLoadToLeader() {
    BackendDTO.LeaderDetail leaderDetail;
    if((leaderDetail = leaderReadContext.getLeader()) != null) {

      //TODO: load = 0.5 hard-coded right now. Replace this with dynamic load computation in future
      float load = 0.5f;

      try {
        httpClient.requestAsync(
            HttpMethod.POST,
            leaderDetail.getHost(),
            leaderDetail.getPort(),
            ApiPathConstants.LEADER_POST_LOAD,
            ProtoUtil.buildBufferFromProto(
                BackendDTO.LoadReportRequest.newBuilder()
                    .setIp(ipAddress)
                    .setPort(backendHttpPort)
                    .setLoad(load)
                    .setCurrTick(loadTickCounter)
                    .build()))
            .setHandler(ar -> {
              try {
                if(ar.failed()) {
                  mtrLoadReportFailure.mark();
                  logger.error("Error when reporting load to leader", ar.cause());
                } else if (ar.result().getStatusCode() != 200) {
                  mtrLoadReportFailure.mark();
                  logger.error("Non OK status returned by leader when reporting load, status=" + ar.result().getStatusCode());
                } else {
                  try {
                    loadTickCounter++;
                    Recorder.ProcessGroups assignedProcessGroups = ProtoUtil.buildProtoFromBuffer(Recorder.ProcessGroups.parser(), ar.result().getResponse());
                    associatedProcessGroups.updateProcessGroupAssociations(assignedProcessGroups, (processGroupDetail, processGroupAssociationResult) -> {
                      switch (processGroupAssociationResult) {
                        case ADDED:
                          this.aggregationWindowPlannerStore.associateAggregationWindowPlannerIfAbsent(processGroupDetail);
                          break;
                        case REMOVED:
                          this.aggregationWindowPlannerStore.deAssociateAggregationWindowPlanner(processGroupDetail.getProcessGroup());
                          break;
                      }
                    });
                    mtrLoadReportSuccess.mark();
                  } catch (Exception ex) {
                    mtrLoadReportFailure.mark();
                    logger.error("Error parsing response returned by leader when reporting load", ex);
                  }
                }
              } catch (Exception ex) {
                logger.error("Unexpected error when reporting load to leader", ex);
              } finally {
                setupTimerForReportingLoad();
              }
            });
      } catch (Exception ex) {
        mtrLoadReportFailure.mark();
        logger.error("Error building load request body", ex);
        setupTimerForReportingLoad();
      }
    } else {
      mtrLoadReportFailure.mark();
      ctrLeaderUnknownReq.inc();
      logger.debug("Not reporting load because leader is unknown");
      setupTimerForReportingLoad();
    }
  }

  private void setupTimerForReportingLoad() {
    vertx.setTimer(configManager.getLoadReportIntervalInSeconds() * 1000, timerId -> postLoadToLeader());
  }

  private Future<BackendDTO.RecordingPolicy> getWorkFromLeader(Recorder.ProcessGroup processGroup, Meter mtrSuccess, Meter mtrFailure) {
    Future<BackendDTO.RecordingPolicy> result = Future.future();
    BackendDTO.LeaderDetail leaderDetail;
    if((leaderDetail = leaderReadContext.getLeader()) != null) {
      try {
        String requestPath = URLUtil.buildPathWithRequestParams(ApiPathConstants.LEADER_GET_WORK,
            processGroup.getAppId(), processGroup.getCluster(), processGroup.getProcName());
        Map<String, String> queryParams = new HashMap<>();
        queryParams.put("ip", configManager.getIPAddress());
        queryParams.put("port", Integer.toString(configManager.getBackendHttpPort()));
        requestPath = URLUtil.buildPathWithQueryParams(requestPath, queryParams);

        //TODO: Support configuring max retries at request level because this request should definitely be retried on failure while other requests like posting load to backend need not be
        httpClient.requestAsync(
            HttpMethod.GET,
            leaderDetail.getHost(),
            leaderDetail.getPort(),
            requestPath,
            null).setHandler(ar -> {
              if (ar.failed()) {
                mtrFailure.mark();
                result.fail("Error when requesting work from leader for process group="
                    + RecorderProtoUtil.processGroupCompactRepr(processGroup)
                    + ", message=" + ar.cause());
                return;
              }
              if (ar.result().getStatusCode() != 200) {
                mtrFailure.mark();
                result.fail("Non-OK status code when requesting work from leader for process group="
                    + RecorderProtoUtil.processGroupCompactRepr(processGroup)
                    + ", status=" + ar.result().getStatusCode());
                return;
              }
              try {
                BackendDTO.RecordingPolicy recordingPolicy = ProtoUtil.buildProtoFromBuffer(BackendDTO.RecordingPolicy.parser(), ar.result().getResponse());
                result.complete(recordingPolicy);
                mtrSuccess.mark();
              } catch (Exception ex) {
                mtrFailure.mark();
                result.fail("Error parsing work response returned by leader for process group=" + RecorderProtoUtil.processGroupCompactRepr(processGroup));
              }
            });
      } catch (UnsupportedEncodingException ex) {
        mtrFailure.mark();
        result.fail("Error building url for process_group=" + RecorderProtoUtil.processGroupCompactRepr(processGroup));
      }
    } else {
      mtrFailure.mark();
      ctrLeaderUnknownReq.inc();
      result.fail("Not reporting load because leader is unknown");
    }

    return result;
  }

  private void serializeAndPersistAggregationWindow(FinalizedAggregationWindow finalizedAggregationWindow) {
    serializationWorkerExecutor.executeBlocking(future -> {
      try {
        aggregationWindowStorage.store(finalizedAggregationWindow);
        future.complete();
      } catch (Exception ex) {
        future.fail(ex);
      }
    }, result -> {
      if(result.succeeded()) {
        logger.info("Successfully initiated save of profile for aggregation_window: " + finalizedAggregationWindow);
      } else {
        logger.error("Error while saving profile for aggregation_window: " + finalizedAggregationWindow, result.cause());
      }
    });
  }
}
