package com.infomedia.abacox.telephonypricing.dto.pbxspecialrule;

import com.infomedia.abacox.telephonypricing.dto.commlocation.CommLocationDto;
import com.infomedia.abacox.telephonypricing.dto.superclass.ActivableDto;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

/**
 * DTO for {@link com.infomedia.abacox.telephonypricing.entity.PbxSpecialRule}
 */
@EqualsAndHashCode(callSuper = true)
@Data
@AllArgsConstructor
@NoArgsConstructor
public class PbxSpecialRuleDto extends ActivableDto {
    private Long id;
    private String name;
    private String searchPattern;
    private String ignorePattern;
    private String replacement;
    private Long commLocationId;
    private Integer minLength;
    private Integer direction;
    private CommLocationDto commLocation;
}