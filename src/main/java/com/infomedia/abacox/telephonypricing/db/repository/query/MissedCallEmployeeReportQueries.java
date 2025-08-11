package com.infomedia.abacox.telephonypricing.db.repository.query;

public final class MissedCallEmployeeReportQueries {
    private MissedCallEmployeeReportQueries() {}

    public static final String QUERY = """
    WITH matched_calls AS (
        SELECT cr.id as call_id, e.id as employee_id
        FROM call_record cr
        JOIN employee e ON cr.employee_id = e.id
        WHERE cr.service_date BETWEEN :startDate AND :endDate
          AND cr.ring_count >= 15
          AND cr.is_incoming = true
          AND cr.duration = 0
          AND e.active = true

        UNION

        SELECT cr.id as call_id, e.id as employee_id
        FROM call_record cr
        JOIN employee e ON e.extension = cr.employee_transfer
        WHERE cr.service_date BETWEEN :startDate AND :endDate
          AND cr.ring_count >= 15
          AND e.active = true
          AND (
               (cr.employee_transfer IN (SELECT se.extension FROM special_extension se WHERE se.type = 1) AND cr.operator_id <> 59)
               OR
               (cr.dial IN (SELECT se.extension FROM special_extension se WHERE se.type = 1) AND cr.operator_id = 59)
          )

        UNION

        SELECT cr.id as call_id, e.id as employee_id
        FROM call_record cr
        JOIN employee e ON e.extension = cr.dial
        WHERE cr.service_date BETWEEN :startDate AND :endDate
          AND cr.ring_count >= 15
          AND e.active = true
          AND cr.operator_id = 59
          AND (
               cr.duration = 0
               OR
               cr.employee_transfer IN (SELECT se.extension FROM special_extension se WHERE se.type = 1)
          )
    )
    SELECT
        e.id as employeeId,
        e.name as employeeName,
        e.extension as employeeExtension,
        COUNT(mc.call_id) as missedCallCount
    FROM matched_calls mc
    JOIN employee e ON mc.employee_id = e.id
    WHERE (:employeeName IS NULL OR e.name ILIKE CONCAT('%', :employeeName, '%'))
    GROUP BY e.id, e.name, e.extension
    """;

    public static final String COUNT_QUERY = """
    WITH matched_calls AS (
        SELECT e.id as employee_id
        FROM call_record cr JOIN employee e ON cr.employee_id = e.id
        WHERE cr.service_date BETWEEN :startDate AND :endDate AND cr.ring_count >= 15 AND cr.is_incoming = true AND cr.duration = 0 AND e.active = true
        UNION
        SELECT e.id as employee_id
        FROM call_record cr JOIN employee e ON e.extension = cr.employee_transfer
        WHERE cr.service_date BETWEEN :startDate AND :endDate AND cr.ring_count >= 15 AND e.active = true
          AND ((cr.employee_transfer IN (SELECT se.extension FROM special_extension se WHERE se.type = 1) AND cr.operator_id <> 59) OR (cr.dial IN (SELECT se.extension FROM special_extension se WHERE se.type = 1) AND cr.operator_id = 59))
        UNION
        SELECT e.id as employee_id
        FROM call_record cr JOIN employee e ON e.extension = cr.dial
        WHERE cr.service_date BETWEEN :startDate AND :endDate AND cr.ring_count >= 15 AND e.active = true AND cr.operator_id = 59
          AND (cr.duration = 0 OR cr.employee_transfer IN (SELECT se.extension FROM special_extension se WHERE se.type = 1))
    )
    SELECT COUNT(DISTINCT e.id)
    FROM matched_calls mc
    JOIN employee e ON mc.employee_id = e.id
    WHERE (:employeeName IS NULL OR e.name ILIKE CONCAT('%', :employeeName, '%'))
    """;
}