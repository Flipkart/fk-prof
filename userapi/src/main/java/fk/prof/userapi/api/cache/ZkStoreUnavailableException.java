package fk.prof.userapi.api.cache;

import fk.prof.userapi.ServiceUnavailableException;

/**
 * Created by gaurav.ashok on 03/08/17.
 */
public class ZkStoreUnavailableException extends ServiceUnavailableException {

    public ZkStoreUnavailableException(String msg) {
        super(msg);
    }

    public ZkStoreUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
