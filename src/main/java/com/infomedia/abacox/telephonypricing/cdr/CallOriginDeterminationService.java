package com.infomedia.abacox.telephonypricing.cdr;

import com.infomedia.abacox.telephonypricing.entity.CommunicationLocation;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.stereotype.Service;

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


    public IncomingCallOriginInfo determineIncomingCallOrigin(String incomingNumber, CommunicationLocation commLocation) {
        IncomingCallOriginInfo originInfo = new IncomingCallOriginInfo();
        originInfo.setEffectiveNumber(incomingNumber);
        originInfo.setTelephonyTypeId(TelephonyTypeEnum.LOCAL.getValue()); // Default
        originInfo.setTelephonyTypeName(telephonyTypeLookupService.getTelephonyTypeName(TelephonyTypeEnum.LOCAL.getValue()));
        originInfo.setIndicatorId(commLocation.getIndicatorId());

        if (incomingNumber == null || incomingNumber.isEmpty() || commLocation.getIndicator() == null) {
            return originInfo;
        }

        Long originCountryId = commLocation.getIndicator().getOriginCountryId();
        Long currentPlantIndicatorId = commLocation.getIndicatorId();

        List<PrefixInfo> allPrefixes = prefixLookupService.findMatchingPrefixes(incomingNumber, commLocation, false, null);

        DestinationInfo bestMatchDestInfo = null;
        PrefixInfo bestMatchedPrefixInfo = null;

        for (PrefixInfo prefixInfo : allPrefixes) {
            if (telephonyTypeLookupService.isInternalIpType(prefixInfo.getTelephonyTypeId()) ||
                    prefixInfo.getTelephonyTypeId() == TelephonyTypeEnum.SPECIAL_SERVICES.getValue() ||
                    prefixInfo.getTelephonyTypeId() == TelephonyTypeEnum.CELUFIJO.getValue()) {
                continue;
            }

            String numberToCheck = incomingNumber;

            Optional<DestinationInfo> destInfoOpt = indicatorLookupService.findDestinationIndicator(
                    numberToCheck,
                    prefixInfo.getTelephonyTypeId(),
                    prefixInfo.getTelephonyTypeMinLength() != null ? prefixInfo.getTelephonyTypeMinLength() : 0,
                    currentPlantIndicatorId,
                    prefixInfo.getPrefixId(),
                    originCountryId,
                    prefixInfo.getBandsAssociatedCount() > 0,
                    false
            );

            if (destInfoOpt.isPresent()) {
                DestinationInfo currentDestInfo = destInfoOpt.get();
                if (bestMatchDestInfo == null ||
                        (!bestMatchDestInfo.isApproximateMatch() && currentDestInfo.isApproximateMatch()) ||
                        (currentDestInfo.isApproximateMatch() == bestMatchDestInfo.isApproximateMatch() &&
                                currentDestInfo.getNdc() != null && bestMatchDestInfo.getNdc() != null &&
                                currentDestInfo.getNdc().length() > bestMatchDestInfo.getNdc().length()) ||
                        (!currentDestInfo.isApproximateMatch() && bestMatchDestInfo.isApproximateMatch())
                ) {
                    bestMatchDestInfo = currentDestInfo;
                    bestMatchedPrefixInfo = prefixInfo;
                    if (bestMatchDestInfo != null && !bestMatchDestInfo.isApproximateMatch()) break;
                }
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
        } else {
            log.debug("No specific prefix/destination match for incoming number '{}'. Defaulting to LOCAL.", incomingNumber);
            originInfo.setTelephonyTypeId(TelephonyTypeEnum.LOCAL.getValue());
            originInfo.setTelephonyTypeName(telephonyTypeLookupService.getTelephonyTypeName(TelephonyTypeEnum.LOCAL.getValue()));
            originInfo.setIndicatorId(commLocation.getIndicatorId());
            originInfo.setDestinationDescription(commLocation.getIndicator() != null ? commLocation.getIndicator().getCityName() : "Unknown City");

            Optional<PrefixInfo> localPrefixDetails = telephonyTypeLookupService.getPrefixInfoForTelephonyType(
                    TelephonyTypeEnum.LOCAL.getValue(),
                    originCountryId
            );
            if (localPrefixDetails.isPresent()) {
                originInfo.setOperatorId(localPrefixDetails.get().getOperatorId());
                originInfo.setOperatorName(localPrefixDetails.get().getOperatorName());
                log.debug("Set operator for LOCAL default: {}", localPrefixDetails.get());
            } else {
                log.warn("Could not find PrefixInfo for LOCAL type in country {}. Operator will be default.", originCountryId);
                originInfo.setOperatorId(CdrConfigService.DEFAULT_OPERATOR_ID_FOR_INTERNAL); // Or a specific default for LOCAL
                originInfo.setOperatorName("Default Local Operator");
            }
        }
        return originInfo;
    }
}