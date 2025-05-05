package com.infomedia.abacox.telephonypricing.dto.trunkrule;

import com.infomedia.abacox.telephonypricing.dto.indicator.IndicatorDto;
import com.infomedia.abacox.telephonypricing.dto.operator.OperatorDto;
import com.infomedia.abacox.telephonypricing.dto.superclass.ActivableDto;
import com.infomedia.abacox.telephonypricing.dto.telephonytype.TelephonyTypeDto;
import com.infomedia.abacox.telephonypricing.dto.trunk.TrunkDto;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * DTO for {@link com.infomedia.abacox.telephonypricing.entity.TrunkRule}
 */
@EqualsAndHashCode(callSuper = true)
@Data
@AllArgsConstructor
@NoArgsConstructor
public class TrunkRuleDto extends ActivableDto {
    private Long id;
    private BigDecimal rateValue;
    private Boolean includesVat;
    private Long telephonyTypeId;
    private String indicatorIds;
    private Long trunkId;
    private Long newOperatorId;
    private Long newTelephonyTypeId;
    private Integer seconds;
    private Long originIndicatorId;
    private TelephonyTypeDto telephonyType;
    private TrunkDto trunk;
    private OperatorDto newOperator;
    private TelephonyTypeDto newTelephonyType;
    private IndicatorDto originIndicator;
}