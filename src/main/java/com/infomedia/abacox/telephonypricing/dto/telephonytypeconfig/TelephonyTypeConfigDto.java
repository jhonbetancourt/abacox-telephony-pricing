package com.infomedia.abacox.telephonypricing.dto.telephonytypeconfig;

import com.infomedia.abacox.telephonypricing.dto.origincountry.OriginCountryDto;
import com.infomedia.abacox.telephonypricing.dto.superclass.ActivableDto;
import com.infomedia.abacox.telephonypricing.dto.telephonytype.TelephonyTypeDto;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

/**
 * DTO for {@link com.infomedia.abacox.telephonypricing.entity.TelephonyTypeConfig}
 */
@EqualsAndHashCode(callSuper = true)
@Data
@AllArgsConstructor
@NoArgsConstructor
public class TelephonyTypeConfigDto extends ActivableDto {
    private Long id;
    private Integer minValue;
    private Integer maxValue;
    private Long telephonyTypeId;
    private Long originCountryId;
    private TelephonyTypeDto telephonyType;
    private OriginCountryDto originCountry;
}