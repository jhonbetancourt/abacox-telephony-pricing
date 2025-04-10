package com.infomedia.abacox.telephonypricing.dto.origincountry;

import com.infomedia.abacox.telephonypricing.dto.superclass.ActivableLegacyMapping;
import com.infomedia.abacox.telephonypricing.entity.OriginCountry;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

/**
 * DTO for {@link OriginCountry}
 */
@EqualsAndHashCode(callSuper = true)
@Data
@AllArgsConstructor
@NoArgsConstructor
public class OriginCountryLegacyMapping extends ActivableLegacyMapping {
    private String id;
    private String currencySymbol;
    private String name;
    private String code;
}