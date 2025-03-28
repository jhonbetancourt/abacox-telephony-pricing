package com.infomedia.abacox.telephonypricing.dto.bandindicator;

import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

/**
 * DTO for {@link com.infomedia.abacox.telephonypricing.entity.BandIndicator}
 */
@EqualsAndHashCode(callSuper = false)
@Data
@AllArgsConstructor
@NoArgsConstructor
public class CreateBandIndicator {
    @NotNull
    private Long bandId;
    
    @NotNull
    private Long indicatorId;
}