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
}