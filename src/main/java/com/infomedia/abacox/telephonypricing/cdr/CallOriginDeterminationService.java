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
            log.debug("Number '{}' transformed by CME rule to '{}'", numberForProcessing, cmeTransformed.getTransformedNumber());
            numberForProcessing = cmeTransformed.getTransformedNumber();
        }
        originInfo.setEffectiveNumber(numberForProcessing); // Store number after initial transformations
        Long hintedTelephonyTypeIdFromTransform = cmeTransformed.getNewTelephonyTypeId();


        DestinationInfo bestMatchDestInfo = null;
        PrefixInfo bestMatchedPrefixInfo = null; // The operator prefix that led to the bestMatchDestInfo

        // --- Path A: Check if number starts with PBX Exit Prefix (PHP: Validar_prefijoSalida) ---
        List<String> pbxExitPrefixes = commLocation.getPbxPrefix() != null && !commLocation.getPbxPrefix().isEmpty() ?
                Arrays.asList(commLocation.getPbxPrefix().split(",")) :
                Collections.emptyList();

        String numberAfterPbxExitStrip = numberForProcessing;
        boolean pbxExitPrefixFoundAndStripped = false;
        String matchedPbxExitPrefix = null;

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
                matchedPbxExitPrefix = longestMatchingPdsPrefix;
                log.debug("PBX exit prefix '{}' stripped from incoming number. Remainder for operator prefix lookup: '{}'", matchedPbxExitPrefix, numberAfterPbxExitStrip);
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
                    if (bestMatchDestInfo == null || currentDestInfo.getSeriesRangeSize() < bestMatchDestInfo.getSeriesRangeSize() || (!currentDestInfo.isApproximateMatch() && bestMatchDestInfo.isApproximateMatch())) {
                        bestMatchDestInfo = currentDestInfo;
                        bestMatchedPrefixInfo = prefixInfo;
                        if (!bestMatchDestInfo.isApproximateMatch()) break;
                    }
                }
            }
            if (bestMatchDestInfo != null) {
                log.debug("Path A (PBX Exit Stripped '{}'): Best match found: Dest='{}', Prefix='{}'", matchedPbxExitPrefix, bestMatchDestInfo.getDestinationDescription(), bestMatchedPrefixInfo.getPrefixCode());
            }
        }

        if (bestMatchDestInfo == null || bestMatchDestInfo.getIndicatorId() == null || bestMatchDestInfo.getIndicatorId() <= 0) {
            log.debug("Path A did not yield a definitive result or no PBX exit prefix. Proceeding with Path B (General Lookup) on number: {}", numberForProcessing);

            List<IncomingTelephonyTypePriority> incomingTypes = telephonyTypeLookupService.getIncomingTelephonyTypePriorities(originCountryId);
            log.debug("Path B (General): Iterating through {} incoming telephony types.", incomingTypes.size());

            // If a transformation hinted a type, try that first if it's in the list
            Stream<IncomingTelephonyTypePriority> prioritizedStream = incomingTypes.stream();
            if (hintedTelephonyTypeIdFromTransform != null) {
                prioritizedStream = Stream.concat(
                    incomingTypes.stream().filter(itp -> itp.getTelephonyTypeId().equals(hintedTelephonyTypeIdFromTransform)),
                    incomingTypes.stream().filter(itp -> !itp.getTelephonyTypeId().equals(hintedTelephonyTypeIdFromTransform))
                ).distinct(); // Ensure no duplicates if the hinted type was already first
            }
            List<IncomingTelephonyTypePriority> typesToIterate = prioritizedStream.collect(Collectors.toList());


            for (IncomingTelephonyTypePriority typePriority : typesToIterate) {
                Optional<DestinationInfo> destInfoOpt = indicatorLookupService.findDestinationIndicator(
                        numberForProcessing,
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
                    PrefixInfo currentPrefixInfo = new PrefixInfo();
                    currentPrefixInfo.setTelephonyTypeId(typePriority.getTelephonyTypeId());
                    currentPrefixInfo.setTelephonyTypeName(typePriority.getTelephonyTypeName());
                    if (currentDestInfo.getOperatorId() != null) {
                        currentPrefixInfo.setOperatorId(currentDestInfo.getOperatorId());
                        currentPrefixInfo.setOperatorName(operatorLookupService.findOperatorNameById(currentDestInfo.getOperatorId()));
                    }

                    if (bestMatchDestInfo == null || currentDestInfo.getSeriesRangeSize() < bestMatchDestInfo.getSeriesRangeSize() || (!currentDestInfo.isApproximateMatch() && bestMatchDestInfo.isApproximateMatch())) {
                        bestMatchDestInfo = currentDestInfo;
                        bestMatchedPrefixInfo = currentPrefixInfo;
                        if (!bestMatchDestInfo.isApproximateMatch()) break;
                    }
                }
            }
            if (bestMatchDestInfo != null) {
                log.debug("Path B (General): Best match found: Dest='{}', Type='{}'", bestMatchDestInfo.getDestinationDescription(), bestMatchedPrefixInfo.getTelephonyTypeName());
            }
        }

        if (bestMatchDestInfo != null && bestMatchedPrefixInfo != null) {
            originInfo.setIndicatorId(bestMatchDestInfo.getIndicatorId());
            originInfo.setDestinationDescription(bestMatchDestInfo.getDestinationDescription());
            originInfo.setOperatorId(bestMatchedPrefixInfo.getOperatorId() != null && bestMatchedPrefixInfo.getOperatorId() != 0L ?
                                     bestMatchedPrefixInfo.getOperatorId() : bestMatchDestInfo.getOperatorId());
            originInfo.setOperatorName(bestMatchedPrefixInfo.getOperatorId() != null && bestMatchedPrefixInfo.getOperatorId() != 0L ?
                                       bestMatchedPrefixInfo.getOperatorName() : operatorLookupService.findOperatorNameById(bestMatchDestInfo.getOperatorId()));

            originInfo.setEffectiveNumber(bestMatchDestInfo.getMatchedPhoneNumber());
            originInfo.setTelephonyTypeId(bestMatchedPrefixInfo.getTelephonyTypeId());
            originInfo.setTelephonyTypeName(bestMatchedPrefixInfo.getTelephonyTypeName());

            if (Objects.equals(currentPlantIndicatorId, bestMatchDestInfo.getIndicatorId())) {
                originInfo.setTelephonyTypeId(TelephonyTypeEnum.LOCAL.getValue());
                originInfo.setTelephonyTypeName(telephonyTypeLookupService.getTelephonyTypeName(TelephonyTypeEnum.LOCAL.getValue()) + " (Incoming)");
            } else if (indicatorLookupService.isLocalExtended(bestMatchDestInfo.getNdc(), currentPlantIndicatorId, bestMatchDestInfo.getIndicatorId())) {
                originInfo.setTelephonyTypeId(TelephonyTypeEnum.LOCAL_EXTENDED.getValue());
                originInfo.setTelephonyTypeName(telephonyTypeLookupService.getTelephonyTypeName(TelephonyTypeEnum.LOCAL_EXTENDED.getValue()) + " (Incoming)");
            }

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