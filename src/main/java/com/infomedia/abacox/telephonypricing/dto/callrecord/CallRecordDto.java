package com.infomedia.abacox.telephonypricing.dto.callrecord;

import com.infomedia.abacox.telephonypricing.dto.commlocation.CommLocationDto;
import com.infomedia.abacox.telephonypricing.dto.employee.EmployeeDto;
import com.infomedia.abacox.telephonypricing.dto.indicator.IndicatorDto;
import com.infomedia.abacox.telephonypricing.dto.operator.OperatorDto;
import com.infomedia.abacox.telephonypricing.dto.superclass.AuditedDto;
import com.infomedia.abacox.telephonypricing.dto.telephonytype.TelephonyTypeDto;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * DTO for {@link com.infomedia.abacox.telephonypricing.db.entity.CallRecord}
 */
@EqualsAndHashCode(callSuper = true)
@Data
@AllArgsConstructor
@NoArgsConstructor
public class CallRecordDto extends AuditedDto {
    private Long id;
    private String dial;
    private Long commLocationId;
    private CommLocationDto commLocation;
    private LocalDateTime serviceDate;
    private Long operatorId;
    private OperatorDto operator;
    private String employeeExtension;
    private String employeeKey;
    private Long indicatorId;
    private IndicatorDto indicator;
    private String destinationPhone;
    private Integer duration;
    private Integer ringCount;
    private Long telephonyTypeId;
    private TelephonyTypeDto telephonyType;
    private BigDecimal billedAmount;
    private BigDecimal pricePerMinute;
    private BigDecimal initialPrice;
    private boolean isIncoming;
    private String trunk;
    private String initialTrunk;
    private Long employeeId;
    private EmployeeDto employee;
    private String employeeTransfer;
    private Integer transferCause;
    private Integer assignmentCause;
    private Long destinationEmployeeId;
    private EmployeeDto destinationEmployee;
    private Long fileInfoId;
}