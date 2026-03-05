package com.infomedia.abacox.telephonypricing.db.repository.query;

public final class CostCenterUsageReportQueries {
    private CostCenterUsageReportQueries() {
    } // Private constructor to prevent instantiation

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
            -- OPTIMIZATION 1: Pre-aggregate millions of calls into a tiny dataset by cost center
            employee_calls_agg AS (
                SELECT
                    e.cost_center_id,
                    COUNT(cr.id) FILTER (WHERE cr.is_incoming = true) AS incomingCallCount,
                    COUNT(cr.id) FILTER (WHERE cr.is_incoming = false) AS outgoingCallCount,
                    COALESCE(SUM(cr.duration), 0) AS totalDuration,
                    COALESCE(SUM(cr.billed_amount), 0.0) AS totalBilledAmount
                FROM
                    call_record cr
                INNER JOIN
                    employee e ON cr.employee_id = e.id
                WHERE
                    (cr.service_date BETWEEN :startDate AND :endDate)
                AND (cr.is_incoming = true OR (cr.is_incoming = false AND cr.operator_id > 0))
                GROUP BY
                    e.cost_center_id
            ),
            tree_data AS (
                SELECT
                    ct.display_cost_center_id AS costCenterId,
                    ct.display_origin_country_id AS originCountryId,
                    ct.display_cost_center_name,
                    ct.display_work_order,
                    -- Because we already COUNTED in employee_calls_agg, we now SUM those counts
                    COALESCE(SUM(eca.incomingCallCount), 0) AS incomingCallCount,
                    COALESCE(SUM(eca.outgoingCallCount), 0) AS outgoingCallCount,
                    COALESCE(SUM(eca.totalDuration), 0) AS totalDuration,
                    COALESCE(SUM(eca.totalBilledAmount), 0.0) AS totalBilledAmount
                FROM
                    employee_calls_agg eca
                INNER JOIN
                    cost_center_tree ct ON eca.cost_center_id = ct.id
                GROUP BY
                    ct.display_cost_center_id, ct.display_cost_center_name, ct.display_work_order, ct.display_origin_country_id
            ),
            no_cc_data AS (
                SELECT
                    0::bigint AS costCenterId,
                    -1::bigint AS originCountryId,
                    '[ Sin Centro de Costos ]' AS costCenterName,
                    COALESCE(SUM(eca.incomingCallCount), 0) AS incomingCallCount,
                    COALESCE(SUM(eca.outgoingCallCount), 0) AS outgoingCallCount,
                    COALESCE(SUM(eca.totalDuration), 0) AS totalDuration,
                    COALESCE(SUM(eca.totalBilledAmount), 0.0) AS totalBilledAmount
                FROM
                    employee_calls_agg eca
                WHERE
                    :#{#parentCostCenterId == null ? 1 : 0} = 1
                AND eca.cost_center_id IS NULL
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
                SELECT
                    costCenterId,
                    originCountryId,
                    -- OPTIMIZATION 2: Deferred string manipulation evaluated only on final mapped rows
                    CASE
                        WHEN COALESCE(TRIM(display_work_order), '') = '' THEN display_cost_center_name
                        ELSE TRIM(display_work_order) || ' - ' || display_cost_center_name
                    END AS costCenterName,
                    incomingCallCount,
                    outgoingCallCount,
                    totalDuration,
                    totalBilledAmount
                FROM tree_data
                
                UNION ALL
                
                SELECT 
                    costCenterId, originCountryId, costCenterName, 
                    incomingCallCount, outgoingCallCount, totalDuration, totalBilledAmount 
                FROM no_cc_data WHERE (incomingCallCount + outgoingCallCount) > 0
                
                UNION ALL
                
                SELECT 
                    costCenterId, originCountryId, costCenterName, 
                    incomingCallCount, outgoingCallCount, totalDuration, totalBilledAmount 
                FROM unrelated_data WHERE (incomingCallCount + outgoingCallCount) > 0
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
}