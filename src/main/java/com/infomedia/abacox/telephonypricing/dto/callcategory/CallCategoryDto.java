package com.infomedia.abacox.telephonypricing.dto.callcategory;

import com.infomedia.abacox.telephonypricing.dto.superclass.AuditedDto;
import com.infomedia.abacox.telephonypricing.entity.CallCategory;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

/**
 * DTO for {@link CallCategory}
 */
@EqualsAndHashCode(callSuper = true)
@Data
@AllArgsConstructor
@NoArgsConstructor
public class CallCategoryDto extends AuditedDto {
    private Long id;
    private String name;
}