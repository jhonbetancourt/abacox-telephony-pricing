// File: com/infomedia/abacox/telephonypricing/cdr/DestinationInfo.java
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
    public Long operatorId;
    public Long prefixIdUsed;
    public Long bandId;
    public boolean isApproximateMatch;
    public Integer seriesInitial; // Added to store series range for sorting
    public Integer seriesFinal;   // Added to store series range for sorting

    public DestinationInfo() {
    } // Default constructor

    public long getSeriesRangeSize() {
        if (seriesInitial != null && seriesFinal != null && seriesFinal >= seriesInitial) {
            return (long)seriesFinal - seriesInitial;
        }
        return Long.MAX_VALUE; // So that un-ranged or invalid ranges sort last
    }
}