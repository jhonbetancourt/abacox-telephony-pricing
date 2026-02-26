package com.infomedia.abacox.telephonypricing.dto.report;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.infomedia.abacox.telephonypricing.constants.DateTimePattern;
import lombok.Data;
import java.time.LocalDateTime;

@Data
public class UnusedExtensionReportDto {
   private Long employeeId;
   private String employeeName;
   private String extension;
   @JsonFormat(pattern = DateTimePattern.DATE_TIME)
   private LocalDateTime employeeHistoryStartDate;
   private String subdivisionName;
   private String costCenter;
   private String plant;
   @JsonFormat(pattern = DateTimePattern.DATE_TIME)
   private LocalDateTime lastCallDate;
   private Long outgoingCalls;
   private Long incomingCalls;
   private Long totalCalls;
}
