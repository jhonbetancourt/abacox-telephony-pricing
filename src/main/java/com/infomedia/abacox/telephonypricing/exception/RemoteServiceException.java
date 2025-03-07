package com.infomedia.abacox.telephonypricing.exception;

public class RemoteServiceException extends RuntimeException {
    public RemoteServiceException(String message) {
        super(message);
    }

    public RemoteServiceException(String message, Throwable cause) {
        super(message, cause);
    }
}
