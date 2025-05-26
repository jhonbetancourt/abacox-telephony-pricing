package com.infomedia.abacox.telephonypricing.cdr;

// Based on IMDEX_TRANSFER_* constants
public enum TransferCause {
    NONE(0),
    NORMAL(1), // Transfer
    BUSY(2), // Call Forward Busy
    NO_ANSWER(3), // Call Forward No Answer
    UNCONDITIONAL(4), // Call Forward All
    // ... other causes from PHP's IMDEX_TRANSFER_*
    CONFERENCE(5), // IMDEX_TRANSFER_CONFERENCIA
    CONFERENCE_END(6), // IMDEX_TRANSFER_CONFERE_FIN
    CONFERENCE_ADD(7), // IMDEX_TRANSFER_CONFERE_ADD
    AUTO(8), // IMDEX_TRANSFER_AUTO
    CONFERENCE_NOW(9), // IMDEX_TRANSFER_CONFENOW (joinOnBehalfOf = 7)
    PRE_CONFERENCE_NOW(10); // IMDEX_TRANSFER_PRECONFENOW (finaliza_union = 7 for conference setup)


    private final int value;

    TransferCause(int value) {
        this.value = value;
    }

    public int getValue() {
        return value;
    }
}
