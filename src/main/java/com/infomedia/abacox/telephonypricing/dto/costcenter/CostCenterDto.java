package com.infomedia.abacox.telephonypricing.dto.costcenter;

import com.infomedia.abacox.telephonypricing.dto.origincountry.OriginCountryDto;
import com.infomedia.abacox.telephonypricing.entity.CostCenter;
import com.infomedia.abacox.telephonypricing.entity.superclass.AuditedEntity;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

/**
 * DTO for {@link CostCenter}
 */
@EqualsAndHashCode(callSuper = true)
@Data
@AllArgsConstructor
@NoArgsConstructor
public class CostCenterDto extends AuditedEntity {
    private Long id;
    private String costCenterName;
    private String workOrder;
    private Long parentCostCenterId;
    private CostCenter parentCostCenter;
    private Long originCountryId;
    private OriginCountryDto originCountry;
}