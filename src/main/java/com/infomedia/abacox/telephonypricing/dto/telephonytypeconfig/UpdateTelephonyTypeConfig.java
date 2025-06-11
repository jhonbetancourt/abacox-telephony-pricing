package com.infomedia.abacox.telephonypricing.dto.telephonytypeconfig;

import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import org.openapitools.jackson.nullable.JsonNullable;

/**
 * DTO for updating {@link com.infomedia.abacox.telephonypricing.db.entity.TelephonyTypeConfig}
 */
@EqualsAndHashCode(callSuper = false)
@Data
@AllArgsConstructor
@NoArgsConstructor
public class UpdateTelephonyTypeConfig {
    @NotNull
    private JsonNullable<Integer> minValue = JsonNullable.undefined();
    
    @NotNull
    private JsonNullable<Integer> maxValue = JsonNullable.undefined();
    
    private JsonNullable<Long> telephonyTypeId = JsonNullable.undefined();
    
    private JsonNullable<Long> originCountryId = JsonNullable.undefined();
}