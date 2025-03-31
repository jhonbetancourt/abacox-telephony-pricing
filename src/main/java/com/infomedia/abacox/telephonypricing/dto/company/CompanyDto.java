package com.infomedia.abacox.telephonypricing.dto.company;

import com.infomedia.abacox.telephonypricing.dto.indicator.IndicatorDto;
import com.infomedia.abacox.telephonypricing.dto.superclass.ActivableDto;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

/**
 * DTO for {@link com.infomedia.abacox.telephonypricing.entity.Company}
 */
@EqualsAndHashCode(callSuper = true)
@Data
@AllArgsConstructor
@NoArgsConstructor
public class CompanyDto extends ActivableDto {
    private Long id;
    private String additionalInfo;
    private String address;
    private String name;
    private String taxId;
    private String legalName;
    private String website;
    private Integer indicatorId;
    private IndicatorDto indicator;
}