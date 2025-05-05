package com.infomedia.abacox.telephonypricing.dto.trunkrule;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * DTO for {@link com.infomedia.abacox.telephonypricing.entity.TrunkRule}
 */
@EqualsAndHashCode(callSuper = false)
@Data
@AllArgsConstructor
@NoArgsConstructor
public class CreateTrunkRule {
    @NotNull
    private BigDecimal rateValue;
    
    @NotNull
    private Boolean includesVat;
    
    private Long telephonyTypeId;
    
    @NotBlank
    @Size(max = 255)
    private String indicatorIds;
    
    private Long trunkId;
    
    @NotNull
    private Long newOperatorId;
    
    @NotNull
    private Long newTelephonyTypeId;
    
    @NotNull
    private Integer seconds;
    
    private Long originIndicatorId;
}