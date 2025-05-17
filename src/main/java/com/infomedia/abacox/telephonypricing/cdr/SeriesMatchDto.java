package com.infomedia.abacox.telephonypricing.cdr;

import com.infomedia.abacox.telephonypricing.entity.Indicator;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class SeriesMatchDto {
    private Indicator indicator;
    private String ndc;
    private String destinationDescription;
    private boolean isApproximate; // For cases like country-level matching without specific series
}
