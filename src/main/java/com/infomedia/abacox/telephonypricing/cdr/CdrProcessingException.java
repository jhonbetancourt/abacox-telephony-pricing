package com.infomedia.abacox.telephonypricing.cdr;

public class CdrProcessingException extends RuntimeException{

    public CdrProcessingException(String message) {
        super(message);
    }

    public CdrProcessingException(String message, Throwable cause) {
        super(message, cause);
    }

    public CdrProcessingException(Throwable cause) {
        super(cause);
    }
}
