package com.infomedia.abacox.telephonypricing.dto.band;

import com.infomedia.abacox.telephonypricing.dto.indicator.IndicatorDto;
import com.infomedia.abacox.telephonypricing.dto.prefix.PrefixDto;
import com.infomedia.abacox.telephonypricing.dto.superclass.ActivableDto;
import com.infomedia.abacox.telephonypricing.db.entity.Band;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;


/**
 * DTO for {@link Band}
 */
@EqualsAndHashCode(callSuper = true)
@Data
@AllArgsConstructor
@NoArgsConstructor
public class BandDto extends ActivableDto {
    private Long id;
    private Long prefixId;
    private PrefixDto prefix;
    private String name;
    private BigDecimal value;
    private boolean vatIncluded;
    private Long originIndicatorId;
    private IndicatorDto originIndicator;
    private Long reference;
}