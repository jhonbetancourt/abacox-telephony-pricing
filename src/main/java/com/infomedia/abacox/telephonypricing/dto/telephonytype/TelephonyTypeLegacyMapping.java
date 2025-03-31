package com.infomedia.abacox.telephonypricing.dto.telephonytype;

import com.infomedia.abacox.telephonypricing.dto.superclass.ActivableLegacyMapping;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

@EqualsAndHashCode(callSuper = true)
@Data
@AllArgsConstructor
@NoArgsConstructor
public class TelephonyTypeLegacyMapping extends ActivableLegacyMapping {
    private String id;
    private String name;
    private String callCategoryId;
    private String usesTrunks;
}