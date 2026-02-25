// src/main/java/com/infomedia/abacox/telephonypricing/db/repository/query/SubdivisionUsageReportQueries.java
package com.infomedia.abacox.telephonypricing.db.repository.query;

public final class SubdivisionUsageReportQueries {
    private SubdivisionUsageReportQueries() {
    } // Private constructor to prevent instantiation

    /**
     * Matches the original PHP "Consumo por Área" report logic:
     * 1. Uses a recursive CTE to build the subdivision tree (children + all
     * descendants)
     * 2. Groups all descendant data under the direct child subdivision
     * 3. Applies Historico_SQLActual filtering on employees:
     * (history_control_id IS NULL OR id IN (SELECT ref_id FROM history_control
     * WHERE ref_table = 1))
     * 4. Employee counts come from the tree structure (all descendants), not from
     * call data
     * 5. Subdivisions with 0 employees are excluded (matching PHP's "if
     * ($funcionarios <= 0) continue")
     */
    public static final String QUERY = """
            WITH RECURSIVE subdivision_descendants AS (
                SELECT
                    s.id,
                    s.id AS display_subdivision_id,
                    s.name AS display_subdivision_name
                FROM subdivision s
                WHERE
                    (:#{#parentSubdivisionId == null ? 1 : 0} = 1 AND s.parent_subdivision_id IS NULL)
                    OR s.parent_subdivision_id = :parentSubdivisionId

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
                    e.subdivision_id,
                    COUNT(cr.id) FILTER (WHERE cr.is_incoming = true) AS incomingCallCount,
                    COUNT(cr.id) FILTER (WHERE cr.is_incoming = false) AS outgoingCallCount,
                    COALESCE(SUM(cr.duration), 0) AS totalDuration,
                    COALESCE(SUM(cr.billed_amount), 0) AS totalBilledAmount
                FROM call_record cr
                INNER JOIN employee e ON cr.employee_id = e.id
                INNER JOIN telephony_type tt ON cr.telephony_type_id = tt.id
                INNER JOIN communication_location cl ON cr.comm_location_id = cl.id
                INNER JOIN indicator i ON cl.indicator_id = i.id
                WHERE cr.service_date BETWEEN :startDate AND :endDate
                GROUP BY e.subdivision_id
            ),
            report_data AS (
                SELECT
                    sd.display_subdivision_id AS subdivisionId,
                    sd.display_subdivision_name AS subdivisionName,
                    COALESCE(SUM(cd.incomingCallCount), 0) AS incomingCallCount,
                    COALESCE(SUM(cd.outgoingCallCount), 0) AS outgoingCallCount,
                    COALESCE(SUM(cd.totalDuration), 0) AS totalDuration,
                    COALESCE(SUM(cd.totalBilledAmount), 0) AS totalBilledAmount
                FROM
                    subdivision_descendants sd
                LEFT JOIN
                    call_data cd ON sd.id = cd.subdivision_id
                GROUP BY
                    sd.display_subdivision_id, sd.display_subdivision_name
            )
            SELECT
                rd.subdivisionId,
                rd.subdivisionName,
                COALESCE(ec.totalEmployees, 0) AS totalEmployees,
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
            LEFT JOIN
                employee_counts ec ON rd.subdivisionId = ec.display_subdivision_id
            WHERE COALESCE(ec.totalEmployees, 0) > 0
            ORDER BY rd.subdivisionName ASC
            """;

    public static final String COUNT_QUERY = """
            WITH RECURSIVE subdivision_descendants AS (
                SELECT
                    s.id,
                    s.id AS display_subdivision_id
                FROM subdivision s
                WHERE
                    (:#{#parentSubdivisionId == null ? 1 : 0} = 1 AND s.parent_subdivision_id IS NULL)
                    OR s.parent_subdivision_id = :parentSubdivisionId

                UNION ALL

                SELECT
                    s.id,
                    sd.display_subdivision_id
                FROM subdivision s
                INNER JOIN subdivision_descendants sd ON s.parent_subdivision_id = sd.id
            )
            SELECT COUNT(*) FROM (
                SELECT sd.display_subdivision_id
                FROM subdivision_descendants sd
                INNER JOIN employee e ON sd.id = e.subdivision_id
                WHERE (e.history_control_id IS NULL OR e.id IN (
                    SELECT hc.ref_id FROM history_control hc WHERE hc.ref_table = 1
                ))
                GROUP BY sd.display_subdivision_id
                HAVING COUNT(*) > 0
            ) AS counted
            """;
}