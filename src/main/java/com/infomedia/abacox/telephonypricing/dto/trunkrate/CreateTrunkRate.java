package com.infomedia.abacox.telephonypricing.dto.trunkrate;

import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * DTO for {@link com.infomedia.abacox.telephonypricing.entity.TrunkRate}
 */
@EqualsAndHashCode(callSuper = false)
@Data
@AllArgsConstructor
@NoArgsConstructor
public class CreateTrunkRate {
    private Long trunkId;
    
    @NotNull
    private BigDecimal rateValue;
    
    @NotNull
    private Boolean includesVat;
    
    private Long operatorId;
    
    private Long telephonyTypeId;
    
    @NotNull
    private Boolean noPbxPrefix;
    
    @NotNull
    private Boolean noPrefix;
    
    @NotNull
    private Integer seconds;
}