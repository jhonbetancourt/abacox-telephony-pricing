package com.infomedia.abacox.telephonypricing.dto.report;

import lombok.Data;
import java.math.BigDecimal;

@Data
public class HighestConsumptionEmployeeReportDto {
    private String employeeName;
    private String extension;
    private String originCity;
    private String originDepartmentCountry;
    private Long callCount;
    private Long totalDuration;
    private BigDecimal totalBilledAmount;
}