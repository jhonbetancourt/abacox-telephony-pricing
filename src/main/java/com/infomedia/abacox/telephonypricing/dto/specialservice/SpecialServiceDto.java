package com.infomedia.abacox.telephonypricing.dto.specialservice;

import com.infomedia.abacox.telephonypricing.dto.indicator.IndicatorDto; // Assuming this exists
import com.infomedia.abacox.telephonypricing.dto.origincountry.OriginCountryDto; // Assuming this exists
import com.infomedia.abacox.telephonypricing.dto.superclass.ActivableDto;
import com.infomedia.abacox.telephonypricing.dto.superclass.AuditedDto; // Assuming this exists
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

@EqualsAndHashCode(callSuper = true)
@Data
@AllArgsConstructor
@NoArgsConstructor
public class SpecialServiceDto extends ActivableDto {
    private Long id;
    private Long indicatorId;
    private String phoneNumber;
    private BigDecimal value;
    private BigDecimal vatAmount;
    private Boolean vatIncluded;
    private String description;
    private Long originCountryId;
    private IndicatorDto indicator;
    private OriginCountryDto originCountry;
}