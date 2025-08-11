package com.infomedia.abacox.telephonypricing.dto.report;

import lombok.Data;
import java.math.BigDecimal;

@Data
public class DialedNumberUsageReportDto {
    private String phoneNumber;
    private String companyName;
    private String contactName;
    private String destinationCity;
    private String destinationCountry;
    private Long callCount;
    private Long totalDuration;
    private BigDecimal totalBilledAmount;
}