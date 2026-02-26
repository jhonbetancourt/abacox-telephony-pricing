package com.infomedia.abacox.telephonypricing.db.repository.query;

public final class CostCenterUsageReportQueries {
    private CostCenterUsageReportQueries() {
    } // Private constructor to prevent instantiation

    /**
     * Matches the original PHP "Centro de Costos" report logic:
     * 1. Uses a recursive CTE to rollup consumption from descendant cost centers.
     * 2. Groups data by direct children of the selected parentCostCenterId.
     * 3. Includes "Unassigned Information" (id = -1) for technical errors or no
     * employee.
     * 4. Includes "[ Sin Centro de Costos ]" (id = 0) for employees with no
     * assigned cost center.
     * 5. Filters outgoing calls to only include those with a valid operator.
     * 6. Incorporates employee history filters.
     */
    public static final String QUERY = """
            WITH RECURSIVE cost_center_tree AS (
                SELECT
                    cc.id,
                    cc.id AS display_cost_center_id,
                    cc.name AS display_cost_center_name,
                    cc.work_order AS display_work_order,
                    cc.origin_country_id AS display_origin_country_id
                FROM
                    cost_center cc
                WHERE
                    ((:#{#parentCostCenterId == null ? 1 : 0} = 1 AND cc.parent_cost_center_id IS NULL)
                    OR cc.parent_cost_center_id = :parentCostCenterId)

                UNION ALL

                SELECT
                    cc.id,
                    ct.display_cost_center_id,
                    ct.display_cost_center_name,
                    ct.display_work_order,
                    ct.display_origin_country_id
                FROM
                    cost_center cc
                INNER JOIN
                    cost_center_tree ct ON cc.parent_cost_center_id = ct.id
            ),
            tree_data AS (
                SELECT
                    ct.display_cost_center_id AS costCenterId,
                    ct.display_origin_country_id AS originCountryId,
                    CASE
                        WHEN COALESCE(TRIM(ct.display_work_order), '') = '' THEN ct.display_cost_center_name
                        ELSE TRIM(ct.display_work_order) || ' - ' || ct.display_cost_center_name
                    END AS costCenterName,
                    COALESCE(COUNT(cr.id) FILTER (WHERE cr.is_incoming = true), 0) AS incomingCallCount,
                    COALESCE(COUNT(cr.id) FILTER (WHERE cr.is_incoming = false), 0) AS outgoingCallCount,
                    COALESCE(SUM(cr.duration), 0) AS totalDuration,
                    COALESCE(SUM(cr.billed_amount), 0.0) AS totalBilledAmount
                FROM
                    call_record cr
                INNER JOIN
                    employee e ON cr.employee_id = e.id
                INNER JOIN
                    cost_center_tree ct ON e.cost_center_id = ct.id
                WHERE
                    (cr.service_date BETWEEN :startDate AND :endDate)
                AND (cr.is_incoming = true OR (cr.is_incoming = false AND cr.operator_id > 0))
                GROUP BY
                    ct.display_cost_center_id, ct.display_cost_center_name, ct.display_work_order, ct.display_origin_country_id
            ),
            no_cc_data AS (
                SELECT
                    0::bigint AS costCenterId,
                    -1::bigint AS originCountryId,
                    '[ Sin Centro de Costos ]' AS costCenterName,
                    COALESCE(COUNT(cr.id) FILTER (WHERE cr.is_incoming = true), 0) AS incomingCallCount,
                    COALESCE(COUNT(cr.id) FILTER (WHERE cr.is_incoming = false), 0) AS outgoingCallCount,
                    COALESCE(SUM(cr.duration), 0) AS totalDuration,
                    COALESCE(SUM(cr.billed_amount), 0.0) AS totalBilledAmount
                FROM
                    call_record cr
                INNER JOIN
                    employee e ON cr.employee_id = e.id
                WHERE
                    :#{#parentCostCenterId == null ? 1 : 0} = 1
                AND e.cost_center_id IS NULL
                AND (cr.service_date BETWEEN :startDate AND :endDate)
                AND (cr.is_incoming = true OR (cr.is_incoming = false AND cr.operator_id > 0))
            ),
            unrelated_data AS (
                SELECT
                    -1::bigint AS costCenterId,
                    -1::bigint AS originCountryId,
                    'Unassigned Information' AS costCenterName,
                    COALESCE(COUNT(cr.id) FILTER (WHERE cr.is_incoming = true), 0) AS incomingCallCount,
                    COALESCE(COUNT(cr.id) FILTER (WHERE cr.is_incoming = false), 0) AS outgoingCallCount,
                    COALESCE(SUM(cr.duration), 0) AS totalDuration,
                    COALESCE(SUM(cr.billed_amount), 0.0) AS totalBilledAmount
                FROM
                    call_record cr
                WHERE
                    :#{#parentCostCenterId == null ? 1 : 0} = 1
                AND (cr.service_date BETWEEN :startDate AND :endDate)
                AND (
                    cr.employee_id IS NULL
                    OR (cr.is_incoming = false AND (cr.telephony_type_id IS NULL OR cr.operator_id IS NULL))
                )
            ),
            combined AS (
                SELECT * FROM tree_data
                UNION ALL
                SELECT * FROM no_cc_data WHERE (incomingCallCount + outgoingCallCount) > 0
                UNION ALL
                SELECT * FROM unrelated_data WHERE (incomingCallCount + outgoingCallCount) > 0
            )
            SELECT
                c.costCenterId,
                c.originCountryId AS originCountryId,
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
                CASE WHEN c.costCenterId = -1 THEN 2
                     WHEN c.costCenterId = 0 THEN 1
                     ELSE 0 END,
                c.totalBilledAmount DESC,
                c.costCenterName ASC
            """;

    public static final String COUNT_QUERY = """
            SELECT COUNT(*) FROM (
                WITH RECURSIVE cost_center_tree AS (
                    SELECT cc.id, cc.id AS display_cost_center_id
                    FROM cost_center cc
                    WHERE ((:#{#parentCostCenterId == null ? 1 : 0} = 1 AND cc.parent_cost_center_id IS NULL)
                       OR cc.parent_cost_center_id = :parentCostCenterId)
                    UNION ALL
                    SELECT cc.id, ct.display_cost_center_id
                    FROM cost_center cc
                    INNER JOIN cost_center_tree ct ON cc.parent_cost_center_id = ct.id
                )
                SELECT ct.display_cost_center_id
                FROM call_record cr
                INNER JOIN employee e ON cr.employee_id = e.id
                INNER JOIN cost_center_tree ct ON e.cost_center_id = ct.id
                WHERE (cr.service_date BETWEEN :startDate AND :endDate)
                GROUP BY ct.display_cost_center_id

                UNION ALL

                SELECT 0::bigint
                FROM call_record cr
                INNER JOIN employee e ON cr.employee_id = e.id
                WHERE :#{#parentCostCenterId == null ? 1 : 0} = 1
                  AND e.cost_center_id IS NULL
                  AND (cr.service_date BETWEEN :startDate AND :endDate)
                HAVING COUNT(*) > 0

                UNION ALL

                SELECT -1::bigint
                FROM call_record cr
                WHERE :#{#parentCostCenterId == null ? 1 : 0} = 1
                  AND (cr.service_date BETWEEN :startDate AND :endDate)
                  AND (cr.employee_id IS NULL OR (cr.is_incoming = false AND (cr.telephony_type_id IS NULL OR cr.operator_id IS NULL)))
                HAVING COUNT(*) > 0
            ) AS group_count
            """;
}