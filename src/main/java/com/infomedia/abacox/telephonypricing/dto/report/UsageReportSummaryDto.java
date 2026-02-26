package com.infomedia.abacox.telephonypricing.dto.report;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * DTO for report summary/total rows.
 * Separated from main entity DTOs to avoid confusion with actual records.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UsageReportSummaryDto {
    private String rowType; // e.g., "SUBTOTAL", "UNASSIGNED"
    private String label; // e.g., "Subtotal (Assigned Calls)", "Unassigned Information"
    private Long incomingCallCount;
    private Long outgoingCallCount;
    private Long totalDuration;
    private BigDecimal totalBilledAmount;
    private BigDecimal durationPercentage;
    private BigDecimal billedAmountPercentage;
}
