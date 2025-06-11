package com.infomedia.abacox.telephonypricing.dto.costcenter;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import org.openapitools.jackson.nullable.JsonNullable;

/**
 * DTO for updating {@link com.infomedia.abacox.telephonypricing.db.entity.CostCenter}
 */
@EqualsAndHashCode(callSuper = false)
@Data
@AllArgsConstructor
@NoArgsConstructor
public class UpdateCostCenter {
    @NotBlank
    @Size(max = 100)
    private JsonNullable<String> name = JsonNullable.undefined();
    
    @NotBlank
    @Size(max = 50)
    private JsonNullable<String> workOrder = JsonNullable.undefined();
    
    private JsonNullable<Long> parentCostCenterId = JsonNullable.undefined();
    
    private JsonNullable<Long> originCountryId = JsonNullable.undefined();
}