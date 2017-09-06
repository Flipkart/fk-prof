package fk.prof.backend.exception;

/**
 * Exception class wrapping RuntimeExceptions for policy related operations
 * Created by rohit.patiyal on 16/05/17.
 */
public class PolicyException extends RuntimeException implements ProfException {
    private boolean serverFailure = false;

    public PolicyException(boolean serverFailure) {
        super();
        this.serverFailure = serverFailure;
    }

    public PolicyException(String message, boolean serverFailure) {
        super(message);
        this.serverFailure = serverFailure;
    }

    public PolicyException(String message, Throwable cause, boolean serverFailure) {
        super(message, cause);
        this.serverFailure = serverFailure;
    }

    public PolicyException(Throwable cause, boolean serverFailure) {
        super(cause);
        this.serverFailure = serverFailure;
    }

    protected PolicyException(String message, Throwable cause, boolean enableSuppression, boolean writableStackTrace, boolean serverFailure) {
        super(message, cause, enableSuppression, writableStackTrace);
        this.serverFailure = serverFailure;
    }

    @Override
    public boolean isServerFailure() {
        return serverFailure;
    }
}