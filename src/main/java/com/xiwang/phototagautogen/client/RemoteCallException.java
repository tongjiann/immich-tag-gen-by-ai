package com.xiwang.phototagautogen.client;

public class RemoteCallException extends RuntimeException {
    private final int statusCode;

    public RemoteCallException(String message, int statusCode) {
        super(message);
        this.statusCode = statusCode;
    }

    public RemoteCallException(String message, Throwable cause) {
        super(message, cause);
        this.statusCode = -1;
    }

    public RemoteCallException(String message, int statusCode, Throwable cause) {
        super(message, cause);
        this.statusCode = statusCode;
    }

    public int statusCode() { return statusCode; }
}
