package com.infomedia.abacox.telephonypricing.component.cdrprocessing;

import lombok.AllArgsConstructor;
import lombok.Data;

@AllArgsConstructor
@Data
public class IndicatorConfig {
    public int minNdcLength;
    public int maxNdcLength;
    public int seriesNumberLength;

    public IndicatorConfig() { // Default constructor
        this.minNdcLength = 0;
        this.maxNdcLength = 0;
        this.seriesNumberLength = 0;
    }
}
