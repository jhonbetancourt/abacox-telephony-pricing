package com.infomedia.abacox.telephonypricing.cdr;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.Getter;
import lombok.Setter;

@AllArgsConstructor
@Data
public class DestinationInfo {
    public String matchedPhoneNumber;
    public Long indicatorId;
    public String ndc;
    public String destinationDescription;
    public Long operatorId; // << THIS IS INDICATOR.OPERATOR_ID
    public Long prefixIdUsed;
    public Long bandId;
    public boolean isApproximateMatch;
    public Integer seriesInitial;
    public Integer seriesFinal;

    public DestinationInfo() {
    }

    public long getSeriesRangeSize() {
        if (seriesInitial != null && seriesFinal != null && seriesFinal >= seriesInitial) {
            return (long)seriesFinal - seriesInitial;
        }
        return Long.MAX_VALUE;
    }
}