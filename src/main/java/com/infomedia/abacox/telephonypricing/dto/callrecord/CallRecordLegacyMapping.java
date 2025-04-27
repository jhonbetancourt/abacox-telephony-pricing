package com.infomedia.abacox.telephonypricing.dto.callrecord;

import com.infomedia.abacox.telephonypricing.dto.superclass.AuditedLegacyMapping;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

@EqualsAndHashCode(callSuper = true)
@Data
@AllArgsConstructor
@NoArgsConstructor
public class CallRecordLegacyMapping extends AuditedLegacyMapping {
    private String id;
    private String dial;
    private String commLocationId;
    private String serviceDate;
    private String operatorId;
    private String employeeExtension;
    private String employeeAuthCode;
    private String indicatorId;
    private String destinationPhone;
    private String duration;
    private String ringCount;
    private String telephonyTypeId;
    private String billedAmount;
    private String pricePerMinute;
    private String initialPrice;
    private String isIncoming;
    private String trunk;
    private String initialTrunk;
    private String employeeId;
    private String employeeTransfer;
    private String transferCause;
    private String assignmentCause;
    private String destinationEmployeeId;
    private String fileInfoId;
    private String centralizedId;
    private String originIp;
}