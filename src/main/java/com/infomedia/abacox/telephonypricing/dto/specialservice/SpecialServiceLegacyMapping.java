package com.infomedia.abacox.telephonypricing.dto.specialservice;

import com.infomedia.abacox.telephonypricing.dto.superclass.ActivableLegacyMapping;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

@EqualsAndHashCode(callSuper = true)
@Data
@AllArgsConstructor
@NoArgsConstructor
public class SpecialServiceLegacyMapping extends ActivableLegacyMapping {
    private String id;
    private String indicatorId;
    private String phoneNumber;
    private String value;
    private String vatAmount;
    private String vatIncluded;
    private String description;
    private String originCountryId;
}