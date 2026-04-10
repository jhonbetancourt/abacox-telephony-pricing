package com.infomedia.abacox.telephonypricing.dto.report;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * Aggregate totals across all cost center rows in a date range, including
 * the synthetic "unassigned" row (costCenterId == -1).
 *
 * Used by the dashboard overview KPIs.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CostCenterUsageTotalsDto {
    private BigDecimal totalBilledAmount;
    private long totalDurationSeconds;
    private long totalIncomingCalls;
    private long totalOutgoingCalls;
    private long unassignedCalls;
}
