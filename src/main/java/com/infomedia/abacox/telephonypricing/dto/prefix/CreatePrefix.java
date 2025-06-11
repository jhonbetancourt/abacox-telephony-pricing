package com.infomedia.abacox.telephonypricing.dto.prefix;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * DTO for {@link com.infomedia.abacox.telephonypricing.db.entity.Prefix}
 */
@EqualsAndHashCode(callSuper = false)
@Data
@AllArgsConstructor
@NoArgsConstructor
public class CreatePrefix {
    private Long operatorId;
    
    private Long telephonyTypeId;
    
    @NotBlank
    @Size(max = 10)
    private String code;
    
    @NotNull
    private BigDecimal baseValue;
    
    @NotNull
    private Boolean bandOk;
    
    @NotNull
    private Boolean vatIncluded;
    
    @NotNull
    private BigDecimal vatValue;
}