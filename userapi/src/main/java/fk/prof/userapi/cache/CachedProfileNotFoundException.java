package fk.prof.userapi.cache;

import java.io.FileNotFoundException;

/**
 * Created by gaurav.ashok on 23/06/17.
 */
public class CachedProfileNotFoundException extends FileNotFoundException {

    private final boolean cachedRemotely;
    private final String ip;
    private final Integer port;
    private final Throwable cause;

    public CachedProfileNotFoundException(String ip, Integer port) {
        this.ip = ip;
        this.port = port;
        this.cachedRemotely = true;
        this.cause = null;
    }

    public CachedProfileNotFoundException() {
        this(null);
    }

    public CachedProfileNotFoundException(Throwable e) {
        this.ip = null;
        this.port = null;
        this.cachedRemotely = false;
        this.cause = e;
    }

    public boolean isCachedRemotely() {
        return cachedRemotely;
    }

    public String getIp() {
        return ip;
    }

    public Integer getPort() {
        return port;
    }

    @Override
    public Throwable getCause() {
        return this.cause;
    }
}
