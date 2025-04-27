package com.infomedia.abacox.telephonypricing.dto.band;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * DTO for {@link com.infomedia.abacox.telephonypricing.entity.Band}
 */
@EqualsAndHashCode(callSuper = false)
@Data
@AllArgsConstructor
@NoArgsConstructor
public class CreateBand {
    private Long prefixId;

    @NotBlank
    @Size(max = 50)
    private String name;

    @NotNull
    private BigDecimal value;

    @NotNull
    private Long originIndicatorId;

    @NotNull
    private Boolean vatIncluded;

    @NotNull
    private Long reference;
}