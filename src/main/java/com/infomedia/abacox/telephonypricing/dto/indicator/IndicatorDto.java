package com.infomedia.abacox.telephonypricing.dto.indicator;

import com.infomedia.abacox.telephonypricing.dto.operator.OperatorDto;
import com.infomedia.abacox.telephonypricing.dto.superclass.ActivableDto;
import com.infomedia.abacox.telephonypricing.dto.telephonytype.TelephonyTypeDto;
import com.infomedia.abacox.telephonypricing.dto.origincountry.OriginCountryDto;
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
public class IndicatorDto extends ActivableDto {
    private Long id;
    private String departmentCountry;
    private String cityName;
    private Long operatorId;
    private Long originCountryId;
    private Long telephonyTypeId;
    private TelephonyTypeDto telephonyType;
    private OperatorDto operator;
    private OriginCountryDto originCountry;
}