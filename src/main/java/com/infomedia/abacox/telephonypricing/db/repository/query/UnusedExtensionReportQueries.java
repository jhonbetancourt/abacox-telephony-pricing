package com.infomedia.abacox.telephonypricing.db.repository.query;

public final class UnusedExtensionReportQueries {

    private UnusedExtensionReportQueries() {
    }

    public static final String QUERY = """
            WITH RECURSIVE CostCenterPath AS (
                SELECT
                    cc.id as start_id,
                    cc.parent_cost_center_id,
                    CAST('' AS TEXT) as parent_path,
                    1 as level
                FROM cost_center cc
                UNION ALL
                SELECT
                    ccp.start_id,
                    p.parent_cost_center_id,
                    CASE
                        WHEN ccp.parent_path = '' THEN CONCAT(p.work_order, ' - ', p.name)
                        ELSE CONCAT(p.work_order, ' - ', p.name, ' / ', ccp.parent_path)
                    END,
                    ccp.level + 1
                FROM CostCenterPath ccp
                JOIN cost_center p ON ccp.parent_cost_center_id = p.id
                WHERE ccp.parent_cost_center_id IS NOT NULL
            ),
            CostCenterHierarchy AS (
                SELECT start_id, parent_path
                FROM CostCenterPath
                WHERE parent_cost_center_id IS NULL
            ),
            ActiveExtensions AS (
                SELECT cr.employee_extension as extension FROM call_record cr
                WHERE cr.service_date BETWEEN :startDate AND :endDate
                  AND cr.comm_location_id IS NOT NULL
                  AND cr.employee_extension IS NOT NULL AND cr.employee_extension != ''
                UNION
                SELECT cr.dial FROM call_record cr
                WHERE cr.service_date BETWEEN :startDate AND :endDate
                  AND cr.comm_location_id IS NOT NULL
                  AND cr.dial IS NOT NULL AND cr.dial != ''
                UNION
                SELECT cr.employee_transfer FROM call_record cr
                WHERE cr.service_date BETWEEN :startDate AND :endDate
                  AND cr.comm_location_id IS NOT NULL
                  AND cr.employee_transfer IS NOT NULL AND cr.employee_transfer != ''
            ),
            FilteredEmployees AS (
                SELECT DISTINCT ON (e.extension)
                    e.id,
                    e.name,
                    e.extension,
                    e.history_since,
                    e.subdivision_id,
                    e.cost_center_id,
                    e.communication_location_id
                FROM employee e
                LEFT JOIN ActiveExtensions ae ON e.extension = ae.extension
                WHERE
                    ae.extension IS NULL
                    AND (
                        e.history_control_id = 0
                        OR e.history_control_id IS NULL
                        OR EXISTS (
                            SELECT 1 FROM history_control hc
                            WHERE hc.ref_table = 1 AND hc.ref_id = e.id
                        )
                    )
                    AND (:employeeName IS NULL OR :employeeName = '' OR e.name ILIKE CONCAT('%', :employeeName, '%'))
                    AND (:extension IS NULL OR :extension = '' OR e.extension ILIKE CONCAT('%', :extension, '%'))
                ORDER BY e.extension, e.id DESC
            ),
            CallHistory AS (
                SELECT
                    extension,
                    COUNT(*) as total,
                    SUM(incoming_val) as incoming,
                    MAX(service_date) as last_call_date
                FROM (
                    SELECT cr.employee_extension as extension, cr.service_date,
                           (CASE WHEN cr.is_incoming THEN 1 ELSE 0 END) as incoming_val
                    FROM call_record cr
                    WHERE cr.service_date < :startDate
                      AND cr.employee_extension IN (SELECT fe.extension FROM FilteredEmployees fe)
                    UNION ALL
                    SELECT cr.dial, cr.service_date, 1
                    FROM call_record cr
                    WHERE cr.service_date < :startDate
                      AND cr.dial IN (SELECT fe.extension FROM FilteredEmployees fe)
                    UNION ALL
                    SELECT cr.employee_transfer, cr.service_date, 1
                    FROM call_record cr
                    WHERE cr.service_date < :startDate
                      AND cr.employee_transfer IN (SELECT fe.extension FROM FilteredEmployees fe)
                ) as all_calls
                WHERE extension IS NOT NULL AND extension != ''
                GROUP BY extension
            ),
            UnusedExtensions AS (
                SELECT
                    fe.id as employeeId,
                    fe.name as employeeName,
                    fe.extension as extension,
                    fe.history_since as employeeHistoryStartDate,
                    s.name as subdivisionName,
                    CASE
                        WHEN cch.parent_path IS NOT NULL AND cch.parent_path != '' THEN
                            CONCAT(cc.work_order, ' - ', cc.name, ' (', cch.parent_path, ')')
                        ELSE
                            CONCAT(cc.work_order, ' - ', cc.name)
                    END as costCenter,
                    CONCAT('[ ', cl.directory, ' ] ', i.city_name, ' - ', i.department_country) as plant,
                    ch.last_call_date as lastCallDate,
                    COALESCE(ch.incoming, 0) as incomingCalls,
                    COALESCE(ch.total, 0) - COALESCE(ch.incoming, 0) as outgoingCalls,
                    COALESCE(ch.total, 0) as totalCalls
                FROM FilteredEmployees fe
                LEFT JOIN CallHistory ch ON fe.extension = ch.extension
                LEFT JOIN subdivision s ON fe.subdivision_id = s.id
                LEFT JOIN cost_center cc ON fe.cost_center_id = cc.id
                LEFT JOIN CostCenterHierarchy cch ON cc.id = cch.start_id
                LEFT JOIN communication_location cl ON fe.communication_location_id = cl.id
                LEFT JOIN indicator i ON cl.indicator_id = i.id
            )
            SELECT * FROM UnusedExtensions
            """;

    public static final String COUNT_QUERY = """
            WITH ActiveExtensions AS (
                SELECT cr.employee_extension as extension FROM call_record cr
                WHERE cr.service_date BETWEEN :startDate AND :endDate
                  AND cr.comm_location_id IS NOT NULL
                  AND cr.employee_extension IS NOT NULL AND cr.employee_extension != ''
                UNION
                SELECT cr.dial FROM call_record cr
                WHERE cr.service_date BETWEEN :startDate AND :endDate
                  AND cr.comm_location_id IS NOT NULL
                  AND cr.dial IS NOT NULL AND cr.dial != ''
                UNION
                SELECT cr.employee_transfer FROM call_record cr
                WHERE cr.service_date BETWEEN :startDate AND :endDate
                  AND cr.comm_location_id IS NOT NULL
                  AND cr.employee_transfer IS NOT NULL AND cr.employee_transfer != ''
            ),
            UnusedExtensions AS (
                SELECT DISTINCT e.extension
                FROM employee e
                LEFT JOIN ActiveExtensions ae ON e.extension = ae.extension
                WHERE
                    ae.extension IS NULL
                    AND (
                        e.history_control_id = 0
                        OR e.history_control_id IS NULL
                        OR EXISTS (
                            SELECT 1 FROM history_control hc
                            WHERE hc.ref_table = 1 AND hc.ref_id = e.id
                        )
                    )
                    AND (:employeeName IS NULL OR :employeeName = '' OR e.name ILIKE CONCAT('%', :employeeName, '%'))
                    AND (:extension IS NULL OR :extension = '' OR e.extension ILIKE CONCAT('%', :extension, '%'))
            )
            SELECT COUNT(*) FROM UnusedExtensions
            """;
}