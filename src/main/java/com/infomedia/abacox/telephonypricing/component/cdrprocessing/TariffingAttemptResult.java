// File: com/infomedia/abacox/telephonypricing/cdr/TariffingAttemptResult.java
// (This seems fine as is, it's a data holder for tariffing attempts)
package com.infomedia.abacox.telephonypricing.component.cdrprocessing;

import lombok.Data;

@Data
public class TariffingAttemptResult {
    DestinationInfo bestDestInfo;
    PrefixInfo bestPrefixInfo;
    String finalNumberUsedForDestLookup; // The number string after operator prefix stripping, used for findDestinationIndicator
    boolean wasNormalizedAttempt = false; // Flag to indicate if this result came from a normalization attempt
}