package com.infomedia.abacox.telephonypricing.dto.origincountry;

import com.infomedia.abacox.telephonypricing.dto.superclass.ActivableDto;
import com.infomedia.abacox.telephonypricing.db.entity.OriginCountry;
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
public class OriginCountryDto extends ActivableDto {
    private Long id;
    private String currencySymbol;
    private String name;
    private String code;
}