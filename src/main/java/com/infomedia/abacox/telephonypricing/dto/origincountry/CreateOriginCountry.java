package com.infomedia.abacox.telephonypricing.dto.origincountry;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

/**
 * DTO for creating {@link com.infomedia.abacox.telephonypricing.db.entity.OriginCountry}
 */
@EqualsAndHashCode(callSuper = false)
@Data
@AllArgsConstructor
@NoArgsConstructor
public class CreateOriginCountry {
    @NotBlank
    @Size(max = 10)
    private String currencySymbol = "$";
    
    @NotBlank
    @Size(max = 50)
    private String name;
    
    @NotBlank
    @Size(max = 3)
    private String code;
}