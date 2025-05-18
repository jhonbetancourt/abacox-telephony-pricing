package com.infomedia.abacox.telephonypricing.cdr;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@AllArgsConstructor
public class DestinationInfo {
    public String matchedPhoneNumber;
    public Long indicatorId;
    public String ndc;
    public String destinationDescription;
    public Long operatorId;
    public Long prefixIdUsed;
    public Long bandId;
    public boolean isApproximateMatch;

    public DestinationInfo() {
    } // Default constructor
}