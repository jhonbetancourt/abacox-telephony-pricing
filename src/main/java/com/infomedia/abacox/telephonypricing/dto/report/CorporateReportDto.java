// src/main/java/com/infomedia/abacox/telephonypricing/dto/callrecord/CorporativeReportEntry.java

package com.infomedia.abacox.telephonypricing.dto.report;

import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CorporateReportDto {

    private Long callRecordId;
    private Long telephonyTypeId;
    private String telephonyTypeName;
    private Long employeeId;
    private String employeeName;
    private Long destinationEmployeeId;
    private String destinationEmployeeName;
    private String employeeExtension;
    private String dial;
    private String destinationPhone;
    private Long operatorId;
    private String operatorName;
    private LocalDateTime serviceDate;
    private Boolean isIncoming;
    private Long indicatorId;
    private Long commLocationId;
    private String commLocationDirectory;
    private String trunk;
    private String initialTrunk;
    private String employeeTransfer;
    private Integer assignmentCause;
    private Integer duration;
    private Integer ringCount;
    private BigDecimal billedAmount;
    private BigDecimal pricePerMinute;
    private Integer transferCause;
    private String originDepartmentCountry;
    private String originCity;
    private String destinationDepartmentCountry;
    private String destinationCity;
    private Long originCountryId;
    private Long subdivisionId;
    private String costCenterName;
    private String workOrder;
    private Boolean contactType;
    private String contactName;
    private Long contactEmployeeId;
    private String companyName;
}