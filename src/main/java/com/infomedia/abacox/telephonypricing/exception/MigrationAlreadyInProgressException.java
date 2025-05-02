package com.infomedia.abacox.telephonypricing.exception; // Use your actual package

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

@ResponseStatus(HttpStatus.CONFLICT) // 409 Conflict is appropriate
public class MigrationAlreadyInProgressException extends RuntimeException {
    public MigrationAlreadyInProgressException(String message) {
        super(message);
    }
}