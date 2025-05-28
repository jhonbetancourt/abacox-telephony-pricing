package com.infomedia.abacox.telephonypricing.cdr;

import lombok.Data;

@Data
public class TariffingAttemptResult {
    DestinationInfo bestDestInfo;
    PrefixInfo bestPrefixInfo;
    String finalNumberUsedForDestLookup;
}