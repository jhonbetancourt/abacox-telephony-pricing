package com.infomedia.abacox.telephonypricing.dto.report;

import lombok.Data;
import java.math.BigDecimal;

@Data
public class DestinationUsageReportDto {
    private String cityName;
    private String departmentCountryName;
    private String telephonyTypeName;
    private Long callCount;
    private Long totalDuration;
    private BigDecimal totalBilledAmount;
}