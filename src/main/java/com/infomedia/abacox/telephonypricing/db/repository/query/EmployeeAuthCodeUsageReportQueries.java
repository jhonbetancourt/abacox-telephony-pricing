package com.infomedia.abacox.telephonypricing.db.repository.query;

public final class EmployeeAuthCodeUsageReportQueries {
    private EmployeeAuthCodeUsageReportQueries() {} // Private constructor to prevent instantiation

    public static final String QUERY = """
    WITH report_data AS (
        SELECT
            e.id AS employeeId,
            e.name AS employeeName,
            e.auth_code AS authCode,
            o.name AS operatorName,
            tt.name AS telephonyTypeName,
            COUNT(cr.id) AS callCount,
            COALESCE(SUM(cr.duration), 0) AS totalDuration,
            COALESCE(SUM(cr.billed_amount), 0) AS totalBilledAmount
        FROM
            call_record cr
        JOIN
            employee e ON cr.employee_id = e.id
        JOIN
            operator o ON cr.operator_id = o.id
        JOIN
            telephony_type tt ON cr.telephony_type_id = tt.id
        JOIN
            communication_location cl ON cr.comm_location_id = cl.id
        WHERE
            (cr.service_date BETWEEN :startDate AND :endDate)
        AND
            cr.is_incoming = false -- Outgoing calls only
        AND
            cr.assignment_cause = 1 -- Assigned by Auth Code
        GROUP BY
            e.id, e.name, e.auth_code, o.id, o.name, tt.id, tt.name
    )
    SELECT
        rd.employeeName,
        rd.authCode,
        rd.operatorName,
        rd.telephonyTypeName,
        rd.callCount,
        rd.totalDuration,
        rd.totalBilledAmount,
        CASE
            WHEN SUM(rd.totalDuration) OVER (PARTITION BY rd.employeeId) = 0 THEN 0
            ELSE ROUND((rd.totalDuration * 100.0 / SUM(rd.totalDuration) OVER (PARTITION BY rd.employeeId)), 2)
        END AS durationPercentage,
        CASE
            WHEN SUM(rd.totalBilledAmount) OVER (PARTITION BY rd.employeeId) = 0 THEN 0
            ELSE ROUND((rd.totalBilledAmount * 100.0 / SUM(rd.totalBilledAmount) OVER (PARTITION BY rd.employeeId)), 2)
        END AS billedAmountPercentage
    FROM
        report_data rd
    """;

    public static final String COUNT_QUERY = """
    SELECT COUNT(*) FROM (
        SELECT
            e.id, o.id, tt.id
        FROM
            call_record cr
        JOIN
            employee e ON cr.employee_id = e.id
        JOIN
            operator o ON cr.operator_id = o.id
        JOIN
            telephony_type tt ON cr.telephony_type_id = tt.id
        WHERE
            (cr.service_date BETWEEN :startDate AND :endDate)
        AND
            cr.is_incoming = false
        AND
            cr.assignment_cause = 1
        GROUP BY
            e.id, o.id, tt.id
    ) AS group_count
    """;
}