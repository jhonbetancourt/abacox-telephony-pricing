package com.infomedia.abacox.telephonypricing.db.repository.query;

public final class MonthlySubdivisionUsageReportQueries {
    private MonthlySubdivisionUsageReportQueries() {
    } // Private constructor to prevent instantiation

    public static final String QUERY = """
            WITH RECURSIVE subdivision_hierarchy AS (
                -- Anchor: start from requested subdivisions OR roots if none provided
                SELECT
                    id,
                    id AS anchor_id,
                    name AS anchor_name
                FROM subdivision
                WHERE
                    (:#{#subdivisionIds == null || #subdivisionIds.isEmpty() ? 1 : 0} = 1 AND parent_subdivision_id IS NULL)
                    OR id IN (:subdivisionIds)

                UNION ALL

                -- Recursive: follow parent-child links
                SELECT
                    s.id,
                    sh.anchor_id,
                    sh.anchor_name
                FROM subdivision s
                INNER JOIN subdivision_hierarchy sh ON s.parent_subdivision_id = sh.id
            ),
            monthly_costs AS (
                SELECT
                    sh.anchor_id AS subdivisionId,
                    sh.anchor_name AS subdivisionName,
                    EXTRACT(YEAR FROM cr.service_date)::integer AS year,
                    EXTRACT(MONTH FROM cr.service_date)::integer AS month,
                    SUM(cr.billed_amount) AS totalBilledAmount
                FROM
                    call_record cr
                JOIN
                    employee e ON cr.employee_id = e.id
                JOIN
                    subdivision_hierarchy sh ON e.subdivision_id = sh.id
                WHERE
                    cr.service_date BETWEEN :startDate AND :endDate
                GROUP BY
                    sh.anchor_id, sh.anchor_name, year, month
            )
            SELECT
                subdivisionId,
                subdivisionName,
                year,
                month,
                totalBilledAmount
            FROM
                monthly_costs
            ORDER BY
                subdivisionId, year, month
            """;

    public static final String COUNT_QUERY = """
            SELECT COUNT(*) FROM (
                SELECT id FROM subdivision
                WHERE
                    (:#{#subdivisionIds == null || #subdivisionIds.isEmpty() ? 1 : 0} = 1 AND parent_subdivision_id IS NULL)
                    OR id IN (:subdivisionIds)
            ) AS count_subquery
            """;
}