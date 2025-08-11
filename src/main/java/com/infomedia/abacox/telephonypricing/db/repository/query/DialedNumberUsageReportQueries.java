package com.infomedia.abacox.telephonypricing.db.repository.query;

public final class DialedNumberUsageReportQueries {
    private DialedNumberUsageReportQueries() {} // Private constructor to prevent instantiation

    public static final String QUERY = """
    SELECT
        cr.dial AS phoneNumber,
        co.name AS companyName,
        c.name AS contactName,
        ind_dest.city_name AS destinationCity,
        ind_dest.department_country AS destinationCountry,
        COUNT(cr.id) AS callCount,
        COALESCE(SUM(cr.duration), 0) AS totalDuration,
        COALESCE(SUM(cr.billed_amount), 0) AS totalBilledAmount
    FROM
        call_record cr
    LEFT JOIN
        contact c ON cr.dial IS NOT NULL AND cr.dial != '' AND cr.dial = c.phone_number
    LEFT JOIN
        company co ON c.company_id = co.id
    INNER JOIN
        indicator ind_dest ON cr.indicator_id = ind_dest.id
    INNER JOIN
        employee e_caller ON cr.employee_id = e_caller.id
    WHERE
        (cr.service_date BETWEEN :startDate AND :endDate)
    GROUP BY
        cr.dial,
        co.name,
        c.name,
        ind_dest.city_name,
        ind_dest.department_country
    """;

    public static final String COUNT_QUERY = """
    SELECT COUNT(*) FROM (
        SELECT
            cr.dial
        FROM
            call_record cr
        LEFT JOIN
            contact c ON cr.dial IS NOT NULL AND cr.dial != '' AND cr.dial = c.phone_number
        LEFT JOIN
            company co ON c.company_id = co.id
        INNER JOIN
            indicator ind_dest ON cr.indicator_id = ind_dest.id
        INNER JOIN
            employee e_caller ON cr.employee_id = e_caller.id
        WHERE
            (cr.service_date BETWEEN :startDate AND :endDate)
        GROUP BY
            cr.dial,
            co.name,
            c.name,
            ind_dest.city_name,
            ind_dest.department_country
    ) AS group_count
    """;
}