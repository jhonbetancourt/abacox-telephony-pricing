package com.infomedia.abacox.telephonypricing.db.repository.query;

public final class CostCenterUsageReportQueries {
    private CostCenterUsageReportQueries() {} // Private constructor to prevent instantiation

    public static final String QUERY = """
    WITH report_data AS (
        SELECT
            COALESCE(cc.name, 'Unassigned') AS costCenterName,
            COALESCE(COUNT(cr.id) FILTER (WHERE cr.is_incoming = true), 0) AS incomingCallCount,
            COALESCE(COUNT(cr.id) FILTER (WHERE cr.is_incoming = false), 0) AS outgoingCallCount,
            COALESCE(SUM(cr.duration), 0) AS totalDuration,
            COALESCE(SUM(cr.billed_amount), 0) AS totalBilledAmount
        FROM
            call_record cr
        JOIN
            employee e ON cr.employee_id = e.id
        LEFT JOIN
            cost_center cc ON e.cost_center_id = cc.id
        JOIN
            subdivision s ON e.subdivision_id = s.id
        JOIN
            communication_location cl ON cr.comm_location_id = cl.id
        LEFT JOIN
            indicator ind_dest ON cr.indicator_id = ind_dest.id
        LEFT JOIN
            indicator ind_origin ON cl.indicator_id = ind_origin.id
        LEFT JOIN
            origin_country oc ON ind_origin.origin_country_id = oc.id
        JOIN
            telephony_type tt ON cr.telephony_type_id = tt.id
        WHERE
            (cr.service_date BETWEEN :startDate AND :endDate)
        AND
            (cr.is_incoming = true OR (cr.is_incoming = false AND cr.operator_id > 0))
        GROUP BY
            cc.id, cc.name
    )
    SELECT
        rd.costCenterName,
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
            cc.id
        FROM
            call_record cr
        JOIN
            employee e ON cr.employee_id = e.id
        LEFT JOIN
            cost_center cc ON e.cost_center_id = cc.id
        WHERE
            (cr.service_date BETWEEN :startDate AND :endDate)
        AND
            (cr.is_incoming = true OR (cr.is_incoming = false AND cr.operator_id > 0))
        GROUP BY
            cc.id
    ) AS group_count
    """;
}