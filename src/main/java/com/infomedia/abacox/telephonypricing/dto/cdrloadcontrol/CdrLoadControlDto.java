package com.infomedia.abacox.telephonypricing.dto.cdrloadcontrol;

import com.infomedia.abacox.telephonypricing.dto.planttype.PlantTypeDto;
import com.infomedia.abacox.telephonypricing.dto.superclass.ActivableDto;
import com.infomedia.abacox.telephonypricing.db.entity.CdrLoadControl;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

/**
 * DTO for {@link CdrLoadControl}
 */
@EqualsAndHashCode(callSuper = true)
@Data
@AllArgsConstructor
@NoArgsConstructor
public class CdrLoadControlDto extends ActivableDto {
    private Long id;
    private String name;
    private Long plantTypeId;
    private PlantTypeDto plantType;
}
