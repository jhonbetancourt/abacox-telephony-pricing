package com.infomedia.abacox.telephonypricing.service;

import com.infomedia.abacox.telephonypricing.config.CacheConfig;
import com.infomedia.abacox.telephonypricing.dto.dashboard.DashboardOverviewDto;
import com.infomedia.abacox.telephonypricing.db.repository.FailedCallRecordRepository;
import com.infomedia.abacox.telephonypricing.dto.report.*;
import com.infomedia.abacox.telephonypricing.multitenancy.TenantContext;
import com.infomedia.abacox.telephonypricing.service.report.EmployeeReportService;
import com.infomedia.abacox.telephonypricing.service.report.SubdivisionReportService;
import com.infomedia.abacox.telephonypricing.service.report.TelephonyUsageReportService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Slice;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class DashboardService {

    private final TelephonyUsageReportService telephonyUsageReportService;
    private final SubdivisionReportService subdivisionReportService;
    private final EmployeeReportService employeeReportService;
    private final FailedCallRecordRepository failedCallRecordRepository;
    private final CacheManager cacheManager;

    public DashboardOverviewDto getDashboardOverview(LocalDateTime startDate, LocalDateTime endDate) {
        String tenant = TenantContext.getTenant();
        String key    = cacheKey(tenant, startDate, endDate);
        Cache  cache  = cacheManager.getCache(cacheName(endDate));

        if (cache != null) {
            Cache.ValueWrapper cached = cache.get(key);
            if (cached != null) {
                log.debug("Dashboard cache hit: key={}", key);
                return (DashboardOverviewDto) cached.get();
            }
        }

        DashboardOverviewDto result = computeDashboardOverview(startDate, endDate);

        if (cache != null) {
            cache.put(key, result);
        }
        return result;
    }

    private String cacheName(LocalDateTime endDate) {
        YearMonth current = YearMonth.now();
        YearMonth end     = YearMonth.from(endDate);
        return end.isBefore(current) ? CacheConfig.DASHBOARD_HISTORICAL : CacheConfig.DASHBOARD_CURRENT;
    }

    private String cacheKey(String tenant, LocalDateTime start, LocalDateTime end) {
        return (tenant != null ? tenant : "default") + ":" + start + ":" + end;
    }

    private DashboardOverviewDto computeDashboardOverview(LocalDateTime startDate, LocalDateTime endDate) {
        // --- KPI totals from cost center summaries (includes grand totals) ---
        var costCenterReport = telephonyUsageReportService
                .generateCostCenterUsageReport(startDate, endDate, null, PageRequest.of(0, 5, Sort.by(Sort.Direction.DESC, "totalBilledAmount")));

        BigDecimal totalCost = BigDecimal.ZERO;
        long totalDurationSeconds = 0;
        long totalIncomingCalls = 0;
        long totalOutgoingCalls = 0;
        long unassignedCalls = 0;

        for (UsageReportSummaryDto summary : costCenterReport.getSummaries()) {
            long inCalls  = summary.getIncomingCallCount()  != null ? summary.getIncomingCallCount()  : 0L;
            long outCalls = summary.getOutgoingCallCount()  != null ? summary.getOutgoingCallCount()  : 0L;
            totalCost = totalCost.add(summary.getTotalBilledAmount() != null ? summary.getTotalBilledAmount() : BigDecimal.ZERO);
            totalDurationSeconds += summary.getTotalDuration() != null ? summary.getTotalDuration() : 0L;
            totalIncomingCalls += inCalls;
            totalOutgoingCalls += outCalls;
            if ("UNASSIGNED".equals(summary.getRowType())) {
                unassignedCalls += inCalls + outCalls;
            }
        }

        long totalCalls = totalIncomingCalls + totalOutgoingCalls;
        BigDecimal avgCost = totalDurationSeconds > 0
                ? totalCost.divide(BigDecimal.valueOf(totalDurationSeconds / 60.0), 4, RoundingMode.HALF_UP)
                : BigDecimal.ZERO;

        // --- Processing failures ---
        long processingFailures = failedCallRecordRepository
                .count((root, query, cb) -> cb.between(root.get("createdDate"), startDate, endDate));

        // --- Top 5 cost centers ---
        List<DashboardOverviewDto.CostCenterUsageDto> topCostCenters = new ArrayList<>();
        for (CostCenterUsageReportDto row : costCenterReport.getContent()) {
            String name = row.getCostCenterName() != null ? row.getCostCenterName() : "Unknown";
            BigDecimal cost = row.getTotalBilledAmount() != null ? row.getTotalBilledAmount() : BigDecimal.ZERO;
            topCostCenters.add(new DashboardOverviewDto.CostCenterUsageDto(name, cost));
        }

        // --- Telephony type breakdown (donut + in/out grouped bar) ---
        Slice<TelephonyTypeUsageGroupDto> telephonyReport = telephonyUsageReportService
                .generateTelephonyTypeUsageReport(startDate, endDate, PageRequest.of(0, 100, Sort.by(Sort.Direction.DESC, "totalBilledAmount")));

        List<DashboardOverviewDto.TelephonyTypeCostDto> costByTelephonyType = new ArrayList<>();
        for (TelephonyTypeUsageGroupDto group : telephonyReport.getContent()) {
            TelephonyTypeUsageReportDto sub = group.getSubtotal();
            BigDecimal cost   = sub != null && sub.getTotalBilledAmount()  != null ? sub.getTotalBilledAmount()  : BigDecimal.ZERO;
            long inCalls      = sub != null && sub.getIncomingCallCount()  != null ? sub.getIncomingCallCount()  : 0L;
            long outCalls     = sub != null && sub.getOutgoingCallCount()  != null ? sub.getOutgoingCallCount()  : 0L;
            costByTelephonyType.add(new DashboardOverviewDto.TelephonyTypeCostDto(group.getCategoryName(), cost, inCalls, outCalls));
        }

        // --- Top 5 subdivisions ---
        Slice<SubdivisionUsageReportDto> subdivisionsReport = subdivisionReportService
                .generateSubdivisionUsageReport(startDate, endDate, null, PageRequest.of(0, 5, Sort.by(Sort.Direction.DESC, "totalBilledAmount")));

        List<DashboardOverviewDto.SubdivisionSummaryDto> topSubdivisions = new ArrayList<>();
        for (SubdivisionUsageReportDto row : subdivisionsReport.getContent()) {
            String name   = row.getSubdivisionName()    != null ? row.getSubdivisionName()    : "Unknown";
            BigDecimal cost = row.getTotalBilledAmount() != null ? row.getTotalBilledAmount()  : BigDecimal.ZERO;
            long calls    = (row.getIncomingCallCount() != null ? row.getIncomingCallCount() : 0L)
                          + (row.getOutgoingCallCount() != null ? row.getOutgoingCallCount() : 0L);
            topSubdivisions.add(new DashboardOverviewDto.SubdivisionSummaryDto(name, cost, calls));
        }

        // --- Top 10 employees by consumption ---
        Slice<HighestConsumptionEmployeeReportDto> employeesReport = employeeReportService
                .generateHighestConsumptionEmployeeReport(startDate, endDate, PageRequest.of(0, 10, Sort.by(Sort.Direction.DESC, "totalBilledAmount")));

        List<DashboardOverviewDto.EmployeeSummaryDto> topEmployees = new ArrayList<>();
        for (HighestConsumptionEmployeeReportDto row : employeesReport.getContent()) {
            topEmployees.add(new DashboardOverviewDto.EmployeeSummaryDto(
                    row.getEmployeeName(),
                    row.getExtension(),
                    row.getCallCount(),
                    row.getTotalDuration(),
                    row.getTotalBilledAmount() != null ? row.getTotalBilledAmount() : BigDecimal.ZERO
            ));
        }

        return DashboardOverviewDto.builder()
                .totalCalls(totalCalls)
                .totalIncomingCalls(totalIncomingCalls)
                .totalOutgoingCalls(totalOutgoingCalls)
                .totalCost(totalCost)
                .totalDurationSeconds(totalDurationSeconds)
                .averageCostPerMinute(avgCost)
                .processingFailures(processingFailures)
                .unassignedCalls(unassignedCalls)
                .costByTelephonyType(costByTelephonyType)
                .topCostCenters(topCostCenters)
                .topSubdivisions(topSubdivisions)
                .topEmployees(topEmployees)
                .build();
    }
}
