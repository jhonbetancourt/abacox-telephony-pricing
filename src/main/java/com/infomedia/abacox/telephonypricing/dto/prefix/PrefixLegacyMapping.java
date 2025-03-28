package com.infomedia.abacox.telephonypricing.dto.prefix;

import com.infomedia.abacox.telephonypricing.dto.superclass.AuditedLegacyMapping;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

@EqualsAndHashCode(callSuper = true)
@Data
@AllArgsConstructor
@NoArgsConstructor
public class PrefixLegacyMapping extends AuditedLegacyMapping {
    private String id;
    private String operatorId;
    private String telephoneTypeId;
    private String code;
    private String baseValue;
    private String bandOk;
    private String vatIncluded;
    private String vatValue;
}