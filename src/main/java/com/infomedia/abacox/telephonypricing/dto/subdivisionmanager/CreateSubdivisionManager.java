package com.infomedia.abacox.telephonypricing.dto.subdivisionmanager;

import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

/**
 * DTO for {@link com.infomedia.abacox.telephonypricing.entity.SubdivisionManager}
 */
@EqualsAndHashCode(callSuper = false)
@Data
@AllArgsConstructor
@NoArgsConstructor
public class CreateSubdivisionManager {
    @NotNull
    private Long subdivisionId;
    
    @NotNull
    private Long managerId;
}