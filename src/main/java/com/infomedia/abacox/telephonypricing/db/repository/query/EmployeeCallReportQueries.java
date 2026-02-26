package com.infomedia.abacox.telephonypricing.db.repository.query;

public final class EmployeeCallReportQueries {
    private EmployeeCallReportQueries() {
    }

    public static final String QUERY = """
            WITH master_costs AS (
                SELECT
                    COALESCE(e.history_control_id, e.id) AS masterId,
                    SUM(CASE WHEN c.is_incoming = false THEN 1 ELSE 0 END) AS outgoingCalls,
                    SUM(CASE WHEN c.is_incoming = true THEN 1 ELSE 0 END) AS incomingCalls,
                    SUM(c.billed_amount) AS totalCost
                FROM
                    call_record c
                    JOIN employee e ON c.employee_id = e.id
                WHERE
                    c.service_date BETWEEN :startDate AND :endDate
                    AND c.billed_amount > 0
                    AND (c.is_incoming = true OR c.operator_id > 0)
                GROUP BY
                    COALESCE(e.history_control_id, e.id)
            ),
            latest_profiles AS (
                SELECT
                    COALESCE(history_control_id, id) AS masterId,
                    name,
                    extension,
                    communication_location_id
                FROM employee
                WHERE (history_control_id IS NULL OR id IN (SELECT hc.ref_id FROM history_control hc WHERE hc.ref_table = 1))
            )
            SELECT
                m.masterId AS employeeId,
                p.name AS employeeName,
                p.extension AS extension,
                i.city_name AS cityName,
                m.outgoingCalls,
                m.incomingCalls,
                m.totalCost
            FROM
                master_costs m
                JOIN latest_profiles p ON m.masterId = p.masterId
                LEFT JOIN communication_location cl ON p.communication_location_id = cl.id
                LEFT JOIN indicator i ON cl.indicator_id = i.id
            WHERE
                (:employeeName IS NULL OR p.name ILIKE CONCAT('%', :employeeName, '%'))
                AND (:employeeExtension IS NULL OR p.extension ILIKE CONCAT('%', :employeeExtension, '%'))
            ORDER BY m.totalCost DESC, p.name ASC
            """;

    public static final String BREAKDOWN_QUERY = """
            SELECT
                COALESCE(e.history_control_id, e.id) AS employeeId,
                tt.name AS telephonyTypeName,
                SUM(c.billed_amount) AS totalCost
            FROM
                call_record c
                JOIN employee e ON c.employee_id = e.id
                INNER JOIN telephony_type tt ON c.telephony_type_id = tt.id
            WHERE
                COALESCE(e.history_control_id, e.id) IN (:employeeIds)
                AND c.service_date BETWEEN :startDate AND :endDate
                AND c.billed_amount > 0
                AND (c.is_incoming = true OR c.operator_id > 0)
            GROUP BY
                COALESCE(e.history_control_id, e.id), tt.name
            """;

    public static final String COUNT_QUERY = """
            SELECT COUNT(*) FROM (
                SELECT COALESCE(e.history_control_id, e.id)
                FROM
                    call_record c JOIN employee e ON c.employee_id = e.id
                WHERE
                    c.service_date BETWEEN :startDate AND :endDate
                    AND c.billed_amount > 0
                    AND (c.is_incoming = true OR c.operator_id > 0)
                    AND (e.history_control_id IS NULL OR e.id IN (SELECT hc.ref_id FROM history_control hc WHERE hc.ref_table = 1))
                    AND (:employeeName IS NULL OR e.name ILIKE CONCAT('%', :employeeName, '%'))
                    AND (:employeeExtension IS NULL OR e.extension ILIKE CONCAT('%', :employeeExtension, '%'))
                GROUP BY
                    COALESCE(e.history_control_id, e.id)
            ) AS count_subquery
            """;
}