package com.infomedia.abacox.telephonypricing.dto.operator;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import org.openapitools.jackson.nullable.JsonNullable;

/**
 * DTO for updating {@link com.infomedia.abacox.telephonypricing.db.entity.Operator}
 */
@EqualsAndHashCode(callSuper = false)
@Data
@AllArgsConstructor
@NoArgsConstructor
public class UpdateOperator {
    @NotBlank
    @Size(max = 50)
    private JsonNullable<String> name = JsonNullable.undefined();
    
    private JsonNullable<Long> originCountryId = JsonNullable.undefined();
}