package com.infomedia.abacox.telephonypricing.dto.prefix;

import com.infomedia.abacox.telephonypricing.dto.operator.OperatorDto;
import com.infomedia.abacox.telephonypricing.dto.telephonytype.TelephonyTypeDto;
import com.infomedia.abacox.telephonypricing.dto.superclass.AuditedDto;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * DTO for {@link com.infomedia.abacox.telephonypricing.entity.Prefix}
 */
@EqualsAndHashCode(callSuper = true)
@Data
@AllArgsConstructor
@NoArgsConstructor
public class PrefixDto extends AuditedDto {
    private Long id;
    private Long operatorId;
    private Long telephoneTypeId;
    private String code;
    private BigDecimal baseValue;
    private boolean bandOk;
    private boolean vatIncluded;
    private BigDecimal vatValue;
    private OperatorDto operator;
    private TelephonyTypeDto telephonyType;
}