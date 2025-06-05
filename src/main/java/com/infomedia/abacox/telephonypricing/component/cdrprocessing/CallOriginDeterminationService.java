// File: com/infomedia/abacox/telephonypricing/component/cdrprocessing/CallOriginDeterminationService.java
package com.infomedia.abacox.telephonypricing.component.cdrprocessing;

import com.infomedia.abacox.telephonypricing.entity.CommunicationLocation;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.stereotype.Service;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

@Service
@Log4j2
@RequiredArgsConstructor
public class CallOriginDeterminationService {

    private final PrefixLookupService prefixLookupService;
    private final IndicatorLookupService indicatorLookupService;
    private final TelephonyTypeLookupService telephonyTypeLookupService;
    private final OperatorLookupService operatorLookupService;

    /**
     * PHP equivalent: buscarOrigen
     */
    public IncomingCallOriginInfo determineIncomingCallOrigin(String processedExternalCallerId,
                                                              Long hintedTelephonyTypeIdFromTransform,
                                                              CommunicationLocation commLocation) {
        log.debug("Determining incoming call origin for: {}, Hinted Type: {}, CommLocation: {}",
                processedExternalCallerId, hintedTelephonyTypeIdFromTransform, commLocation.getDirectory());
        IncomingCallOriginInfo result = new IncomingCallOriginInfo();
        result.setEffectiveNumber(processedExternalCallerId);
        result.setTelephonyTypeId(TelephonyTypeEnum.LOCAL.getValue()); // PHP default
        result.setTelephonyTypeName(telephonyTypeLookupService.getTelephonyTypeName(result.getTelephonyTypeId()));
        result.setIndicatorId(commLocation.getIndicatorId());
        result.setOperatorId(null);

        if (processedExternalCallerId == null || processedExternalCallerId.isEmpty()) {
            log.warn("External caller ID is empty, cannot determine origin accurately.");
            return result;
        }

        String numberForProcessing = processedExternalCallerId;
        List<String> pbxPrefixes = commLocation.getPbxPrefix() != null ?
                Arrays.asList(commLocation.getPbxPrefix().split(",")) : java.util.Collections.emptyList();

        CleanPhoneNumberResult cleanPhoneNumber = CdrUtil.cleanPhoneNumber(numberForProcessing, pbxPrefixes, false);

        // --- Path A (PHP: if ($maxCaracterAExtraer > 0)) ---
        if (cleanPhoneNumber.isPbxPrefixStripped()) {
            String numberAfterPbxStrip = cleanPhoneNumber.getCleanedNumber();
            log.debug("Path A (PBX prefix stripped): Number for prefix lookup: {}", numberAfterPbxStrip);
            List<PrefixInfo> prefixes = prefixLookupService.findMatchingPrefixes(
                    numberAfterPbxStrip, commLocation, false, null
            );
            for (PrefixInfo pi : prefixes) {
                if (hintedTelephonyTypeIdFromTransform == null || hintedTelephonyTypeIdFromTransform.equals(pi.getTelephonyTypeId())) {
                    Optional<DestinationInfo> destInfoOpt = indicatorLookupService.findDestinationIndicator(
                            numberAfterPbxStrip,
                            pi.getTelephonyTypeId(),
                            pi.getTelephonyTypeMinLength() != null ? pi.getTelephonyTypeMinLength() : 0,
                            commLocation.getIndicatorId(),
                            pi.getPrefixId(),
                            commLocation.getIndicator().getOriginCountryId(),
                            pi.getBandsAssociatedCount() > 0,
                            false,
                            pi.getPrefixCode()
                    );
                    if (destInfoOpt.isPresent() && destInfoOpt.get().getIndicatorId() != null && destInfoOpt.get().getIndicatorId() > 0) {
                        DestinationInfo di = destInfoOpt.get();
                        // The operator is the one from the PrefixInfo (pi) that we are currently testing.
                        result.setOperatorId(pi.getOperatorId());
                        result.setOperatorName(pi.getOperatorName());
                        result.setEffectiveNumber(di.getMatchedPhoneNumber());
                        updateResultFromDestinationInfo(result, di, pi.getTelephonyTypeId(), pi.getTelephonyTypeName(), commLocation);
                        log.debug("Path A match found: {}", result);
                        return result; // Return immediately, mimicking PHP's 'break'
                    }
                    if (hintedTelephonyTypeIdFromTransform != null && hintedTelephonyTypeIdFromTransform.equals(pi.getTelephonyTypeId())) {
                        log.debug("Hinted type {} used in Path A but no destination found. Breaking from Path A prefix loop.", hintedTelephonyTypeIdFromTransform);
                        break;
                    }
                }
            }
        }

        // --- Path B (PHP: if ($arreglo['INDICATIVO_ID'] <= 0)) ---
        log.debug("Path B (No PBX prefix stripped or Path A failed): Number for processing: {}", numberForProcessing);
        List<IncomingTelephonyTypePriority> typePriorities = telephonyTypeLookupService.getIncomingTelephonyTypePriorities(
                commLocation.getIndicator().getOriginCountryId()
        );

        if (hintedTelephonyTypeIdFromTransform != null) {
            log.debug("Path B: Prioritizing hinted telephony type: {}", hintedTelephonyTypeIdFromTransform);
            Optional<IncomingTelephonyTypePriority> hintedTypePriorityOpt = typePriorities.stream()
                    .filter(tp -> tp.getTelephonyTypeId().equals(hintedTelephonyTypeIdFromTransform))
                    .findFirst();

            if (hintedTypePriorityOpt.isPresent()) {
                IncomingTelephonyTypePriority hintedTypePriority = hintedTypePriorityOpt.get();
                int phoneLen = numberForProcessing.length();
                if (phoneLen >= hintedTypePriority.getMinSubscriberLength() && phoneLen <= hintedTypePriority.getMaxSubscriberLength()) {
                    Optional<DestinationInfo> destInfoOpt = indicatorLookupService.findDestinationIndicator(
                            numberForProcessing,
                            hintedTypePriority.getTelephonyTypeId(),
                            hintedTypePriority.getMinSubscriberLength(),
                            commLocation.getIndicatorId(),
                            null,
                            commLocation.getIndicator().getOriginCountryId(),
                            false,
                            true,
                            null
                    );
                    if (destInfoOpt.isPresent() && destInfoOpt.get().getIndicatorId() != null && destInfoOpt.get().getIndicatorId() > 0) {
                        DestinationInfo di = destInfoOpt.get();
                        setOperatorForIncomingPathB(result, hintedTypePriority.getTelephonyTypeId(), di, commLocation);
                        result.setEffectiveNumber(di.getMatchedPhoneNumber());
                        updateResultFromDestinationInfo(result, di, hintedTypePriority.getTelephonyTypeId(), hintedTypePriority.getTelephonyTypeName(), commLocation);
                        log.debug("Path B match found using HINTED type {}: {}", hintedTelephonyTypeIdFromTransform, result);
                        return result;
                    }
                }
            }
        }

        for (IncomingTelephonyTypePriority typePriority : typePriorities) {
            if (hintedTelephonyTypeIdFromTransform != null && typePriority.getTelephonyTypeId().equals(hintedTelephonyTypeIdFromTransform)) {
                continue;
            }

            int phoneLen = numberForProcessing.length();
            if (phoneLen >= typePriority.getMinSubscriberLength() && phoneLen <= typePriority.getMaxSubscriberLength()) {
                Optional<DestinationInfo> destInfoOpt = indicatorLookupService.findDestinationIndicator(
                        numberForProcessing,
                        typePriority.getTelephonyTypeId(),
                        typePriority.getMinSubscriberLength(),
                        commLocation.getIndicatorId(),
                        null,
                        commLocation.getIndicator().getOriginCountryId(),
                        false,
                        true,
                        null
                );
                if (destInfoOpt.isPresent() && destInfoOpt.get().getIndicatorId() != null && destInfoOpt.get().getIndicatorId() > 0) {
                    DestinationInfo di = destInfoOpt.get();
                    setOperatorForIncomingPathB(result, typePriority.getTelephonyTypeId(), di, commLocation);
                    result.setEffectiveNumber(di.getMatchedPhoneNumber());
                    updateResultFromDestinationInfo(result, di, typePriority.getTelephonyTypeId(), typePriority.getTelephonyTypeName(), commLocation);
                    log.debug("Path B match found: {}", result);
                    return result; // Return immediately, mimicking PHP's 'break 2'
                }
            }
        }
        log.warn("No definitive origin found for incoming call '{}'. Returning defaults.", processedExternalCallerId);
        return result;
    }

