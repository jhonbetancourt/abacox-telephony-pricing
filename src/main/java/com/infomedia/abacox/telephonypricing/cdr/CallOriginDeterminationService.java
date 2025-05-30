package com.infomedia.abacox.telephonypricing.cdr;

import com.infomedia.abacox.telephonypricing.entity.CommunicationLocation;
import com.infomedia.abacox.telephonypricing.entity.TelephonyTypeConfig;
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
    private final PbxSpecialRuleLookupService pbxSpecialRuleLookupService;
    private final PhoneNumberTransformationService phoneNumberTransformationService;

    public IncomingCallOriginInfo determineIncomingCallOrigin(String originalIncomingNumberExt, CommunicationLocation commLocation) {
        IncomingCallOriginInfo originInfo = new IncomingCallOriginInfo();
        originInfo.setEffectiveNumber(originalIncomingNumberExt);
        originInfo.setTelephonyTypeId(TelephonyTypeEnum.LOCAL.getValue()); // Default
        originInfo.setTelephonyTypeName(telephonyTypeLookupService.getTelephonyTypeName(TelephonyTypeEnum.LOCAL.getValue()));
        if (commLocation != null && commLocation.getIndicator() != null) {
            originInfo.setIndicatorId(commLocation.getIndicatorId());
        } else {
            log.warn("CommLocation or its Indicator is null in determineIncomingCallOrigin. Cannot set default indicatorId.");
            originInfo.setIndicatorId(null); // Or handle as error
        }

        if (originalIncomingNumberExt == null || originalIncomingNumberExt.isEmpty() || commLocation == null || commLocation.getIndicator() == null) {
            log.warn("Insufficient data for incoming call origin determination. Number: {}, CommLocation: {}", originalIncomingNumberExt, commLocation);
            return originInfo;
        }
        log.debug("Determining incoming call origin for: {}, CommLocation: {}", originalIncomingNumberExt, commLocation.getDirectory());

        Long originCountryId = commLocation.getIndicator().getOriginCountryId();
        Long currentPlantIndicatorId = commLocation.getIndicatorId();

        String numberForProcessing = originalIncomingNumberExt;

        Optional<String> pbxTransformed = pbxSpecialRuleLookupService.applyPbxSpecialRule(
                numberForProcessing, commLocation.getDirectory(), 1 // 1 for incoming
        );
        if (pbxTransformed.isPresent()) {
            log.debug("External Caller ID '{}' transformed by PBX incoming rule to '{}'", numberForProcessing, pbxTransformed.get());
            numberForProcessing = pbxTransformed.get();
        }

        TransformationResult cmeTransformed = phoneNumberTransformationService.transformIncomingNumberCME(
                numberForProcessing, originCountryId
        );
        if (cmeTransformed.isTransformed()) {
            log.debug("External Caller ID '{}' transformed by CME rule to '{}'", numberForProcessing, cmeTransformed.getTransformedNumber());
            numberForProcessing = cmeTransformed.getTransformedNumber();
            // Hinted type from CME transform is not directly used here to override main logic, but could be a factor
        }

        DestinationInfo bestMatchDestInfo = null;
        PrefixInfo bestMatchedPrefixInfo = null;

        // PHP: if ($maxCaracterAExtraer > 0) { ... buscarPrefijo ... buscarDestino ... }
        // This implies checking if a PBX prefix was stripped, then trying to find operator prefixes.
        // The current Java logic for PBX prefix stripping is in CdrUtil.cleanPhoneNumber,
        // which is usually called *before* this stage for outgoing. For incoming, it's less direct.
        // Let's assume numberForProcessing is the number *after* any PBX exit prefix might have been conceptually removed
        // (though for incoming, PBX exit prefixes are less common on the source number).

        // PHP: else { iterate $_lista_Prefijos['in']['orden'] ... buscarDestino ... }
        // This is the main path for incoming numbers that don't show an obvious operator prefix after PBX handling.
        List<TelephonyTypeEnum> typesToTry = Arrays.asList(
                TelephonyTypeEnum.INTERNATIONAL, TelephonyTypeEnum.SATELLITE,
                TelephonyTypeEnum.NATIONAL, TelephonyTypeEnum.CELLULAR
        );

        for (TelephonyTypeEnum typeToTry : typesToTry) {
            log.debug("Attempting to match incoming number '{}' as TelephonyType: {}", numberForProcessing, typeToTry);

            TelephonyTypeConfig ttConfig = telephonyTypeLookupService.getTelephonyTypeConfig(typeToTry.getValue(), originCountryId);
            int minSubscriberLengthForType = (ttConfig != null && ttConfig.getMinValue() != null) ? ttConfig.getMinValue() : 0;
            // For incoming, the "minTotalNumberLengthFromPrefixConfig" for findDestinationIndicator should be this minSubscriberLength.

            if (numberForProcessing.length() >= minSubscriberLengthForType) { // Basic length check
                Optional<DestinationInfo> destInfoOpt = indicatorLookupService.findDestinationIndicator(
                        numberForProcessing,
                        typeToTry.getValue(),
                        minSubscriberLengthForType,
                        currentPlantIndicatorId,
                        null, // No specific prefix_id known for incoming external number
                        originCountryId,
                        false, // No specific prefix bands for incoming external
                        true,  // No operator prefix expected to be stripped at this stage
                        null   // No operator prefix
                );

                if (destInfoOpt.isPresent() && destInfoOpt.get().getIndicatorId() != null && destInfoOpt.get().getIndicatorId() > 0) {
                    bestMatchDestInfo = destInfoOpt.get();
                    bestMatchedPrefixInfo = telephonyTypeLookupService.getRepresentativePrefixInfo(typeToTry.getValue(), originCountryId);
                    log.info("Incoming number '{}' matched as type {} ({}). Destination: {}",
                             numberForProcessing, typeToTry, bestMatchedPrefixInfo != null ? bestMatchedPrefixInfo.getTelephonyTypeName() : "N/A",
                             bestMatchDestInfo.getDestinationDescription());
                    break;
                }
            }
        }

        if (bestMatchDestInfo != null && bestMatchedPrefixInfo != null) {
            originInfo.setIndicatorId(bestMatchDestInfo.getIndicatorId());
            originInfo.setDestinationDescription(bestMatchDestInfo.getDestinationDescription());
            originInfo.setOperatorId(bestMatchedPrefixInfo.getOperatorId()); // This is operator of the representative prefix
            originInfo.setOperatorName(bestMatchedPrefixInfo.getOperatorName());
            originInfo.setEffectiveNumber(bestMatchDestInfo.getMatchedPhoneNumber()); // Number that led to match
            originInfo.setTelephonyTypeId(bestMatchedPrefixInfo.getTelephonyTypeId());
            originInfo.setTelephonyTypeName(bestMatchedPrefixInfo.getTelephonyTypeName());

            if (!Objects.equals(currentPlantIndicatorId, bestMatchDestInfo.getIndicatorId())) {
                if (indicatorLookupService.isLocalExtended(bestMatchDestInfo.getNdc(), currentPlantIndicatorId, bestMatchDestInfo.getIndicatorId())) {
                    originInfo.setTelephonyTypeId(TelephonyTypeEnum.LOCAL_EXTENDED.getValue());
                    originInfo.setTelephonyTypeName(telephonyTypeLookupService.getTelephonyTypeName(TelephonyTypeEnum.LOCAL_EXTENDED.getValue()) + " (Incoming)");
                }
            } else { // Matched current plant's indicator
                originInfo.setTelephonyTypeId(TelephonyTypeEnum.LOCAL.getValue());
                originInfo.setTelephonyTypeName(telephonyTypeLookupService.getTelephonyTypeName(TelephonyTypeEnum.LOCAL.getValue()) + " (Incoming)");
            }
        } else {
            log.warn("No definitive origin found for incoming number: {}. Using defaults (Local).", originalIncomingNumberExt);
            // Defaults are already set in originInfo initialization
            originInfo.setEffectiveNumber(originalIncomingNumberExt); // No transformation applied if no match
        }

        log.info("Determined incoming call origin: {}", originInfo);
        return originInfo;
    }
}