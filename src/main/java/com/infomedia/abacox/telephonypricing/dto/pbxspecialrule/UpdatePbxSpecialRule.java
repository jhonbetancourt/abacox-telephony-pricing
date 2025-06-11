package com.infomedia.abacox.telephonypricing.dto.pbxspecialrule;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import org.openapitools.jackson.nullable.JsonNullable;

/**
 * DTO for updating {@link com.infomedia.abacox.telephonypricing.db.entity.PbxSpecialRule}
 */
@EqualsAndHashCode(callSuper = false)
@Data
@AllArgsConstructor
@NoArgsConstructor
public class UpdatePbxSpecialRule {
    @NotBlank
    @Size(max = 200)
    private JsonNullable<String> name = JsonNullable.undefined();
    
    @NotBlank
    @Size(max = 50)
    private JsonNullable<String> searchPattern = JsonNullable.undefined();
    
    @NotBlank
    @Size(max = 200)
    private JsonNullable<String> ignorePattern = JsonNullable.undefined();
    
    @NotBlank
    @Size(max = 50)
    private JsonNullable<String> replacement = JsonNullable.undefined();
    
    private JsonNullable<Long> commLocationId = JsonNullable.undefined();
    
    @NotNull
    private JsonNullable<Integer> minLength = JsonNullable.undefined();
    
    @NotNull
    private JsonNullable<Integer> direction = JsonNullable.undefined();
}