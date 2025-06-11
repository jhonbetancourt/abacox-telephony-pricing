package com.infomedia.abacox.telephonypricing.dto.telephonytype;

import com.infomedia.abacox.telephonypricing.dto.callcategory.CallCategoryDto;
import com.infomedia.abacox.telephonypricing.dto.superclass.ActivableDto;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

/**
 * DTO for {@link com.infomedia.abacox.telephonypricing.db.entity.TelephonyType}
 */
@EqualsAndHashCode(callSuper = true)
@Data
@AllArgsConstructor
@NoArgsConstructor
public class TelephonyTypeDto extends ActivableDto {
    private Long id;
    private String name;
    private Long callCategoryId;
    private boolean usesTrunks;
    private CallCategoryDto callCategory;
}