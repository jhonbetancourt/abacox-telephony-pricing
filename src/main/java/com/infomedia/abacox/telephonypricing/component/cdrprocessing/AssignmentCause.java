// File: com/infomedia/abacox/telephonypricing/component/cdrprocessing/AssignmentCause.java
package com.infomedia.abacox.telephonypricing.component.cdrprocessing;

// Based on IMDEX_ASIGNA_* constants
public enum AssignmentCause {
    NOT_ASSIGNED(0),      // Default or if no specific cause (PHP defaulted to 0)
    EXTENSION(0),         // IMDEX_ASIGNA_EXT
    AUTH_CODE(1),         // IMDEX_ASIGNA_CLAVE
    IGNORED_AUTH_CODE(2), // IMDEX_IGNORA_CLAVE
    CONFERENCE(4),        // IMDEX_ASIGNA_CONFE
    TRANSFER(5),          // IMDEX_ASIGNA_TRANS
    RANGES(6);            // IMDEX_ASIGNA_RANGOS

    private final Integer value;

    AssignmentCause(Integer value) {
        this.value = value;
    }

    public Integer getValue() {
        return value;
    }
}