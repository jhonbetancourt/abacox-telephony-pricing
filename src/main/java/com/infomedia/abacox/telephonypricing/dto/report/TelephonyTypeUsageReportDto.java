package com.infomedia.abacox.telephonypricing.dto.report;

import lombok.Data;
import java.math.BigDecimal;

@Data
public class TelephonyTypeUsageReportDto {
    private String telephonyTypeName;
    private Long outgoingCallCount;
    private Long incomingCallCount;
    private Long totalDuration;
    private BigDecimal durationPercentage;
    private BigDecimal totalBilledAmount;
    private BigDecimal billedAmountPercentage;
}