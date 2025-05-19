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
        PrefixInfo bestMatchedPrefixInfo = null; // Store the prefix that led to the best match

        for (PrefixInfo prefixInfo : allPrefixes) {
            if (TelephonyTypeEnum.isInternalIpType(prefixInfo.getTelephonyTypeId()) ||
                    prefixInfo.getTelephonyTypeId() == TelephonyTypeEnum.SPECIAL_SERVICES.getValue() ||
                    prefixInfo.getTelephonyTypeId() == TelephonyTypeEnum.CELUFIJO.getValue()) {
                continue;
            }

            String numberToCheck = incomingNumber; // For incoming, we usually check with the prefix intact

            Optional<DestinationInfo> destInfoOpt = indicatorLookupService.findDestinationIndicator(
                    numberToCheck,
                    prefixInfo.getTelephonyTypeId(),
                    prefixInfo.getTelephonyTypeMinLength() != null ? prefixInfo.getTelephonyTypeMinLength() : 0,
                    currentPlantIndicatorId,
                    prefixInfo.getPrefixId(),
                    originCountryId,
                    prefixInfo.getBandsAssociatedCount() > 0
            );

            if (destInfoOpt.isPresent()) {
                DestinationInfo currentDestInfo = destInfoOpt.get();
                // Prefer exact matches, then longer NDC matches if both are approximate or both exact
                if (bestMatchDestInfo == null ||
                        (!bestMatchDestInfo.isApproximateMatch() && currentDestInfo.isApproximateMatch()) || // Current best is exact, new is approx (keep current)
                        (currentDestInfo.isApproximateMatch() == bestMatchDestInfo.isApproximateMatch() &&
                                currentDestInfo.getNdc() != null && bestMatchDestInfo.getNdc() != null &&
                                currentDestInfo.getNdc().length() > bestMatchDestInfo.getNdc().length()) ||
                        (!currentDestInfo.isApproximateMatch() && bestMatchDestInfo.isApproximateMatch()) // New is exact, current was approx
                ) {
                    bestMatchDestInfo = currentDestInfo;
                    bestMatchedPrefixInfo = prefixInfo; // Store the prefix that led to this match
                    if (!bestMatchDestInfo.isApproximateMatch()) break; // Found an exact match, stop
                }
            }
        }

        if (bestMatchDestInfo != null && bestMatchedPrefixInfo != null) {
            originInfo.setIndicatorId(bestMatchDestInfo.getIndicatorId());
            originInfo.setDestinationDescription(bestMatchDestInfo.getDestinationDescription());
            originInfo.setOperatorId(bestMatchedPrefixInfo.getOperatorId()); // Operator from the matched prefix
            originInfo.setOperatorName(bestMatchedPrefixInfo.getOperatorName());
            originInfo.setEffectiveNumber(bestMatchDestInfo.getMatchedPhoneNumber());
            originInfo.setTelephonyTypeId(bestMatchedPrefixInfo.getTelephonyTypeId()); // Type from the prefix
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
        }
        return originInfo;
    }
}
