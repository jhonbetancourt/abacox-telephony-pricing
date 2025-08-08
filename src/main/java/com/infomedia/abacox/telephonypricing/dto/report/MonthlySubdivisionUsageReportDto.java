package com.infomedia.abacox.telephonypricing.dto.report;

import lombok.Data;
import java.math.BigDecimal;

@Data
public class MonthlySubdivisionUsageReportDto {
    private Long parentSubdivisionId;
    private String parentSubdivisionName;
    private Long subdivisionId;
    private String subdivisionName;
    private Integer year;
    private Integer month;
    private BigDecimal totalBilledAmount;
    private Long totalDuration;
    private Long outgoingCallCount;
    private Long incomingCallCount;
}