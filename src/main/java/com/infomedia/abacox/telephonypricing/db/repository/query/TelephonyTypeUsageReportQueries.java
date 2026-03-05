package com.infomedia.abacox.telephonypricing.db.repository.query;

public final class TelephonyTypeUsageReportQueries {
    private TelephonyTypeUsageReportQueries() {} // Private constructor to prevent instantiation

    public static final String QUERY = """
    WITH aggregated_calls AS (
        -- OPTIMIZATION 1: Pre-aggregate the massive call_record table 
        -- grouped strictly by the integer ID before joining lookup dictionary tables.
        SELECT
            cr.telephony_type_id,
            COALESCE(COUNT(cr.id) FILTER (WHERE cr.is_incoming = false), 0) AS outgoingCallCount,
            COALESCE(COUNT(cr.id) FILTER (WHERE cr.is_incoming = true), 0) AS incomingCallCount,
            COALESCE(SUM(cr.duration), 0) AS totalDuration,
            COALESCE(SUM(cr.billed_amount), 0) AS totalBilledAmount
        FROM
            call_record cr
        -- We keep these INNER JOINs to guarantee identical filtering behavior 
        -- (e.g. dropping calls if the employee or subdivision was removed/null)
        INNER JOIN
            employee e ON cr.employee_id = e.id
        INNER JOIN
            subdivision s ON e.subdivision_id = s.id
        INNER JOIN
            communication_location cl ON cr.comm_location_id = cl.id
        WHERE
            (cr.service_date BETWEEN :startDate AND :endDate)
        AND
            (cr.is_incoming = true OR (cr.is_incoming = false AND cr.operator_id > 0))
        GROUP BY
            cr.telephony_type_id
    ),
    report_data AS (
        -- OPTIMIZATION 2: Attach the string names only to the final aggregated subset
        SELECT
            cc.name AS telephonyCategoryName,
            tt.name AS telephonyTypeName,
            ac.outgoingCallCount,
            ac.incomingCallCount,
            ac.totalDuration,
            ac.totalBilledAmount
        FROM
            aggregated_calls ac
        INNER JOIN
            telephony_type tt ON ac.telephony_type_id = tt.id
        LEFT JOIN
            call_category cc ON tt.call_category_id = cc.id
    )
    SELECT
        rd.telephonyCategoryName,
        rd.telephonyTypeName,
        rd.outgoingCallCount,
        rd.incomingCallCount,
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
    ORDER BY
        rd.telephonyCategoryName, rd.telephonyTypeName
    """;
}