package com.infomedia.abacox.telephonypricing.service;

import com.infomedia.abacox.telephonypricing.config.CacheConfig;
import com.infomedia.abacox.telephonypricing.dto.dashboard.DashboardOverviewDto;
import com.infomedia.abacox.telephonypricing.dto.dashboard.EmployeeActivityDashboardDto;
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
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class DashboardService {

    private final TelephonyUsageReportService telephonyUsageReportService;
    private final SubdivisionReportService subdivisionReportService;
    private final EmployeeReportService employeeReportService;
    private final FailedCallRecordRepository failedCallRecordRepository;
    private final CacheManager cacheManager;

    @SuppressWarnings("unchecked")
    public EmployeeActivityDashboardDto getEmployeeActivityDashboard(
            String employeeName, String employeeExtension, Long subdivisionId, Long costCenterId,
            LocalDateTime startDate, LocalDateTime endDate) {
        String tenant = TenantContext.getTenant();
        String key = cacheKey(tenant, startDate, endDate)
                + ":activity-dashboard"
                + ":" + Objects.toString(employeeName, "")
                + ":" + Objects.toString(employeeExtension, "")
                + ":" + subdivisionId + ":" + costCenterId;
        Cache cache = cacheManager.getCache(employeeActivityCacheName(endDate));

        if (cache != null) {
            Cache.ValueWrapper cached = cache.get(key);
            if (cached != null) {
                log.debug("Dashboard employee activity cache hit: key={}", key);
                return (EmployeeActivityDashboardDto) cached.get();
            }
        }

        EmployeeActivityDashboardDto result = computeEmployeeActivityDashboard(
                employeeName, employeeExtension, subdivisionId, costCenterId, startDate, endDate);

        if (cache != null) {
            cache.put(key, result);
        }
        return result;
    }

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

    private String employeeActivityCacheName(LocalDateTime endDate) {
        YearMonth current = YearMonth.now();
        YearMonth end     = YearMonth.from(endDate);
        return end.isBefore(current)
                ? CacheConfig.DASHBOARD_EMPLOYEE_ACTIVITY_HISTORICAL
                : CacheConfig.DASHBOARD_EMPLOYEE_ACTIVITY_CURRENT;
    }

    private String cacheName(LocalDateTime endDate) {
        YearMonth current = YearMonth.now();
        YearMonth end     = YearMonth.from(endDate);
        return end.isBefore(current) ? CacheConfig.DASHBOARD_HISTORICAL : CacheConfig.DASHBOARD_CURRENT;
    }

    private String cacheKey(String tenant, LocalDateTime start, LocalDateTime end) {
        return (tenant != null ? tenant : "default") + ":" + start + ":" + end;
    }

    private EmployeeActivityDashboardDto computeEmployeeActivityDashboard(
            String employeeName, String employeeExtension, Long subdivisionId, Long costCenterId,
            LocalDateTime startDate, LocalDateTime endDate) {

        List<EmployeeActivityReportDto> all = employeeReportService.fetchAllEmployeeActivity(
                employeeName, employeeExtension, subdivisionId, costCenterId, startDate, endDate);

        // 1. Usability distribution
        long usedCount   = all.stream().filter(r -> Boolean.TRUE.equals(r.getIsUsed())).count();
        long unusedCount = all.size() - usedCount;

        // 2. Equipment distribution
        Map<String, Long> equipmentCounts = all.stream()
                .collect(Collectors.groupingBy(
                        r -> r.getEquipmentTypeName() != null ? r.getEquipmentTypeName() : "Unknown",
                        Collectors.counting()));
        long totalRecords = all.size();
        List<EmployeeActivityDashboardDto.EquipmentDistributionItem> equipmentDistribution = equipmentCounts
                .entrySet().stream()
                .map(e -> new EmployeeActivityDashboardDto.EquipmentDistributionItem(
                        e.getKey(),
                        e.getValue(),
                        totalRecords > 0 ? Math.round(e.getValue() * 10000.0 / totalRecords) / 100.0 : 0.0))
                .sorted(Comparator.comparingLong(EmployeeActivityDashboardDto.EquipmentDistributionItem::getCount).reversed())
                .collect(Collectors.toList());

        // 3. Top extensions by total calls (top 20)
        List<EmployeeActivityDashboardDto.ExtensionCallsItem> topExtensions = all.stream()
                .filter(r -> r.getTotalCallCount() != null && r.getTotalCallCount() > 0)
                .sorted(Comparator.comparingLong((EmployeeActivityReportDto r) ->
                        r.getTotalCallCount() != null ? r.getTotalCallCount() : 0L).reversed())
                .limit(100)
                .map(r -> new EmployeeActivityDashboardDto.ExtensionCallsItem(
                        r.getExtension(),
                        r.getEmployeeName(),
                        r.getIncomingCallCount() != null ? r.getIncomingCallCount() : 0L,
                        r.getOutgoingCallCount() != null ? r.getOutgoingCallCount() : 0L,
                        r.getTotalCallCount() != null ? r.getTotalCallCount() : 0L))
                .collect(Collectors.toList());

        // 4. Calls by location (group by departmentCountry + cityName)
        record LocationKey(String country, String city) {}
        Map<LocationKey, long[]> locationAccum = new LinkedHashMap<>();
        for (EmployeeActivityReportDto r : all) {
            String country = r.getDepartmentCountry() != null ? r.getDepartmentCountry() : "Unknown";
            String city    = r.getCityName()           != null ? r.getCityName()           : "Unknown";
            long incoming  = r.getIncomingCallCount()  != null ? r.getIncomingCallCount()  : 0L;
            long outgoing  = r.getOutgoingCallCount()  != null ? r.getOutgoingCallCount()  : 0L;
            locationAccum.computeIfAbsent(new LocationKey(country, city), k -> new long[3]);
            long[] acc = locationAccum.get(new LocationKey(country, city));
            acc[0] += incoming;
            acc[1] += outgoing;
            acc[2] += incoming + outgoing;
        }
        List<EmployeeActivityDashboardDto.LocationCallsItem> callsByLocation = locationAccum.entrySet().stream()
                .map(e -> new EmployeeActivityDashboardDto.LocationCallsItem(
                        e.getKey().country(),
                        e.getKey().city(),
                        e.getValue()[0],
                        e.getValue()[1],
                        e.getValue()[2]))
                .sorted(Comparator.comparingLong(EmployeeActivityDashboardDto.LocationCallsItem::getTotalCallCount).reversed())
                .collect(Collectors.toList());

        // 5. Top subdivisions (group by subdivisionId + subdivisionName)
        record SubKey(Long id, String name) {}
        Map<SubKey, long[]> subAccum = new LinkedHashMap<>();
        for (EmployeeActivityReportDto r : all) {
            if (r.getSubdivisionId() == null) continue;
            SubKey key = new SubKey(r.getSubdivisionId(),
                    r.getSubdivisionName() != null ? r.getSubdivisionName() : "Unknown");
            long incoming = r.getIncomingCallCount() != null ? r.getIncomingCallCount() : 0L;
            long outgoing = r.getOutgoingCallCount() != null ? r.getOutgoingCallCount() : 0L;
            subAccum.computeIfAbsent(key, k -> new long[4]);
            long[] acc = subAccum.get(key);
            acc[0]++; // extensionCount
            acc[1] += incoming;
            acc[2] += outgoing;
            acc[3] += incoming + outgoing;
        }
        List<EmployeeActivityDashboardDto.SubdivisionActivityItem> topSubdivisions = subAccum.entrySet().stream()
                .map(e -> new EmployeeActivityDashboardDto.SubdivisionActivityItem(
                        e.getKey().id(),
                        e.getKey().name(),
                        e.getValue()[0],
                        e.getValue()[1],
                        e.getValue()[2],
                        e.getValue()[3]))
                .sorted(Comparator.comparingLong(EmployeeActivityDashboardDto.SubdivisionActivityItem::getTotalCallCount).reversed())
                .collect(Collectors.toList());

        // 6. Top cost centers (group by costCenterId + costCenterName)
        record CcKey(Long id, String name) {}
        Map<CcKey, long[]> ccAccum = new LinkedHashMap<>();
        for (EmployeeActivityReportDto r : all) {
            if (r.getCostCenterId() == null) continue;
            CcKey key = new CcKey(r.getCostCenterId(),
                    r.getCostCenterName() != null ? r.getCostCenterName() : "Unknown");
            long incoming = r.getIncomingCallCount() != null ? r.getIncomingCallCount() : 0L;
            long outgoing = r.getOutgoingCallCount() != null ? r.getOutgoingCallCount() : 0L;
            ccAccum.computeIfAbsent(key, k -> new long[4]);
            long[] acc = ccAccum.get(key);
            acc[0]++; // extensionCount
            acc[1] += incoming;
            acc[2] += outgoing;
            acc[3] += incoming + outgoing;
        }
        List<EmployeeActivityDashboardDto.CostCenterActivityItem> topCostCenters = ccAccum.entrySet().stream()
                .map(e -> new EmployeeActivityDashboardDto.CostCenterActivityItem(
                        e.getKey().id(),
                        e.getKey().name(),
                        e.getValue()[0],
                        e.getValue()[1],
                        e.getValue()[2],
                        e.getValue()[3]))
                .sorted(Comparator.comparingLong(EmployeeActivityDashboardDto.CostCenterActivityItem::getTotalCallCount).reversed())
                .collect(Collectors.toList());

        return EmployeeActivityDashboardDto.builder()
                .usedCount(usedCount)
                .unusedCount(unusedCount)
                .equipmentDistribution(equipmentDistribution)
                .topExtensions(topExtensions)
                .callsByLocation(callsByLocation)
                .topSubdivisions(topSubdivisions)
                .topCostCenters(topCostCenters)
                .build();
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
