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
    private final OperatorLookupService operatorLookupService; // Added


    public IncomingCallOriginInfo determineIncomingCallOrigin(String originalIncomingNumber, CommunicationLocation commLocation) {
        IncomingCallOriginInfo originInfo = new IncomingCallOriginInfo();
        originInfo.setEffectiveNumber(originalIncomingNumber); // Initial effective number
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

        Optional<String> pbxTransformedNumberOpt = pbxSpecialRuleLookupService.applyPbxSpecialRule(
                numberForProcessing, commLocation.getDirectory(), 1
        );
        if (pbxTransformedNumberOpt.isPresent()) {
            log.debug("Original incoming number '{}' transformed by PBX rule to '{}'", numberForProcessing, pbxTransformedNumberOpt.get());
            numberForProcessing = pbxTransformedNumberOpt.get();
        }

        TransformationResult cmeTransformed = phoneNumberTransformationService.transformIncomingNumberCME(
                numberForProcessing, originCountryId
        );
        if (cmeTransformed.isTransformed()) {
            log.debug("Number '{}' transformed by CME incoming rule to '{}'", numberForProcessing, cmeTransformed.getTransformedNumber());
            numberForProcessing = cmeTransformed.getTransformedNumber();
            if (cmeTransformed.getNewTelephonyTypeId() != null) {
                log.debug("CME transformation hinted telephony type: {}", cmeTransformed.getNewTelephonyTypeId());
            }
        }
        originInfo.setEffectiveNumber(numberForProcessing);

        DestinationInfo bestMatchDestInfo = null;
        PrefixInfo bestMatchedPrefixInfo = null;

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
                log.debug("PBX exit prefix '{}' stripped. Number for operator prefix lookup: '{}'", longestMatchingPdsPrefix, numberAfterPbxExitStrip);
            }
        }

        if (pbxExitPrefixFoundAndStripped) {
            List<PrefixInfo> operatorPrefixes = prefixLookupService.findMatchingPrefixes(
                    numberAfterPbxExitStrip, commLocation, false, null
            );
            log.debug("Path 1 (PBX Stripped): Found {} potential operator prefixes for '{}'", operatorPrefixes.size(), numberAfterPbxExitStrip);

            for (PrefixInfo prefixInfo : operatorPrefixes) {
                Optional<DestinationInfo> destInfoOpt = indicatorLookupService.findDestinationIndicator(
                        numberAfterPbxExitStrip,
                        prefixInfo.getTelephonyTypeId(),
                        prefixInfo.getTelephonyTypeMinLength() != null ? prefixInfo.getTelephonyTypeMinLength() : 0,
                        currentPlantIndicatorId,
                        prefixInfo.getPrefixId(),
                        originCountryId,
                        prefixInfo.getBandsAssociatedCount() > 0,
                        false,
                        prefixInfo.getPrefixCode()
                );

                if (destInfoOpt.isPresent()) {
                    DestinationInfo currentDestInfo = destInfoOpt.get();
                    if (bestMatchDestInfo == null || currentDestInfo.getSeriesRangeSize() < bestMatchDestInfo.getSeriesRangeSize() || (!bestMatchDestInfo.isApproximateMatch() && currentDestInfo.isApproximateMatch())) {
                        bestMatchDestInfo = currentDestInfo;
                        bestMatchedPrefixInfo = prefixInfo;
                        if (!bestMatchDestInfo.isApproximateMatch()) break;
                    }
                }
            }
            if (bestMatchDestInfo != null) {
                log.debug("Path 1 (PBX Stripped): Best match found: Dest='{}', Prefix='{}'", bestMatchDestInfo.getDestinationDescription(), bestMatchedPrefixInfo.getPrefixCode());
            }
        }

        if (bestMatchDestInfo == null || bestMatchDestInfo.getIndicatorId() == null || bestMatchDestInfo.getIndicatorId() <= 0) {
            log.debug("Path 1 did not yield a definitive result. Proceeding with Path 2 (General Lookup) on: {}", numberForProcessing);
            List<PrefixInfo> generalPrefixes = prefixLookupService.findMatchingPrefixes(
                    numberForProcessing, commLocation, false, null
            );
            log.debug("Path 2 (General): Found {} potential prefixes for '{}'", generalPrefixes.size(), numberForProcessing);

            for (PrefixInfo prefixInfo : generalPrefixes) {
                Optional<DestinationInfo> destInfoOpt = indicatorLookupService.findDestinationIndicator(
                        numberForProcessing,
                        prefixInfo.getTelephonyTypeId(),
                        prefixInfo.getTelephonyTypeMinLength() != null ? prefixInfo.getTelephonyTypeMinLength() : 0,
                        currentPlantIndicatorId,
                        prefixInfo.getPrefixId(),
                        originCountryId,
                        prefixInfo.getBandsAssociatedCount() > 0,
                        false,
                        prefixInfo.getPrefixCode()
                );

                if (destInfoOpt.isPresent()) {
                    DestinationInfo currentDestInfo = destInfoOpt.get();
                     if (bestMatchDestInfo == null || currentDestInfo.getSeriesRangeSize() < bestMatchDestInfo.getSeriesRangeSize() || (!bestMatchDestInfo.isApproximateMatch() && currentDestInfo.isApproximateMatch())) {
                        bestMatchDestInfo = currentDestInfo;
                        bestMatchedPrefixInfo = prefixInfo;
                        if (!bestMatchDestInfo.isApproximateMatch()) break;
                    }
                }
            }
            if (bestMatchDestInfo != null) {
                log.debug("Path 2 (General): Best match found: Dest='{}', Prefix='{}'", bestMatchDestInfo.getDestinationDescription(), bestMatchedPrefixInfo.getPrefixCode());
            }
        }

        if (bestMatchDestInfo != null && bestMatchedPrefixInfo != null) {
            originInfo.setIndicatorId(bestMatchDestInfo.getIndicatorId());
            originInfo.setDestinationDescription(bestMatchDestInfo.getDestinationDescription());
            originInfo.setOperatorId(bestMatchedPrefixInfo.getOperatorId());
            originInfo.setOperatorName(bestMatchedPrefixInfo.getOperatorName());
            originInfo.setEffectiveNumber(bestMatchDestInfo.getMatchedPhoneNumber());
            originInfo.setTelephonyTypeId(bestMatchedPrefixInfo.getTelephonyTypeId());
            originInfo.setTelephonyTypeName(bestMatchedPrefixInfo.getTelephonyTypeName());

            if (!Objects.equals(currentPlantIndicatorId, bestMatchDestInfo.getIndicatorId())) {
                if (indicatorLookupService.isLocalExtended(bestMatchDestInfo.getNdc(), currentPlantIndicatorId, bestMatchDestInfo.getIndicatorId())) {
                    originInfo.setTelephonyTypeId(TelephonyTypeEnum.LOCAL_EXTENDED.getValue());
                    originInfo.setTelephonyTypeName(telephonyTypeLookupService.getTelephonyTypeName(TelephonyTypeEnum.LOCAL_EXTENDED.getValue()) + " (Incoming)");
                }
            } else {
                originInfo.setTelephonyTypeId(TelephonyTypeEnum.LOCAL.getValue());
                originInfo.setTelephonyTypeName(telephonyTypeLookupService.getTelephonyTypeName(TelephonyTypeEnum.LOCAL.getValue()) + " (Incoming)");
            }

            // PHP: Solo para celulares cuando no se detectan automaticamente
            if (originInfo.getTelephonyTypeId() == TelephonyTypeEnum.CELLULAR.getValue() &&
                (originInfo.getOperatorId() == null || originInfo.getOperatorId() == 0L)) {
                log.debug("Incoming cellular call with generic operator from prefix. Attempting band-based operator lookup for indicator ID: {}", originInfo.getIndicatorId());
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
            log.warn("No definitive origin found for incoming number: '{}' (after all transforms: '{}'). Using defaults.", originalIncomingNumber, numberForProcessing);
        }

        log.info("Final determined incoming call origin for '{}': {}", originalIncomingNumber, originInfo);
        return originInfo;
    }
}