package com.infomedia.abacox.telephonypricing.dto.commlocation;

import com.infomedia.abacox.telephonypricing.dto.superclass.ActivableLegacyMapping;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

@EqualsAndHashCode(callSuper = true)
@Data
@AllArgsConstructor
@NoArgsConstructor
public class CommLocationLegacyMapping extends ActivableLegacyMapping {
    private String id;
    private String directory;
    private String plantTypeId;
    private String serial;
    private String indicatorId;
    private String pbxPrefix;
    private String address;
    private String captureDate;
    private String cdrCount;
    private String fileName;
    private String bandGroupId;
    private String headerId;
    private String withoutCaptures;
}