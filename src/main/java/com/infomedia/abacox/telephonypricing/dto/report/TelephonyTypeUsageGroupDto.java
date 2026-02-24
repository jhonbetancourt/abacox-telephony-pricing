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
public class TelephonyTypeUsageGroupDto {
    private String categoryName;
    private List<TelephonyTypeUsageReportDto> items;
    private TelephonyTypeUsageReportDto subtotal;
}
