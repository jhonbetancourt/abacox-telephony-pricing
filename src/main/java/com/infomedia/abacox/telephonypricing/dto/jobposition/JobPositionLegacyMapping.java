package com.infomedia.abacox.telephonypricing.dto.jobposition;

import com.infomedia.abacox.telephonypricing.dto.superclass.ActivableLegacyMapping;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

@EqualsAndHashCode(callSuper = true)
@Data
@AllArgsConstructor
@NoArgsConstructor
public class JobPositionLegacyMapping extends ActivableLegacyMapping {
    private String id;
    private String name;
}