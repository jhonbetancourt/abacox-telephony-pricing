package com.infomedia.abacox.telephonypricing.component.cdrprocessing;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public class CleanPhoneNumberResult {
    private final String cleanedNumber;
    private final boolean pbxPrefixStripped;
}