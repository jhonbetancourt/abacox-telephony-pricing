package com.infomedia.abacox.telephonypricing.db.repository.query;

public final class MonthlySubdivisionUsageReportQueries {
    private MonthlySubdivisionUsageReportQueries() {} // Private constructor to prevent instantiation

    public static final String QUERY = """
    SELECT
        s.parent_subdivision_id AS parentSubdivisionId,
        ps.name AS parentSubdivisionName,
        s.id AS subdivisionId,
        s.name AS subdivisionName,
        EXTRACT(YEAR FROM cr.service_date)::integer AS year,
        EXTRACT(MONTH FROM cr.service_date)::integer AS month,
        COALESCE(SUM(cr.billed_amount), 0) AS totalBilledAmount,
        COALESCE(SUM(cr.duration), 0) AS totalDuration,
        COALESCE(COUNT(cr.id) FILTER (WHERE cr.is_incoming = false), 0) AS outgoingCallCount,
        COALESCE(COUNT(cr.id) FILTER (WHERE cr.is_incoming = true), 0) AS incomingCallCount
    FROM
        call_record cr
    JOIN
        employee e ON cr.employee_id = e.id
    JOIN
        subdivision s ON e.subdivision_id = s.id
    LEFT JOIN
        subdivision ps ON s.parent_subdivision_id = ps.id
    JOIN
        telephony_type tt ON cr.telephony_type_id = tt.id
    JOIN
        communication_location cl ON cr.comm_location_id = cl.id
    WHERE
        (cr.service_date BETWEEN :startDate AND :endDate)
    AND
        (s.id IN (:subdivisionIds))
    GROUP BY
        s.id, s.name, ps.id, ps.name, year, month
    """;

    public static final String COUNT_QUERY = """
    SELECT COUNT(*) FROM (
        SELECT
            s.id, ps.id, EXTRACT(YEAR FROM cr.service_date), EXTRACT(MONTH FROM cr.service_date)
        FROM
            call_record cr
        JOIN
            employee e ON cr.employee_id = e.id
        JOIN
            subdivision s ON e.subdivision_id = s.id
        LEFT JOIN
            subdivision ps ON s.parent_subdivision_id = ps.id
        WHERE
            (cr.service_date BETWEEN :startDate AND :endDate)
        AND
            (s.id IN (:subdivisionIds))
        GROUP BY
            s.id, ps.id, EXTRACT(YEAR FROM cr.service_date), EXTRACT(MONTH FROM cr.service_date)
    ) AS group_count
    """;
}