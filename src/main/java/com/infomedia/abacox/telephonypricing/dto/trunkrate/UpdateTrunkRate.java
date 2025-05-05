package com.infomedia.abacox.telephonypricing.dto.trunkrate;

import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import org.openapitools.jackson.nullable.JsonNullable;

import java.math.BigDecimal;

/**
 * DTO for updating {@link com.infomedia.abacox.telephonypricing.entity.TrunkRate}
 */
@EqualsAndHashCode(callSuper = false)
@Data
@AllArgsConstructor
@NoArgsConstructor
public class UpdateTrunkRate {
    private JsonNullable<Long> trunkId = JsonNullable.undefined();
    
    @NotNull
    private JsonNullable<BigDecimal> rateValue = JsonNullable.undefined();
    
    @NotNull
    private JsonNullable<Boolean> includesVat = JsonNullable.undefined();
    
    private JsonNullable<Long> operatorId = JsonNullable.undefined();
    
    private JsonNullable<Long> telephonyTypeId = JsonNullable.undefined();
    
    @NotNull
    private JsonNullable<Boolean> noPbxPrefix = JsonNullable.undefined();
    
    @NotNull
    private JsonNullable<Boolean> noPrefix = JsonNullable.undefined();
    
    @NotNull
    private JsonNullable<Integer> seconds = JsonNullable.undefined();
}