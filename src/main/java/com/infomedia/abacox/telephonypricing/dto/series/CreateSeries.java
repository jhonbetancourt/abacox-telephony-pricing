package com.infomedia.abacox.telephonypricing.dto.series;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

/**
 * DTO for {@link com.infomedia.abacox.telephonypricing.entity.Series}
 */
@EqualsAndHashCode(callSuper = false)
@Data
@AllArgsConstructor
@NoArgsConstructor
public class CreateSeries {
    private Long indicatorId;
    
    @NotBlank
    private String ndc;

    @NotBlank
    private String initialNumber;

    @NotBlank
    private String finalNumber;
    
    @Size(max = 200)
    private String company;
}