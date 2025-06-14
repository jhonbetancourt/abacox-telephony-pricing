package com.infomedia.abacox.telephonypricing.component.cdrprocessing;

// Based on IMDEX_TRANSFER_* constants
public enum TransferCause {
    NONE(0),
    NORMAL(1), // Transfer
    AUTO(2), // IMDEX_TRANSFER_AUTO
    CONFERENCE(3), // IMDEX_TRANSFER_CONFERENCIA
    CONFERENCE_END(4), // IMDEX_TRANSFER_CONFERE_FIN
    NO_ANSWER(6), // Call Forward No Answer
    BUSY(7), // Call Forward Busy
    CONFERENCE_ADD(8), // IMDEX_TRANSFER_CONFERE_ADD
    PRE_CONFERENCE_NOW(9), // IMDEX_TRANSFER_PRECONFENOW (finaliza_union = 7 for conference setup)
    CONFERENCE_NOW(10); // IMDEX_TRANSFER_CONFENOW (joinOnBehalfOf = 7)

    // ... other causes from PHP's IMDEX_TRANSFER_*




    private final int value;

    TransferCause(int value) {
        this.value = value;
    }

    public int getValue() {
        return value;
    }
}
