package com.infomedia.abacox.telephonypricing.db.projection;

import java.math.BigDecimal;

/**
 * A Projection Interface for the native hierarchical monthly trend report.
 * Spring Data JPA will create a proxy instance of this interface, backed by the query results.
 * The getter method names MUST match the column aliases in the native query.
 */
public interface MonthlySubdivisionUsageReport {
    Long getParentSubdivisionId();
    String getParentSubdivisionName();
    Long getSubdivisionId();
    String getSubdivisionName();
    Integer getYear();
    Integer getMonth();
    BigDecimal getTotalBilledAmount();
    Long getTotalDuration();
    Long getOutgoingCallCount();
    Long getIncomingCallCount();
}