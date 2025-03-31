package com.infomedia.abacox.telephonypricing.dto.company;

import com.infomedia.abacox.telephonypricing.dto.superclass.ActivableLegacyMapping;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

@EqualsAndHashCode(callSuper = true)
@Data
@AllArgsConstructor
@NoArgsConstructor
public class CompanyLegacyMapping extends ActivableLegacyMapping {
    private String id;
    private String additionalInfo;
    private String address;
    private String name;
    private String taxId;
    private String legalName;
    private String website;
    private String indicatorId;
}