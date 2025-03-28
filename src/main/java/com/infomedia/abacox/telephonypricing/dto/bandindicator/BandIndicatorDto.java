package com.infomedia.abacox.telephonypricing.dto.bandindicator;

import com.infomedia.abacox.telephonypricing.dto.band.BandDto;
import com.infomedia.abacox.telephonypricing.dto.indicator.IndicatorDto;
import com.infomedia.abacox.telephonypricing.dto.superclass.AuditedDto;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

/**
 * DTO for {@link com.infomedia.abacox.telephonypricing.entity.BandIndicator}
 */
@EqualsAndHashCode(callSuper = true)
@Data
@AllArgsConstructor
@NoArgsConstructor
public class BandIndicatorDto extends AuditedDto {
    private Long id;
    private Long bandId;
    private Long indicatorId;
    private BandDto band;
    private IndicatorDto indicator;
}