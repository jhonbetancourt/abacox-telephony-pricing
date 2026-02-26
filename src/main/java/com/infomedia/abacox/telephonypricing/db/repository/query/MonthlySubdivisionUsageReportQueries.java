package com.infomedia.abacox.telephonypricing.db.repository.query;

public final class MonthlySubdivisionUsageReportQueries {
    private MonthlySubdivisionUsageReportQueries() {
    } // Private constructor to prevent instantiation

    public static final String QUERY = """
            WITH RECURSIVE subdivision_hierarchy AS (
                -- Anchor: start from requested subdivisions OR roots if none provided
                SELECT
                    s.id,
                    s.id AS anchor_id,
                    s.name AS anchor_name
                FROM subdivision s
                WHERE
                    (:#{#subdivisionIds == null || #subdivisionIds.isEmpty() ? 1 : 0} = 1 AND s.parent_subdivision_id IS NULL)
                    OR s.id IN (:subdivisionIds)

                UNION ALL

                -- Recursive: follow parent-child links
                SELECT
                    s.id,
                    sh.anchor_id,
                    sh.anchor_name
                FROM subdivision s
                INNER JOIN subdivision_hierarchy sh ON s.parent_subdivision_id = sh.id
            ),
            employee_counts AS (
                SELECT
                    sh.anchor_id,
                    COUNT(*) AS totalEmployees
                FROM subdivision_hierarchy sh
                INNER JOIN employee e ON sh.id = e.subdivision_id
                WHERE (e.history_control_id IS NULL OR e.id IN (
                    SELECT hc.ref_id FROM history_control hc WHERE hc.ref_table = 1
                ))
                GROUP BY sh.anchor_id
            ),
            monthly_costs AS (
                SELECT
                    e.subdivision_id,
                    EXTRACT(YEAR FROM cr.service_date)::integer AS year,
                    EXTRACT(MONTH FROM cr.service_date)::integer AS month,
                    SUM(cr.billed_amount) AS totalBilledAmount
                FROM
                    call_record cr
                INNER JOIN employee e ON cr.employee_id = e.id
                INNER JOIN telephony_type tt ON cr.telephony_type_id = tt.id
                INNER JOIN communication_location cl ON cr.comm_location_id = cl.id
                INNER JOIN indicator i ON cl.indicator_id = i.id
                WHERE
                    cr.service_date BETWEEN :startDate AND :endDate
                    AND (e.history_control_id IS NULL OR e.id IN (
                        SELECT hc.ref_id FROM history_control hc WHERE hc.ref_table = 1
                    ))
                GROUP BY
                    e.subdivision_id, year, month
            ),
            date_range AS (
                SELECT
                    EXTRACT(YEAR FROM CAST(:startDate AS timestamp))::integer AS startYear,
                    EXTRACT(MONTH FROM CAST(:startDate AS timestamp))::integer AS startMonth
            ),
            aggregated_costs AS (
                SELECT
                    sh.anchor_id,
                    COALESCE(mc.year, dr.startYear) AS year,
                    COALESCE(mc.month, dr.startMonth) AS month,
                    COALESCE(SUM(mc.totalBilledAmount), 0) AS totalBilledAmount
                FROM
                    subdivision_hierarchy sh
                CROSS JOIN date_range dr
                LEFT JOIN
                    monthly_costs mc ON sh.id = mc.subdivision_id
                GROUP BY sh.anchor_id, sh.anchor_name, COALESCE(mc.year, dr.startYear), COALESCE(mc.month, dr.startMonth)
            ),
            report_data AS (
                SELECT
                    ac.anchor_id AS subdivisionId,
                    sh.anchor_name AS subdivisionName,
                    ac.year,
                    ac.month,
                    ac.totalBilledAmount
                FROM
                    aggregated_costs ac
                INNER JOIN subdivision_hierarchy sh ON ac.anchor_id = sh.anchor_id
                GROUP BY ac.anchor_id, sh.anchor_name, ac.year, ac.month, ac.totalBilledAmount
            )
            SELECT
                rd.subdivisionId,
                rd.subdivisionName,
                rd.year,
                rd.month,
                rd.totalBilledAmount
            FROM
                report_data rd
            INNER JOIN
                employee_counts ec ON rd.subdivisionId = ec.anchor_id
            WHERE
                ec.totalEmployees > 0
            ORDER BY
                rd.subdivisionName ASC, rd.year, rd.month
            """;

    public static final String COUNT_QUERY = """
            WITH RECURSIVE subdivision_hierarchy AS (
                SELECT
                    s.id,
                    s.id AS anchor_id
                FROM subdivision s
                WHERE
                    (:#{#subdivisionIds == null || #subdivisionIds.isEmpty() ? 1 : 0} = 1 AND s.parent_subdivision_id IS NULL)
                    OR s.id IN (:subdivisionIds)

                UNION ALL

                SELECT
                    s.id,
                    sh.anchor_id
                FROM subdivision s
                INNER JOIN subdivision_hierarchy sh ON s.parent_subdivision_id = sh.id
            ),
            employee_counts AS (
                SELECT
                    sh.anchor_id,
                    COUNT(*) AS totalEmployees
                FROM subdivision_hierarchy sh
                INNER JOIN employee e ON sh.id = e.subdivision_id
                WHERE (e.history_control_id IS NULL OR e.id IN (
                    SELECT hc.ref_id FROM history_control hc WHERE hc.ref_table = 1
                ))
                GROUP BY sh.anchor_id
            )
            SELECT COUNT(*) FROM employee_counts WHERE totalEmployees > 0
            """;
}
