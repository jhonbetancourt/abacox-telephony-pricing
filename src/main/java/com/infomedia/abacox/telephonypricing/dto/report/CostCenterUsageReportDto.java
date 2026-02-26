package com.infomedia.abacox.telephonypricing.dto.report;

import lombok.Data;
import java.math.BigDecimal;

@Data
public class CostCenterUsageReportDto {
    private Long costCenterId;
    private Long originCountryId;
    private String costCenterName;
    private Long incomingCallCount;
    private Long outgoingCallCount;
    private Long totalDuration;
    private BigDecimal durationPercentage;
    private BigDecimal totalBilledAmount;
    private BigDecimal billedAmountPercentage;
}