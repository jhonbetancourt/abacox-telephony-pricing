package com.infomedia.abacox.telephonypricing.dto.report;

import lombok.Data;
import java.math.BigDecimal;

@Data
public class EmployeeAuthCodeUsageReportDto {
    private String employeeName;
    private String authCode;
    private String operatorName;
    private String telephonyTypeName;
    private Long callCount;
    private Long totalDuration;
    private BigDecimal durationPercentage;
    private BigDecimal totalBilledAmount;
    private BigDecimal billedAmountPercentage;
}