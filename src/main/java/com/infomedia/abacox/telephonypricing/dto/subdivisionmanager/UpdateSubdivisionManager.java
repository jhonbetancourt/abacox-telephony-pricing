package com.infomedia.abacox.telephonypricing.dto.subdivisionmanager;

import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import org.openapitools.jackson.nullable.JsonNullable;

/**
 * DTO for updating {@link com.infomedia.abacox.telephonypricing.entity.SubdivisionManager}
 */
@EqualsAndHashCode(callSuper = false)
@Data
@AllArgsConstructor
@NoArgsConstructor
public class UpdateSubdivisionManager {
    @NotNull
    private JsonNullable<Long> subdivisionId = JsonNullable.undefined();
    
    @NotNull
    private JsonNullable<Long> managerId = JsonNullable.undefined();
}