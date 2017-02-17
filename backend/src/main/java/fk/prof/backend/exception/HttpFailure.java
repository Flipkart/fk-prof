package fk.prof.backend.exception;

import io.vertx.core.eventbus.ReplyException;
import io.vertx.core.eventbus.ReplyFailure;

public class HttpFailure extends RuntimeException {
  private int statusCode;

  public HttpFailure() {
    super();
    statusCode = 500;
  }

  public HttpFailure(String message) {
    super(message);
    statusCode = 500;
  }

  public HttpFailure(Throwable throwable) {
    super(throwable);
    statusCode = 500;
  }

  public HttpFailure(int failureCode) {
    statusCode = failureCode;
    initCause(new RuntimeException());
  }

  public HttpFailure(String message, Throwable throwable) {
    super(message, throwable);
    statusCode = 500;
  }

  public HttpFailure(String message, int failureCode) {
    super(message);
    statusCode = failureCode;
  }

  public HttpFailure(Throwable throwable, int failureCode) {
    super(throwable);
    statusCode = failureCode;
  }

  public HttpFailure(String message, Throwable throwable, int failureCode) {
    super(message, throwable);
    statusCode = failureCode;
  }

  public int getStatusCode() {
    return statusCode;
  }

  public static HttpFailure failure(Throwable throwable) {
    if (throwable instanceof HttpFailure) {
      return (HttpFailure) throwable;
    }
    if (throwable instanceof AggregationFailure) {
      AggregationFailure exception = (AggregationFailure) throwable;
      return new HttpFailure(throwable, exception.isServerFailure() ? 500 : 400);
    }
    if (throwable instanceof ReplyException) {
      ReplyException exception = (ReplyException) throwable;
      if(exception.failureType().equals(ReplyFailure.RECIPIENT_FAILURE)) {
        return new HttpFailure(exception.getMessage(), exception.failureCode());
      } else {
        return new HttpFailure(exception.getMessage());
      }
    }
    if (throwable.getMessage() == null) {
      return new HttpFailure("No message provided", throwable.getCause());
    }
    return new HttpFailure(throwable.getMessage(), throwable.getCause());
  }
}
