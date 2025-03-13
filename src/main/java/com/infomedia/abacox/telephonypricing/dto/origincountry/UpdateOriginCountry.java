package com.infomedia.abacox.telephonypricing.dto.origincountry;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import org.openapitools.jackson.nullable.JsonNullable;

/**
 * DTO for updating {@link com.infomedia.abacox.telephonypricing.entity.OriginCountry}
 */
@EqualsAndHashCode(callSuper = false)
@Data
@AllArgsConstructor
@NoArgsConstructor
public class UpdateOriginCountry {
    @NotBlank
    @Size(max = 10)
    private JsonNullable<String> currencySymbol = JsonNullable.undefined();
    
    @NotBlank
    @Size(max = 50)
    private JsonNullable<String> name = JsonNullable.undefined();
    
    @NotBlank
    @Size(max = 3)
    private JsonNullable<String> code = JsonNullable.undefined();
}