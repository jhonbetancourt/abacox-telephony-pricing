package com.infomedia.abacox.telephonypricing.component.cdrprocessing;

// Based on IMDEX_ASIGNA_* constants
public enum AssignmentCause {
    NOT_ASSIGNED(null), // Default or if no specific cause
    EXTENSION(0),    // IMDEX_ASIGNA_EXT
    AUTH_CODE(2),    // IMDEX_ASIGNA_CLAVE
    TRANSFER(5),     // IMDEX_ASIGNA_TRANS
    CONFERENCE(4),   // IMDEX_ASIGNA_CONFE
    IGNORED_AUTH_CODE(6), // IMDEX_IGNORA_CLAVE
    RANGES(3);       // IMDEX_ASIGNA_RANGOS

    private final Integer value;

    AssignmentCause(Integer value) {
        this.value = value;
    }

    public Integer getValue() {
        return value;
    }

}
