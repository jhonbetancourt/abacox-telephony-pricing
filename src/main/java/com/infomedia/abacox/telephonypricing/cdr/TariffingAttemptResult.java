package com.infomedia.abacox.telephonypricing.cdr;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@AllArgsConstructor
@NoArgsConstructor
class TariffingAttemptResult {
    private DestinationInfo bestDestInfo;
    private PrefixInfo bestPrefixInfo;
}