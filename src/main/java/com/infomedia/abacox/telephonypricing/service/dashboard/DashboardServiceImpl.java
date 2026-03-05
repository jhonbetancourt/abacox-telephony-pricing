package com.infomedia.abacox.telephonypricing.service.dashboard;

import com.infomedia.abacox.telephonypricing.dto.dashboard.DashboardOverviewDto;
import com.infomedia.abacox.telephonypricing.db.repository.FailedCallRecordRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import org.springframework.data.domain.PageRequest;
import com.infomedia.abacox.telephonypricing.service.report.TelephonyUsageReportService;
import com.infomedia.abacox.telephonypricing.dto.generic.SliceWithSummaries;
import com.infomedia.abacox.telephonypricing.dto.report.CostCenterUsageReportDto;
import com.infomedia.abacox.telephonypricing.dto.report.UsageReportSummaryDto;
import com.infomedia.abacox.telephonypricing.dto.report.TelephonyTypeUsageGroupDto;
import org.springframework.data.domain.Slice;

@Slf4j
@Service
@RequiredArgsConstructor
public class DashboardServiceImpl implements DashboardService {

        private final TelephonyUsageReportService telephonyUsageReportService;
        private final FailedCallRecordRepository failedCallRecordRepository;

        @Override
        public DashboardOverviewDto getDashboardOverview(LocalDateTime startDate, LocalDateTime endDate) {
                log.info("Generating dashboard overview from {} to {} using real report services", startDate, endDate);

                // Use the CostCenterUsageReport to get both the top 5 cost centers AND the
                // grand totals
                // (assigned + unassigned)
                SliceWithSummaries<CostCenterUsageReportDto, UsageReportSummaryDto> costCenterReport = telephonyUsageReportService
                                .generateCostCenterUsageReport(startDate, endDate, null, PageRequest.of(0, 5));

                BigDecimal totalCost = BigDecimal.ZERO;
                long totalDurationSeconds = 0;
                long totalCalls = 0;
                long unassignedCalls = 0;

                for (UsageReportSummaryDto summary : costCenterReport.getSummaries()) {
                        totalCost = totalCost.add(summary.getTotalBilledAmount());
                        totalDurationSeconds += summary.getTotalDuration();
                        totalCalls += summary.getIncomingCallCount() + summary.getOutgoingCallCount();

                        // Treat the UR (Unassigned Row) specifically if needed
                        if ("UNASSIGNED".equals(summary.getRowType())) {
                                unassignedCalls += summary.getIncomingCallCount() + summary.getOutgoingCallCount();
                        }
                }

                BigDecimal avgCost = totalDurationSeconds > 0
                                ? totalCost.divide(BigDecimal.valueOf(totalDurationSeconds / 60.0), 4,
                                                RoundingMode.HALF_UP)
                                : BigDecimal.ZERO;

                // Processing failures
                long processingFailures = failedCallRecordRepository
                                .count((root, query, cb) -> cb.between(root.get("createdDate"), startDate, endDate));

                // Map Top 5 Cost Centers from the slice content
                List<DashboardOverviewDto.CostCenterUsageDto> topCostCenters = new ArrayList<>();
                for (CostCenterUsageReportDto row : costCenterReport.getContent()) {
                        String name = row.getCostCenterName() != null ? row.getCostCenterName() : "Unknown";
                        BigDecimal cost = row.getTotalBilledAmount() != null ? row.getTotalBilledAmount()
                                        : BigDecimal.ZERO;
                        topCostCenters.add(new DashboardOverviewDto.CostCenterUsageDto(name, cost));
                }

                // Get Telephony Type Breakdown
                Slice<TelephonyTypeUsageGroupDto> telephonyReport = telephonyUsageReportService
                                .generateTelephonyTypeUsageReport(startDate, endDate, PageRequest.of(0, 100));

                List<DashboardOverviewDto.TelephonyTypeCostDto> telephonyCost = new ArrayList<>();
                for (TelephonyTypeUsageGroupDto group : telephonyReport.getContent()) {
                        String name = group.getCategoryName();
                        BigDecimal cost = group.getSubtotal() != null
                                        && group.getSubtotal().getTotalBilledAmount() != null
                                                        ? group.getSubtotal().getTotalBilledAmount()
                                                        : BigDecimal.ZERO;
                        telephonyCost.add(new DashboardOverviewDto.TelephonyTypeCostDto(name, cost));
                }

                return DashboardOverviewDto.builder()
                                .totalCalls(totalCalls)
                                .totalCost(totalCost)
                                .totalDurationSeconds(totalDurationSeconds)
                                .averageCostPerMinute(avgCost)
                                .processingFailures(processingFailures)
                                .unassignedCalls(unassignedCalls)
                                .costByTelephonyType(telephonyCost)
                                .topCostCenters(topCostCenters)
                                .build();
        }
}
