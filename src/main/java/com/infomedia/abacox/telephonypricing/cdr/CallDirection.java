package com.infomedia.abacox.telephonypricing.cdr;

public enum CallDirection {
    OUTGOING(0), // Assuming 0 for outgoing from PHP logic (false)
    INCOMING(1); // Assuming 1 for incoming from PHP logic (true)

    private final int value;

    CallDirection(int value) {
        this.value = value;
    }

    public int getValue() {
        return value;
    }

    public static CallDirection fromBoolean(boolean isIncoming) {
        return isIncoming ? INCOMING : OUTGOING;
    }
}