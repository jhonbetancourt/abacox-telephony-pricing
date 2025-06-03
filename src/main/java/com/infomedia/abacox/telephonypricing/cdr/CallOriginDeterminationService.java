// File: com/infomedia/abacox/telephonypricing/cdr/CallOriginDeterminationService.java
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

    private final PrefixLookupService prefixLookupService;
    private final IndicatorLookupService indicatorLookupService;
    private final TelephonyTypeLookupService telephonyTypeLookupService;
    private final OperatorLookupService operatorLookupService;

    /**
     * PHP equivalent: buscarOrigen
     */
    public IncomingCallOriginInfo determineIncomingCallOrigin(String externalCallerId, CommunicationLocation commLocation) {
        log.debug("Determining incoming call origin for: {}, CommLocation: {}", externalCallerId, commLocation.getDirectory());
        IncomingCallOriginInfo result = new IncomingCallOriginInfo();
        result.setEffectiveNumber(externalCallerId); // Start with the input number
        result.setTelephonyTypeId(TelephonyTypeEnum.LOCAL.getValue()); // PHP default
        result.setTelephonyTypeName(telephonyTypeLookupService.getTelephonyTypeName(result.getTelephonyTypeId()));
        result.setIndicatorId(commLocation.getIndicatorId()); // Default to current plant's indicator
        result.setOperatorId(null); // Default

        if (externalCallerId == null || externalCallerId.isEmpty()) {
            log.warn("External caller ID is empty, cannot determine origin accurately.");
            return result;
        }

        String numberForProcessing = externalCallerId;
        List<String> pbxPrefixes = commLocation.getPbxPrefix() != null ?
                java.util.Arrays.asList(commLocation.getPbxPrefix().split(",")) : java.util.Collections.emptyList();

        // PHP: $maxCaracterAExtraer = Validar_prefijoSalida($telefono, $_PREFIJO_SALIDA_PBX);
        // PHP: if ($maxCaracterAExtraer > 0) { $telefono_eval = substr($telefono, $maxCaracterAExtraer); ... }
        // This implies if a PBX prefix is found, the logic inside the IF is used.
        // If not, the logic in the ELSE (Path B) is used.

        String numberAfterPbxStrip = CdrUtil.cleanPhoneNumber(numberForProcessing, pbxPrefixes, false);
        boolean pbxPrefixWasStripped = !numberAfterPbxStrip.equals(numberForProcessing) &&
                                       CdrUtil.cleanPhoneNumber(numberForProcessing, pbxPrefixes, true).length() > numberAfterPbxStrip.length();
                                       // The second part of condition mimics PHP's $maxCaracterAExtraer > 0

        if (pbxPrefixWasStripped) {
            log.debug("Path A (PBX prefix stripped): Number for prefix lookup: {}", numberAfterPbxStrip);
            // PHP: $tipoteles_arr = buscarPrefijo($telefono_eval, false, $mporigen_id, $link);
            List<PrefixInfo> prefixes = prefixLookupService.findMatchingPrefixes(
                    numberAfterPbxStrip, commLocation, false, null
            );
            for (PrefixInfo pi : prefixes) {
                // PHP: $arreglo = buscarDestino($telefono_eval, $tipotele_id, $tipotele_min, $indicativo_origen_id, $prefijo_operador, $prefijo_id, false, $mporigen_id, $bandas_ok, $link);
                Optional<DestinationInfo> destInfoOpt = indicatorLookupService.findDestinationIndicator(
                        numberAfterPbxStrip, // Number after PBX strip, but *before* operator prefix strip for this path
                        pi.getTelephonyTypeId(),
                        pi.getTelephonyTypeMinLength() != null ? pi.getTelephonyTypeMinLength() : 0,
                        commLocation.getIndicatorId(),
                        pi.getPrefixId(),
                        commLocation.getIndicator().getOriginCountryId(),
                        pi.getBandsAssociatedCount() > 0,
                        false, // Operator prefix NOT yet stripped for this path
                        pi.getPrefixCode()
                );
                if (destInfoOpt.isPresent() && destInfoOpt.get().getIndicatorId() != null && destInfoOpt.get().getIndicatorId() > 0) {
                    DestinationInfo di = destInfoOpt.get();
                    result.setOperatorId(pi.getOperatorId());
                    result.setOperatorName(pi.getOperatorName());
                    result.setEffectiveNumber(di.getMatchedPhoneNumber()); // Number used for successful match
                    updateResultFromDestinationInfo(result, di, pi.getTelephonyTypeId(), pi.getTelephonyTypeName(), commLocation);
                    log.debug("Path A match found: {}", result);
                    return result;
                }
            }
        }
        // Path B: No PBX prefix stripped, or Path A failed to find a match
        log.debug("Path B (No PBX prefix stripped or Path A failed): Number for processing: {}", numberForProcessing);
        List<IncomingTelephonyTypePriority> typePriorities = telephonyTypeLookupService.getIncomingTelephonyTypePriorities(
                commLocation.getIndicator().getOriginCountryId()
        );

        for (IncomingTelephonyTypePriority typePriority : typePriorities) {
            int phoneLen = numberForProcessing.length();
            // PHP: if ($tipotele_valido && $len_telefono >= $tipotele_min && $len_telefono <= $tipotele_max)
            // PHP's $tipotele_min and $tipotele_max here are for the *subscriber part* after op prefix.
            // The outer loop condition in PHP is based on total length.

            if (phoneLen >= typePriority.getMinSubscriberLength() &&
                    phoneLen <= typePriority.getMaxSubscriberLength()) {
                // PHP: $arreglo = buscarDestino($telefono_eval, $tipotele_id, $tipotele_min, $indicativo_origen_id, $prefijo_actual, $prefijo_id, true, $mporigen_id, false, $link);
                // For Path B, $reducir (isOperatorPrefixAlreadyStripped) is true, meaning findDestinationIndicator should expect number *without* operator prefix.
                // $prefijo_actual and $prefijo_id are empty/0 because we are trying to determine the type, not match a specific prefix.
                Optional<DestinationInfo> destInfoOpt = indicatorLookupService.findDestinationIndicator(
                        numberForProcessing, // Number *without* operator prefix
                        typePriority.getTelephonyTypeId(),
                        typePriority.getMinSubscriberLength(), // Min length of subscriber part
                        commLocation.getIndicatorId(),
                        null, // No specific prefix ID known yet
                        commLocation.getIndicator().getOriginCountryId(),
                        false, // Not checking bands in this generic path
                        true,  // Assume operator prefix (if any) is *not* in numberForProcessing for this path
                        null   // No specific operator prefix to strip
                );
                if (destInfoOpt.isPresent() && destInfoOpt.get().getIndicatorId() != null && destInfoOpt.get().getIndicatorId() > 0) {
                    DestinationInfo di = destInfoOpt.get();
                    // Operator for incoming calls in Path B is often determined by the bands of the destination indicator
                    // if it's cellular, or defaults.
                    if (typePriority.getTelephonyTypeId().equals(TelephonyTypeEnum.CELLULAR.getValue())) {
                        operatorLookupService.findOperatorForIncomingCellularByIndicatorBands(di.getIndicatorId())
                            .ifPresent(opInfo -> {
                                result.setOperatorId(opInfo.getId());
                                result.setOperatorName(opInfo.getName());
                            });
                    } else {
                        // For other types, operator might be from indicator or default
                        result.setOperatorId(di.getOperatorId());
                        if (result.getOperatorId() != null) {
                             result.setOperatorName(operatorLookupService.findOperatorNameById(result.getOperatorId()));
                        }
                    }
                    result.setEffectiveNumber(di.getMatchedPhoneNumber());
                    updateResultFromDestinationInfo(result, di, typePriority.getTelephonyTypeId(), typePriority.getTelephonyTypeName(), commLocation);
                    log.debug("Path B match found: {}", result);
                    return result;
                }
            }
        }
        log.warn("No definitive origin found for incoming call '{}'. Returning defaults.", externalCallerId);
        return result; // Return defaults if no match
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