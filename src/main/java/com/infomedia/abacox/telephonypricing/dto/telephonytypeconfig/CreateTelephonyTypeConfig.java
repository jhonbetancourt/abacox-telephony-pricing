package com.infomedia.abacox.telephonypricing.dto.telephonytypeconfig;

import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

/**
 * DTO for {@link com.infomedia.abacox.telephonypricing.entity.TelephonyTypeConfig}
 */
@EqualsAndHashCode(callSuper = false)
@Data
@AllArgsConstructor
@NoArgsConstructor
public class CreateTelephonyTypeConfig {
    @NotNull
    private Integer minValue;
    
    @NotNull
    private Integer maxValue;
    
    private Long telephonyTypeId;
    
    private Long originCountryId;
}