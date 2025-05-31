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
        log.debug("Determining incoming call origin for: {}, CommLocation: {}", originalIncomingNumber, commLocation.getDirectory());

        Long originCountryId = commLocation.getIndicator().getOriginCountryId();
        Long currentPlantIndicatorId = commLocation.getIndicatorId();

        String numberForProcessing = originalIncomingNumber;

        List<String> pbxExitPrefixes = commLocation.getPbxPrefix() != null && !commLocation.getPbxPrefix().isEmpty() ?
                                       Arrays.asList(commLocation.getPbxPrefix().split(",")) :
                                       Collections.emptyList();
        String numberAfterPbxStrip = numberForProcessing;
        boolean pbxPrefixFoundAndStripped = false;

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
                numberAfterPbxStrip = numberForProcessing.substring(longestMatchingPdsPrefix.length());
                pbxPrefixFoundAndStripped = true;
                log.debug("PBX exit prefix '{}' stripped. Number for operator prefix lookup: '{}'", longestMatchingPdsPrefix, numberAfterPbxStrip);
            }
        }

        DestinationInfo bestMatchDestInfo = null;
        PrefixInfo bestMatchedPrefixInfo = null;

        if (pbxPrefixFoundAndStripped) {
            List<PrefixInfo> operatorPrefixes = prefixLookupService.findMatchingPrefixes(
                    numberAfterPbxStrip, commLocation, false, null
            );
            log.debug("Found {} potential operator prefixes for PBX-stripped number '{}'", operatorPrefixes.size(), numberAfterPbxStrip);

            for (PrefixInfo prefixInfo : operatorPrefixes) {
                Optional<DestinationInfo> destInfoOpt = indicatorLookupService.findDestinationIndicator(
                        numberAfterPbxStrip, // Number after PBX stripping
                        prefixInfo.getTelephonyTypeId(),
                        prefixInfo.getTelephonyTypeMinLength() != null ? prefixInfo.getTelephonyTypeMinLength() : 0,
                        currentPlantIndicatorId,
                        prefixInfo.getPrefixId(),
                        originCountryId,
                        prefixInfo.getBandsAssociatedCount() > 0,
                        false, // Operator prefix (prefixInfo.getPrefixCode()) has NOT been stripped yet
                        prefixInfo.getPrefixCode() // Pass the operator prefix to be potentially stripped by findDestinationIndicator
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
                log.debug("Best match after PBX strip and operator prefix lookup: {}", bestMatchDestInfo);
            }
        }

        if (bestMatchDestInfo == null) {
            String numberForGeneralLookup = numberForProcessing;
            log.debug("No definitive match from PBX/operator prefix stage. Proceeding with general incoming prefix lookup on: {}", numberForGeneralLookup);

            List<PrefixInfo> incomingGeneralPrefixes = prefixLookupService.findMatchingPrefixes(
                    numberForGeneralLookup, commLocation, false, null
            );
            log.debug("Found {} potential general incoming prefixes for number '{}'", incomingGeneralPrefixes.size(), numberForGeneralLookup);

            for (PrefixInfo prefixInfo : incomingGeneralPrefixes) {
                Optional<DestinationInfo> destInfoOpt = indicatorLookupService.findDestinationIndicator(
                        numberForGeneralLookup,
                        prefixInfo.getTelephonyTypeId(),
                        prefixInfo.getTelephonyTypeMinLength() != null ? prefixInfo.getTelephonyTypeMinLength() : 0,
                        currentPlantIndicatorId,
                        prefixInfo.getPrefixId(),
                        originCountryId,
                        prefixInfo.getBandsAssociatedCount() > 0,
                        false, // Operator prefix (prefixInfo.getPrefixCode()) has NOT been stripped yet
                        prefixInfo.getPrefixCode() // Pass the operator prefix to be potentially stripped
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
                log.debug("Best match after general incoming prefix lookup: {}", bestMatchDestInfo);
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

            if (originInfo.getTelephonyTypeId() == TelephonyTypeEnum.CELLULAR.getValue() &&
                (originInfo.getOperatorId() == null || originInfo.getOperatorId() == 0L)) {
                log.debug("Cellular call origin, operator determined from prefix: {}. If null/0, it means generic cellular.", originInfo.getOperatorName());
            }
        } else {
            log.warn("No definitive origin found for incoming number: {}. Using defaults.", originalIncomingNumber);
        }

        log.info("Determined incoming call origin: {}", originInfo);
        return originInfo;
    }
}