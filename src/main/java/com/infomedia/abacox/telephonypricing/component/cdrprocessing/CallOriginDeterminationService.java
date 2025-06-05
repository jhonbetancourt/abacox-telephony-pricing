// File: com/infomedia/abacox/telephonypricing/cdr/CallOriginDeterminationService.java
package com.infomedia.abacox.telephonypricing.component.cdrprocessing;

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

    private final PrefixLookupService prefixLookupService;
    private final IndicatorLookupService indicatorLookupService;
    private final TelephonyTypeLookupService telephonyTypeLookupService;
    private final OperatorLookupService operatorLookupService;

    /**
     * PHP equivalent: buscarOrigen
     *
     * @param processedExternalCallerId The phone number *after* initial transformations (like _esEntrante_60)
     * @param hintedTelephonyTypeIdFromTransform The telephony type ID hinted by the transformation, if any.
     * @param commLocation The current communication location.
     * @return IncomingCallOriginInfo
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
                java.util.Arrays.asList(commLocation.getPbxPrefix().split(",")) : java.util.Collections.emptyList();

        String numberAfterPbxStrip = CdrUtil.cleanPhoneNumber(numberForProcessing, pbxPrefixes, false);
        boolean pbxPrefixWasStripped = !numberAfterPbxStrip.equals(numberForProcessing) &&
                                       CdrUtil.cleanPhoneNumber(numberForProcessing, pbxPrefixes, true).length() > numberAfterPbxStrip.length();

        if (pbxPrefixWasStripped) {
            log.debug("Path A (PBX prefix stripped): Number for prefix lookup: {}", numberAfterPbxStrip);
            List<PrefixInfo> prefixes = prefixLookupService.findMatchingPrefixes(
                    numberAfterPbxStrip, commLocation, false, null
            );
            for (PrefixInfo pi : prefixes) {
                // If a hint was provided and it matches the current prefix's type, prioritize it.
                // Or, if no hint, proceed normally.
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
                        result.setOperatorId(pi.getOperatorId());
                        result.setOperatorName(pi.getOperatorName());
                        result.setEffectiveNumber(di.getMatchedPhoneNumber());
                        updateResultFromDestinationInfo(result, di, pi.getTelephonyTypeId(), pi.getTelephonyTypeName(), commLocation);
                        log.debug("Path A match found (hint considered if present): {}", result);
                        return result;
                    }
                    // If hint was used and didn't match, don't try other prefixes for Path A with this hint.
                    // PHP's $g_tipotele is reset after one use.
                    if (hintedTelephonyTypeIdFromTransform != null && hintedTelephonyTypeIdFromTransform.equals(pi.getTelephonyTypeId())) {
                        log.debug("Hinted type {} used in Path A but no destination found. Breaking from Path A prefix loop.", hintedTelephonyTypeIdFromTransform);
                        break;
                    }
                }
            }
        }

        log.debug("Path B (No PBX prefix stripped or Path A failed): Number for processing: {}", numberForProcessing);
        List<IncomingTelephonyTypePriority> typePriorities = telephonyTypeLookupService.getIncomingTelephonyTypePriorities(
                commLocation.getIndicator().getOriginCountryId()
        );

        // If a hint is present, try it first with high priority in Path B
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

        // If hint didn't lead to a match, or no hint, proceed with normal Path B iteration
        for (IncomingTelephonyTypePriority typePriority : typePriorities) {
            // Skip the hinted type if we already tried it and it failed
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
                    return result;
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
        } else {
            result.setOperatorId(di.getOperatorId());
            if (result.getOperatorId() != null) {
                 result.setOperatorName(operatorLookupService.findOperatorNameById(result.getOperatorId()));
            }
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