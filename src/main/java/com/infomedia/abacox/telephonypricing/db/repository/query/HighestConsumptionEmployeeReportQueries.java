package com.infomedia.abacox.telephonypricing.db.repository.query;

public final class HighestConsumptionEmployeeReportQueries {
    private HighestConsumptionEmployeeReportQueries() {} // Private constructor to prevent instantiation

    public static final String QUERY = """
    SELECT
        e.name AS employeeName,
        e.extension AS extension,
        ind_origin.city_name AS originCity,
        ind_origin.department_country AS originDepartmentCountry,
        COUNT(cr.id) AS callCount,
        COALESCE(SUM(cr.duration), 0) AS totalDuration,
        COALESCE(SUM(cr.billed_amount), 0) AS totalBilledAmount
    FROM
        call_record cr
    INNER JOIN
        employee e ON cr.employee_id = e.id
    INNER JOIN
        communication_location cl ON cr.comm_location_id = cl.id
    LEFT JOIN -- Use LEFT JOIN in case a location has no indicator assigned
        indicator ind_origin ON cl.indicator_id = ind_origin.id
    WHERE
        (cr.service_date BETWEEN :startDate AND :endDate)
    AND
        cr.billed_amount > 0
    AND
        e.active = true
    GROUP BY
        e.id, e.name, e.extension,
        ind_origin.city_name, ind_origin.department_country
    """;

    public static final String COUNT_QUERY = """
    SELECT COUNT(*) FROM (
        SELECT
            e.id
        FROM
            call_record cr
        INNER JOIN
            employee e ON cr.employee_id = e.id
        INNER JOIN
            communication_location cl ON cr.comm_location_id = cl.id
        LEFT JOIN
            indicator ind_origin ON cl.indicator_id = ind_origin.id
        WHERE
            (cr.service_date BETWEEN :startDate AND :endDate)
        AND
            cr.billed_amount > 0
        AND
            e.active = true
        GROUP BY
            e.id, e.name, e.extension,
            ind_origin.city_name, ind_origin.department_country
    ) AS group_count
    """;
}