package com.infomedia.abacox.telephonypricing.db.repository.query;

public final class UnusedExtensionReportQueries {

    private UnusedExtensionReportQueries() {}

    // The main query remains the same, as it is already well-structured.
    public static final String QUERY = """
        WITH UnusedExtensions AS (
            SELECT
                e.id as employeeId,
                e.name as employeeName,
                e.extension as extension,
                s.name as subdivisionName,
                cc.name as costCenterName,
                cc.work_order as costCenterWorkOrder,
                cl.directory as commLocationDirectory,
                i.department_country as indicatorDepartmentCountry,
                i.city_name as indicatorCityName,
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
        )
        SELECT * FROM UnusedExtensions
        """;

    /**
     * FIX APPLIED HERE:
     * The COUNT_QUERY is restructured to use the same CTE pattern as the main QUERY.
     * This isolates the complex filtering logic within the CTE and presents a very
     * simple final SELECT statement to the Spring Data parser, avoiding parsing errors.
     */
    public static final String COUNT_QUERY = """
        WITH UnusedExtensions AS (
            SELECT 1
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
        )
        SELECT COUNT(*) FROM UnusedExtensions
        """;
}