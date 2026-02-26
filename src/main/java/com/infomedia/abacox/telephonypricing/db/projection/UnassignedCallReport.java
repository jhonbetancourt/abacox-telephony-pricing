package com.infomedia.abacox.telephonypricing.db.projection;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Projection for the "Unassigned Calls" report.
 * <p>
 * This interface maps to the native SQL query result that aggregates call data
 * for extensions that are not assigned to a specific employee or are assigned
 * for a special reason (cause #5).
 */
public interface UnassignedCallReport {

    String getConcept();

    String getPlant();

    Long getCommLocationId();

    BigDecimal getTotalCost();

    Long getTotalDuration();

    Long getCallCount();

    LocalDateTime getLastCallDate();
}