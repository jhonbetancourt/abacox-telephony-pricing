package com.infomedia.abacox.telephonypricing.cdr;

// Based on IMDEX constants in PHP
public enum CallAssignmentCause {
    UNKNOWN(0), // Default or not specified
    EXTENSION(1),
    AUTH_CODE(2),
    IGNORED_AUTH_CODE(3), // Clave ignorada
    CONFERENCE(4),
    TRANSFER(5),
    RANGES(6); // Asignado por rangos

    private final int value;

    CallAssignmentCause(int value) {
        this.value = value;
    }

    public int getValue() {
        return value;
    }
}