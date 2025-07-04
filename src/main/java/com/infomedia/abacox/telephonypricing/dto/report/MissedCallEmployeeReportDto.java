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
    private Long missedCallCount;
}