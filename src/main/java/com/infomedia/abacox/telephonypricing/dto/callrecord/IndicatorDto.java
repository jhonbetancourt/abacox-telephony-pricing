package com.infomedia.abacox.telephonypricing.dto.callrecord;

import com.infomedia.abacox.telephonypricing.dto.superclass.AuditedDto;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * DTO for {@link com.infomedia.abacox.telephonypricing.entity.Indicator}
 */
@EqualsAndHashCode(callSuper = true)
@Data
@AllArgsConstructor
@NoArgsConstructor
public class IndicatorDto extends AuditedDto {
    private Long id;
    private Long telephoneTypeId;
    private Integer code;
    private String departmentCountry;
    private String city;
    private Long cityId;
    private boolean isAssociated;
    private Long operatorId;
    private Long originCountryId;
}