package com.infomedia.abacox.telephonypricing.db.repository.query;

public final class ExtensionGroupReportQueries {

    private ExtensionGroupReportQueries() {
    }

    /**
     * This query generates a performance scorecard for a given list of employees.
     *
     * How it works:
     * 1. A Common Table Expression (CTE) named `employee_calls` is created first.
     * This CTE uses a UNION ALL to gather all relevant calls in one place:
     * a) Outgoing calls made by the specified employees.
     * b) Incoming calls received by the specified employees' extensions.
     * Crucially, it identifies the single 'responsible' employee ID for each call
     * record.
     * This CTE is where all primary filtering (dates, extensions, optional
     * operators) happens,
     * making the main query more efficient.
     *
     * 2. The main query starts with the `employee` table and LEFT JOINs the
     * `employee_calls` CTE.
     * A LEFT JOIN ensures that employees from the list who had zero calls still
     * appear in the report.
     *
     * 3. It uses conditional aggregation (COUNT with FILTER) to calculate the
     * metrics:
     * - `incomingCount`: Counts calls where is_incoming = true.
     * - `outgoingCount`: Counts calls where is_incoming = false.
     * - `voicemailCount`: Counts outgoing calls made to the specified voicemail
     * number.
     *
     * 4. A window function `SUM(...) OVER ()` is used to calculate the grand total
     * of all calls
     * in the result set. This is used to compute the `percent` contribution for
     * each employee
     * without needing a second query.
     *
     * 5. Spring Expression Language (SpEL) is used for the optional operatorIds
     * parameter:
     * `:#{#operatorIds == null || #operatorIds.isEmpty()}` dynamically includes or
     * excludes
     * the operator filtering clause.
     */
    public static final String QUERY = """
            WITH employee_calls AS (
                SELECT
                    cr.id,
                    cr.is_incoming,
                    cr.destination_phone,
                    e.id as employee_id
                FROM
                    employee e
                JOIN
                    call_record cr ON cr.employee_id = e.id
                WHERE
                    e.extension IN (:extensions)
                    AND cr.service_date BETWEEN :startDate AND :endDate
                    AND cr.assignment_cause = 0
                    AND ( (:#{#operatorIds == null || #operatorIds.isEmpty()}) = true OR cr.operator_id IN (:operatorIds) )

                UNION ALL

                SELECT
                    cr.id,
                    CAST(true AS boolean) as is_incoming,
                    cr.destination_phone,
                    e.id as employee_id
                FROM
                    employee e
                JOIN
                    call_record cr ON cr.destination_phone = e.extension
                WHERE
                    e.extension IN (:extensions)
                    AND cr.service_date BETWEEN :startDate AND :endDate
                    AND cr.is_incoming = false
                    AND cr.assignment_cause = 0
                    AND ( (:#{#operatorIds == null || #operatorIds.isEmpty()}) = true OR cr.operator_id IN (:operatorIds) )
            )
            SELECT
                e.id as employeeId,
                e.name as employeeName,
                e.extension as extension,
                s.name as subdivisionName,
                CASE WHEN i.city_name != '' AND i.department_country != ''
                     THEN CONCAT(i.city_name, ' - ', i.department_country)
                     ELSE COALESCE(NULLIF(i.city_name,''), i.department_country)
                END AS city,
                CAST(COALESCE(COUNT(c.id) FILTER (WHERE c.is_incoming = true), 0) AS INTEGER) as incomingCount,
                CAST(COALESCE(COUNT(c.id) FILTER (WHERE c.is_incoming = false), 0) AS INTEGER) as outgoingCount,
                CAST(COALESCE(COUNT(c.id) FILTER (WHERE c.is_incoming = false AND c.destination_phone = :voicemailNumber), 0) AS INTEGER) as voicemailCount,
                CAST(COALESCE(COUNT(c.id), 0) AS INTEGER) as total,
                CASE
                    WHEN SUM(COUNT(c.id)) OVER () = 0 THEN 0.00
                    ELSE ROUND( (COUNT(c.id) * 100.0) / SUM(COUNT(c.id)) OVER (), 2)
                END as percent
            FROM
                employee e
                LEFT JOIN employee_calls c ON e.id = c.employee_id
                LEFT JOIN subdivision s ON e.subdivision_id = s.id
                LEFT JOIN communication_location cl ON e.communication_location_id = cl.id
                LEFT JOIN indicator i ON cl.indicator_id = i.id
            WHERE
                e.extension IN (:extensions)
                AND (e.history_control_id IS NULL OR e.id IN (SELECT hc.ref_id FROM history_control hc WHERE hc.ref_table = 1))
            GROUP BY
                e.id, s.name, i.city_name, i.department_country
            ORDER BY e.extension
            """;

    public static final String COUNT_QUERY = """
            SELECT COUNT(DISTINCT e.id)
            FROM employee e
            WHERE e.extension IN (:extensions)
            AND (e.history_control_id IS NULL OR e.id IN (SELECT hc.ref_id FROM history_control hc WHERE hc.ref_table = 1))
            """;
}