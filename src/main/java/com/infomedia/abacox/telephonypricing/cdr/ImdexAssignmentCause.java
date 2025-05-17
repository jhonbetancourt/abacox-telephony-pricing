package com.infomedia.abacox.telephonypricing.cdr;

public enum ImdexAssignmentCause {
    NOT_ASSIGNED(0),
    BY_EXTENSION(1),
    BY_AUTH_CODE(2),
    BY_EXTENSION_RANGE(3),
    BY_TRANSFER(4), // Employee assigned due to a transfer
    BY_CONFERENCE(5); // Employee assigned due to conference logic

    private final int value;

    ImdexAssignmentCause(int value) {
        this.value = value;
    }

    public int getValue() {
        return value;
    }
}