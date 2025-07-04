package com.infomedia.abacox.telephonypricing.dto.subdivision;

import com.infomedia.abacox.telephonypricing.dto.superclass.ActivableDto;
import com.infomedia.abacox.telephonypricing.db.entity.Subdivision;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

/**
 * DTO for {@link Subdivision}
 */
@EqualsAndHashCode(callSuper = true)
@Data
@AllArgsConstructor
@NoArgsConstructor
public class SubdivisionDto extends ActivableDto {
    private Long id;
    private Long parentSubdivisionId;
    private Subdivision parentSubdivision;
    private String name;
}