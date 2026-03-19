package com.infomedia.abacox.telephonypricing.db.repository.query;

public final class SubdivisionUsageByTypeReportQueries {
    private SubdivisionUsageByTypeReportQueries() {}

    /**
     * Matches the original PHP "Consolidado por Área" report logic:
     * 1. Uses a recursive CTE to build the subdivision tree (same as SubdivisionUsageReportQueries)
     * 2. Groups all descendant call data under the direct child subdivision (display_subdivision)
     * 3. Excludes subdivisions with 0 employees (matching PHP's tree filtering)
     * 4. Excludes rows with zero billed amount (matching PHP's "if($rs["FACTURADO"] > 0)")
     * 5. Returns one row per display subdivision with aggregated totals (breakdown by type done separately)
     */
    public static final String QUERY = """
            SELECT * FROM (
            WITH RECURSIVE subdivision_descendants AS (
                SELECT
                    s.id,
                    s.id AS display_subdivision_id,
                    s.name AS display_subdivision_name
                FROM subdivision s
                WHERE
                    (:#{#parentSubdivisionId == null ? 1 : 0} = 1 AND s.parent_subdivision_id IS NULL)
                    OR s.id = :parentSubdivisionId

                UNION ALL

                SELECT
                    s.id,
                    sd.display_subdivision_id,
                    sd.display_subdivision_name
                FROM subdivision s
                INNER JOIN subdivision_descendants sd ON s.parent_subdivision_id = sd.id
            ),
            employee_counts AS (
                SELECT
                    sd.display_subdivision_id,
                    COUNT(*) AS totalEmployees
                FROM subdivision_descendants sd
                INNER JOIN employee e ON sd.id = e.subdivision_id
                WHERE (e.history_control_id IS NULL OR e.id IN (
                    SELECT hc.ref_id FROM history_control hc WHERE hc.ref_table = 1
                ))
                GROUP BY sd.display_subdivision_id
            ),
            call_data AS (
                SELECT
                    sd.display_subdivision_id,
                    COALESCE(SUM(cr.billed_amount), 0) AS totalBilledAmount,
                    COALESCE(SUM(cr.duration), 0) AS totalDuration
                FROM call_record cr
                INNER JOIN employee e ON cr.employee_id = e.id
                INNER JOIN subdivision_descendants sd ON e.subdivision_id = sd.id
                WHERE cr.service_date BETWEEN :startDate AND :endDate
                  AND cr.billed_amount > 0
                GROUP BY sd.display_subdivision_id
            )
            SELECT
                ds.display_subdivision_id AS subdivisionId,
                ds.display_subdivision_name AS subdivisionName,
                COALESCE(cd.totalBilledAmount, 0) AS totalBilledAmount,
                COALESCE(cd.totalDuration, 0) AS totalDuration
            FROM (SELECT DISTINCT display_subdivision_id, display_subdivision_name FROM subdivision_descendants) ds
            LEFT JOIN call_data cd ON ds.display_subdivision_id = cd.display_subdivision_id
            LEFT JOIN employee_counts ec ON ds.display_subdivision_id = ec.display_subdivision_id
            WHERE COALESCE(ec.totalEmployees, 0) > 0
              AND COALESCE(cd.totalBilledAmount, 0) > 0
            ) AS ds
            """;

    /**
     * Breakdown query: given a list of display subdivision IDs, returns cost per telephony type
     * per subdivision, rolling up all descendants under their display subdivision.
     * Follows the same pattern as EmployeeCallReportQueries.BREAKDOWN_QUERY.
     */
    public static final String BREAKDOWN_QUERY = """
            WITH RECURSIVE subdivision_descendants AS (
                SELECT
                    s.id,
                    s.id AS display_subdivision_id
                FROM subdivision s
                WHERE s.id IN (:subdivisionIds)

                UNION ALL

                SELECT
                    s.id,
                    sd.display_subdivision_id
                FROM subdivision s
                INNER JOIN subdivision_descendants sd ON s.parent_subdivision_id = sd.id
            )
            SELECT
                sd.display_subdivision_id AS subdivisionId,
                tt.name AS telephonyTypeName,
                COALESCE(SUM(cr.billed_amount), 0) AS totalBilledAmount
            FROM call_record cr
            INNER JOIN employee e ON cr.employee_id = e.id
            INNER JOIN subdivision_descendants sd ON e.subdivision_id = sd.id
            INNER JOIN telephony_type tt ON cr.telephony_type_id = tt.id
            WHERE cr.service_date BETWEEN :startDate AND :endDate
              AND cr.billed_amount > 0
            GROUP BY sd.display_subdivision_id, tt.name
            ORDER BY sd.display_subdivision_id, tt.name
            """;
}
