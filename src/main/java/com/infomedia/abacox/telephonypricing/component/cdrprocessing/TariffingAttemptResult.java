// File: com/infomedia/abacox/telephonypricing/component/cdrprocessing/TariffingAttemptResult.java
package com.infomedia.abacox.telephonypricing.component.cdrprocessing;

import lombok.Data;

@Data
public class TariffingAttemptResult {
    DestinationInfo bestDestInfo;
    PrefixInfo bestPrefixInfo;
    String finalNumberUsedForDestLookup; // The number string after operator prefix stripping, used for findDestinationIndicator
    boolean wasNormalizedAttempt = false; // Flag to indicate if this result came from a normalization attempt
    String matchedNumber;
}