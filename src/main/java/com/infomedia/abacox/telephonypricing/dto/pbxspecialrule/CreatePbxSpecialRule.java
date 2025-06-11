package com.infomedia.abacox.telephonypricing.dto.pbxspecialrule;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

/**
 * DTO for {@link com.infomedia.abacox.telephonypricing.db.entity.PbxSpecialRule}
 */
@EqualsAndHashCode(callSuper = false)
@Data
@AllArgsConstructor
@NoArgsConstructor
public class CreatePbxSpecialRule {
    @NotBlank
    @Size(max = 200)
    private String name;
    
    @NotBlank
    @Size(max = 50)
    private String searchPattern;
    
    @NotBlank
    @Size(max = 200)
    private String ignorePattern;
    
    @NotBlank
    @Size(max = 50)
    private String replacement;
    
    private Long commLocationId;
    
    @NotNull
    private Integer minLength;
    
    @NotNull
    private Integer direction;
}