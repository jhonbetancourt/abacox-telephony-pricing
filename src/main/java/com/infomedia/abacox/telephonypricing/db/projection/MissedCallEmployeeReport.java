package com.infomedia.abacox.telephonypricing.db.projection;

/**
 * Projection for the "Missed / Abandoned Calls by Employee" report.
 * <p>
 * This interface maps to the native SQL query that identifies employees
 * who have missed important calls based on a set of complex business rules.
 */
public interface MissedCallEmployeeReport {
    Long getEmployeeId();

    String getEmployeeName();

    String getEmployeeExtension();

    String getSubdivisionName();

    Double getAverageRingTime();

    Long getMissedCallCount();

    Long getTotalCallCount();

    Double getMissedCallPercentage();
}