    private void setOperatorForIncomingPathB(IncomingCallOriginInfo result, Long telephonyTypeId, DestinationInfo di, CommunicationLocation commLocation) {
        if (telephonyTypeId.equals(TelephonyTypeEnum.CELLULAR.getValue())) {
            operatorLookupService.findOperatorForIncomingCellularByIndicatorBands(di.getIndicatorId())
                .ifPresent(opInfo -> {
                    result.setOperatorId(opInfo.getId());
                    result.setOperatorName(opInfo.getName());
                });
        }
    }

    private void updateResultFromDestinationInfo(IncomingCallOriginInfo result, DestinationInfo di,
                                                 Long matchedTelephonyTypeId, String matchedTelephonyTypeName,
                                                 CommunicationLocation commLocation) {
        result.setIndicatorId(di.getIndicatorId());
        result.setDestinationDescription(di.getDestinationDescription());

        if (Objects.equals(result.getIndicatorId(), commLocation.getIndicatorId())) {
            result.setTelephonyTypeId(TelephonyTypeEnum.LOCAL.getValue());
            result.setTelephonyTypeName(telephonyTypeLookupService.getTelephonyTypeName(TelephonyTypeEnum.LOCAL.getValue()));
        } else if (indicatorLookupService.isLocalExtended(di.getNdc(), commLocation.getIndicatorId(), di.getIndicatorId())) {
            result.setTelephonyTypeId(TelephonyTypeEnum.LOCAL_EXTENDED.getValue());
            result.setTelephonyTypeName(telephonyTypeLookupService.getTelephonyTypeName(TelephonyTypeEnum.LOCAL_EXTENDED.getValue()));
        } else {
            result.setTelephonyTypeId(matchedTelephonyTypeId);
            result.setTelephonyTypeName(matchedTelephonyTypeName);
        }
    }
}