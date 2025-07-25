// src/main/java/com/infomedia/abacox/telephonypricing/db/repository/query/SubdivisionUsageReportQueries.java
package com.infomedia.abacox.telephonypricing.db.repository.query;

public final class SubdivisionUsageReportQueries {
    private SubdivisionUsageReportQueries() {} // Private constructor to prevent instantiation

    public static final String QUERY = """
    WITH report_data AS (
        SELECT
            s.id,
            s.name AS subdivisionName,
            COUNT(DISTINCT e.id) AS totalEmployees,
            COALESCE(COUNT(cr.id) FILTER (WHERE cr.is_incoming = true), 0) AS incomingCallCount,
            COALESCE(COUNT(cr.id) FILTER (WHERE cr.is_incoming = false), 0) AS outgoingCallCount,
            COALESCE(SUM(cr.duration), 0) AS totalDuration,
            COALESCE(SUM(cr.billed_amount), 0) AS totalBilledAmount
        FROM
            subdivision s
        LEFT JOIN
            employee e ON s.id = e.subdivision_id AND e.active = true
        LEFT JOIN
            call_record cr ON e.id = cr.employee_id
                         AND cr.service_date BETWEEN :startDate AND :endDate
        WHERE
            s.id IN (:subdivisionIds)
        GROUP BY
            s.id, s.name
    )
    SELECT
        rd.subdivisionName,
        rd.totalEmployees,
        rd.incomingCallCount,
        rd.outgoingCallCount,
        rd.totalDuration,
        rd.totalBilledAmount,
        CASE
            WHEN SUM(rd.totalDuration) OVER () = 0 THEN 0
            ELSE ROUND((rd.totalDuration * 100.0 / SUM(rd.totalDuration) OVER ()), 2)
        END AS durationPercentage,
        CASE
            WHEN SUM(rd.totalBilledAmount) OVER () = 0 THEN 0
            ELSE ROUND((rd.totalBilledAmount * 100.0 / SUM(rd.totalBilledAmount) OVER ()), 2)
        END AS billedAmountPercentage
    FROM
        report_data rd
    """;

    public static final String COUNT_QUERY = """
    SELECT COUNT(*)
    FROM subdivision s
    WHERE s.id IN (:subdivisionIds)
    """;
}