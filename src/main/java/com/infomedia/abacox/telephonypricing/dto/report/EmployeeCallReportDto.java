package com.infomedia.abacox.telephonypricing.dto.report;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.List;
import java.util.ArrayList;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class EmployeeCallReportDto {
    private Long employeeId;
    private String employeeName;
    private String extension;
    private String cityName;
    private Long outgoingCalls;
    private Long incomingCalls;
    private BigDecimal totalCost;
    private List<TelephonyTypeCostDto> telephonyCosts = new ArrayList<>();
}