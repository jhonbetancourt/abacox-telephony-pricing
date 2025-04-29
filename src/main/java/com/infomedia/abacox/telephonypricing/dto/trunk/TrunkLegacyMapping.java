package com.infomedia.abacox.telephonypricing.dto.trunk;

import com.infomedia.abacox.telephonypricing.dto.superclass.ActivableLegacyMapping;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

@EqualsAndHashCode(callSuper = true)
@Data
@AllArgsConstructor
@NoArgsConstructor
public class TrunkLegacyMapping extends ActivableLegacyMapping {
    private String id;
    private String commLocationId;
    private String description;
    private String name;
    private String operatorId;
    private String noPbxPrefix;
    private String channels;
}