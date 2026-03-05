package com.infomedia.abacox.telephonypricing.db.repository.query;

public final class HighestConsumptionEmployeeReportQueries {
    private HighestConsumptionEmployeeReportQueries() {}

    public static final String QUERY = """
    WITH report_data AS (
        SELECT
            e.name AS employeeName,
            e.extension AS extension,
            -- Complex string calculation happens here (fast, because grouping is done by ID below)
            CASE WHEN ind_origin.city_name != '' AND ind_origin.department_country != ''
                 THEN CONCAT(ind_origin.city_name, ' - ', ind_origin.department_country)
                 ELSE COALESCE(NULLIF(ind_origin.city_name,''), ind_origin.department_country)
            END AS originCity,
            COUNT(*) AS callCount,
            COALESCE(SUM(cr.duration), 0) AS totalDuration,
            COALESCE(SUM(cr.billed_amount), 0) AS totalBilledAmount
        FROM
            call_record cr
        INNER JOIN
            employee e ON cr.employee_id = e.id
        INNER JOIN
            communication_location cl ON cr.comm_location_id = cl.id
        LEFT JOIN
            indicator ind_origin ON cl.indicator_id = ind_origin.id
        WHERE
            cr.service_date BETWEEN :startDate AND :endDate
        AND
            cr.billed_amount > 0
        AND
            e.active = true
        GROUP BY
            -- OPTIMIZATION: We group by IDs/Raw columns, not the complex string
            e.id, 
            e.name, 
            e.extension,
            ind_origin.id,
            ind_origin.city_name, 
            ind_origin.department_country
    )
    SELECT
        employeeName,
        extension,
        originCity,
        callCount,
        totalDuration,
        totalBilledAmount
    FROM
        report_data
    """;
}