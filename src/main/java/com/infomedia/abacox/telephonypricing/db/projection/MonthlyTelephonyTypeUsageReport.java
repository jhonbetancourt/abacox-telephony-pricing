package com.infomedia.abacox.telephonypricing.db.projection;

import java.math.BigDecimal;

public interface MonthlyTelephonyTypeUsageReport {
    Integer getYear();
    Integer getMonth();
    String getTelephonyTypeName();
    Long getIncomingCallCount();
    Long getOutgoingCallCount();
    Long getTotalDuration();
    BigDecimal getDurationPercentage();
    BigDecimal getTotalBilledAmount();
    BigDecimal getBilledAmountPercentage();
}