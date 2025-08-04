package com.infomedia.abacox.telephonypricing.dto.report;

import lombok.Data;
import java.math.BigDecimal;

@Data
public class MonthlyTelephonyTypeUsageReportDto {
    private Integer year;
    private Integer month;
    private String telephonyTypeName;
    private Long incomingCallCount;
    private Long outgoingCallCount;
    private Long totalDuration;
    private BigDecimal durationPercentage;
    private BigDecimal totalBilledAmount;
    private BigDecimal billedAmountPercentage;
}