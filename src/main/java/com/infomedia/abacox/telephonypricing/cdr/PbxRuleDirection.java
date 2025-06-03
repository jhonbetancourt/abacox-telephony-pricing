
package com.infomedia.abacox.telephonypricing.cdr;

import lombok.Getter;

@Getter
public enum PbxRuleDirection {
    BOTH(0),    // Applies to incoming and outgoing
    INCOMING(1),
    OUTGOING(2),
    INTERNAL(3);

    private final int value;

    PbxRuleDirection(int value) {
        this.value = value;
    }
}