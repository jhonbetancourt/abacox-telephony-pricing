package com.infomedia.abacox.telephonypricing.dto.costcenter;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;


@Data
@AllArgsConstructor
@NoArgsConstructor
public class CostCenterLegacyLoad {
    @NotBlank
    private String csvFileBase64;

    @Valid
    @NotNull
    private CostCenterLegacyMapping legacyMapping;
}
