package com.infomedia.abacox.telephonypricing.dto.report;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.infomedia.abacox.telephonypricing.constants.DateTimePattern;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class EmployeeActivityReportDto {
    private Long employeeId;
    private String employeeName;
    private String extension;
    private LocalDate installationDate;
    private String equipmentTypeName;
    private String equipmentModelName;
    private String costCenterWorkOrder;
    private String subdivisionName;
    private String regionalName;
    private String officeLocation;
    private Long outgoingCallCount;
    private Long incomingCallCount;
    private Long totalCallCount;
    private Long outgoingDuration;
    private Long incomingDuration;
    private Long outgoingRingDuration;
    private Long incomingRingDuration;
    private Long transferCount;
    private Long conferenceCount;
    @JsonFormat(pattern = DateTimePattern.DATE_TIME)
    private LocalDateTime lastIncomingCallDate;
    @JsonFormat(pattern = DateTimePattern.DATE_TIME)
    private LocalDateTime lastOutgoingCallDate;
    private Boolean isUsed;
}