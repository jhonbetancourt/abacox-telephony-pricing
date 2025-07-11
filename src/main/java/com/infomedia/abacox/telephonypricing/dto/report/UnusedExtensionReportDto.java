 package com.infomedia.abacox.telephonypricing.dto.report;

 import com.fasterxml.jackson.annotation.JsonFormat;
 import com.infomedia.abacox.telephonypricing.constants.DateTimePattern;
 import lombok.Data;
 import java.math.BigDecimal;
 import java.time.LocalDateTime;

 @Data
 public class UnusedExtensionReportDto {
    private Long employeeId;
    private String employeeName;
    private String extension;
    private String subdivisionName;
    private String costCenterName;
    private String costCenterWorkOrder;
    private String commLocationDirectory;
    private String indicatorDepartmentCountry;
    private String indicatorCityName;
    @JsonFormat(pattern = DateTimePattern.DATE_TIME)
    private LocalDateTime lastCallDate;
 }