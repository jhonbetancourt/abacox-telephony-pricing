package com.infomedia.abacox.telephonypricing.dto.planttype;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

/**
 * DTO for creating {@link com.infomedia.abacox.telephonypricing.db.entity.PlantType}
 */
@EqualsAndHashCode(callSuper = false)
@Data
@AllArgsConstructor
@NoArgsConstructor
public class CreatePlantType {
    @NotBlank
    @Size(max = 40)
    private String name;
}