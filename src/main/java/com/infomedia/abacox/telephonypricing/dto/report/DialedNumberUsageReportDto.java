package com.infomedia.abacox.telephonypricing.dto.report;

import lombok.Data;
import java.math.BigDecimal;

@Data
public class DialedNumberUsageReportDto {
    private String phoneNumber;
    private String companyName;
    private String contactName;
    private String city;
    private Long callCount;
    private Long totalDuration;
    private BigDecimal totalBilledAmount;
    private String destinationEmployeeName;
    private Long destinationSubdivisionId;
}