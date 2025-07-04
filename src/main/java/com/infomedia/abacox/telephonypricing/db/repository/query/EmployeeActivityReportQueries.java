package com.infomedia.abacox.telephonypricing.db.repository.query;

public final class EmployeeActivityReportQueries {
    private EmployeeActivityReportQueries() {} // Private constructor to prevent instantiation

    public static final String QUERY = """
    WITH call_aggregates_in_range AS (
        SELECT
            cr.employee_extension AS extension,
            COUNT(*) FILTER (WHERE cr.is_incoming = false) AS outgoing_calls,
            COUNT(*) FILTER (WHERE cr.is_incoming = true) AS incoming_calls,
            MAX(cr.service_date) FILTER (WHERE cr.is_incoming = false) AS last_outgoing,
            MAX(cr.service_date) FILTER (WHERE cr.is_incoming = true) AS last_incoming
        FROM call_record cr
        WHERE cr.service_date BETWEEN :startDate AND :endDate
        GROUP BY cr.employee_extension
    ),
    latest_employees AS (
        SELECT DISTINCT ON (e.extension)
            e.id, e.name, e.extension, e.cost_center_id, e.subdivision_id
        FROM employee e
        WHERE e.active = true
        ORDER BY e.extension, e.id DESC
    )
    SELECT
        f.id AS employeeId,
        f.name AS employeeName,
        f.extension AS extension,
        cc.work_order AS costCenterWorkOrder,
        sub.name AS subdivisionName,
        (
            SELECT i.department_country || ' / ' || i.city_name || '  /  ' || od.address
            FROM office_details od
            JOIN indicator i ON od.indicator_id = i.id
            WHERE od.subdivision_id = f.subdivision_id
            LIMIT 1
        ) AS officeLocation,
        COALESCE(agg.outgoing_calls, 0) AS outgoingCallCount,
        COALESCE(agg.incoming_calls, 0) AS incomingCallCount,
        agg.last_incoming AS lastIncomingCallDate,
        agg.last_outgoing AS lastOutgoingCallDate
    FROM latest_employees f
    LEFT JOIN call_aggregates_in_range agg ON f.extension = agg.extension
    LEFT JOIN cost_center cc ON f.cost_center_id = cc.id
    LEFT JOIN subdivision sub ON f.subdivision_id = sub.id
    WHERE
        (:employeeName IS NULL OR f.name ILIKE CONCAT('%', :employeeName, '%'))
    AND
        (:extension IS NULL OR f.extension ILIKE CONCAT('%', :extension, '%'))
    """;

    public static final String COUNT_QUERY = """
    WITH latest_employees AS (
        SELECT DISTINCT ON (e.extension) e.name, e.extension
        FROM employee e
        WHERE e.active = true
        ORDER BY e.extension, e.id DESC
    )
    SELECT COUNT(*)
    FROM latest_employees f
    WHERE
        (:employeeName IS NULL OR f.name ILIKE CONCAT('%', :employeeName, '%'))
    AND
        (:extension IS NULL OR f.extension ILIKE CONCAT('%', :extension, '%'))
    """;
}