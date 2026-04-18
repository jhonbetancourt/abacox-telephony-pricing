package com.infomedia.abacox.telephonypricing.component.cdrprocessing;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.util.List;

/**
 * Result of {@link PrefixLookupService#findMatchingPrefixes}. Exposes both the list
 * of matched prefixes AND the number-for-lookup that was actually used (after the
 * Colombia 10-digit-fixed-line transformation). Callers must pass {@link #getNumberForLookup()}
 * to {@code IndicatorLookupService.findDestinationIndicator} — passing the original dialed number
 * instead causes the destination lookup to resolve against the untransformed number and produces
 * the wrong indicator (mis-classifying LOCAL as LOCAL_EXTENDED for calls like 60X NNNNNNN).
 */
@Data
@AllArgsConstructor
public class PrefixMatchResult {
    private List<PrefixInfo> prefixes;
    private String numberForLookup;
    private Long hintedTelephonyTypeId;
}
