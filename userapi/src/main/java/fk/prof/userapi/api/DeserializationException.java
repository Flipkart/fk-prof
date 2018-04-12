package fk.prof.userapi.api;

import java.io.IOException;

public class DeserializationException extends IOException {
    public DeserializationException(String message) {
        super(message);
    }
    public DeserializationException(String message, Throwable cause) {
        super(message, cause);
    }
}
