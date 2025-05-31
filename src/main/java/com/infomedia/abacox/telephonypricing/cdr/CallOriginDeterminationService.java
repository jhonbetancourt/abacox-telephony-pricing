// File: com/infomedia/abacox/telephonypricing/cdr/CallOriginDeterminationService.java
package com.infomedia.abacox.telephonypricing.cdr;

import com.infomedia.abacox.telephonypricing.entity.CommunicationLocation;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.stereotype.Service;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

@Service
@Log4j2
@RequiredArgsConstructor
public class CallOriginDeterminationService {

    private final TelephonyTypeLookupService telephonyTypeLookupService;
    private final PrefixLookupService prefixLookupService;
    private final IndicatorLookupService indicatorLookupService;
    private final PhoneNumberTransformationService phoneNumberTransformationService;
    private final PbxSpecialRuleLookupService pbxSpecialRuleLookupService;
    private final OperatorLookupService operatorLookupService;


    public IncomingCallOriginInfo determineIncomingCallOrigin(String originalIncomingNumber, CommunicationLocation commLocation) {
        IncomingCallOriginInfo originInfo = new IncomingCallOriginInfo();
        originInfo.setEffectiveNumber(originalIncomingNumber);
        originInfo.setTelephonyTypeId(TelephonyTypeEnum.LOCAL.getValue());
        originInfo.setTelephonyTypeName(telephonyTypeLookupService.getTelephonyTypeName(TelephonyTypeEnum.LOCAL.getValue()));
        if (commLocation != null && commLocation.getIndicator() != null) {
            originInfo.setIndicatorId(commLocation.getIndicatorId());
        } else {
            log.warn("CommLocation or its Indicator is null in determineIncomingCallOrigin. Cannot set default indicatorId.");
            originInfo.setIndicatorId(0L);
        }

        if (originalIncomingNumber == null || originalIncomingNumber.isEmpty() || commLocation == null || commLocation.getIndicator() == null) {
            log.warn("Insufficient data for incoming call origin determination. Number: {}, CommLocation: {}", originalIncomingNumber, commLocation);
            return originInfo;
        }
        log.info("Determining incoming call origin for: '{}', CommLocation: {}", originalIncomingNumber, commLocation.getDirectory());

        Long originCountryId = commLocation.getIndicator().getOriginCountryId();
        Long currentPlantIndicatorId = commLocation.getIndicatorId();
        String numberForProcessing = originalIncomingNumber;

        // 1. Apply PBX incoming rules (PHP: evaluarPBXEspecial with incoming=1)
        Optional<String> pbxTransformedNumberOpt = pbxSpecialRuleLookupService.applyPbxSpecialRule(
                numberForProcessing, commLocation.getDirectory(), 1 // 1 for incoming
        );
        if (pbxTransformedNumberOpt.isPresent()) {
            log.debug("Original incoming number '{}' transformed by PBX incoming rule to '{}'", numberForProcessing, pbxTransformedNumberOpt.get());
            numberForProcessing = pbxTransformedNumberOpt.get();
        }

        // 2. Apply CME-specific incoming transformations (PHP: _esEntrante_60)
        TransformationResult cmeTransformed = phoneNumberTransformationService.transformIncomingNumberCME(
                numberForProcessing, originCountryId
        );
        if (cmeTransformed.isTransformed()) {
            log.debug("Number '{}' transformed by CME incoming rule to '{}'", numberForProcessing, cmeTransformed.getTransformedNumber());
            numberForProcessing = cmeTransformed.getTransformedNumber();
            // Hinted type from CME transform is not directly used here, but could be a factor later if needed
        }
        originInfo.setEffectiveNumber(numberForProcessing); // Store number after initial transformations

        DestinationInfo bestMatchDestInfo = null;
        PrefixInfo bestMatchedPrefixInfo = null;

        // --- Path A: PBX Exit Prefix + Operator Prefix ---
        List<String> pbxExitPrefixes = commLocation.getPbxPrefix() != null && !commLocation.getPbxPrefix().isEmpty() ?
                Arrays.asList(commLocation.getPbxPrefix().split(",")) :
                Collections.emptyList();

        String numberAfterPbxExitStrip = numberForProcessing; // Start with the number after PBX-incoming and CME rules
        boolean pbxExitPrefixFoundAndStripped = false;

        if (!pbxExitPrefixes.isEmpty()) {
            String longestMatchingPdsPrefix = "";
            for (String pds : pbxExitPrefixes) {
                String trimmedPds = pds.trim();
                if (!trimmedPds.isEmpty() && numberForProcessing.startsWith(trimmedPds)) {
                    if (trimmedPds.length() > longestMatchingPdsPrefix.length()) {
                        longestMatchingPdsPrefix = trimmedPds;
                    }
                }
            }
            if (!longestMatchingPdsPrefix.isEmpty()) {
                numberAfterPbxExitStrip = numberForProcessing.substring(longestMatchingPdsPrefix.length());
                pbxExitPrefixFoundAndStripped = true;
                log.debug("PBX exit prefix '{}' stripped. Number for operator prefix lookup: '{}'", longestMatchingPdsPrefix, numberAfterPbxExitStrip);
            }
        }

        if (pbxExitPrefixFoundAndStripped) {
            // Try to find operator prefixes in the remainder
            List<PrefixInfo> operatorPrefixes = prefixLookupService.findMatchingPrefixes(
                    numberAfterPbxExitStrip, commLocation, false, null // false for isTrunkCall
            );
            log.debug("Path A (PBX Exit Stripped): Found {} potential operator prefixes for '{}'", operatorPrefixes.size(), numberAfterPbxExitStrip);

            for (PrefixInfo prefixInfo : operatorPrefixes) {
                // The number passed to findDestinationIndicator should be the one *after* the operator prefix is stripped.
                String numberForDestLookup = numberAfterPbxExitStrip;
                boolean opPrefixStrippedForThisIteration = false;
                String opPrefixToStrip = null;

                if (prefixInfo.getPrefixCode() != null && !prefixInfo.getPrefixCode().isEmpty() && numberAfterPbxExitStrip.startsWith(prefixInfo.getPrefixCode())) {
                    // This path implies we are using the operator prefix, so it should be stripped before findDestinationIndicator
                    // unless findDestinationIndicator is told not to strip it.
                    // PHP's buscarDestino: if ($prefijotrim != '' && substr($telefono, 0, $len_prefijo ) == $prefijotrim && !$reducir)
                    // Here, $reducir would be false because we are providing the operator prefix.
                    opPrefixToStrip = prefixInfo.getPrefixCode();
                }


                Optional<DestinationInfo> destInfoOpt = indicatorLookupService.findDestinationIndicator(
                        numberAfterPbxExitStrip, // Pass number after PBX exit strip
                        prefixInfo.getTelephonyTypeId(),
                        prefixInfo.getTelephonyTypeMinLength() != null ? prefixInfo.getTelephonyTypeMinLength() : 0,
                        currentPlantIndicatorId,
                        prefixInfo.getPrefixId(),
                        originCountryId,
                        prefixInfo.getBandsAssociatedCount() > 0,
                        false, // Operator prefix is NOT YET stripped from numberAfterPbxExitStrip by findDestinationIndicator
                        opPrefixToStrip // Tell findDestinationIndicator which operator prefix to conceptually strip
                );

                if (destInfoOpt.isPresent()) {
                    DestinationInfo currentDestInfo = destInfoOpt.get();
                    if (bestMatchDestInfo == null || currentDestInfo.getSeriesRangeSize() < bestMatchDestInfo.getSeriesRangeSize() || (!bestMatchDestInfo.isApproximateMatch() && currentDestInfo.isApproximateMatch())) {
                        bestMatchDestInfo = currentDestInfo;
                        bestMatchedPrefixInfo = prefixInfo;
                        if (!bestMatchDestInfo.isApproximateMatch()) break; // Found an exact match
                    }
                }
            }
            if (bestMatchDestInfo != null) {
                log.debug("Path A (PBX Exit Stripped): Best match found: Dest='{}', Prefix='{}'", bestMatchDestInfo.getDestinationDescription(), bestMatchedPrefixInfo.getPrefixCode());
            }
        }

        // --- Path B: General Telephony Type Iteration ---
        // PHP: if ($arreglo['INDICATIVO_ID'] <= 0) // Modelo original (no PBX exit prefix, or no match after it)
        if (bestMatchDestInfo == null || bestMatchDestInfo.getIndicatorId() == null || bestMatchDestInfo.getIndicatorId() <= 0) {
            log.debug("Path A did not yield a definitive result. Proceeding with Path B (General Lookup) on number: {}", numberForProcessing);
            // numberForProcessing is after PBX incoming and CME rules, but *before* PBX exit prefix stripping for this path.

            List<IncomingTelephonyTypePriority> incomingTypes = telephonyTypeLookupService.getIncomingTelephonyTypePriorities(originCountryId);
            log.debug("Path B (General): Iterating through {} incoming telephony types.", incomingTypes.size());

            for (IncomingTelephonyTypePriority typePriority : incomingTypes) {
                // For this path, no operator prefix is assumed yet.
                // `isOperatorPrefixAlreadyStripped` is true because `numberForProcessing` is the full number to check.
                // `operatorPrefixToStripIfPresent` is null.
                Optional<DestinationInfo> destInfoOpt = indicatorLookupService.findDestinationIndicator(
                        numberForProcessing,
                        typePriority.getTelephonyTypeId(),
                        typePriority.getMinSubscriberLength(),
                        currentPlantIndicatorId,
                        null, // No specific operator prefix ID known at this stage for this path
                        originCountryId,
                        false, // Assume no specific prefix bands unless discovered by findDestinationIndicator
                        true,  // The numberForProcessing is passed as is, findDestinationIndicator handles NDC.
                        null
                );

                if (destInfoOpt.isPresent()) {
                    DestinationInfo currentDestInfo = destInfoOpt.get();
                    // We need to find the PrefixInfo that corresponds to this telephony type (usually the one with empty code or a generic one)
                    // This is tricky because findDestinationIndicator doesn't return the PrefixInfo.
                    // For now, we'll create a placeholder PrefixInfo.
                    PrefixInfo currentPrefixInfo = new PrefixInfo();
                    currentPrefixInfo.setTelephonyTypeId(typePriority.getTelephonyTypeId());
                    currentPrefixInfo.setTelephonyTypeName(typePriority.getTelephonyTypeName());
                    // Operator would be determined by the indicator if possible, or default.

                    if (bestMatchDestInfo == null || currentDestInfo.getSeriesRangeSize() < bestMatchDestInfo.getSeriesRangeSize() || (!bestMatchDestInfo.isApproximateMatch() && currentDestInfo.isApproximateMatch())) {
                        bestMatchDestInfo = currentDestInfo;
                        bestMatchedPrefixInfo = currentPrefixInfo; // This is a simplified PrefixInfo
                        if (!bestMatchDestInfo.isApproximateMatch()) break;
                    }
                }
            }
            if (bestMatchDestInfo != null) {
                log.debug("Path B (General): Best match found: Dest='{}', Type='{}'", bestMatchDestInfo.getDestinationDescription(), bestMatchedPrefixInfo.getTelephonyTypeName());
            }
        }

        // Finalizing the originInfo based on the best match
        if (bestMatchDestInfo != null && bestMatchedPrefixInfo != null) {
            originInfo.setIndicatorId(bestMatchDestInfo.getIndicatorId());
            originInfo.setDestinationDescription(bestMatchDestInfo.getDestinationDescription());
            // Operator ID from the matched prefix, or from the indicator if prefix's is null/0
            originInfo.setOperatorId(bestMatchedPrefixInfo.getOperatorId() != null && bestMatchedPrefixInfo.getOperatorId() != 0L ?
                                     bestMatchedPrefixInfo.getOperatorId() : bestMatchDestInfo.getOperatorId());
            originInfo.setOperatorName(bestMatchedPrefixInfo.getOperatorId() != null && bestMatchedPrefixInfo.getOperatorId() != 0L ?
                                       bestMatchedPrefixInfo.getOperatorName() : operatorLookupService.findOperatorNameById(bestMatchDestInfo.getOperatorId()));

            originInfo.setEffectiveNumber(bestMatchDestInfo.getMatchedPhoneNumber()); // This is number after op prefix strip, before local NDC prepend
            originInfo.setTelephonyTypeId(bestMatchedPrefixInfo.getTelephonyTypeId());
            originInfo.setTelephonyTypeName(bestMatchedPrefixInfo.getTelephonyTypeName());

            // PHP: if ($indicativo_destino != $arreglo['INDICATIVO_ID']) { ... elseif ($tipotele != $tipotele_id) ... }
            // PHP: else { $tipotele = _TIPOTELE_LOCAL; $tipotele_nombre = 'Local'; }
            if (Objects.equals(currentPlantIndicatorId, bestMatchDestInfo.getIndicatorId())) {
                originInfo.setTelephonyTypeId(TelephonyTypeEnum.LOCAL.getValue());
                originInfo.setTelephonyTypeName(telephonyTypeLookupService.getTelephonyTypeName(TelephonyTypeEnum.LOCAL.getValue()) + " (Incoming)");
            } else if (indicatorLookupService.isLocalExtended(bestMatchDestInfo.getNdc(), currentPlantIndicatorId, bestMatchDestInfo.getIndicatorId())) {
                originInfo.setTelephonyTypeId(TelephonyTypeEnum.LOCAL_EXTENDED.getValue());
                originInfo.setTelephonyTypeName(telephonyTypeLookupService.getTelephonyTypeName(TelephonyTypeEnum.LOCAL_EXTENDED.getValue()) + " (Incoming)");
            }
            // else, it remains the type determined by the prefix.

            // PHP: Solo para celulares cuando no se detectan automaticamente
            if (originInfo.getTelephonyTypeId() == TelephonyTypeEnum.CELLULAR.getValue() &&
                (originInfo.getOperatorId() == null || originInfo.getOperatorId() == 0L)) {
                log.debug("Incoming cellular call with generic operator. Attempting band-based operator lookup for indicator ID: {}", originInfo.getIndicatorId());
                Optional<OperatorInfo> bandOperatorOpt = operatorLookupService.findOperatorForIncomingCellularByIndicatorBands(originInfo.getIndicatorId());
                if (bandOperatorOpt.isPresent()) {
                    log.info("Found operator {} via bands for incoming cellular to indicator {}", bandOperatorOpt.get().getName(), originInfo.getIndicatorId());
                    originInfo.setOperatorId(bandOperatorOpt.get().getId());
                    originInfo.setOperatorName(bandOperatorOpt.get().getName());
                } else {
                    log.debug("No specific operator found via bands for incoming cellular to indicator {}. Operator remains: {}", originInfo.getIndicatorId(), originInfo.getOperatorName());
                }
            }
        } else {
            log.warn("No definitive origin found for incoming number: '{}' (after initial transforms: '{}'). Using defaults.", originalIncomingNumber, numberForProcessing);
        }

        log.info("Final determined incoming call origin for '{}': {}", originalIncomingNumber, originInfo);
        return originInfo;
    }
}