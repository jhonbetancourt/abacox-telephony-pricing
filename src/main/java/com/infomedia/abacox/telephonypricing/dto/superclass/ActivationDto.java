package com.infomedia.abacox.telephonypricing.dto.superclass;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

@AllArgsConstructor
@NoArgsConstructor
@Data
@SuperBuilder
public class ActivationDto {
    @NotNull
    @Schema(description = "activation status", example = "true")
    private Boolean active;
}
