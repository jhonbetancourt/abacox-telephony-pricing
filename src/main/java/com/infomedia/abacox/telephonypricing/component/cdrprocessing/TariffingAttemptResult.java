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
    // PBX-cleaned number before the Colombia _esCelular_fijo transformation. Legacy PHP keeps this
    // as $info_destino (the outer $telefono) while using $g_numero internally for lookups only.
    // Persisting this value preserves the full 10-digit form (e.g. '6076881810') instead of the
    // local-subscriber form ('6881810') that our matchedNumber carries.
    String originalInputNumber;
}