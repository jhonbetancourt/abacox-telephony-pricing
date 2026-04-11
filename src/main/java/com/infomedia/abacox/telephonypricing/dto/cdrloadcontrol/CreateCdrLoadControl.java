package com.infomedia.abacox.telephonypricing.dto.cdrloadcontrol;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

/**
 * DTO for creating {@link com.infomedia.abacox.telephonypricing.db.entity.CdrLoadControl}
 */
@EqualsAndHashCode(callSuper = false)
@Data
@AllArgsConstructor
@NoArgsConstructor
public class CreateCdrLoadControl {
    @NotBlank
    @Size(max = 64)
    private String name;

    @NotNull
    private Long plantTypeId;
}
