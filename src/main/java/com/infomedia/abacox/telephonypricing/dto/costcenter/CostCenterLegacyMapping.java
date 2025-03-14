package com.infomedia.abacox.telephonypricing.dto.costcenter;

import com.infomedia.abacox.telephonypricing.dto.superclass.AuditedLegacyMapping;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

@EqualsAndHashCode(callSuper = true)
@Data
@AllArgsConstructor
@NoArgsConstructor
public class CostCenterLegacyMapping extends AuditedLegacyMapping {
    private String id;
    private String name;
    private String workOrder;
    private String parentCostCenterId;
    private String originCountryId;
}