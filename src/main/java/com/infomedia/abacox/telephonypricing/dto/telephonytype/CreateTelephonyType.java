package com.infomedia.abacox.telephonypricing.dto.telephonytype;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

/**
 * DTO for {@link com.infomedia.abacox.telephonypricing.entity.TelephonyType}
 */
@EqualsAndHashCode(callSuper = false)
@Data
@AllArgsConstructor
@NoArgsConstructor
public class CreateTelephonyType {
    @NotBlank
    @Size(max = 40)
    private String name;
    
    private Long callCategoryId;
    
    @NotNull
    private Boolean usesTrunks;
}