package com.infomedia.abacox.telephonypricing.dto.extensionrange;

import com.infomedia.abacox.telephonypricing.dto.superclass.ActivableLegacyMapping;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

/**
 * Legacy mapping DTO for {@link com.infomedia.abacox.telephonypricing.entity.ExtensionRange}
 */
@EqualsAndHashCode(callSuper = true)
@Data
@AllArgsConstructor
@NoArgsConstructor
public class ExtensionRangeLegacyMapping extends ActivableLegacyMapping {
    private String id;
    private String commLocationId;
    private String subdivisionId;
    private String prefix;
    private String rangeStart;
    private String rangeEnd;
    private String costCenterId;
}