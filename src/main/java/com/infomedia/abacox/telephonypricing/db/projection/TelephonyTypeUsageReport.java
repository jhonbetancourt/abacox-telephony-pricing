package com.infomedia.abacox.telephonypricing.db.projection;

import java.math.BigDecimal;

/**
 * A Projection Interface for the native system-wide usage summary, broken down by telephony type.
 * Spring Data JPA will create a proxy instance of this interface, backed by the query results.
 * The getter method names MUST match the column aliases in the native query.
 */
public interface TelephonyTypeUsageReport {
    String getTelephonyCategoryName();
    String getTelephonyTypeName();
    Long getOutgoingCallCount();
    Long getIncomingCallCount();
    Long getTotalDuration();
    BigDecimal getDurationPercentage();
    BigDecimal getTotalBilledAmount();
    BigDecimal getBilledAmountPercentage();
}

    