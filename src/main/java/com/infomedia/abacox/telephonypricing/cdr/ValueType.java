package com.infomedia.abacox.telephonypricing.cdr;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public enum ValueType {
    FIXED(0),
    PERCENTAGE(1);

    private final int value;

    public static ValueType fromValue(int value) {
        for (ValueType type : ValueType.values()) {
            if (type.getValue() == value) {
                return type;
            }
        }
        return FIXED; // Default
    }
}