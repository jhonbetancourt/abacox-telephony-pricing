package com.infomedia.abacox.telephonypricing.component.cdrprocessing;

// Based on IMDEX_ASIGNA_* constants
public enum AssignmentCause {
    NOT_ASSIGNED(0), // Default or if no specific cause
    EXTENSION(1),    // IMDEX_ASIGNA_EXT
    AUTH_CODE(2),    // IMDEX_ASIGNA_CLAVE
    TRANSFER(3),     // IMDEX_ASIGNA_TRANS
    CONFERENCE(4),   // IMDEX_ASIGNA_CONFE
    IGNORED_AUTH_CODE(5), // IMDEX_IGNORA_CLAVE
    RANGES(6);       // IMDEX_ASIGNA_RANGOS

    private final int value;

    AssignmentCause(int value) {
        this.value = value;
    }

    public int getValue() {
        return value;
    }
}
