package com.infomedia.abacox.telephonypricing.db.repository.query;

public final class MonthlyTelephonyTypeUsageReportQueries {
    private MonthlyTelephonyTypeUsageReportQueries() {
    } // Private constructor to prevent instantiation

    public static final String QUERY = """
            WITH report_data AS (
                SELECT
                    EXTRACT(YEAR FROM cr.service_date)::integer AS year,
                    EXTRACT(MONTH FROM cr.service_date)::integer AS month,
                    tt.name AS telephonyTypeName,
                    COALESCE(COUNT(cr.id) FILTER (WHERE cr.is_incoming = true), 0) AS incomingCallCount,
                    COALESCE(COUNT(cr.id) FILTER (WHERE cr.is_incoming = false), 0) AS outgoingCallCount,
                    COALESCE(SUM(cr.duration), 0) AS totalDuration,
                    COALESCE(SUM(cr.billed_amount), 0) AS totalBilledAmount
                FROM
                    call_record cr
                JOIN
                    telephony_type tt ON cr.telephony_type_id = tt.id
                JOIN
                    employee e ON cr.employee_id = e.id
                JOIN
                    communication_location cl ON cr.comm_location_id = cl.id
                WHERE
                    (cr.service_date BETWEEN :startDate AND :endDate)
                AND
                    (cr.is_incoming = true OR (cr.is_incoming = false AND cr.operator_id > 0))
                GROUP BY
                    tt.id, tt.name, year, month
            )
            SELECT
                rd.year,
                rd.month,
                rd.telephonyTypeName,
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
            SELECT COUNT(*) FROM (
                SELECT
                    tt.id, EXTRACT(YEAR FROM cr.service_date), EXTRACT(MONTH FROM cr.service_date)
                FROM
                    call_record cr
                JOIN
                    telephony_type tt ON cr.telephony_type_id = tt.id
                JOIN
                    employee e ON cr.employee_id = e.id
                JOIN
                    communication_location cl ON cr.comm_location_id = cl.id
                WHERE
                    (cr.service_date BETWEEN :startDate AND :endDate)
                AND
                    (cr.is_incoming = true OR (cr.is_incoming = false AND cr.operator_id > 0))
                GROUP BY
                    tt.id, EXTRACT(YEAR FROM cr.service_date), EXTRACT(MONTH FROM cr.service_date)
            ) AS group_count
            """;
}