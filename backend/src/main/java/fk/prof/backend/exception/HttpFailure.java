package fk.prof.backend.exception;


public class HttpFailure extends RuntimeException {
  private int statusCode = 500;

  public HttpFailure() {
    super();
  }

  public HttpFailure(String message) {
    super(message);
  }

  public HttpFailure(Throwable throwable) {
    super(throwable);
  }

  public HttpFailure(int failureCode) {
    statusCode = failureCode;
    initCause(new RuntimeException());
  }

  public HttpFailure(String message, Throwable throwable) {
    super(message, throwable);
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

  @Override
  public String toString() {
    return "status=" + statusCode + ", " + super.toString();
  }

  public static HttpFailure failure(Throwable throwable) {
    if (throwable instanceof HttpFailure) {
      return (HttpFailure) throwable;
    }
    if (throwable instanceof ProfException) {
      ProfException exception = (ProfException) throwable;
      return new HttpFailure(throwable, exception.isServerFailure() ? 500 : 400);
    }
    if(throwable instanceof IllegalArgumentException) {
      return new HttpFailure(throwable.getMessage(), 400);
    }
    if(throwable instanceof IllegalStateException) {
      return new HttpFailure(throwable.getMessage());
    }
    if (throwable.getMessage() == null) {
      return new HttpFailure("No message provided", throwable.getCause());
    }
    return new HttpFailure(throwable.getMessage(), throwable.getCause());
  }
}
