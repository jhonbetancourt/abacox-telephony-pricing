package com.infomedia.abacox.telephonypricing.dto.indicator;

import com.infomedia.abacox.telephonypricing.dto.superclass.AuditedLegacyMapping;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

@EqualsAndHashCode(callSuper = true)
@Data
@AllArgsConstructor
@NoArgsConstructor
public class IndicatorLegacyMapping extends AuditedLegacyMapping {
    private String id;
    private String telephonyTypeId;
    private String departmentCountry;
    private String cityId;
    private String cityName;
    private String isAssociated;
    private String operatorId;
    private String originCountryId;
}