package fk.prof.userapi.exception;


import com.google.protobuf.InvalidProtocolBufferException;

//TODO : Duplicate from backend, extract out in a common module
public class UserapiHttpFailure extends RuntimeException {
  private int statusCode = 500;

  public UserapiHttpFailure() {
    super();
  }

  public UserapiHttpFailure(String message) {
    super(message);
  }

  public UserapiHttpFailure(Throwable throwable) {
    super(throwable);
  }

  public UserapiHttpFailure(int failureCode) {
    statusCode = failureCode;
    initCause(new RuntimeException());
  }

  public UserapiHttpFailure(String message, Throwable throwable) {
    super(message, throwable);
  }

  public UserapiHttpFailure(String message, int failureCode) {
    super(message);
    statusCode = failureCode;
  }

  public UserapiHttpFailure(Throwable throwable, int failureCode) {
    super(throwable);
    statusCode = failureCode;
  }

  public UserapiHttpFailure(String message, Throwable throwable, int failureCode) {
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

  public static UserapiHttpFailure failure(Throwable throwable) {
    if (throwable instanceof UserapiHttpFailure) {
      return (UserapiHttpFailure) throwable;
    }
    if (throwable instanceof ProfException) {
      ProfException exception = (ProfException) throwable;
      return new UserapiHttpFailure(throwable, exception.isServerFailure() ? 500 : 400);
    }
    if(throwable instanceof IllegalArgumentException) {
      return new UserapiHttpFailure(throwable.getMessage(), 400);
    }
    if(throwable instanceof IllegalStateException) {
      return new UserapiHttpFailure(throwable.getMessage());
    }
    if (throwable instanceof InvalidProtocolBufferException) {
      return new UserapiHttpFailure(throwable.getMessage(), 400);
    }
    if (throwable.getMessage() == null) {
      return new UserapiHttpFailure("No message provided", throwable.getCause());
    }
    return new UserapiHttpFailure(throwable.getMessage(), throwable.getCause());
  }
}
