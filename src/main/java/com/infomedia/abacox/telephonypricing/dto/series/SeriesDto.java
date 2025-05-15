package com.infomedia.abacox.telephonypricing.dto.series;

import com.infomedia.abacox.telephonypricing.dto.indicator.IndicatorDto;
import com.infomedia.abacox.telephonypricing.dto.superclass.ActivableDto;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

/**
 * DTO for {@link com.infomedia.abacox.telephonypricing.entity.Series}
 */
@EqualsAndHashCode(callSuper = true)
@Data
@AllArgsConstructor
@NoArgsConstructor
public class SeriesDto extends ActivableDto {
    private Long id;
    private Long indicatorId;
    private String ndc;
    private String initialNumber;
    private String finalNumber;
    private String company;
    private IndicatorDto indicator;
}