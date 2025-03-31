package com.infomedia.abacox.telephonypricing.dto.bandgroup;

import com.infomedia.abacox.telephonypricing.dto.superclass.ActivableLegacyMapping;
import com.infomedia.abacox.telephonypricing.entity.BandGroup;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

/**
 * DTO for {@link BandGroup}
 */
@EqualsAndHashCode(callSuper = true)
@Data
@AllArgsConstructor
@NoArgsConstructor
public class BandGroupLegacyMapping extends ActivableLegacyMapping {
    private String id;
    private String name;
}