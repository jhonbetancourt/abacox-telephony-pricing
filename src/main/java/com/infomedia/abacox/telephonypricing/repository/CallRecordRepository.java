package com.infomedia.abacox.telephonypricing.repository;

import com.infomedia.abacox.telephonypricing.dto.callrecord.EmployeeActivityReportDto;
import com.infomedia.abacox.telephonypricing.entity.CallRecord;
import com.infomedia.abacox.telephonypricing.entity.projection.EmployeeActivityReportProjection;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;

public interface CallRecordRepository extends JpaRepository<CallRecord, Long>, JpaSpecificationExecutor<CallRecord> {




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
    Page<EmployeeActivityReportProjection> findEmployeeActivityReport(
            @Param("startDate") LocalDateTime startDate,
            @Param("endDate") LocalDateTime endDate,
            @Param("employeeName") String employeeName,
            @Param("extension") String extension,
            Pageable pageable
    );
}