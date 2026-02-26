package com.infomedia.abacox.telephonypricing.dto.report;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MonthlySubdivisionUsageReportDto {
    private Long subdivisionId;
    private String subdivisionName;
    @Builder.Default
    private List<MonthlyCostDto> monthlyCosts = new ArrayList<>();
    @Builder.Default
    private BigDecimal totalVariation = BigDecimal.ZERO;
}