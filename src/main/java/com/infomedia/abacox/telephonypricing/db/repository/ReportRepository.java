package com.infomedia.abacox.telephonypricing.db.repository;

import com.infomedia.abacox.telephonypricing.db.projection.*;
import com.infomedia.abacox.telephonypricing.db.util.VirtualEntity;
import com.infomedia.abacox.telephonypricing.db.repository.query.*;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;

public interface ReportRepository extends JpaRepository<VirtualEntity, Long> {

    @Query(
            value = EmployeeActivityReportQueries.QUERY,
            countQuery = EmployeeActivityReportQueries.COUNT_QUERY,
            nativeQuery = true
    )
    Page<EmployeeActivityReport> getEmployeeActivityReport(
            @Param("startDate") LocalDateTime startDate,
            @Param("endDate") LocalDateTime endDate,
            @Param("employeeName") String employeeName,
            @Param("extension") String extension,
            Pageable pageable
    );

    @Query(
            value = EmployeeCallReportQueries.QUERY,
            countQuery = EmployeeCallReportQueries.COUNT_QUERY,
            nativeQuery = true
    )
    Page<EmployeeCallReport> getEmployeeCallReport(
            @Param("startDate") LocalDateTime startDate,
            @Param("endDate") LocalDateTime endDate,
            @Param("employeeName") String employeeName,
            @Param("employeeExtension") String employeeExtension,
            Pageable pageable
    );

    @Query(
            value = UnassignedCallReportQueries.QUERY,
            countQuery = UnassignedCallReportQueries.COUNT_QUERY,
            nativeQuery = true
    )
    Page<UnassignedCallReport> getUnassignedCallReport(
            @Param("startDate") LocalDateTime startDate,
            @Param("endDate") LocalDateTime endDate,
            @Param("extension") String extension,
            Pageable pageable
    );

    @Query(
            value = ProcessingFailureReportQueries.QUERY,
            countQuery = ProcessingFailureReportQueries.COUNT_QUERY,
            nativeQuery = true
    )
    Page<ProcessingFailureReport> getProcessingFailureReport(
            @Param("startDate") LocalDateTime startDate,
            @Param("endDate") LocalDateTime endDate,
            @Param("directory") String directory,
            @Param("errorType") String errorType,
            Pageable pageable
    );

    @Query(
            value = MissedCallEmployeeReportQueries.QUERY,
            countQuery = MissedCallEmployeeReportQueries.COUNT_QUERY,
            nativeQuery = true
    )
    Page<MissedCallEmployeeReport> getMissedCallEmployeeReport(
            @Param("startDate") LocalDateTime startDate,
            @Param("endDate") LocalDateTime endDate,
            @Param("employeeName") String employeeName,
            Pageable pageable
    );

    @Query(
            value = UnusedExtensionReportQueries.QUERY,
            countQuery = UnusedExtensionReportQueries.COUNT_QUERY,
            nativeQuery = true
    )
    Page<UnusedExtensionReport> getUnusedExtensionReport(
            @Param("startDate") LocalDateTime startDate,
            @Param("endDate") LocalDateTime endDate,
            @Param("employeeName") String employeeName,
            @Param("extension") String extension,
            Pageable pageable
    );

    @Query(
            value = ExtensionGroupReportQueries.QUERY,
            countQuery = ExtensionGroupReportQueries.COUNT_QUERY,
            nativeQuery = true
    )
    Page<ExtensionGroupReport> getExtensionGroupReport(
            @Param("startDate") LocalDateTime startDate,
            @Param("endDate") LocalDateTime endDate,
            @Param("extensions") List<String> extensions,
            @Param("operatorIds") List<Long> operatorIds,
            @Param("voicemailNumber") String voicemailNumber,
            Pageable pageable
    );
}