package com.infomedia.abacox.telephonypricing.cdr;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public enum TrunkDirection {
    BOTH(0),
    INCOMING(1),
    OUTGOING(2);

    private final int value;

    public static TrunkDirection fromValue(int value) {
        for (TrunkDirection direction : TrunkDirection.values()) {
            if (direction.getValue() == value) {
                return direction;
            }
        }
        return BOTH; // Default
    }
}