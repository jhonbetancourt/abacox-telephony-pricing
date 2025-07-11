package com.infomedia.abacox.telephonypricing.dto.report;

 import lombok.Data;
 import java.math.BigDecimal;

 @Data
 public class ExtensionGroupReportDto {
    private Long employeeId;
    private String employeeName;
    private String extension;
    private String subdivisionName;
    private String indicatorDepartmentCountry;
    private String indicatorCityName;
    private Integer incomingCount;
    private Integer outgoingCount;
    private Integer voicemailCount;
    private Integer total;
    private BigDecimal percent;
 }
