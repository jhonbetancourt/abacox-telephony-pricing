package com.infomedia.abacox.telephonypricing.db.repository.query;

public final class DialedNumberUsageReportQueries {
    private DialedNumberUsageReportQueries() {
    } // Private constructor to prevent instantiation

    public static final String QUERY = """
            WITH report_data AS (
                SELECT
                    cr.dial AS phoneNumber,
                    co.name AS companyName,
                    c.name AS contactName,
                    CASE WHEN ind_dest.city_name != '' AND ind_dest.department_country != ''
                         THEN CONCAT(ind_dest.city_name, ' - ', ind_dest.department_country)
                         ELSE COALESCE(NULLIF(ind_dest.city_name,''), ind_dest.department_country)
                    END AS city,
                    COUNT(cr.id) AS callCount,
                    COALESCE(SUM(cr.duration), 0) AS totalDuration,
                    COALESCE(SUM(cr.billed_amount), 0) AS totalBilledAmount,
                    emp_dest.name AS destinationEmployeeName,
                    emp_dest.subdivision_id AS destinationSubdivisionId
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
                LEFT JOIN
                    employee emp_dest ON cr.destination_employee_id = emp_dest.id
                WHERE
                    (cr.service_date BETWEEN :startDate AND :endDate)
                GROUP BY
                    cr.dial,
                    co.name,
                    c.name,
                    CASE WHEN ind_dest.city_name != '' AND ind_dest.department_country != ''
                         THEN CONCAT(ind_dest.city_name, ' - ', ind_dest.department_country)
                         ELSE COALESCE(NULLIF(ind_dest.city_name,''), ind_dest.department_country)
                    END,
                    emp_dest.name,
                    emp_dest.subdivision_id
            )
            SELECT
                phoneNumber,
                companyName,
                contactName,
                city,
                callCount,
                totalDuration,
                totalBilledAmount,
                destinationEmployeeName,
                destinationSubdivisionId
            FROM
                report_data
            ORDER BY totalBilledAmount DESC, totalDuration DESC
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
                LEFT JOIN
                    employee emp_dest ON cr.destination_employee_id = emp_dest.id
                WHERE
                    (cr.service_date BETWEEN :startDate AND :endDate)
                GROUP BY
                    cr.dial,
                    co.name,
                    c.name,
                    CASE WHEN ind_dest.city_name != '' AND ind_dest.department_country != ''
                         THEN CONCAT(ind_dest.city_name, ' - ', ind_dest.department_country)
                         ELSE COALESCE(NULLIF(ind_dest.city_name,''), ind_dest.department_country)
                    END,
                    emp_dest.name,
                    emp_dest.subdivision_id
            ) AS group_count
            """;
}