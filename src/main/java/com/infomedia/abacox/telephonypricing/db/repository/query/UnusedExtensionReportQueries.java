package com.infomedia.abacox.telephonypricing.db.repository.query;

public final class UnusedExtensionReportQueries {

    private UnusedExtensionReportQueries() {}

    /**
     * This query finds active employees whose extensions have had no call activity
     * within a given date range. It enriches the result with data from related entities
     * and calculates the last time the extension was ever used.
     *
     * How it works:
     * 1. It starts with the `employee` table and uses LEFT JOINs to connect to
     *    `subdivision`, `cost_center`, `communication_location`, and `indicator` to
     *    retrieve related names and details.
     * 2. A correlated subquery in the SELECT clause calculates `lastCallDate`. For each
     *    employee, it scans the entire `call_record` table to find the MAX(service_date)
     *    where the employee's extension was involved in any capacity. This gives the
     *    true last time the extension was used, regardless of the report's date range.
     * 3. The main WHERE clause filters for employees whose extensions are NOT IN the list
     *    of extensions active during the specified `:startDate` and `:endDate` period.
     *    This remains the core logic for identifying inactivity.
     */
    public static final String QUERY = """
        SELECT
            e.id as employeeId,
            e.name as employeeName,
            e.extension as extension,
            s.name as subdivisionName,
            cc.name as costCenterName,
            cc.work_order as costCenterWorkOrder,
            cl.directory as commLocationDirectory,
            i.department_country as indicatorDepartmentCountry,
            (
                SELECT MAX(all_calls.service_date)
                FROM (
                    SELECT cr.service_date FROM call_record cr WHERE cr.employee_extension = e.extension
                    UNION ALL
                    SELECT cr.service_date FROM call_record cr WHERE cr.dial = e.extension
                    UNION ALL
                    SELECT cr.service_date FROM call_record cr WHERE cr.employee_transfer = e.extension
                ) as all_calls
            ) as lastCallDate
        FROM
            employee e
            LEFT JOIN subdivision s ON e.subdivision_id = s.id
            LEFT JOIN cost_center cc ON e.cost_center_id = cc.id
            LEFT JOIN communication_location cl ON e.communication_location_id = cl.id
            LEFT JOIN indicator i ON cl.indicator_id = i.id
        WHERE
            e.active = true
            AND (e.name ILIKE CONCAT('%', :employeeName, '%') OR :employeeName IS NULL OR :employeeName = '')
            AND (e.extension ILIKE CONCAT('%', :extension, '%') OR :extension IS NULL OR :extension = '')
            AND e.extension NOT IN (
                SELECT active_extensions.extension FROM (
                    SELECT cr.employee_extension as extension FROM call_record cr
                    WHERE cr.service_date BETWEEN :startDate AND :endDate
                    AND cr.comm_location_id IS NOT NULL
                    AND cr.employee_extension IS NOT NULL AND cr.employee_extension != ''
                UNION
                    SELECT cr.dial as extension FROM call_record cr
                    WHERE cr.service_date BETWEEN :startDate AND :endDate
                    AND cr.comm_location_id IS NOT NULL
                    AND cr.dial IN (SELECT emp.extension FROM employee emp WHERE emp.extension IS NOT NULL AND emp.extension != '')
                UNION
                    SELECT cr.employee_transfer as extension FROM call_record cr
                    WHERE cr.service_date BETWEEN :startDate AND :endDate
                    AND cr.comm_location_id IS NOT NULL
                    AND cr.employee_transfer IN (SELECT emp.extension FROM employee emp WHERE emp.extension IS NOT NULL AND emp.extension != '')
                ) as active_extensions
            )
        """;

    public static final String COUNT_QUERY = """
        SELECT COUNT(*) FROM (
        """ + QUERY + """
        ) as count_wrapper
        """;
}