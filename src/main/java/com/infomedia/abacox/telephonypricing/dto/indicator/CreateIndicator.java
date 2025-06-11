package com.infomedia.abacox.telephonypricing.dto.indicator;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

/**
 * DTO for creating {@link com.infomedia.abacox.telephonypricing.db.entity.Indicator}
 */
@EqualsAndHashCode(callSuper = false)
@Data
@AllArgsConstructor
@NoArgsConstructor
public class CreateIndicator {
    private Long telephonyTypeId;
    
    @NotBlank
    @Size(max = 80)
    private String departmentCountry;
    
    @NotBlank
    @Size(max = 80)
    private String cityName;
    
    private Long operatorId;
    
    private Long originCountryId;
}