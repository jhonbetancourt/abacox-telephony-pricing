package com.infomedia.abacox.telephonypricing.dto.generic;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder(toBuilder = true)
@AllArgsConstructor
@NoArgsConstructor
public class MessageResponse {
    @Schema(description = "mensaje de respuesta", example = "Operación exitosa")
    private String message;
}
