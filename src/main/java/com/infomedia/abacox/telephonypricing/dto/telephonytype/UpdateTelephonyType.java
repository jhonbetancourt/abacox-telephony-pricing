package com.infomedia.abacox.telephonypricing.dto.telephonytype;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import org.openapitools.jackson.nullable.JsonNullable;

/**
 * DTO for updating {@link com.infomedia.abacox.telephonypricing.entity.TelephonyType}
 */
@EqualsAndHashCode(callSuper = false)
@Data
@AllArgsConstructor
@NoArgsConstructor
public class UpdateTelephonyType {
    @NotBlank
    @Size(max = 40)
    private JsonNullable<String> name = JsonNullable.undefined();
    
    private JsonNullable<Long> callCategoryId = JsonNullable.undefined();
    
    @NotNull
    private JsonNullable<Boolean> usesTrunks = JsonNullable.undefined();
}