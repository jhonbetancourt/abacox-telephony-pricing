package com.infomedia.abacox.telephonypricing.dto.report;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.infomedia.abacox.telephonypricing.constants.DateTimePattern;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CorporateReportDto {

    private Long callRecordId;
    private String originPhone;
    private String destinationPhone;
    private String originLocation;
    private String destinationLocation;
    private String costCenter;
    private Long telephonyTypeId;
    private String telephonyTypeName;
    private Long employeeId;
    private String employeeName;
    private Long destinationEmployeeId;
    private String destinationEmployeeName;
    private Long operatorId;
    private String operatorName;
    @JsonFormat(pattern = DateTimePattern.DATE_TIME)
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
    private Long originCountryId;
    private String originCountryName;
    private Long subdivisionId;
    private String subdivisionName;
    private Boolean contactType;
    private String contactName;
    private Long contactEmployeeId;
    private String companyName;
}