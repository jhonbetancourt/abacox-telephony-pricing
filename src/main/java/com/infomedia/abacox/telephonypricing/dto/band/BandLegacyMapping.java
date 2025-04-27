package com.infomedia.abacox.telephonypricing.dto.band;

import com.infomedia.abacox.telephonypricing.dto.superclass.ActivableLegacyMapping;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

@EqualsAndHashCode(callSuper = true)
@Data
@AllArgsConstructor
@NoArgsConstructor
public class BandLegacyMapping extends ActivableLegacyMapping {
    private String id;
    private String prefixId;
    private String name;
    private String value;
    private String vatIncluded;
    private String originIndicatorId;
    private String reference;
}