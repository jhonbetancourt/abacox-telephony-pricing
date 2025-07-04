package com.infomedia.abacox.telephonypricing.db.repository.query;

public final class ProcessingFailureReportQueries {
    private ProcessingFailureReportQueries() {}

    public static final String QUERY = """
    SELECT
        fr.error_type AS errorType,
        cl.id AS commLocationId,
        cl.directory AS commLocationDirectory,
        pt.id AS plantTypeId,
        pt.name AS plantTypeName,
        COUNT(fr.id) AS failureCount
    FROM
        failed_call_record fr
        JOIN communication_location cl ON fr.comm_location_id = cl.id
        JOIN plant_type pt ON cl.plant_type_id = pt.id
    WHERE
        fr.created_date BETWEEN :startDate AND :endDate
        AND (:directory IS NULL OR cl.directory ILIKE CONCAT('%', :directory, '%'))
        AND (:errorType IS NULL OR fr.error_type ILIKE CONCAT('%', :errorType, '%'))
    GROUP BY
        fr.error_type, cl.id, cl.directory, pt.id, pt.name
    """;

    public static final String COUNT_QUERY = """
    SELECT COUNT(*) FROM (
        SELECT 1
        FROM
            failed_call_record fr
            JOIN communication_location cl ON fr.comm_location_id = cl.id
            JOIN plant_type pt ON cl.plant_type_id = pt.id
        WHERE
            fr.created_date BETWEEN :startDate AND :endDate
            AND (:directory IS NULL OR cl.directory ILIKE CONCAT('%', :directory, '%'))
            AND (:errorType IS NULL OR fr.error_type ILIKE CONCAT('%', :errorType, '%'))
        GROUP BY
            fr.error_type, cl.id, cl.directory, pt.id, pt.name
    ) AS count_subquery
    """;
}