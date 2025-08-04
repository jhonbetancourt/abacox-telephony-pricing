package com.infomedia.abacox.telephonypricing.db.projection;

import java.math.BigDecimal;

/**
 * A Projection Interface for the native financial summary report, aggregated by Cost Center.
 * Spring Data JPA will create a proxy instance of this interface, backed by the query results.
 * The getter method names MUST match the column aliases in the native query.
 */
public interface CostCenterUsageReport {
    String getCostCenterName();
    Long getIncomingCallCount();
    Long getOutgoingCallCount();
    Long getTotalDuration();
    BigDecimal getDurationPercentage();
    BigDecimal getTotalBilledAmount();
    BigDecimal getBilledAmountPercentage();
}