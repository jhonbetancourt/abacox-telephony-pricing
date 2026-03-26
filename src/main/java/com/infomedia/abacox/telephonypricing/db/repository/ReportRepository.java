package com.infomedia.abacox.telephonypricing.db.repository;

import com.infomedia.abacox.telephonypricing.db.projection.*;
import com.infomedia.abacox.telephonypricing.db.util.VirtualEntity;
import com.infomedia.abacox.telephonypricing.db.repository.query.*;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
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

        @Query(value = EmployeeActivityReportQueries.QUERY, nativeQuery = true)
        Slice<EmployeeActivityReport> getEmployeeActivityReport(
                        @Param("startDate") LocalDateTime startDate,
                        @Param("endDate") LocalDateTime endDate,
                        @Param("employeeName") String employeeName,
                        @Param("extension") String extension,
                        @Param("subdivisionId") Long subdivisionId,
                        @Param("costCenterId") Long costCenterId,
                        Pageable pageable);

        @Query(value = EmployeeCallReportQueries.QUERY, nativeQuery = true)
        Slice<EmployeeCallReport> getEmployeeCallReport(
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

        @Query(value = UnassignedCallReportQueries.QUERY, nativeQuery = true)
        Slice<UnassignedCallReport> getUnassignedCallReport(
                        @Param("startDate") LocalDateTime startDate,
                        @Param("endDate") LocalDateTime endDate,
                        @Param("extension") String extension,
                        @Param("groupingType") String groupingType,
                        @Param("minLength") Integer minLength,
                        @Param("maxLength") Integer maxLength,
                        Pageable pageable);

        @Query(value = ProcessingFailureReportQueries.QUERY, nativeQuery = true)
        Slice<ProcessingFailureReport> getProcessingFailureReport(
                        @Param("startDate") LocalDateTime startDate,
                        @Param("endDate") LocalDateTime endDate,
                        @Param("directory") String directory,
                        @Param("errorType") String errorType,
                        Pageable pageable);

        @Query(value = MissedCallEmployeeReportQueries.QUERY, nativeQuery = true)
        Slice<MissedCallEmployeeReport> getMissedCallEmployeeReport(
                        @Param("startDate") LocalDateTime startDate,
                        @Param("endDate") LocalDateTime endDate,
                        @Param("employeeName") String employeeName,
                        @Param("minRingCount") Integer minRingCount,
                        Pageable pageable);

        @Query(value = UnusedExtensionReportQueries.QUERY, nativeQuery = true)
        Slice<UnusedExtensionReport> getUnusedExtensionReport(
                        @Param("startDate") LocalDateTime startDate,
                        @Param("endDate") LocalDateTime endDate,
                        @Param("employeeName") String employeeName,
                        @Param("extension") String extension,
                        Pageable pageable);

        @Query(value = SubdivisionUsageReportQueries.QUERY, nativeQuery = true)
        Slice<SubdivisionUsageReport> getSubdivisionUsageReport(
                        @Param("startDate") LocalDateTime startDate,
                        @Param("endDate") LocalDateTime endDate,
                        @Param("parentSubdivisionId") Long parentSubdivisionId,
                        Pageable pageable);

        @Query(value = SubdivisionUsageByTypeReportQueries.QUERY, nativeQuery = true)
        Slice<SubdivisionUsageByTypeReport> getSubdivisionUsageByTypeReport(
                        @Param("startDate") LocalDateTime startDate,
                        @Param("endDate") LocalDateTime endDate,
                        @Param("parentSubdivisionId") Long parentSubdivisionId,
                        Pageable pageable);

        @Query(value = SubdivisionUsageByTypeReportQueries.BREAKDOWN_QUERY, nativeQuery = true)
        List<SubdivisionTelephonyTypeBreakdown> getSubdivisionTelephonyTypeBreakdown(
                        @Param("startDate") LocalDateTime startDate,
                        @Param("endDate") LocalDateTime endDate,
                        @Param("subdivisionIds") List<Long> subdivisionIds);

        @Query(value = TelephonyTypeUsageReportQueries.QUERY, nativeQuery = true)
        Slice<TelephonyTypeUsageReport> getTelephonyTypeUsageReport(
                        @Param("startDate") LocalDateTime startDate,
                        @Param("endDate") LocalDateTime endDate,
                        Pageable pageable);

        @Query(value = MonthlyTelephonyTypeUsageReportQueries.QUERY, nativeQuery = true)
        Slice<MonthlyTelephonyTypeUsageReport> getMonthlyTelephonyTypeUsageReport(
                        @Param("startDate") LocalDateTime startDate,
                        @Param("endDate") LocalDateTime endDate,
                        Pageable pageable);

        @Query(value = CostCenterUsageReportQueries.QUERY, nativeQuery = true)
        Slice<CostCenterUsageReport> getCostCenterUsageReport(
                        @Param("startDate") LocalDateTime startDate,
                        @Param("endDate") LocalDateTime endDate,
                        @Param("parentCostCenterId") Long parentCostCenterId,
                        Pageable pageable);

        @Query(value = EmployeeAuthCodeUsageReportQueries.QUERY, nativeQuery = true)
        Slice<EmployeeAuthCodeUsageReport> getEmployeeAuthCodeUsageReport(
                        @Param("startDate") LocalDateTime startDate,
                        @Param("endDate") LocalDateTime endDate,
                        Pageable pageable);

        @Query(value = MonthlySubdivisionUsageReportQueries.QUERY, nativeQuery = true)
        Slice<MonthlySubdivisionUsage> getMonthlySubdivisionUsageReport(
                        @Param("startDate") LocalDateTime startDate,
                        @Param("endDate") LocalDateTime endDate,
                        @Param("subdivisionIds") List<Long> subdivisionIds,
                        Pageable pageable);

        @Query(value = MonthlySubdivisionUsageReportQueries.QUERY, nativeQuery = true)
        List<MonthlySubdivisionUsage> getMonthlySubdivisionUsageReportAll(
                        @Param("startDate") LocalDateTime startDate,
                        @Param("endDate") LocalDateTime endDate,
                        @Param("subdivisionIds") List<Long> subdivisionIds);

        @Query(value = DialedNumberUsageReportQueries.QUERY, nativeQuery = true)
        Slice<DialedNumberUsageReport> getDialedNumberUsageReport(
                        @Param("startDate") LocalDateTime startDate,
                        @Param("endDate") LocalDateTime endDate,
                        Pageable pageable);

        @Query(value = DestinationUsageReportQueries.QUERY, nativeQuery = true)
        Slice<DestinationUsageReport> getDestinationUsageReport(
                        @Param("startDate") LocalDateTime startDate,
                        @Param("endDate") LocalDateTime endDate,
                        Pageable pageable);

        @Query(value = HighestConsumptionEmployeeReportQueries.QUERY, nativeQuery = true)
        Slice<HighestConsumptionEmployeeReport> getHighestConsumptionEmployeeReport(
                        @Param("startDate") LocalDateTime startDate,
                        @Param("endDate") LocalDateTime endDate,
                        Pageable pageable);

        @Query(value = ExtensionGroupReportQueries.QUERY, nativeQuery = true)
        Slice<ExtensionGroupReport> getExtensionGroupReport(
                        @Param("startDate") LocalDateTime startDate,
                        @Param("endDate") LocalDateTime endDate,
                        @Param("extensions") List<String> extensions,
                        @Param("voicemailNumber") String voicemailNumber,
                        @Param("operatorIds") List<Long> operatorIds,
                        Pageable pageable);
}
