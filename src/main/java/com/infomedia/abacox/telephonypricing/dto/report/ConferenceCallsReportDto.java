package com.infomedia.abacox.telephonypricing.dto.report;

import lombok.Data;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
public class ConferenceCallsReportDto {
    private Long callRecordId;
    private LocalDateTime serviceDate;
    private String employeeExtension;
    private Integer duration;
    private Boolean isIncoming;
    private String dialedNumber;
    private BigDecimal billedAmount;
    private Long employeeId;
    private String employeeName;
    private Long subdivisionId;
    private String subdivisionName;
    private String employeeAuthCode;
    private Long operatorId;
    private String operatorName;
    private String telephonyTypeName;
    private Long telephonyTypeId;
    private String companyName;
    private Boolean contactType;
    private String contactName;
    private Long contactOwnerId;
    private Integer transferCause;
    private String transferKey;
}