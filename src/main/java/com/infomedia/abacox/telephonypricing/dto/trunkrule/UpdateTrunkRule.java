package com.infomedia.abacox.telephonypricing.dto.trunkrule;

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
 * DTO for updating {@link com.infomedia.abacox.telephonypricing.entity.TrunkRule}
 */
@EqualsAndHashCode(callSuper = false)
@Data
@AllArgsConstructor
@NoArgsConstructor
public class UpdateTrunkRule {
    @NotNull
    private JsonNullable<BigDecimal> rateValue = JsonNullable.undefined();
    
    @NotNull
    private JsonNullable<Boolean> includesVat = JsonNullable.undefined();
    
    private JsonNullable<Long> telephonyTypeId = JsonNullable.undefined();
    
    @NotBlank
    @Size(max = 255)
    private JsonNullable<String> indicatorIds = JsonNullable.undefined();
    
    private JsonNullable<Long> trunkId = JsonNullable.undefined();
    
    @NotNull
    private JsonNullable<Long> newOperatorId = JsonNullable.undefined();
    
    @NotNull
    private JsonNullable<Long> newTelephonyTypeId = JsonNullable.undefined();
    
    @NotNull
    private JsonNullable<Integer> seconds = JsonNullable.undefined();
    
    private JsonNullable<Long> originIndicatorId = JsonNullable.undefined();
}