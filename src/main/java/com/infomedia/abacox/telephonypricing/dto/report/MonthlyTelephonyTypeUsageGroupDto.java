package com.infomedia.abacox.telephonypricing.dto.report;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MonthlyTelephonyTypeUsageGroupDto {
    private String telephonyTypeName;
    private List<MonthlyTelephonyTypeUsageReportDto> items;
    private MonthlyTelephonyTypeUsageReportDto subtotal;
}
