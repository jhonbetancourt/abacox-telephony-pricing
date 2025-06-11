package com.infomedia.abacox.telephonypricing.dto.series;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

/**
 * DTO for {@link com.infomedia.abacox.telephonypricing.db.entity.Series}
 */
@EqualsAndHashCode(callSuper = false)
@Data
@AllArgsConstructor
@NoArgsConstructor
public class CreateSeries {
    private Long indicatorId;
    
    @NotNull
    private Integer ndc;

    @NotNull
    private Integer initialNumber;

    @NotNull
    private Integer finalNumber;
    
    @Size(max = 200)
    private String company;
}