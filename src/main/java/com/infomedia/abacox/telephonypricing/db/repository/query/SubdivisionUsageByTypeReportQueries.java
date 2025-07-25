// src/main/java/com/infomedia/abacox/telephonypricing/db/repository/query/SubdivisionUsageByTypeReportQueries.java
package com.infomedia.abacox.telephonypricing.db.repository.query;

public final class SubdivisionUsageByTypeReportQueries {
    private SubdivisionUsageByTypeReportQueries() {} // Private constructor to prevent instantiation

    public static final String QUERY = """
    SELECT
        s.id AS subdivisionId,
        s.name AS subdivisionName,
        tt.name AS telephonyTypeName,
        COALESCE(SUM(cr.billed_amount), 0) AS totalBilledAmount,
        COALESCE(SUM(cr.duration), 0) AS totalDuration
    FROM
        call_record cr
    JOIN
        employee e ON cr.employee_id = e.id
    JOIN
        subdivision s ON e.subdivision_id = s.id
    JOIN
        telephony_type tt ON cr.telephony_type_id = tt.id
    JOIN
        call_category cc ON tt.call_category_id = cc.id
    JOIN
        communication_location cl ON cr.comm_location_id = cl.id
    WHERE
        (cr.service_date BETWEEN :startDate AND :endDate)
    AND
        (s.id IN (:subdivisionIds))
    GROUP BY
        s.id, s.name, tt.name
    """;

    public static final String COUNT_QUERY = """
    SELECT COUNT(*) FROM (
        SELECT
            s.id, tt.name
        FROM
            call_record cr
        JOIN
            employee e ON cr.employee_id = e.id
        JOIN
            subdivision s ON e.subdivision_id = s.id
        JOIN
            telephony_type tt ON cr.telephony_type_id = tt.id
        JOIN
            call_category cc ON tt.call_category_id = cc.id
        JOIN
            communication_location cl ON cr.comm_location_id = cl.id
        WHERE
            (cr.service_date BETWEEN :startDate AND :endDate)
        AND
            (s.id IN (:subdivisionIds))
        GROUP BY
            s.id, tt.name
    ) AS group_count
    """;
}