package com.infomedia.abacox.telephonypricing.dto.report;

import lombok.Data;
import java.math.BigDecimal;

@Data
public class SubdivisionUsageByTypeReportDto {
    private Long subdivisionId;
    private String subdivisionName;
    private String telephonyTypeName;
    private BigDecimal totalBilledAmount;
    private Long totalDuration;
}