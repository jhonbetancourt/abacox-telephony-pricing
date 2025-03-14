package com.infomedia.abacox.telephonypricing.dto.subdivision;

import com.infomedia.abacox.telephonypricing.dto.superclass.AuditedLegacyMapping;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

@EqualsAndHashCode(callSuper = true)
@Data
@AllArgsConstructor
@NoArgsConstructor
public class SubdivisionLegacyMapping extends AuditedLegacyMapping {
    private String id;
    private String parentSubdivisionId;
    private String name;
}