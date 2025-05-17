package com.infomedia.abacox.telephonypricing.cdr;

// Based on PHP constants like IMDEX_TRANSFER_NORMAL, IMDEX_TRANSFER_CONFERENCIA etc.
// The exact numeric values would need to be mapped from the PHP code if they are significant.
// For now, using descriptive names.
public enum ImdexTransferCause {
    NO_TRANSFER(0),
    NORMAL(1),          // e.g., lastRedirectRedirectReason = 1 (Forward No Answer)
    CONFERENCE(2),      // e.g., lastRedirectRedirectReason = 10 (Transfer) when it's a conference setup
    AUTO_TRANSFER(3),   // Other redirect reasons
    CONFERENCE_END(4),  // Logic for calls after a conference leg drops
    CONFERENCE_ADD(5),   // Logic for adding a new leg to an existing CallRecord due to conference
    PRE_CONFERENCE_NOW(6); // For joinOnBehalfOf = 7

    private final int value;

    ImdexTransferCause(int value) {
        this.value = value;
    }

    public int getValue() {
        return value;
    }
}