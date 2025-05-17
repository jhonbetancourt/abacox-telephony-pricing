package com.infomedia.abacox.telephonypricing.cdr;

public enum PbxSpecialRuleDirection {
    BOTH(0),
    INCOMING(1),
    OUTGOING(2);

    private final int value;

    PbxSpecialRuleDirection(int value) {
        this.value = value;
    }

    public int getValue() {
        return value;
    }

    public static PbxSpecialRuleDirection fromValue(Integer value) {
        if (value == null) return OUTGOING; // Default as per entity
        for (PbxSpecialRuleDirection dir : values()) {
            if (dir.value == value) {
                return dir;
            }
        }
        return OUTGOING; // Or throw an exception for invalid value
    }
}