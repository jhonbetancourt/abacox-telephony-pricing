package com.infomedia.abacox.telephonypricing.dto.indicator;

import com.infomedia.abacox.telephonypricing.dto.city.CityDto;
import com.infomedia.abacox.telephonypricing.dto.operator.OperatorDto;
import com.infomedia.abacox.telephonypricing.dto.telephonytype.TelephonyTypeDto;
import com.infomedia.abacox.telephonypricing.dto.origincountry.OriginCountryDto;
import com.infomedia.abacox.telephonypricing.dto.superclass.AuditedDto;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

/**
 * DTO for {@link com.infomedia.abacox.telephonypricing.entity.Indicator}
 */
@EqualsAndHashCode(callSuper = true)
@Data
@AllArgsConstructor
@NoArgsConstructor
public class IndicatorDto extends AuditedDto {
    private Long id;
    private String departmentCountry;
    private Long cityId;
    private String cityName;
    private CityDto city;
    private boolean isAssociated;
    private Long operatorId;
    private Long originCountryId;
    private Long telephonyTypeId;
    private TelephonyTypeDto telephonyType;
    private OperatorDto operator;
    private OriginCountryDto originCountry;
}