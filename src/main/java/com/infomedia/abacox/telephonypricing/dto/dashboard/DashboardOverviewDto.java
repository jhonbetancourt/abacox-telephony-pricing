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
    private Long totalIncomingCalls;
    private Long totalOutgoingCalls;
    private Long totalDurationSeconds;
    private BigDecimal averageCostPerMinute;

    // Donut chart — cost split by telephony category
    private List<TelephonyTypeCostDto> costByTelephonyType;

    // Horizontal bar charts
    private List<CostCenterUsageDto> topCostCenters;
    private List<SubdivisionSummaryDto> topSubdivisions;

    // Top employees table
    private List<EmployeeSummaryDto> topEmployees;

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
        private Long incomingCalls;
        private Long outgoingCalls;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CostCenterUsageDto {
        private String costCenterName;
        private BigDecimal cost;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class SubdivisionSummaryDto {
        private String subdivisionName;
        private BigDecimal totalCost;
        private Long totalCalls;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class EmployeeSummaryDto {
        private String employeeName;
        private String extension;
        private Long callCount;
        private Long totalDuration;
        private BigDecimal totalCost;
    }
}
