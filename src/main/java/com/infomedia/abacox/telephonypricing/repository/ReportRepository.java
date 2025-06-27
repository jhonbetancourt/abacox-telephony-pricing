package com.infomedia.abacox.telephonypricing.repository;

import com.infomedia.abacox.telephonypricing.db.projection.EmployeeActivityReport;
import com.infomedia.abacox.telephonypricing.db.projection.EmployeeCallReport;
import com.infomedia.abacox.telephonypricing.db.projection.ProcessingFailureReport;
import com.infomedia.abacox.telephonypricing.db.projection.UnassignedCallReport;
import com.infomedia.abacox.telephonypricing.db.util.VirtualEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;

public interface ReportRepository extends JpaRepository<VirtualEntity, Long> {


    /**
     * Custom native query to generate the employee activity report, projecting results into a DTO.
     *
     * @param startDate     The start of the date range for call aggregation.
     * @param endDate       The end of the date range for call aggregation.
     * @param employeeName  Optional filter for the employee's name (case-insensitive, partial match).
     * @param extension     Optional filter for the employee's extension (case-insensitive, partial match).
     * @param pageable      The pagination and sorting information.
     * @return A paginated list of report DTOs.
     */
    @Query(
            value = """
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
            """,
            countQuery = """
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
            """,
            nativeQuery = true
    )
    Page<EmployeeActivityReport> getEmployeeActivityReport(
            @Param("startDate") LocalDateTime startDate,
            @Param("endDate") LocalDateTime endDate,
            @Param("employeeName") String employeeName,
            @Param("extension") String extension,
            Pageable pageable
    );



    /**
     * Generates a summary of telephone call costs and volumes, aggregated by each active employee
     * for a specific date range. This query replicates the logic of the original "DETALLADO POR FUNCIONARIO" report
     * using the new JPA entities.
     *
     * It supports optional, case-insensitive filtering by employee name and extension.
     *
     * @param startDate         The start of the reporting period (inclusive).
     * @param endDate           The end of the reporting period (inclusive).
     * @param employeeName      Optional filter for the employee's name (case-insensitive, partial match).
     * @param employeeExtension Optional filter for the employee's extension (case-insensitive, partial match).
     * @return A list of EmployeeCallSummary projections, each containing the aggregated data for one employee.
     */
    @Query(
            value = """
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
        """,
            countQuery = """
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
        """,
            nativeQuery = true
    )
    Page<EmployeeCallReport> getEmployeeCallReport(
            @Param("startDate") LocalDateTime startDate,
            @Param("endDate") LocalDateTime endDate,
            @Param("employeeName") String employeeName,
            @Param("employeeExtension") String employeeExtension,
            Pageable pageable
    );


    /**
     * Generates a report summarizing call costs and usage for unassigned extensions.
     * This report identifies calls from extensions that are not linked to an employee (employee_id = 0)
     * or are flagged with a specific assignment cause (cause = 5). It's useful for tracking costs
     * from common areas like conference rooms or identifying misconfigured extensions.
     *
     * The results are aggregated by extension, location, and plant type.
     *
     * @param startDate The start of the reporting period.
     * @param endDate The end of the reporting period.
     * @param extension Optional filter for the extension number.
     * @param pageable  Pagination and sorting information. The original report sorts by totalCost desc,
     *                  callCount desc, and lastCallDate desc.
     * @return A paginated list of UnassignedCallReport projections.
     */
    @Query(
            value = """
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
                AND (cr.employee_id = 0 OR cr.assignment_cause = 5)
                AND LENGTH(cr.employee_extension) BETWEEN 3 AND 5
                AND cr.duration > 0
                AND (:extension IS NULL OR cr.employee_extension ILIKE CONCAT('%', :extension, '%'))
            GROUP BY
                cr.employee_extension, cr.employee_id, cr.assignment_cause, cl.directory, cl.id, pt.name
            """,
            countQuery = """
            SELECT COUNT(*) FROM (
                SELECT 1
                FROM
                    call_record cr
                    JOIN communication_location cl ON cr.comm_location_id = cl.id
                    JOIN plant_type pt ON cl.plant_type_id = pt.id
                WHERE
                    cr.service_date BETWEEN :startDate AND :endDate
                    AND (cr.employee_id = 0 OR cr.assignment_cause = 5)
                    AND LENGTH(cr.employee_extension) BETWEEN 3 AND 5
                    AND cr.duration > 0
                    AND (:extension IS NULL OR cr.employee_extension ILIKE CONCAT('%', :extension, '%'))
                GROUP BY
                    cr.employee_extension, cr.employee_id, cr.assignment_cause, cl.directory, cl.id, pt.name
            ) AS count_subquery
            """,
            nativeQuery = true
    )
    Page<UnassignedCallReport> getUnassignedCallReport(
            @Param("startDate") LocalDateTime startDate,
            @Param("endDate") LocalDateTime endDate,
            @Param("extension") String extension,
            Pageable pageable
    );



    /**
     * Generates a diagnostic report of call processing failures, grouped by error type.
     * <p>
     * This report identifies the most frequent types of errors (e.g., PARSING_ERROR, ENRICHMENT_ERROR)
     * by grouping failed records based on their error category and the trusted configuration of the CommunicationLocation where they originated.
     * It is essential for diagnosing systemic issues with parsers or enrichment logic for a specific PlantType.
     *
     * @param startDate The start of the reporting period.
     * @param endDate   The end of the reporting period.
     * @param directory Optional filter for the communication location's directory name.
     * @param errorType Optional filter for a specific error type.
     * @param pageable  Pagination and sorting information. It's recommended to sort by `failureCount` descending.
     * @return A paginated list of ProcessingFailureReport projections.
     */
    @Query(
            value = """
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
            """,
            countQuery = """
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
            """,
            nativeQuery = true
    )
    Page<ProcessingFailureReport> getProcessingFailureReport(
            @Param("startDate") LocalDateTime startDate,
            @Param("endDate") LocalDateTime endDate,
            @Param("directory") String directory,
            @Param("errorType") String errorType,
            Pageable pageable
    );
}