package fk.prof.backend.http;

import fk.prof.backend.exception.HttpFailure;
import fk.prof.backend.model.association.BackendAssociationStore;
import fk.prof.backend.proto.BackendDTO;
import io.vertx.core.AbstractVerticle;
import io.vertx.core.AsyncResult;
import io.vertx.core.Future;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpServer;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import io.vertx.ext.web.handler.BodyHandler;
import io.vertx.ext.web.handler.LoggerHandler;
import recording.Recorder;

public class LeaderHttpVerticle extends AbstractVerticle {
  private BackendAssociationStore backendAssociationStore;
  private JsonObject leaderHttpServerConfig;

  public LeaderHttpVerticle(JsonObject leaderHttpServerConfig, BackendAssociationStore backendAssociationStore) {
    this.leaderHttpServerConfig = leaderHttpServerConfig;
    this.backendAssociationStore = backendAssociationStore;
  }

  @Override
  public void start(Future<Void> fut) {
    Router router = setupRouting();
    vertx.createHttpServer(HttpHelper.getHttpServerOptions(leaderHttpServerConfig))
        .requestHandler(router::accept)
        .listen(leaderHttpServerConfig.getInteger("port"),
            http -> completeStartup(http, fut));
  }

  private Router setupRouting() {
    Router router = Router.router(vertx);
    router.route().handler(LoggerHandler.create());

    router.post(ApiPathConstants.LEADER_POST_LOAD)
        .handler(BodyHandler.create().setBodyLimit(64));
    router.post(ApiPathConstants.LEADER_POST_LOAD)
        .handler(this::handlePostLoad);

    router.post(ApiPathConstants.LEADER_PUT_ASSOCIATION)
        .handler(BodyHandler.create().setBodyLimit(1024 * 10));
    router.post(ApiPathConstants.LEADER_PUT_ASSOCIATION)
        .handler(this::handlePutAssociation);

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
      BackendDTO.LoadReportRequest payload = BackendDTO.LoadReportRequest.parseFrom(context.getBody().getBytes());
      backendAssociationStore.reportBackendLoad(payload).setHandler(ar -> {
        if(ar.succeeded()) {
          Buffer responseBuffer = Buffer.buffer(ar.result().toByteArray());
          context.response().end(responseBuffer);
        } else {
          HttpFailure httpFailure = HttpFailure.failure(ar.cause());
          HttpHelper.handleFailure(context, httpFailure);
        }
      });
    } catch (Exception ex) {
      HttpFailure httpFailure = HttpFailure.failure(ex);
      HttpHelper.handleFailure(context, httpFailure);
    }
  }

  private void handlePutAssociation(RoutingContext context) {
    try {
      Recorder.ProcessGroup processGroup = Recorder.ProcessGroup.parseFrom(context.getBody().getBytes());
      backendAssociationStore.getAssociatedBackend(processGroup).setHandler(ar -> {
        if(ar.succeeded()) {
          context.response().end(ar.result());
        } else {
          HttpFailure httpFailure = HttpFailure.failure(ar.cause());
          HttpHelper.handleFailure(context, httpFailure);
        }
      });
    } catch (Exception ex) {
      HttpFailure httpFailure = HttpFailure.failure(ex);
      HttpHelper.handleFailure(context, httpFailure);
    }
  }

}
