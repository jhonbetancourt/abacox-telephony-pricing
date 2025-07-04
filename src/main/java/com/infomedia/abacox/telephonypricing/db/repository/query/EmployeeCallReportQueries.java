package com.infomedia.abacox.telephonypricing.db.repository.query;

public final class EmployeeCallReportQueries {
    private EmployeeCallReportQueries() {}

    public static final String QUERY = """
    SELECT
        e.id AS employeeId,
        e.name AS employeeName,
        SUM(CASE WHEN c.is_incoming = false THEN 1 ELSE 0 END) AS outgoingCalls,
        SUM(CASE WHEN c.is_incoming = true THEN 1 ELSE 0 END) AS incomingCalls,
        SUM(c.billed_amount) AS totalCost
    FROM
        call_record c JOIN employee e ON c.employee_id = e.id
    WHERE
        e.active = true
        AND c.employee_id IS NOT NULL
        AND c.billed_amount > 0
        AND c.service_date BETWEEN :startDate AND :endDate
        AND (:employeeName IS NULL OR e.name ILIKE CONCAT('%', :employeeName, '%'))
        AND (:employeeExtension IS NULL OR e.extension ILIKE CONCAT('%', :employeeExtension, '%'))
    GROUP BY
        e.id, e.name
    """;

    public static final String COUNT_QUERY = """
    SELECT COUNT(*) FROM (
        SELECT e.id
        FROM
            call_record c JOIN employee e ON c.employee_id = e.id
        WHERE
            e.active = true
            AND c.employee_id IS NOT NULL
            AND c.billed_amount > 0
            AND c.service_date BETWEEN :startDate AND :endDate
            AND (:employeeName IS NULL OR e.name ILIKE CONCAT('%', :employeeName, '%'))
            AND (:employeeExtension IS NULL OR e.extension ILIKE CONCAT('%', :employeeExtension, '%'))
        GROUP BY
            e.id
    ) AS count_subquery
    """;
}