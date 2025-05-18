package com.infomedia.abacox.telephonypricing.dto.prefix;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import org.openapitools.jackson.nullable.JsonNullable;

import java.math.BigDecimal;

/**
 * DTO for updating {@link com.infomedia.abacox.telephonypricing.entity.Prefix}
 */
@EqualsAndHashCode(callSuper = false)
@Data
@AllArgsConstructor
@NoArgsConstructor
public class UpdatePrefix {
    private JsonNullable<Long> operatorId = JsonNullable.undefined();
    
    private JsonNullable<Long> telephonyTypeId = JsonNullable.undefined();
    
    @NotBlank
    @Size(max = 10)
    private JsonNullable<String> code = JsonNullable.undefined();
    
    @NotNull
    private JsonNullable<BigDecimal> baseValue = JsonNullable.undefined();
    
    @NotNull
    private JsonNullable<Boolean> bandOk = JsonNullable.undefined();
    
    @NotNull
    private JsonNullable<Boolean> vatIncluded = JsonNullable.undefined();
    
    @NotNull
    private JsonNullable<BigDecimal> vatValue = JsonNullable.undefined();
}