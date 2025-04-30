package com.infomedia.abacox.telephonypricing.cdr;

public enum CallDirection {
    OUTGOING(0),
    INCOMING(1);

    private final int value;

    CallDirection(int value) {
        this.value = value;
    }

    public int getValue() {
        return value;
    }

    public static CallDirection fromValue(int value) {
        for (CallDirection dir : CallDirection.values()) {
            if (dir.value == value) {
                return dir;
            }
        }
        // Default or throw exception
        return OUTGOING;
    }
     public static CallDirection fromBoolean(boolean isIncoming) {
        return isIncoming ? INCOMING : OUTGOING;
    }
}