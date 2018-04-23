package fk.prof.userapi.http;

/**
 * Created by gaurav.ashok on 26/06/17.
 */
public class ServiceUnavailableException extends Exception {

    public ServiceUnavailableException(String msg) {
        super(msg);
    }

    public ServiceUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
