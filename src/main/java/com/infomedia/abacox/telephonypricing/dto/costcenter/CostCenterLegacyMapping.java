package com.infomedia.abacox.telephonypricing.dto.costcenter;

import com.infomedia.abacox.telephonypricing.dto.superclass.ActivableLegacyMapping;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

@EqualsAndHashCode(callSuper = true)
@Data
@AllArgsConstructor
@NoArgsConstructor
public class CostCenterLegacyMapping extends ActivableLegacyMapping {
    private String id;
    private String name;
    private String workOrder;
    private String parentCostCenterId;
    private String originCountryId;
}