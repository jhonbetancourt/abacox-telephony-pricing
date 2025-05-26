package com.infomedia.abacox.telephonypricing.dto.officedetails;

import com.infomedia.abacox.telephonypricing.dto.indicator.IndicatorDto;
import com.infomedia.abacox.telephonypricing.dto.subdivision.SubdivisionDto;
import com.infomedia.abacox.telephonypricing.dto.superclass.ActivableDto;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

/**
 * DTO for {@link com.infomedia.abacox.telephonypricing.entity.OfficeDetails}
 */
@EqualsAndHashCode(callSuper = true)
@Data
@AllArgsConstructor
@NoArgsConstructor
public class OfficeDetailsDto extends ActivableDto {
    private Long id;
    private Long subdivisionId;
    private String address;
    private String phone;
    private Long indicatorId;
    private SubdivisionDto subdivision;
    private IndicatorDto indicator;
}