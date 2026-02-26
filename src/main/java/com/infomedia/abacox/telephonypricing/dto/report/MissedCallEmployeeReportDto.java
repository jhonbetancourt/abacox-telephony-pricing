package com.infomedia.abacox.telephonypricing.dto.report;

import lombok.Data;

/**
 * DTO for the "Missed / Abandoned Calls by Employee" report.
 */
@Data
public class MissedCallEmployeeReportDto {
    private Long employeeId;
    private String employeeName;
    private String employeeExtension;
    private String subdivisionName;
    private Double averageRingTime;
    private Long missedCallCount;
    private Long totalCallCount;
    private Double missedCallPercentage;
}