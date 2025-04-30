package com.infomedia.abacox.telephonypricing.cdr;

// Based on IMDEX constants in PHP
public enum CallTransferCause {
    NONE(0),
    NORMAL(1),
    BUSY(2),
    NO_ANSWER(3),
    CONFERENCE(4),
    CONFERENCE_END(5), // Fin de conferencia
    CONFERENCE_ADD(6), // Adicionado por conferencia
    AUTO(7), // Auto-transferencia
    PRE_CONFERENCE_NOW(8), // Pre-Conferencia Now
    CONFERENCE_NOW(9); // Conferencia Now

    private final int value;

    CallTransferCause(int value) {
        this.value = value;
    }

    public int getValue() {
        return value;
    }

     public static CallTransferCause fromValue(int value) {
        for (CallTransferCause cause : CallTransferCause.values()) {
            if (cause.value == value) {
                return cause;
            }
        }
        return NONE; // Default
    }
}