package com.infomedia.abacox.telephonypricing.db.repository.query;

public final class CostCenterUsageReportQueries {
    private CostCenterUsageReportQueries() {
    } // Private constructor to prevent instantiation

    /**
     * Matches the original PHP "Centro de Costos" report logic:
     * 1. Groups call records by cost center (via employee → cost_center)
     * 2. Includes an "Información no relacionada" row for calls with no assigned
     * employee
     * (employee_id IS NULL), using costCenterId = -1 as sentinel value
     * 3. Cost center name format: "work_order - name" matching PHP Arbol_Listar
     * output
     * 4. Percentages are computed over the grand total (cost centers + unrelated
     * info)
     * 5. Filters outgoing calls to only include those with a valid operator
     * (operator_id > 0),
     * matching the PHP filter: (ACUMTOTAL_IO > 0 OR (ACUMTOTAL_IO = 0 AND
     * ACUMTOTAL_OPERADOR_ID > 0))
     */
    public static final String QUERY = """
            WITH assigned_data AS (
                SELECT
                    cc.id AS costCenterId,
                    cc.origin_country_id AS originCountryId,
                    CASE
                        WHEN cc.id IS NULL THEN 'No Cost Center'
                        WHEN COALESCE(TRIM(cc.work_order), '') = '' THEN cc.name
                        ELSE TRIM(cc.work_order) || ' - ' || cc.name
                    END AS costCenterName,
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
                    communication_location cl ON cr.comm_location_id = cl.id
                JOIN
                    telephony_type tt ON cr.telephony_type_id = tt.id
                WHERE
                    (cr.service_date BETWEEN :startDate AND :endDate)
                AND
                    (cr.is_incoming = true OR (cr.is_incoming = false AND cr.operator_id > 0))
                GROUP BY
                    cc.id, cc.name, cc.work_order, cc.origin_country_id
            ),
            unrelated_data AS (
                SELECT
                    -1::bigint AS costCenterId,
                    -1::bigint AS originCountryId,
                    'Unassigned Information' AS costCenterName,
                    COALESCE(COUNT(cr.id) FILTER (WHERE cr.is_incoming = true), 0) AS incomingCallCount,
                    COALESCE(COUNT(cr.id) FILTER (WHERE cr.is_incoming = false), 0) AS outgoingCallCount,
                    COALESCE(SUM(cr.duration), 0) AS totalDuration,
                    COALESCE(SUM(cr.billed_amount), 0) AS totalBilledAmount
                FROM
                    call_record cr
                WHERE
                    cr.employee_id IS NULL
                AND
                    (cr.service_date BETWEEN :startDate AND :endDate)
            ),
            combined AS (
                SELECT * FROM assigned_data
                UNION ALL
                SELECT * FROM unrelated_data
                WHERE (incomingCallCount + outgoingCallCount) > 0
            )
            SELECT
                c.costCenterId,
                c.originCountryId,
                c.costCenterName,
                c.incomingCallCount,
                c.outgoingCallCount,
                c.totalDuration,
                c.totalBilledAmount,
                CASE
                    WHEN SUM(c.totalDuration) OVER () = 0 THEN 0
                    ELSE ROUND((c.totalDuration * 100.0 / SUM(c.totalDuration) OVER ()), 2)
                END AS durationPercentage,
                CASE
                    WHEN SUM(c.totalBilledAmount) OVER () = 0 THEN 0
                    ELSE ROUND((c.totalBilledAmount * 100.0 / SUM(c.totalBilledAmount) OVER ()), 2)
                END AS billedAmountPercentage
            FROM
                combined c
            ORDER BY
                CASE WHEN c.costCenterId = -1 THEN 1 ELSE 0 END,
                c.originCountryId ASC,
                c.costCenterId ASC
            """;

    public static final String COUNT_QUERY = """
            SELECT COUNT(*) FROM (
                SELECT cc.id
                FROM call_record cr
                JOIN employee e ON cr.employee_id = e.id
                LEFT JOIN cost_center cc ON e.cost_center_id = cc.id
                WHERE
                    (cr.service_date BETWEEN :startDate AND :endDate)
                AND
                    (cr.is_incoming = true OR (cr.is_incoming = false AND cr.operator_id > 0))
                GROUP BY cc.id

                UNION ALL

                SELECT -1::bigint AS id
                FROM call_record cr
                WHERE cr.employee_id IS NULL
                AND (cr.service_date BETWEEN :startDate AND :endDate)
                HAVING COUNT(*) > 0
            ) AS group_count
            """;
}