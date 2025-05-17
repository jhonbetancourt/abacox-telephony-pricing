package com.infomedia.abacox.telephonypricing.cdr;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class PrefixNdcLimitsDto {
    private int minNdcLength;
    private int maxNdcLength;
    private int seriesNumberLength; // Expected length of the number part after NDC (from SERIE_INICIAL/FINAL length)
}
