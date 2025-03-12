package com.infomedia.abacox.telephonypricing.dto.planttype;

import com.infomedia.abacox.telephonypricing.dto.superclass.AuditedDto;
import com.infomedia.abacox.telephonypricing.entity.PlantType;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

/**
 * DTO for {@link PlantType}
 */
@EqualsAndHashCode(callSuper = true)
@Data
@AllArgsConstructor
@NoArgsConstructor
public class PlantTypeDto extends AuditedDto {
    private Long id;
    private String name;
}