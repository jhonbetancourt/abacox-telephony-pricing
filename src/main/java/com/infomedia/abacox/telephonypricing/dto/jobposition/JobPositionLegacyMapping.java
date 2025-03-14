package com.infomedia.abacox.telephonypricing.dto.jobposition;

import com.infomedia.abacox.telephonypricing.dto.superclass.AuditedLegacyMapping;
import com.infomedia.abacox.telephonypricing.entity.JobPosition;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

/**
 * DTO for {@link JobPosition}
 */
@EqualsAndHashCode(callSuper = true)
@Data
@AllArgsConstructor
@NoArgsConstructor
public class JobPositionLegacyMapping extends AuditedLegacyMapping {
    private String id;
    private String name;
}