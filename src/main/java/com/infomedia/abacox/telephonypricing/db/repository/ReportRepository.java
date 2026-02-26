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

        @Query(value = ConferenceCallsReportQueries.CONFERENCE_CANDIDATES_QUERY, nativeQuery = true)
        List<ConferenceCandidateProjection> findConferenceCandidates(
                        @Param("startDate") LocalDateTime startDate,
                        @Param("endDate") LocalDateTime endDate,
                        @Param("extension") String extension,
                        @Param("employeeName") String employeeName);

        @Query(value = EmployeeActivityReportQueries.QUERY, countQuery = EmployeeActivityReportQueries.COUNT_QUERY, nativeQuery = true)
        Page<EmployeeActivityReport> getEmployeeActivityReport(
                        @Param("startDate") LocalDateTime startDate,
                        @Param("endDate") LocalDateTime endDate,
                        @Param("employeeName") String employeeName,
                        @Param("extension") String extension,
                        Pageable pageable);

        @Query(value = EmployeeCallReportQueries.QUERY, countQuery = EmployeeCallReportQueries.COUNT_QUERY, nativeQuery = true)
        Page<EmployeeCallReport> getEmployeeCallReport(
                        @Param("startDate") LocalDateTime startDate,
                        @Param("endDate") LocalDateTime endDate,
                        @Param("employeeName") String employeeName,
                        @Param("employeeExtension") String employeeExtension,
                        Pageable pageable);

        @Query(value = EmployeeCallReportQueries.BREAKDOWN_QUERY, nativeQuery = true)
        List<EmployeeTelephonyTypeBreakdown> getEmployeeTelephonyTypeBreakdown(
                        @Param("startDate") LocalDateTime startDate,
                        @Param("endDate") LocalDateTime endDate,
                        @Param("employeeIds") List<Long> employeeIds);

        @Query(value = UnassignedCallReportQueries.QUERY, countQuery = UnassignedCallReportQueries.COUNT_QUERY, nativeQuery = true)
        Page<UnassignedCallReport> getUnassignedCallReport(
                        @Param("startDate") LocalDateTime startDate,
                        @Param("endDate") LocalDateTime endDate,
                        @Param("extension") String extension,
                        @Param("groupingType") String groupingType,
                        @Param("minLength") Integer minLength,
                        @Param("maxLength") Integer maxLength,
                        Pageable pageable);

        @Query(value = ProcessingFailureReportQueries.QUERY, countQuery = ProcessingFailureReportQueries.COUNT_QUERY, nativeQuery = true)
        Page<ProcessingFailureReport> getProcessingFailureReport(
                        @Param("startDate") LocalDateTime startDate,
                        @Param("endDate") LocalDateTime endDate,
                        @Param("directory") String directory,
                        @Param("errorType") String errorType,
                        Pageable pageable);

        @Query(value = MissedCallEmployeeReportQueries.QUERY, countQuery = MissedCallEmployeeReportQueries.COUNT_QUERY, nativeQuery = true)
        Page<MissedCallEmployeeReport> getMissedCallEmployeeReport(
                        @Param("startDate") LocalDateTime startDate,
                        @Param("endDate") LocalDateTime endDate,
                        @Param("employeeName") String employeeName,
                        @Param("ringCount") Integer ringCount,
                        Pageable pageable);

        @Query(value = UnusedExtensionReportQueries.QUERY, countQuery = UnusedExtensionReportQueries.COUNT_QUERY, nativeQuery = true)
        Page<UnusedExtensionReport> getUnusedExtensionReport(
                        @Param("startDate") LocalDateTime startDate,
                        @Param("endDate") LocalDateTime endDate,
                        @Param("employeeName") String employeeName,
                        @Param("extension") String extension,
                        Pageable pageable);

        @Query(value = ExtensionGroupReportQueries.QUERY, countQuery = ExtensionGroupReportQueries.COUNT_QUERY, nativeQuery = true)
        Page<ExtensionGroupReport> getExtensionGroupReport(
                        @Param("startDate") LocalDateTime startDate,
                        @Param("endDate") LocalDateTime endDate,
                        @Param("extensions") List<String> extensions,
                        @Param("operatorIds") List<Long> operatorIds,
                        @Param("voicemailNumber") String voicemailNumber,
                        Pageable pageable);

        @Query(value = SubdivisionUsageReportQueries.QUERY, countQuery = SubdivisionUsageReportQueries.COUNT_QUERY, nativeQuery = true)
        Page<SubdivisionUsageReport> getSubdivisionUsageReport(
                        @Param("startDate") LocalDateTime startDate,
                        @Param("endDate") LocalDateTime endDate,
                        @Param("parentSubdivisionId") Long parentSubdivisionId,
                        Pageable pageable);

        @Query(value = SubdivisionUsageByTypeReportQueries.QUERY, countQuery = SubdivisionUsageByTypeReportQueries.COUNT_QUERY, nativeQuery = true)
        Page<SubdivisionUsageByTypeReport> getSubdivisionUsageByTypeReport(
                        @Param("startDate") LocalDateTime startDate,
                        @Param("endDate") LocalDateTime endDate,
                        @Param("subdivisionIds") List<Long> subdivisionIds,
                        Pageable pageable);

        @Query(value = TelephonyTypeUsageReportQueries.QUERY, countQuery = TelephonyTypeUsageReportQueries.COUNT_QUERY, nativeQuery = true)
        Page<TelephonyTypeUsageReport> getTelephonyTypeUsageReport(
                        @Param("startDate") LocalDateTime startDate,
                        @Param("endDate") LocalDateTime endDate,
                        Pageable pageable);

        @Query(value = MonthlyTelephonyTypeUsageReportQueries.QUERY, countQuery = MonthlyTelephonyTypeUsageReportQueries.COUNT_QUERY, nativeQuery = true)
        Page<MonthlyTelephonyTypeUsageReport> getMonthlyTelephonyTypeUsageReport(
                        @Param("startDate") LocalDateTime startDate,
                        @Param("endDate") LocalDateTime endDate,
                        Pageable pageable);

        @Query(value = CostCenterUsageReportQueries.QUERY, countQuery = CostCenterUsageReportQueries.COUNT_QUERY, nativeQuery = true)
        Page<CostCenterUsageReport> getCostCenterUsageReport(
                        @Param("startDate") LocalDateTime startDate,
                        @Param("endDate") LocalDateTime endDate,
                        Pageable pageable);

        @Query(value = EmployeeAuthCodeUsageReportQueries.QUERY, countQuery = EmployeeAuthCodeUsageReportQueries.COUNT_QUERY, nativeQuery = true)
        Page<EmployeeAuthCodeUsageReport> getEmployeeAuthCodeUsageReport(
                        @Param("startDate") LocalDateTime startDate,
                        @Param("endDate") LocalDateTime endDate,
                        Pageable pageable);

        @Query(value = MonthlySubdivisionUsageReportQueries.QUERY, countQuery = MonthlySubdivisionUsageReportQueries.COUNT_QUERY, nativeQuery = true)
        Page<MonthlySubdivisionUsage> getMonthlySubdivisionUsageReport(
                        @Param("startDate") LocalDateTime startDate,
                        @Param("endDate") LocalDateTime endDate,
                        @Param("subdivisionIds") List<Long> subdivisionIds,
                        Pageable pageable);

        @Query(value = DialedNumberUsageReportQueries.QUERY, countQuery = DialedNumberUsageReportQueries.COUNT_QUERY, nativeQuery = true)
        Page<DialedNumberUsageReport> getDialedNumberUsageReport(
                        @Param("startDate") LocalDateTime startDate,
                        @Param("endDate") LocalDateTime endDate,
                        Pageable pageable);

        @Query(value = DestinationUsageReportQueries.QUERY, countQuery = DestinationUsageReportQueries.COUNT_QUERY, nativeQuery = true)
        Page<DestinationUsageReport> getDestinationUsageReport(
                        @Param("startDate") LocalDateTime startDate,
                        @Param("endDate") LocalDateTime endDate,
                        Pageable pageable);

        @Query(value = HighestConsumptionEmployeeReportQueries.QUERY, countQuery = HighestConsumptionEmployeeReportQueries.COUNT_QUERY, nativeQuery = true)
        Page<HighestConsumptionEmployeeReport> getHighestConsumptionEmployeeReport(
                        @Param("startDate") LocalDateTime startDate,
                        @Param("endDate") LocalDateTime endDate,
                        Pageable pageable);
}