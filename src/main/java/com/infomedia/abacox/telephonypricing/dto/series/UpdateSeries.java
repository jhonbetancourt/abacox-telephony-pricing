package com.infomedia.abacox.telephonypricing.dto.series;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import org.openapitools.jackson.nullable.JsonNullable;

/**
 * DTO for updating {@link com.infomedia.abacox.telephonypricing.entity.Series}
 */
@EqualsAndHashCode(callSuper = false)
@Data
@AllArgsConstructor
@NoArgsConstructor
public class UpdateSeries {
    private JsonNullable<Long> indicatorId = JsonNullable.undefined();
    
    @NotNull
    private JsonNullable<Integer> ndc = JsonNullable.undefined();
    
    @NotNull
    private JsonNullable<Integer> initialNumber = JsonNullable.undefined();
    
    @NotNull
    private JsonNullable<Integer> finalNumber = JsonNullable.undefined();
    
    @Size(max = 200)
    private JsonNullable<String> company = JsonNullable.undefined();
}