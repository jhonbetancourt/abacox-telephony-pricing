package com.infomedia.abacox.telephonypricing.db.repository.query;

public final class DestinationUsageReportQueries {
    private DestinationUsageReportQueries() {} // Private constructor to prevent instantiation

    public static final String QUERY = """
    SELECT
        ind_dest.city_name AS cityName,
        ind_dest.department_country AS departmentCountryName,
        tt.name AS telephonyTypeName,
        COUNT(cr.id) AS callCount,
        COALESCE(SUM(cr.duration), 0) AS totalDuration,
        COALESCE(SUM(cr.billed_amount), 0) AS totalBilledAmount
    FROM
        call_record cr
    INNER JOIN
        indicator ind_dest ON cr.indicator_id = ind_dest.id
    LEFT JOIN
        telephony_type tt ON ind_dest.telephony_type_id = tt.id
    WHERE
        (cr.service_date BETWEEN :startDate AND :endDate)
    GROUP BY
        ind_dest.city_name,
        ind_dest.department_country,
        ind_dest.id,
        tt.name
    """;

    public static final String COUNT_QUERY = """
    SELECT COUNT(*) FROM (
        SELECT
            ind_dest.id
        FROM
            call_record cr
        INNER JOIN
            indicator ind_dest ON cr.indicator_id = ind_dest.id
        LEFT JOIN
            telephony_type tt ON ind_dest.telephony_type_id = tt.id
        WHERE
            (cr.service_date BETWEEN :startDate AND :endDate)
        GROUP BY
            ind_dest.city_name,
            ind_dest.department_country,
            ind_dest.id,
            tt.name
    ) AS group_count
    """;
}