package com.infomedia.abacox.telephonypricing.db.repository.query;

public final class UnassignedCallReportQueries {
    private UnassignedCallReportQueries() {}

    public static final String QUERY = """
    WITH report_data AS (
        SELECT
            cr.employee_extension as employeeExtension,
            cr.employee_id as employeeId,
            cr.assignment_cause as assignmentCause,
            cl.directory as commLocationDirectory,
            cl.id as commLocationId,
            pt.name as plantTypeName,
            SUM(cr.billed_amount) as totalCost,
            SUM(cr.duration) as totalDuration,
            COUNT(cr.id) as callCount,
            MAX(cr.service_date) as lastCallDate
        FROM
            call_record cr
            JOIN communication_location cl ON cr.comm_location_id = cl.id
            JOIN plant_type pt ON cl.plant_type_id = pt.id
        WHERE
            cr.service_date BETWEEN :startDate AND :endDate
            AND (cr.employee_id IS NULL OR cr.assignment_cause = 5)
            AND LENGTH(cr.employee_extension) BETWEEN 3 AND 5
            AND cr.duration > 0
            AND (:extension IS NULL OR cr.employee_extension ILIKE CONCAT('%', :extension, '%'))
        GROUP BY
            cr.employee_extension, cr.employee_id, cr.assignment_cause, cl.directory, cl.id, pt.name
    )
    SELECT
        employeeExtension,
        employeeId,
        assignmentCause,
        commLocationDirectory,
        commLocationId,
        plantTypeName,
        totalCost,
        totalDuration,
        callCount,
        lastCallDate
    FROM
        report_data
    """;

    public static final String COUNT_QUERY = """
    SELECT COUNT(*) FROM (
        SELECT 1
        FROM
            call_record cr
            JOIN communication_location cl ON cr.comm_location_id = cl.id
            JOIN plant_type pt ON cl.plant_type_id = pt.id
        WHERE
            cr.service_date BETWEEN :startDate AND :endDate
            AND (cr.employee_id IS NULL OR cr.assignment_cause = 5)
            AND LENGTH(cr.employee_extension) BETWEEN 3 AND 5
            AND cr.duration > 0
            AND (:extension IS NULL OR cr.employee_extension ILIKE CONCAT('%', :extension, '%'))
        GROUP BY
            cr.employee_extension, cr.employee_id, cr.assignment_cause, cl.directory, cl.id, pt.name
    ) AS count_subquery
    """;
}