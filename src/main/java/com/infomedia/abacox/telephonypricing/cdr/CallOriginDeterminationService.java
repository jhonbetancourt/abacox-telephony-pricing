// File: com/infomedia/abacox/telephonypricing/cdr/CallOriginDeterminationService.java
package com.infomedia.abacox.telephonypricing.cdr;

import com.infomedia.abacox.telephonypricing.entity.CommunicationLocation;
import com.infomedia.abacox.telephonypricing.entity.Indicator; // Assuming Indicator entity exists
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.stereotype.Service;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.Stream;


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
        originInfo.setTelephonyTypeId(TelephonyTypeEnum.LOCAL.getValue()); // Default
        originInfo.setTelephonyTypeName(telephonyTypeLookupService.getTelephonyTypeName(TelephonyTypeEnum.LOCAL.getValue()));
        if (commLocation != null && commLocation.getIndicator() != null) {
            originInfo.setIndicatorId(commLocation.getIndicatorId());
        } else {
            log.warn("CommLocation or its Indicator is null in determineIncomingCallOrigin. Cannot set default indicatorId.");
            originInfo.setIndicatorId(0L); // Default or error indicator
        }

        if (originalIncomingNumber == null || originalIncomingNumber.isEmpty() || commLocation == null || commLocation.getIndicator() == null) {
            log.warn("Insufficient data for incoming call origin determination. Number: {}, CommLocation: {}", originalIncomingNumber, commLocation);
            return originInfo;
        }
        log.info("Determining incoming call origin for: '{}', CommLocation: {}", originalIncomingNumber, commLocation.getDirectory());

        Long originCountryId = commLocation.getIndicator().getOriginCountryId();
        Long currentPlantIndicatorId = commLocation.getIndicatorId();
        String numberForProcessing = originalIncomingNumber;

        // 1. Apply PBX incoming rules
        Optional<String> pbxTransformedNumberOpt = pbxSpecialRuleLookupService.applyPbxSpecialRule(
                numberForProcessing, commLocation.getDirectory(), 1 // 1 for incoming
        );
        if (pbxTransformedNumberOpt.isPresent()) {
            log.debug("Original incoming number '{}' transformed by PBX incoming rule to '{}'", numberForProcessing, pbxTransformedNumberOpt.get());
            numberForProcessing = pbxTransformedNumberOpt.get();
        }

        // 2. Apply CME-specific incoming transformations
        TransformationResult cmeTransformed = phoneNumberTransformationService.transformIncomingNumberCME(
                numberForProcessing, originCountryId
        );
        if (cmeTransformed.isTransformed()) {
            log.debug("Number '{}' transformed by CME rule to '{}'", numberForProcessing, cmeTransformed.getTransformedNumber());
            numberForProcessing = cmeTransformed.getTransformedNumber();
        }
        originInfo.setEffectiveNumber(numberForProcessing);
        Long hintedTelephonyTypeIdFromTransform = cmeTransformed.getNewTelephonyTypeId();

        DestinationInfo bestMatchDestInfo = null;
        PrefixInfo bestMatchedPrefixInfo = null; // This will store the prefix that led to the bestDestInfo

        // --- Path A: Check if number starts with PBX Exit Prefix ---
        List<String> pbxExitPrefixes = commLocation.getPbxPrefix() != null && !commLocation.getPbxPrefix().isEmpty() ?
                Arrays.asList(commLocation.getPbxPrefix().split(",")) :
                Collections.emptyList();
        String numberAfterPbxExitStrip = numberForProcessing;
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
                log.debug("PBX exit prefix '{}' stripped. Remainder for operator prefix lookup: '{}'", longestMatchingPdsPrefix, numberAfterPbxExitStrip);
            }
        }

        if (pbxExitPrefixFoundAndStripped) {
            List<PrefixInfo> operatorPrefixes = prefixLookupService.findMatchingPrefixes(
                    numberAfterPbxExitStrip, commLocation, false, null
            );
            log.debug("Path A (PBX Exit Stripped): Found {} potential operator prefixes for '{}'", operatorPrefixes.size(), numberAfterPbxExitStrip);

            for (PrefixInfo prefixInfo : operatorPrefixes) {
                String opPrefixToStrip = (prefixInfo.getPrefixCode() != null && !prefixInfo.getPrefixCode().isEmpty() &&
                                          numberAfterPbxExitStrip.startsWith(prefixInfo.getPrefixCode())) ?
                                         prefixInfo.getPrefixCode() : null;

                Optional<DestinationInfo> destInfoOpt = indicatorLookupService.findDestinationIndicator(
                        numberAfterPbxExitStrip,
                        prefixInfo.getTelephonyTypeId(),
                        prefixInfo.getTelephonyTypeMinLength() != null ? prefixInfo.getTelephonyTypeMinLength() : 0,
                        currentPlantIndicatorId,
                        prefixInfo.getPrefixId(),
                        originCountryId,
                        prefixInfo.getBandsAssociatedCount() > 0,
                        false,
                        opPrefixToStrip
                );

                if (destInfoOpt.isPresent()) {
                    DestinationInfo currentDestInfo = destInfoOpt.get();
                    if (!currentDestInfo.isApproximateMatch()) {
                        bestMatchDestInfo = currentDestInfo;
                        bestMatchedPrefixInfo = prefixInfo; // Store the prefix that led to this dest
                        log.debug("Path A: Found non-approximate match. Dest='{}', Leading Prefix='{}'", bestMatchDestInfo.getDestinationDescription(), bestMatchedPrefixInfo.getPrefixCode());
                        break;
                    } else if (bestMatchDestInfo == null) { // Only take approximate if no exact match found yet
                        bestMatchDestInfo = currentDestInfo;
                        bestMatchedPrefixInfo = prefixInfo;
                    }
                }
            }
        }

        // --- Path B: General Lookup (if Path A failed or no PBX exit prefix) ---
        if (bestMatchDestInfo == null || bestMatchDestInfo.getIndicatorId() == null || bestMatchDestInfo.getIndicatorId() <= 0) {
            log.debug("Path A did not yield a definitive result or no PBX exit prefix. Proceeding with Path B on number: {}", numberForProcessing);
            bestMatchedPrefixInfo = null; // Reset prefix info for Path B

            List<IncomingTelephonyTypePriority> incomingTypes = telephonyTypeLookupService.getIncomingTelephonyTypePriorities(originCountryId);
            Stream<IncomingTelephonyTypePriority> prioritizedStream = incomingTypes.stream();
            if (hintedTelephonyTypeIdFromTransform != null) {
                prioritizedStream = Stream.concat(
                    incomingTypes.stream().filter(itp -> itp.getTelephonyTypeId().equals(hintedTelephonyTypeIdFromTransform)),
                    incomingTypes.stream().filter(itp -> !itp.getTelephonyTypeId().equals(hintedTelephonyTypeIdFromTransform))
                ).distinct();
            }
            List<IncomingTelephonyTypePriority> typesToIterate = prioritizedStream.collect(Collectors.toList());
            final String finalNumberForProcessingPathB = numberForProcessing;

            for (IncomingTelephonyTypePriority typePriority : typesToIterate) {
                if (finalNumberForProcessingPathB.length() >= typePriority.getMinSubscriberLength() &&
                    finalNumberForProcessingPathB.length() <= typePriority.getMaxSubscriberLength()) {

                    Optional<DestinationInfo> destInfoOpt = indicatorLookupService.findDestinationIndicator(
                            finalNumberForProcessingPathB,
                            typePriority.getTelephonyTypeId(),
                            typePriority.getMinSubscriberLength(),
                            currentPlantIndicatorId,
                            null,
                            originCountryId,
                            false,
                            true,
                            null
                    );

                    if (destInfoOpt.isPresent()) {
                        DestinationInfo currentDestInfo = destInfoOpt.get();
                        bestMatchDestInfo = currentDestInfo;
                        bestMatchedPrefixInfo = new PrefixInfo(); // Create a shell
                        bestMatchedPrefixInfo.setTelephonyTypeId(typePriority.getTelephonyTypeId());
                        bestMatchedPrefixInfo.setTelephonyTypeName(typePriority.getTelephonyTypeName());
                        // Operator for Path B will be derived from bestMatchDestInfo or band lookup
                        if (currentDestInfo.getOperatorId() != null) {
                             bestMatchedPrefixInfo.setOperatorId(currentDestInfo.getOperatorId()); // Tentatively set from dest
                             bestMatchedPrefixInfo.setOperatorName(operatorLookupService.findOperatorNameById(currentDestInfo.getOperatorId()));
                        }
                        log.debug("Path B: Found match for Type {}: Dest='{}'", typePriority.getTelephonyTypeName(), currentDestInfo.getDestinationDescription());
                        if (!bestMatchDestInfo.isApproximateMatch()) break;
                    }
                }
            }
        }

        // --- Finalizing OriginInfo ---
        if (bestMatchDestInfo != null && bestMatchedPrefixInfo != null) {
            originInfo.setIndicatorId(bestMatchDestInfo.getIndicatorId());
            originInfo.setDestinationDescription(bestMatchDestInfo.getDestinationDescription());
            originInfo.setEffectiveNumber(bestMatchDestInfo.getMatchedPhoneNumber());
            originInfo.setTelephonyTypeId(bestMatchedPrefixInfo.getTelephonyTypeId()); // Type from the prefix/type that led to dest
            originInfo.setTelephonyTypeName(bestMatchedPrefixInfo.getTelephonyTypeName());

            // **CRITICAL CHANGE FOR PHP PARITY: Operator comes from the DestinationInfo (Indicator's operator)**
            originInfo.setOperatorId(bestMatchDestInfo.getOperatorId());
            originInfo.setOperatorName(operatorLookupService.findOperatorNameById(bestMatchDestInfo.getOperatorId()));
            log.debug("Operator set from DestinationInfo's Indicator: ID={}, Name={}", originInfo.getOperatorId(), originInfo.getOperatorName());

            // If it's cellular and the operator from indicator is generic (or null), try band-based lookup
            // This is the PHP fallback: `if ($tipotele == _TIPOTELE_CELULAR && $operador <= 0)`
            if (Objects.equals(originInfo.getTelephonyTypeId(), TelephonyTypeEnum.CELLULAR.getValue()) &&
                (originInfo.getOperatorId() == null || originInfo.getOperatorId() == 0L)) {
                log.debug("Incoming cellular with generic/null operator from Indicator. Attempting band-based operator lookup for indicator ID: {}", originInfo.getIndicatorId());
                Optional<OperatorInfo> bandOperatorOpt = operatorLookupService.findOperatorForIncomingCellularByIndicatorBands(originInfo.getIndicatorId());
                if (bandOperatorOpt.isPresent()) {
                    log.info("Found operator {} via bands for incoming cellular to indicator {}", bandOperatorOpt.get().getName(), originInfo.getIndicatorId());
                    originInfo.setOperatorId(bandOperatorOpt.get().getId());
                    originInfo.setOperatorName(bandOperatorOpt.get().getName());
                } else {
                    log.debug("No specific operator found via bands for incoming cellular to indicator {}. Operator remains: {}", originInfo.getIndicatorId(), originInfo.getOperatorName());
                }
            }

            // Local/Local Extended Overrides
            if (Objects.equals(currentPlantIndicatorId, bestMatchDestInfo.getIndicatorId())) {
                originInfo.setTelephonyTypeId(TelephonyTypeEnum.LOCAL.getValue());
                originInfo.setTelephonyTypeName(telephonyTypeLookupService.getTelephonyTypeName(TelephonyTypeEnum.LOCAL.getValue()) + " (Incoming)");
            } else if (indicatorLookupService.isLocalExtended(bestMatchDestInfo.getNdc(), currentPlantIndicatorId, bestMatchDestInfo.getIndicatorId())) {
                originInfo.setTelephonyTypeId(TelephonyTypeEnum.LOCAL_EXTENDED.getValue());
                originInfo.setTelephonyTypeName(telephonyTypeLookupService.getTelephonyTypeName(TelephonyTypeEnum.LOCAL_EXTENDED.getValue()) + " (Incoming)");
            }
        } else {
            log.warn("No definitive origin found for incoming number: '{}' (after initial transforms: '{}'). Using defaults.", originalIncomingNumber, numberForProcessing);
        }

        log.info("Final determined incoming call origin for '{}': {}", originalIncomingNumber, originInfo);
        return originInfo;
    }
}