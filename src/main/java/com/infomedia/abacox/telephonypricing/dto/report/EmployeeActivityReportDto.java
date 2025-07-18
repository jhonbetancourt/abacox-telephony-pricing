package com.infomedia.abacox.telephonypricing.dto.report;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.infomedia.abacox.telephonypricing.constants.DateTimePattern;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class EmployeeActivityReportDto {
    private Long employeeId;
    private String employeeName;
    private String extension;
   // private String deviceModel;
   // private String deviceType;
    private String costCenterWorkOrder;
    private String subdivisionName;
    private String officeLocation;
    private Long outgoingCallCount;
    private Long incomingCallCount;
    @JsonFormat(pattern = DateTimePattern.DATE_TIME)
    private LocalDateTime lastIncomingCallDate;
    @JsonFormat(pattern = DateTimePattern.DATE_TIME)
    private LocalDateTime lastOutgoingCallDate;
}