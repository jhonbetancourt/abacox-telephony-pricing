package com.infomedia.abacox.telephonypricing.dto.indicator;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import org.openapitools.jackson.nullable.JsonNullable;

/**
 * DTO for updating {@link com.infomedia.abacox.telephonypricing.db.entity.Indicator}
 */
@EqualsAndHashCode(callSuper = false)
@Data
@AllArgsConstructor
@NoArgsConstructor
public class UpdateIndicator {
    private JsonNullable<Long> telephonyTypeId = JsonNullable.undefined();
    
    @NotBlank
    @Size(max = 80)
    private JsonNullable<String> departmentCountry = JsonNullable.undefined();
    
    @NotBlank
    @Size(max = 80)
    private JsonNullable<String> cityName = JsonNullable.undefined();
    
    private JsonNullable<Long> operatorId = JsonNullable.undefined();
    
    private JsonNullable<Long> originCountryId = JsonNullable.undefined();
}