package com.infomedia.abacox.telephonypricing.dto.operator;

import com.infomedia.abacox.telephonypricing.dto.superclass.AuditedLegacyMapping;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

@EqualsAndHashCode(callSuper = true)
@Data
@AllArgsConstructor
@NoArgsConstructor
public class OperatorLegacyMapping extends AuditedLegacyMapping {
    private String id;
    private String name;
    private String originCountryId;
}