package fk.prof.userapi.http;

import fk.prof.userapi.exception.UserapiHttpFailure;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.vertx.core.Handler;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.HttpServerOptions;
import io.vertx.core.json.JsonObject;
import io.vertx.core.logging.Logger;
import io.vertx.core.logging.LoggerFactory;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;

//TODO : Duplicate from backend, extract out in a common module
public class UserapiHttpHelper {
  private static Logger logger = LoggerFactory.getLogger(UserapiHttpHelper.class);

  public static void handleFailure(RoutingContext context, UserapiHttpFailure exception) {
    long currentTimeMillis = System.currentTimeMillis();
    String requestPath = context.normalisedPath();

    final JsonObject error = new JsonObject()
        .put("timestamp", currentTimeMillis)
        .put("status", exception.getStatusCode())
        .put("error", HttpResponseStatus.valueOf(exception.getStatusCode()).reasonPhrase())
        .put("path", requestPath);

    if (exception.getMessage() != null) {
      error.put("message", exception.getMessage());
    }
    logger.error("Http error path={}, time={}, {}", exception, requestPath, currentTimeMillis);

    if (!context.response().ended()) {
      context.response().setStatusCode(exception.getStatusCode());
      context.response().end(error.encode());
    }
  }

  public static HttpServerOptions getHttpServerOptions(JsonObject httpServerConfig) {
    HttpServerOptions serverOptions = new HttpServerOptions();
    serverOptions
        .setCompressionSupported(true)
        .setIdleTimeout(httpServerConfig.getInteger("idle.timeout.secs", 120));
    return serverOptions;
  }

  @SafeVarargs
  public static void attachHandlersToRoute(Router router, HttpMethod method, String route, Handler<RoutingContext>... requestHandlers) {
    for(Handler<RoutingContext> handler: requestHandlers) {
      router.route(method, route).handler(handler);
    }
  }
}
