package com.infomedia.abacox.telephonypricing.dto.bandindicator;

import com.infomedia.abacox.telephonypricing.dto.superclass.ActivableLegacyMapping;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

@EqualsAndHashCode(callSuper = true)
@Data
@AllArgsConstructor
@NoArgsConstructor
public class BandIndicatorLegacyMapping extends ActivableLegacyMapping {
    private String id;
    private String bandId;
    private String indicatorId;
}