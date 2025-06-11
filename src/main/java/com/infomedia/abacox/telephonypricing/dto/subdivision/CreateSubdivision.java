package com.infomedia.abacox.telephonypricing.dto.subdivision;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

/**
 * DTO for creating {@link com.infomedia.abacox.telephonypricing.db.entity.Subdivision}
 */
@EqualsAndHashCode(callSuper = false)
@Data
@AllArgsConstructor
@NoArgsConstructor
public class CreateSubdivision {
    private Long parentSubdivisionId;
    
    @NotBlank
    @Size(max = 200)
    private String name;
}