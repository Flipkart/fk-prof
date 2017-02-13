package fk.prof.backend.http;

import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpClient;
import io.vertx.core.http.HttpClientOptions;
import io.vertx.core.http.HttpClientRequest;
import io.vertx.core.http.HttpMethod;

public class ConfigurableHttpClient {
  private final Vertx vertx;
  private final HttpClient httpClient;
  private final int maxAttempts;

  private ConfigurableHttpClient(Vertx vertx,
                                 int maxAttempts,
                                 boolean keepAlive,
                                 boolean useCompression,
                                 int connectTimeoutInMs,
                                 int idleTimeoutInSeconds) {
    this.vertx = vertx;
    HttpClientOptions httpClientOptions = new HttpClientOptions()
        .setKeepAlive(keepAlive)
        .setConnectTimeout(connectTimeoutInMs)
        .setIdleTimeout(idleTimeoutInSeconds)
        .setTryUseCompression(useCompression);
    this.httpClient = vertx.createHttpClient(httpClientOptions);
    this.maxAttempts = maxAttempts;
  }

  public Future<ResponseWithStatusTuple> requestAsyncWithRetry(HttpMethod httpMethod,
                                                               String host, int port, String path,
                                                               Buffer payload) {
    return executeRequestWithRetry(0, httpMethod, host, port, path, payload, true);
  }

  public Future<ResponseWithStatusTuple> requestAsync(HttpMethod httpMethod,
                                                      String host, int port, String path, Buffer payload) {
    return executeRequestWithRetry(0, httpMethod, host, port, path, payload, false);
  }

  private Future<ResponseWithStatusTuple> executeRequestWithRetry(
      int attemptsMade,
      HttpMethod httpMethod,
      String host, int port, String path,
      Buffer payload,
      boolean enableRetries) {

    Future<ResponseWithStatusTuple> result = Future.future();
    Future<ResponseWithStatusTuple> responseFut = executeRequestAsync(httpMethod, host, port, path, payload);
    responseFut.setHandler(asyncResult -> {
      if(asyncResult.succeeded()) {
        result.complete(asyncResult.result());
      } else {
        if(enableRetries && (attemptsMade + 1) < maxAttempts) {
          int delayInSeconds = (int)Math.pow(2, attemptsMade + 1) - 1;
          vertx.setTimer(delayInSeconds * 1000, timerId -> {
            executeRequestWithRetry(attemptsMade + 1, httpMethod, host, port, path, payload, enableRetries).setHandler(result.completer());
          });
        } else {
          result.fail(asyncResult.cause());
        }
      }
    });

    return result;
  }

  private Future<ResponseWithStatusTuple> executeRequestAsync(
      HttpMethod httpMethod,
      String host, int port, String path,
      Buffer payload) {
    Future<ResponseWithStatusTuple> result = Future.future();
    HttpClientRequest request = httpClient.request(httpMethod, port, host, path)
        .handler(response -> {
          response.bodyHandler(buffer -> {
            result.complete(ResponseWithStatusTuple.of(response.statusCode(), buffer));
          });
        }).exceptionHandler(ex -> result.fail(ex));

    if(payload != null) {
      request.end(payload);
    } else {
      request.end();
    }
    return result;
  }

  public static Builder newBuilder() {
    return new Builder();
  }

  public static class Builder {
    private int maxAttempts = 3;
    private boolean keepAlive = true;
    private int connectTimeoutInMs = 5000;
    private int idleTimeoutInSeconds = 10;
    private boolean useCompression = true;

    public Builder setMaxAttempts(int maxAttempts) {
      if(maxAttempts < 1) {
        throw new IllegalArgumentException("Max attempts has to be a positive number");
      }
      this.maxAttempts = maxAttempts;
      return this;
    }

    public Builder keepAlive(boolean keepAlive) {
      this.keepAlive = keepAlive;
      return this;
    }

    public Builder useCompression(boolean useCompression) {
      this.useCompression = useCompression;
      return this;
    }

    public Builder setConnectTimeoutInMs(int connectTimeoutInMs) {
      this.connectTimeoutInMs = connectTimeoutInMs;
      return this;
    }

    public Builder setIdleTimeoutInSeconds(int idleTimeoutInSeconds) {
      this.idleTimeoutInSeconds = idleTimeoutInSeconds;
      return this;
    }

    public ConfigurableHttpClient build(Vertx vertx) {
      if(vertx == null) {
        throw new IllegalStateException("Vertx instance is required");
      }

      return new ConfigurableHttpClient(vertx, maxAttempts, keepAlive,
          useCompression, connectTimeoutInMs, idleTimeoutInSeconds);
    }
  }

  public static class ResponseWithStatusTuple {
    private final int statusCode;
    private final Buffer response;

    private ResponseWithStatusTuple(int statusCode, Buffer response) {
      this.statusCode = statusCode;
      this.response = response;
    }

    public int getStatusCode() {
      return this.statusCode;
    }

    public Buffer getResponse() {
      return this.response;
    }

    public static ResponseWithStatusTuple of(int statusCode, Buffer response) {
      return new ResponseWithStatusTuple(statusCode, response);
    }
  }

}
