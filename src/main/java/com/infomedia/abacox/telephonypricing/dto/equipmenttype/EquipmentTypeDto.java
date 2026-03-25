package com.infomedia.abacox.telephonypricing.dto.equipmenttype;

import com.infomedia.abacox.telephonypricing.dto.superclass.AuditedDto;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

/**
 * DTO for {@link com.infomedia.abacox.telephonypricing.db.entity.EquipmentType}
 */
@EqualsAndHashCode(callSuper = true)
@Data
@AllArgsConstructor
@NoArgsConstructor
public class EquipmentTypeDto extends AuditedDto {
    private Long id;
    private String name;
}
