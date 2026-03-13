package com.infomedia.abacox.telephonypricing.dto.report;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class SubdivisionUsageByTypeReportDto {
    private Long subdivisionId;
    private String subdivisionName;
    private BigDecimal totalBilledAmount;
    private Long totalDuration;
    private List<TelephonyTypeCostDto> telephonyTypeCosts = new ArrayList<>();
}
