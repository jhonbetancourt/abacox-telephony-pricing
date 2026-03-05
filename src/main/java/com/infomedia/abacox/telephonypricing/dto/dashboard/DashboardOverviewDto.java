package com.infomedia.abacox.telephonypricing.dto.dashboard;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DashboardOverviewDto {
    private BigDecimal totalCost;
    private Long totalCalls;
    private Long totalDurationSeconds;
    private BigDecimal averageCostPerMinute;

    // Simple breakdown for the donut chart
    private List<TelephonyTypeCostDto> costByTelephonyType;

    // Top 5 cost centers for the bar chart
    private List<CostCenterUsageDto> topCostCenters;

    // System health
    private Long processingFailures;
    private Long unassignedCalls;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class TelephonyTypeCostDto {
        private String telephonyTypeName;
        private BigDecimal cost;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CostCenterUsageDto {
        private String costCenterName;
        private BigDecimal cost;
    }
}
