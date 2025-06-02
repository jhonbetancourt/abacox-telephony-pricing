// File: com/infomedia/abacox/telephonypricing/cdr/IncomingTelephonyTypePriority.java
package com.infomedia.abacox.telephonypricing.cdr;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class IncomingTelephonyTypePriority {
    private Long telephonyTypeId;
    private String telephonyTypeName;
    private int minSubscriberLength; // Minimum length of the number *after* any operator prefix is removed
    private int maxSubscriberLength; // Maximum length of the number *after* any operator prefix is removed
    private int minTotalLength; // From TelephonyTypeConfig.minValue (NDC + Subscriber)
    private int maxTotalLength; // From TelephonyTypeConfig.maxValue (NDC + Subscriber)
    private String orderKey; // Derived from minTotalLength for sorting
}