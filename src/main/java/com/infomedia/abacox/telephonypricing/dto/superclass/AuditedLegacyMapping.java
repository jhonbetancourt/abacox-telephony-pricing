package com.infomedia.abacox.telephonypricing.dto.superclass;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

@AllArgsConstructor
@NoArgsConstructor
@Data
@SuperBuilder(toBuilder = true)
public class AuditedLegacyMapping {
    private String createdDate;
    private String createdBy;
    private String lastModifiedDate;
    private String lastModifiedBy;
}
