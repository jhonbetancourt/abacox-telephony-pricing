package com.infomedia.abacox.telephonypricing.dto.report;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class EmployeeCallReportDto {
    private Long employeeId;
    private String employeeName;
    private Long outgoingCalls;
    private Long incomingCalls;
    private BigDecimal totalCost;
}