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

    /**
     * PHP equivalent: buscarOrigen
     * Determines the origin details of an incoming call.
     */
    public IncomingCallOriginInfo determineIncomingCallOrigin(String originalIncomingNumber, CommunicationLocation commLocation) {
        IncomingCallOriginInfo originInfo = new IncomingCallOriginInfo();
        originInfo.setEffectiveNumber(originalIncomingNumber);
        // PHP: $tipotele = _TIPOTELE_LOCAL; (default)
        originInfo.setTelephonyTypeId(TelephonyTypeEnum.LOCAL.getValue());
        originInfo.setTelephonyTypeName(telephonyTypeLookupService.getTelephonyTypeName(TelephonyTypeEnum.LOCAL.getValue()));
        if (commLocation != null && commLocation.getIndicator() != null) {
            originInfo.setIndicatorId(commLocation.getIndicatorId()); // PHP: $indicativo_destino = $resultado_directorio['COMUBICACION_INDICATIVO_ID'];
        } else {
            log.warn("CommLocation or its Indicator is null in determineIncomingCallOrigin. Cannot set default indicatorId.");
            originInfo.setIndicatorId(0L); // Or handle as error
        }
        originInfo.setOperatorId(0L); // PHP: $operador = 0;

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

        // PHP: $maxCaracterAExtraer = Validar_prefijoSalida($telefono, $_PREFIJO_SALIDA_PBX);
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

        // Pass 1: If PBX prefix was stripped, try to find origin based on that.
        // PHP: if ($maxCaracterAExtraer > 0) { $telefono_eval = substr($telefono, $maxCaracterAExtraer); $tipoteles_arr = buscarPrefijo(...); foreach ($tipoteles_arr) { $arreglo = buscarDestino(...); if ($arreglo['INDICATIVO_ID'] > 0) break; } }
        if (pbxPrefixFoundAndStripped) {
            List<PrefixInfo> operatorPrefixes = prefixLookupService.findMatchingPrefixes(
                    numberAfterPbxStrip, commLocation, false, null // Treat as non-trunk
            );
            log.debug("Pass 1 (PBX stripped): Found {} potential operator prefixes for '{}'", operatorPrefixes.size(), numberAfterPbxStrip);

            for (PrefixInfo prefixInfo : operatorPrefixes) {
                Optional<DestinationInfo> destInfoOpt = indicatorLookupService.findDestinationIndicator(
                        numberAfterPbxStrip,
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
                    // PHP takes the first one that results in a valid INDICATIVO_ID
                    bestMatchDestInfo = destInfoOpt.get();
                    bestMatchedPrefixInfo = prefixInfo;
                    log.debug("Pass 1: Match found. Dest: {}, Prefix: {}", bestMatchDestInfo, bestMatchedPrefixInfo.getPrefixCode());
                    break;
                }
            }
        }

        // Pass 2: If no match from Pass 1, or no PBX prefix was stripped, try general incoming logic.
        // PHP: if ($arreglo['INDICATIVO_ID'] <= 0) { foreach ($_lista_Prefijos['in']['orden'] as $k => $tipotele_arr) { foreach ($tipotele_arr as $k2 => $tipotele_id) { $arreglo = buscarDestino(...); if ($arreglo['INDICATIVO_ID'] > 0) break 2; } } }
        if (bestMatchDestInfo == null) {
            String numberForGeneralLookup = numberForProcessing; // Use original number if PBX strip didn't lead to match or wasn't applicable
            log.debug("Pass 2 (General): No definitive match from Pass 1. Processing number: {}", numberForGeneralLookup);

            // PHP's $_lista_Prefijos['in']['orden'] implies an ordered list of telephony types to try.
            // We'll simulate this by trying relevant incoming types.
            // For simplicity, using findMatchingPrefixes which already sorts.
            // The key is that findDestinationIndicator needs to be called for each potential prefix.
            List<PrefixInfo> incomingGeneralPrefixes = prefixLookupService.findMatchingPrefixes(
                    numberForGeneralLookup, commLocation, false, null
            );
             log.debug("Pass 2: Found {} potential general incoming prefixes for '{}'", incomingGeneralPrefixes.size(), numberForGeneralLookup);

            for (PrefixInfo prefixInfo : incomingGeneralPrefixes) {
                // PHP's buscarDestino is complex. It tries to match NDC and then series.
                Optional<DestinationInfo> destInfoOpt = indicatorLookupService.findDestinationIndicator(
                        numberForGeneralLookup, // This is the number that might contain an operator prefix
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
                    bestMatchDestInfo = destInfoOpt.get();
                    bestMatchedPrefixInfo = prefixInfo;
                    log.debug("Pass 2: Match found. Dest: {}, Prefix: {}", bestMatchDestInfo, bestMatchedPrefixInfo.getPrefixCode());
                    break;
                }
            }
        }

        if (bestMatchDestInfo != null && bestMatchedPrefixInfo != null) {
            originInfo.setIndicatorId(bestMatchDestInfo.getIndicatorId());
            originInfo.setDestinationDescription(bestMatchDestInfo.getDestinationDescription());
            originInfo.setOperatorId(bestMatchedPrefixInfo.getOperatorId()); // This is the operator of the *prefix*
            originInfo.setOperatorName(bestMatchedPrefixInfo.getOperatorName());
            originInfo.setEffectiveNumber(bestMatchDestInfo.getMatchedPhoneNumber()); // Number after transformations by findDestinationIndicator
            originInfo.setTelephonyTypeId(bestMatchedPrefixInfo.getTelephonyTypeId());
            originInfo.setTelephonyTypeName(bestMatchedPrefixInfo.getTelephonyTypeName());

            // PHP: if ($indicativo_destino != $arreglo['INDICATIVO_ID']) { if (BuscarLocalExtendida(...)) { $tipotele = _TIPOTELE_LOCAL_EXT; } ... } else { $tipotele = _TIPOTELE_LOCAL; }
            if (!Objects.equals(currentPlantIndicatorId, bestMatchDestInfo.getIndicatorId())) {
                if (indicatorLookupService.isLocalExtended(bestMatchDestInfo.getNdc(), currentPlantIndicatorId, bestMatchDestInfo.getIndicatorId())) {
                    originInfo.setTelephonyTypeId(TelephonyTypeEnum.LOCAL_EXTENDED.getValue());
                    originInfo.setTelephonyTypeName(telephonyTypeLookupService.getTelephonyTypeName(TelephonyTypeEnum.LOCAL_EXTENDED.getValue()) + " (Incoming)");
                }
                // If not local extended, it keeps the type determined by the prefix (e.g., NATIONAL, CELLULAR)
            } else { // Destination indicator is the same as the plant's indicator
                originInfo.setTelephonyTypeId(TelephonyTypeEnum.LOCAL.getValue());
                originInfo.setTelephonyTypeName(telephonyTypeLookupService.getTelephonyTypeName(TelephonyTypeEnum.LOCAL.getValue()) + " (Incoming)");
            }

            // PHP: if ($tipotele == _TIPOTELE_CELULAR && $operador <= 0) { ... SQL to get PREFIJO_OPERADOR_ID from BANDAINDICA ... }
            if (originInfo.getTelephonyTypeId() == TelephonyTypeEnum.CELLULAR.getValue() &&
                (originInfo.getOperatorId() == null || originInfo.getOperatorId() == 0L)) {
                // The operatorId from bestMatchedPrefixInfo is from the prefix table.
                // PHP tries to find a more specific operator from bandaindica if the prefix operator is generic (0).
                // This is complex and might need a dedicated method if operator from prefix is not sufficient.
                // For now, we rely on the operator derived from the prefix.
                log.debug("Cellular call origin, operator determined from prefix: {}. PHP might do further bandaindica lookup if this is 0.", originInfo.getOperatorName());
            }
        } else {
            log.warn("No definitive origin found for incoming number: {}. Using defaults.", originalIncomingNumber);
        }

        log.info("Determined incoming call origin: {}", originInfo);
        return originInfo;
    }
}