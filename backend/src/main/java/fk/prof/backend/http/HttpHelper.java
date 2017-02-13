package fk.prof.backend.http;

import fk.prof.backend.exception.HttpFailure;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.vertx.core.http.HttpServerOptions;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.RoutingContext;

public class HttpHelper {
  public static void handleFailure(RoutingContext context, HttpFailure exception) {
    final JsonObject error = new JsonObject()
        .put("timestamp", System.nanoTime())
        .put("status", exception.getStatusCode())
        .put("error", HttpResponseStatus.valueOf(exception.getStatusCode()).reasonPhrase())
        .put("path", context.normalisedPath());

    if (exception.getMessage() != null) {
      error.put("message", exception.getMessage());
    }

    if (!context.response().ended()) {
      context.response().setStatusCode(exception.getStatusCode());
      context.response().end(error.encode());
    }
  }

  public static HttpServerOptions getHttpServerOptions(JsonObject httpServerConfig) {
    HttpServerOptions serverOptions = new HttpServerOptions();
    serverOptions
        .setCompressionSupported(true)
        .setTcpKeepAlive(true)
        .setIdleTimeout(httpServerConfig.getInteger("idle.timeout.secs"));
    return serverOptions;
  }


}
