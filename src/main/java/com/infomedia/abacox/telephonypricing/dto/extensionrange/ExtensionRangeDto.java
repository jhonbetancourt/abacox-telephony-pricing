package com.infomedia.abacox.telephonypricing.dto.extensionrange;

import com.infomedia.abacox.telephonypricing.dto.commlocation.CommLocationDto;
import com.infomedia.abacox.telephonypricing.dto.costcenter.CostCenterDto;
import com.infomedia.abacox.telephonypricing.dto.subdivision.SubdivisionDto;
import com.infomedia.abacox.telephonypricing.dto.superclass.ActivableDto;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

/**
 * DTO for {@link com.infomedia.abacox.telephonypricing.db.entity.ExtensionRange}
 */
@EqualsAndHashCode(callSuper = true)
@Data
@AllArgsConstructor
@NoArgsConstructor
public class ExtensionRangeDto extends ActivableDto {
    private Long id;
    private Long commLocationId;
    private Long subdivisionId;
    private String prefix;
    private Long rangeStart;
    private Long rangeEnd;
    private Long costCenterId;
    private CommLocationDto commLocation;
    private SubdivisionDto subdivision;
    private CostCenterDto costCenter;
}