package com.infomedia.abacox.telephonypricing.dto.trunkrate;

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
 * DTO for {@link com.infomedia.abacox.telephonypricing.entity.TrunkRate}
 */
@EqualsAndHashCode(callSuper = true)
@Data
@AllArgsConstructor
@NoArgsConstructor
public class TrunkRateDto extends ActivableDto {
    private Long id;
    private Long trunkId;
    private BigDecimal rateValue;
    private Boolean includesVat;
    private Long operatorId;
    private Long telephonyTypeId;
    private Boolean noPbxPrefix;
    private Boolean noPrefix;
    private Integer seconds;
    private TrunkDto trunk;
    private OperatorDto operator;
    private TelephonyTypeDto telephonyType;
}