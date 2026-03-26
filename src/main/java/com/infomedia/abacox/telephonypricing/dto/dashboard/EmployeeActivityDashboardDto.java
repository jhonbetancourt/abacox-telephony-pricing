package com.infomedia.abacox.telephonypricing.dto.dashboard;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EmployeeActivityDashboardDto {

    // Totals
    private long totalExtensions;
    private long totalIncomingCalls;
    private long totalOutgoingCalls;
    private long totalCalls;

    // 1. Usability distribution (pie)
    private long usedCount;
    private long unusedCount;

    // 2. Equipment distribution (donut)
    private List<EquipmentDistributionItem> equipmentDistribution;

    // 3. Top extensions by call volume (bar/table)
    private List<ExtensionCallsItem> topExtensions;

    // 4. Calls aggregated by location (map / bar)
    private List<LocationCallsItem> callsByLocation;

    // 5. Top subdivisions (bar/table)
    private List<SubdivisionActivityItem> topSubdivisions;

    // 6. Top cost centers (bar/table)
    private List<CostCenterActivityItem> topCostCenters;

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class EquipmentDistributionItem {
        private String equipmentTypeName;
        private long count;
        private double percentage;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ExtensionCallsItem {
        private String extension;
        private String employeeName;
        private long incomingCallCount;
        private long outgoingCallCount;
        private long totalCallCount;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class LocationCallsItem {
        private String departmentCountry;
        private String cityName;
        private long incomingCallCount;
        private long outgoingCallCount;
        private long totalCallCount;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class SubdivisionActivityItem {
        private Long subdivisionId;
        private String subdivisionName;
        private long extensionCount;
        private long incomingCallCount;
        private long outgoingCallCount;
        private long totalCallCount;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CostCenterActivityItem {
        private Long costCenterId;
        private String costCenterName;
        private long extensionCount;
        private long incomingCallCount;
        private long outgoingCallCount;
        private long totalCallCount;
    }
}
