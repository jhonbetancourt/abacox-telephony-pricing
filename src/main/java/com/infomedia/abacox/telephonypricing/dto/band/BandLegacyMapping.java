package com.infomedia.abacox.telephonypricing.dto.band;

import com.infomedia.abacox.telephonypricing.dto.superclass.AuditedLegacyMapping;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

@EqualsAndHashCode(callSuper = true)
@Data
@AllArgsConstructor
@NoArgsConstructor
public class BandLegacyMapping extends AuditedLegacyMapping {
    private String id;
    private String prefixId;
    private String name;
    private String value;
    private String vatIncluded;
    private String minDistance;
    private String maxDistance;
    private String bandGroupId;
}