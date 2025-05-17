package com.infomedia.abacox.telephonypricing.cdr;

import com.infomedia.abacox.telephonypricing.entity.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;


@Service
@RequiredArgsConstructor
@Log4j2
public class EnrichmentService {

    private final LookupService lookupService;
    private final CdrConfigService cdrConfigService;

    public void enrichCallRecord(CallRecord callRecord, RawCdrData rawData, CommunicationLocation commLocation) {
        InternalExtensionLimitsDto internalLimits = cdrConfigService.getInternalExtensionLimits(
            commLocation.getIndicator() != null ? commLocation.getIndicator().getOriginCountryId() : null,
            commLocation.getId()
        );

        assignEmployee(callRecord, rawData, commLocation, internalLimits);
        determineCallTypeAndPricing(callRecord, rawData, commLocation, internalLimits);

        if (callRecord.getDuration() != null && callRecord.getDuration() == 0 &&
            callRecord.getTelephonyTypeId() != null &&
            !Objects.equals(callRecord.getTelephonyTypeId(), TelephonyTypeConstants.ERRORES) &&
            !Objects.equals(callRecord.getTelephonyTypeId(), TelephonyTypeConstants.SIN_CONSUMO)) {
            
            log.debug("Call duration is zero, marking as SIN_CONSUMO. CDR Hash: {}", callRecord.getCdrHash());
            setTelephonyTypeAndOperator(callRecord, TelephonyTypeConstants.SIN_CONSUMO,
                commLocation.getIndicator() != null ? commLocation.getIndicator().getOriginCountryId() : null,
                null, true); // forceOperatorUpdate = true for SIN_CONSUMO
            callRecord.setBilledAmount(BigDecimal.ZERO);
            callRecord.setPricePerMinute(BigDecimal.ZERO);
            callRecord.setInitialPrice(BigDecimal.ZERO);
        }
    }

    private void assignEmployee(CallRecord callRecord, RawCdrData rawData, CommunicationLocation commLocation, InternalExtensionLimitsDto limits) {
        Optional<Employee> employeeOpt = Optional.empty();
        ImdexAssignmentCause assignmentCause = ImdexAssignmentCause.NOT_ASSIGNED;
        List<String> ignoredAuthCodes = cdrConfigService.getIgnoredAuthCodes();

        String effectiveOriginator = rawData.getEffectiveOriginatingNumber();

        if (rawData.getAuthCodeDescription() != null && !rawData.getAuthCodeDescription().isEmpty() &&
            !ignoredAuthCodes.contains(rawData.getAuthCodeDescription())) {
            employeeOpt = lookupService.findEmployeeByAuthCode(rawData.getAuthCodeDescription(), commLocation.getId());
            if (employeeOpt.isPresent()) {
                assignmentCause = ImdexAssignmentCause.BY_AUTH_CODE;
            }
        }

        if (employeeOpt.isEmpty() && effectiveOriginator != null && !effectiveOriginator.isEmpty()) {
            employeeOpt = lookupService.findEmployeeByExtension(effectiveOriginator, commLocation.getId(), limits);
            if (employeeOpt.isPresent()) {
                 assignmentCause = (employeeOpt.get().getId() == null) ? ImdexAssignmentCause.BY_EXTENSION_RANGE : ImdexAssignmentCause.BY_EXTENSION;
            }
        }
        
        // PHP: if (!$info_cdr['funcionario_funid']['ok'] && isset($info_cdr['ext-redir']) ... )
        // This means if the primary assignment (by auth code or originating extension) failed,
        // then try assigning based on the lastRedirectDn.
        if (employeeOpt.isEmpty() && rawData.getImdexTransferCause() != ImdexTransferCause.NO_TRANSFER &&
            rawData.getLastRedirectDn() != null && !rawData.getLastRedirectDn().isEmpty()) {
            
             Optional<Employee> redirectingEmployeeOpt = lookupService.findEmployeeByExtension(rawData.getLastRedirectDn(), commLocation.getId(), limits);
             if (redirectingEmployeeOpt.isPresent()) {
                 employeeOpt = redirectingEmployeeOpt;
                 assignmentCause = ImdexAssignmentCause.BY_TRANSFER;
             }
        }


        employeeOpt.ifPresent(emp -> {
            callRecord.setEmployee(emp);
            callRecord.setEmployeeId(emp.getId());
        });
        callRecord.setAssignmentCause(assignmentCause.getValue());
    }


    private void determineCallTypeAndPricing(CallRecord callRecord, RawCdrData rawData, CommunicationLocation commLocation, InternalExtensionLimitsDto limits) {
        String effectiveDialedNumber = rawData.getEffectiveDestinationNumber();
        
        effectiveDialedNumber = applyPbxSpecialRules(effectiveDialedNumber, commLocation, callRecord.isIncoming());
        callRecord.setDial(CdrHelper.cleanPhoneNumber(effectiveDialedNumber));

        setDefaultErrorTelephonyType(callRecord); 

        if (effectiveDialedNumber == null || effectiveDialedNumber.isEmpty()) {
            log.warn("Effective dialed number is empty for CDR hash: {}", callRecord.getCdrHash());
            return;
        }

        Long originCountryId = commLocation.getIndicator() != null ? commLocation.getIndicator().getOriginCountryId() : null;
        if (originCountryId == null) {
            log.error("Origin Country ID is null for commLocationId: {}. Cannot proceed with pricing.", commLocation.getId());
            return;
        }

        if (handleSpecialServices(callRecord, effectiveDialedNumber, commLocation, originCountryId)) {
            return;
        }

        InternalCallTypeResultDto internalCallResult = evaluateInternalCallType(
            rawData.getEffectiveOriginatingNumber(),
            effectiveDialedNumber,
            commLocation, limits, originCountryId, rawData.getDateTimeOrigination(),
            rawData.getEffectiveOriginatingPartition(), // Pass partitions for more context
            rawData.getEffectiveDestinationPartition()
        );

        if (internalCallResult != null) {
            if (internalCallResult.isIgnoreCall()) {
                log.info("Ignoring internal call as per logic (e.g., inter-plant global): {}", callRecord.getCdrHash());
                callRecord.setTelephonyTypeId(TelephonyTypeConstants.SIN_CONSUMO); 
                lookupService.findTelephonyTypeById(TelephonyTypeConstants.SIN_CONSUMO).ifPresent(callRecord::setTelephonyType);
                callRecord.setBilledAmount(BigDecimal.ZERO);
                callRecord.setPricePerMinute(BigDecimal.ZERO);
                callRecord.setInitialPrice(BigDecimal.ZERO);
                return;
            }
            setTelephonyTypeAndOperator(callRecord, internalCallResult.getTelephonyType().getId(), originCountryId, internalCallResult.getDestinationIndicator(), true);
            callRecord.setOperator(internalCallResult.getOperator()); 
            callRecord.setOperatorId(internalCallResult.getOperator() != null ? internalCallResult.getOperator().getId() : null);
            callRecord.setDestinationEmployee(internalCallResult.getDestinationEmployee());
            callRecord.setDestinationEmployeeId(internalCallResult.getDestinationEmployee() != null ? internalCallResult.getDestinationEmployee().getId() : null);
            
            if (internalCallResult.isIncomingInternal() && !callRecord.isIncoming()) {
                 callRecord.setIncoming(true);
                 // Swap trunks as per PHP's InvertirLlamada
                 String tempTrunk = callRecord.getTrunk();
                 callRecord.setTrunk(callRecord.getInitialTrunk());
                 callRecord.setInitialTrunk(tempTrunk);
            }

            Optional<Prefix> internalPrefixOpt = lookupService.findInternalPrefixForType(internalCallResult.getTelephonyType().getId(), originCountryId);
            if (internalPrefixOpt.isPresent()) {
                Prefix internalPrefix = internalPrefixOpt.get();
                BigDecimal priceExVat = internalPrefix.getBaseValue() != null ? internalPrefix.getBaseValue() : BigDecimal.ZERO;
                BigDecimal vatRate = internalPrefix.getVatValue() != null ? internalPrefix.getVatValue() : BigDecimal.ZERO;
                boolean vatIncluded = internalPrefix.isVatIncluded();

                if (vatIncluded && vatRate.compareTo(BigDecimal.ZERO) > 0) {
                    priceExVat = priceExVat.divide(BigDecimal.ONE.add(vatRate.divide(BigDecimal.valueOf(100), 10, RoundingMode.HALF_UP)), 4, RoundingMode.HALF_UP);
                }
                callRecord.setInitialPrice(priceExVat); // Base price ex-VAT

                BigDecimal priceWithVat = priceExVat;
                if (vatRate.compareTo(BigDecimal.ZERO) > 0) {
                    priceWithVat = priceExVat.multiply(BigDecimal.ONE.add(vatRate.divide(BigDecimal.valueOf(100), 10, RoundingMode.HALF_UP))).setScale(4, RoundingMode.HALF_UP);
                }
                callRecord.setPricePerMinute(priceWithVat); 
                
                int durationInSeconds = callRecord.getDuration() != null ? callRecord.getDuration() : 0;
                long billedUnits = CdrHelper.duracionMinuto(durationInSeconds, false);
                callRecord.setBilledAmount(priceWithVat.multiply(BigDecimal.valueOf(billedUnits)).setScale(2, RoundingMode.HALF_UP));

            } else {
                callRecord.setBilledAmount(BigDecimal.ZERO);
                callRecord.setPricePerMinute(BigDecimal.ZERO);
                callRecord.setInitialPrice(BigDecimal.ZERO);
            }
            log.debug("Call identified as Internal (Type: {})", internalCallResult.getTelephonyType().getName());
            return;
        }
        
        handleExternalCallPricing(callRecord, rawData, effectiveDialedNumber, commLocation, originCountryId);
    }
    
    private boolean handleSpecialServices(CallRecord callRecord, String dialedNumber, CommunicationLocation commLocation, Long originCountryId) {
        List<String> pbxPrefixes = cdrConfigService.getPbxOutputPrefixes(commLocation);
        // PHP: $telefono_orig = limpiar_numero($info['dial_number'], $_PREFIJO_SALIDA_PBX, true);
        // `true` for safeMode means if no PBX prefix matches, it uses the original number (after basic cleaning).
        // If PBX prefixes are empty, it just cleans.
        String numberForSpecialLookup = CdrHelper.cleanAndStripPhoneNumber(dialedNumber, pbxPrefixes, true);

        // PHP: if ($telefono_orig == '') { return false; }
        // This happens if pbxPrefixes exist, none match, AND safeMode was false.
        // With safeMode=true, numberForSpecialLookup will be non-empty if dialedNumber was non-empty.
        if (numberForSpecialLookup.isEmpty()) {
            return false;
        }

        Optional<SpecialService> specialServiceOpt = lookupService.findSpecialService(
            numberForSpecialLookup, 
            commLocation.getIndicatorId(),
            originCountryId
        );

        if (specialServiceOpt.isPresent()) {
            SpecialService ss = specialServiceOpt.get();
            setTelephonyTypeAndOperator(callRecord, TelephonyTypeConstants.NUMEROS_ESPECIALES, originCountryId, ss.getIndicator(), true);
            
            BigDecimal valueExVat = ss.getValue() != null ? ss.getValue() : BigDecimal.ZERO;
            BigDecimal vatRate = ss.getVatAmount() != null ? ss.getVatAmount() : BigDecimal.ZERO; // PHP uses SERVESPECIAL_IVA as a rate, not amount
            
            if (ss.getVatIncluded() != null && ss.getVatIncluded() && vatRate.compareTo(BigDecimal.ZERO) > 0) {
                 valueExVat = valueExVat.divide(BigDecimal.ONE.add(vatRate.divide(BigDecimal.valueOf(100), 10, RoundingMode.HALF_UP)), 4, RoundingMode.HALF_UP);
            }
            callRecord.setInitialPrice(valueExVat);

            BigDecimal priceWithVat = valueExVat;
            if (vatRate.compareTo(BigDecimal.ZERO) > 0) {
                priceWithVat = valueExVat.multiply(BigDecimal.ONE.add(vatRate.divide(BigDecimal.valueOf(100), 10, RoundingMode.HALF_UP))).setScale(4, RoundingMode.HALF_UP);
            }
            callRecord.setPricePerMinute(priceWithVat);
            
            // For special services, billed amount is often just the flat rate, duration might not apply.
            // PHP: $valor_facturado = Calcular_Valor($duracion, $infovalor); -> uses duracion_minuto.
            // If special services are flat-rate, duration should be ignored for billing.
            // Assuming duration applies for now, as per PHP's generic Calcular_Valor.
            int durationInSeconds = callRecord.getDuration() != null ? callRecord.getDuration() : 0;
            long billedUnits = CdrHelper.duracionMinuto(durationInSeconds, false); // Assuming minute billing for special services
            callRecord.setBilledAmount(priceWithVat.multiply(BigDecimal.valueOf(billedUnits)).setScale(2, RoundingMode.HALF_UP));

            log.debug("Call identified as Special Service: {}", ss.getDescription());
            return true;
        }
        return false;
    }

    private InternalCallTypeResultDto evaluateInternalCallType(String originExtension, String destinationExtension,
                                                              CommunicationLocation currentCommLocation,
                                                              InternalExtensionLimitsDto limits, Long currentOriginCountryId,
                                                              LocalDateTime callDateTime,
                                                              String originPartition, String destinationPartition) {
        // This is a significant simplification of PHP's tipo_llamada_interna.
        // A full port requires deep analysis of ObtenerFuncionario_Arreglo, FunIDValido, Validar_RangoExt,
        // Subdireccion_Listar, and complex inter-office/inter-country IP call logic.

        Optional<Employee> originEmpOpt = lookupService.findEmployeeByExtension(originExtension, currentCommLocation.getId(), limits); // PHP uses date for historical
        Optional<Employee> destEmpOpt = lookupService.findEmployeeByExtension(destinationExtension, currentCommLocation.getId(), limits);   // PHP uses date for historical

        boolean isOriginFound = originEmpOpt.isPresent();
        boolean isDestinationFound = destEmpOpt.isPresent();

        // PHP: if ($subdireccionDestino == '') -> No existe el destino
        // PHP: else -> Existe destino
        // This is a very basic check. PHP's logic is far more involved.
        if (isOriginFound && isDestinationFound) {
            Employee originEmp = originEmpOpt.get();
            Employee destEmp = destEmpOpt.get();

            Long originEmpCommLocationId = originEmp.getCommunicationLocationId();
            Long destEmpCommLocationId = destEmp.getCommunicationLocationId();

            // PHP: ValidarOrigenDestino logic
            boolean ignoreCall = false;
            if (cdrConfigService.isGlobalExtensionsEnabled(currentCommLocation.getPlantType().getId()) && // Assuming a config for ext_globales
                originEmpCommLocationId != null && destEmpCommLocationId != null &&
                (!Objects.equals(currentCommLocation.getId(), originEmpCommLocationId) || !Objects.equals(currentCommLocation.getId(), destEmpCommLocationId)))
            {
                if (!Objects.equals(currentCommLocation.getId(), originEmpCommLocationId) && 
                    Objects.equals(currentCommLocation.getId(), destEmpCommLocationId)) {
                    ignoreCall = true; // Origin in another plant, dest in current. PHP might ignore.
                } else if (!Objects.equals(currentCommLocation.getId(), originEmpCommLocationId) &&
                           !Objects.equals(currentCommLocation.getId(), destEmpCommLocationId)) {
                    ignoreCall = true; // Neither party in current plant.
                }
            }
            if (ignoreCall) {
                return InternalCallTypeResultDto.builder().ignoreCall(true).build();
            }

            Long telephonyTypeIdToUse;
            Indicator destIndicator = destEmp.getCommunicationLocation() != null ? destEmp.getCommunicationLocation().getIndicator() : null;
            Long destOriginCountryId = destIndicator != null ? destIndicator.getOriginCountryId() : null;
            
            boolean isIncomingInternal = false;

            if (!Objects.equals(currentOriginCountryId, destOriginCountryId)) {
                telephonyTypeIdToUse = TelephonyTypeConstants.INTERNA_INTERNACIONAL;
            } else if (originEmp.getCommunicationLocation() != null && destEmp.getCommunicationLocation() != null &&
                       originEmp.getCommunicationLocation().getIndicator() != null && destEmp.getCommunicationLocation().getIndicator() != null &&
                       !Objects.equals(originEmp.getCommunicationLocation().getIndicatorId(), destEmp.getCommunicationLocation().getIndicatorId())) {
                telephonyTypeIdToUse = TelephonyTypeConstants.INTERNA_NACIONAL;
            } else if (originEmp.getSubdivision() != null && destEmp.getSubdivision() != null &&
                       !Objects.equals(originEmp.getSubdivisionId(), destEmp.getSubdivisionId())) { // Simplified office check
                telephonyTypeIdToUse = TelephonyTypeConstants.INTERNA_LOCAL;
            } else {
                telephonyTypeIdToUse = TelephonyTypeConstants.INTERNA;
            }
            
            // PHP: if (!ExtensionEncontrada($info['funcionario_funid']) && ExtensionEncontrada($info['funcionario_fundes']) ... )
            // This implies if origin employee was not found (or not from current plant) but dest is, treat as incoming internal.
            if (originEmpCommLocationId == null || !Objects.equals(originEmpCommLocationId, currentCommLocation.getId())) {
                if (destEmpCommLocationId != null && Objects.equals(destEmpCommLocationId, currentCommLocation.getId())) {
                    isIncomingInternal = true;
                }
            }


            TelephonyType tt = lookupService.findTelephonyTypeById(telephonyTypeIdToUse).orElse(null);
            Operator op = lookupService.findInternalOperatorByTelephonyType(telephonyTypeIdToUse, currentOriginCountryId).orElse(null);
            
            return InternalCallTypeResultDto.builder()
                .telephonyType(tt)
                .operator(op)
                .destinationIndicator(destIndicator)
                .destinationEmployee(destEmp)
                .ignoreCall(false)
                .isIncomingInternal(isIncomingInternal)
                .build();
        }
        // PHP has fallback to default internal type if destination is not found but origin is, or via prefix matching.
        // This simplified version only handles direct employee-to-employee internal calls.
        return null;
    }


    private void handleExternalCallPricing(CallRecord callRecord, RawCdrData rawData, String dialedNumber, CommunicationLocation commLocation, Long originCountryId) {
        List<String> pbxPrefixes = cdrConfigService.getPbxOutputPrefixes(commLocation);
        boolean usesTrunk = callRecord.getTrunk() != null && !callRecord.getTrunk().isEmpty();
        
        // PHP: if ($existe_troncal === false) { $telefono = $info_destino_limpio; } else { $telefono = $info_destino; }
        String numberForPrefixLookup = usesTrunk ? 
                                       dialedNumber : // For trunk, PHP uses raw dialedNumber for buscarPrefijo
                                       CdrHelper.cleanAndStripPhoneNumber(dialedNumber, pbxPrefixes, true); // Non-trunk, clean with PBX prefix (safe mode)

        List<Prefix> prefixes = lookupService.findMatchingPrefixes(numberForPrefixLookup, originCountryId, usesTrunk);
        EvaluatedDestinationDto bestEval = null;

        for (Prefix prefix : prefixes) {
            // PHP: if ($existe_troncal !== false && $existe_troncal['noprefijopbx']) { $telefono = $info_destino_limpio; }
            // This means if it's a trunk call AND the trunk rule says "no pbx prefix", then the number used for
            // matching against the prefix code itself should be the one *already stripped* of PBX prefixes.
            // This is complex because `numberForPrefixLookup` is decided *before* knowing the trunk rule.
            // Let's assume `numberForPrefixLookup` is the one to match prefix.code against.
            // The actual number to process (for indicator lookup) might change based on trunk rules.
            String numberToProcessForIndicator = numberForPrefixLookup;
            if (usesTrunk) {
                 Optional<Trunk> trunkOpt = lookupService.findTrunkByNameAndCommLocation(callRecord.getTrunk(), commLocation.getId());
                 if (trunkOpt.isPresent() && trunkOpt.get().getNoPbxPrefix() != null && trunkOpt.get().getNoPbxPrefix()) {
                     numberToProcessForIndicator = CdrHelper.cleanAndStripPhoneNumber(dialedNumber, pbxPrefixes, true);
                 } else {
                     numberToProcessForIndicator = dialedNumber; // Use raw if trunk doesn't say no pbx prefix
                 }
            }


            EvaluatedDestinationDto currentEval = evaluateDestinationWithPrefix(
                numberToProcessForIndicator, // Number to strip prefix.code from and use for indicator lookup
                dialedNumber,                // Original dialed number for context
                prefix, commLocation, originCountryId, usesTrunk, pbxPrefixes, callRecord.getServiceDate()
            );
            if (currentEval != null && (bestEval == null || isBetterEvaluation(currentEval, bestEval))) {
                bestEval = currentEval;
                if (bestEval.getTelephonyType() != null &&
                    !Objects.equals(bestEval.getTelephonyType().getId(), TelephonyTypeConstants.ERRORES) &&
                    !bestEval.isAssumed()) {
                    break; 
                }
            }
        }

        // Normalization logic (PHP: if ($existe_troncal !== false && evaluarDestino_novalido($infovalor)))
        if (usesTrunk && (bestEval == null || bestEval.getTelephonyType() == null || Objects.equals(bestEval.getTelephonyType().getId(), TelephonyTypeConstants.ERRORES) || bestEval.isAssumed())) {
            log.debug("Trunk call pricing was not definitive for {}. Attempting non-trunk 'normalization'.", dialedNumber);
            String normalizedNumberLookup = CdrHelper.cleanAndStripPhoneNumber(dialedNumber, pbxPrefixes, true); // Non-trunk context
            List<Prefix> normalizedPrefixes = lookupService.findMatchingPrefixes(normalizedNumberLookup, originCountryId, false); // false for non-trunk

            if (!normalizedPrefixes.isEmpty()) {
                EvaluatedDestinationDto normalizedBestEval = null;
                for (Prefix normPrefix : normalizedPrefixes) {
                     EvaluatedDestinationDto currentNormEval = evaluateDestinationWithPrefix(
                        normalizedNumberLookup, // Number for prefix matching and indicator lookup
                        dialedNumber,           // Original for context
                        normPrefix, commLocation, originCountryId, false, pbxPrefixes, callRecord.getServiceDate() // false for non-trunk
                    );
                    if (currentNormEval != null && (normalizedBestEval == null || isBetterEvaluation(currentNormEval, normalizedBestEval))) {
                        normalizedBestEval = currentNormEval;
                         if (normalizedBestEval.getTelephonyType() != null &&
                            !Objects.equals(normalizedBestEval.getTelephonyType().getId(), TelephonyTypeConstants.ERRORES) &&
                            !normalizedBestEval.isAssumed()) {
                            break; 
                        }
                    }
                }
                if (normalizedBestEval != null && (bestEval == null || isBetterEvaluation(normalizedBestEval, bestEval))) {
                    log.info("Normalized pricing provided a better result for {}. Original trunk eval: {}", dialedNumber, bestEval);
                    bestEval = normalizedBestEval;
                    bestEval.setFromTrunk(true); 
                    bestEval.setDestinationDescription(bestEval.getDestinationDescription() + " (Normalized)");
                }
            }
        }


        if (bestEval == null) {
            log.warn("No viable prefix/indicator combination found for {}. Falling back to error.", dialedNumber);
            setDefaultErrorTelephonyType(callRecord);
            return;
        }

        applyEvaluationToCallRecord(callRecord, bestEval, commLocation, originCountryId);
    }
    
    private boolean isBetterEvaluation(EvaluatedDestinationDto newEval, EvaluatedDestinationDto oldEval) {
        if (oldEval == null) return true;
        if (newEval.getTelephonyType() == null) return false;
        if (oldEval.getTelephonyType() == null) return true;

        boolean newIsError = Objects.equals(newEval.getTelephonyType().getId(), TelephonyTypeConstants.ERRORES);
        boolean oldIsError = Objects.equals(oldEval.getTelephonyType().getId(), TelephonyTypeConstants.ERRORES);

        if (oldIsError && !newIsError) return true;
        if (!oldIsError && newIsError) return false;
        if (oldIsError && newIsError) return false; // If both error, no improvement

        if (oldEval.isAssumed() && !newEval.isAssumed()) return true;
        if (!oldEval.isAssumed() && newEval.isAssumed()) return false;
        
        // PHP's logic for preferring more specific matches (e.g. longer prefix, specific band) is complex.
        // For now, this covers error and assumed states.
        return false;
    }


    private EvaluatedDestinationDto evaluateDestinationWithPrefix(
        String numberToProcess, String originalDialedNumberForContext, Prefix prefix,
        CommunicationLocation commLocation, Long originCountryId, boolean isTrunkCallContext,
        List<String> pbxPrefixes, LocalDateTime callDateTime) {

        BigDecimal currentCallValueExVat;
        boolean currentVatIncluded = prefix.isVatIncluded();
        BigDecimal currentVatRate = prefix.getVatValue() != null ? prefix.getVatValue() : BigDecimal.ZERO;
        
        currentCallValueExVat = prefix.getBaseValue() != null ? prefix.getBaseValue() : BigDecimal.ZERO;
        if (currentVatIncluded && currentVatRate.compareTo(BigDecimal.ZERO) > 0) {
            currentCallValueExVat = currentCallValueExVat.divide(BigDecimal.ONE.add(currentVatRate.divide(BigDecimal.valueOf(100), 10, RoundingMode.HALF_UP)), 4, RoundingMode.HALF_UP);
        }

        Long currentTelephonyTypeId = prefix.getTelephoneTypeId();
        TelephonyType currentTelephonyType = prefix.getTelephonyType();
        Operator currentOperator = prefix.getOperator();
        Long currentOperatorId = prefix.getOperatorId();
        String currentDestinationDescription = currentTelephonyType != null ? currentTelephonyType.getName() : "Unknown";
        Indicator currentIndicator = null;
        Long bandIdForSpecialRate = null;
        String bandName = null;
        boolean currentBilledInSeconds = false; // PHP: $infovalor['ensegundos']
        Integer currentBillingUnit = 60; // Default to minute billing

        String numberAfterPrefix = numberToProcess;
        if (prefix.getCode() != null && !prefix.getCode().isEmpty() && numberToProcess.startsWith(prefix.getCode())) {
            // PHP: if ($prefijotrim != '' && substr($telefono, 0, $len_prefijo ) == $prefijotrim && !$reducir)
            // !$reducir means if it's not a trunk call that says "no prefix"
            // This logic is tricky. If it's a trunk call and trunk rule says "no prefix", then PHP's $reducir is true.
            // If $reducir is true, PHP's buscarDestino does *not* strip the prefix.code here.
            // It subtracts len_prefijo from tipotele_min.
            // For now, always strip if it matches. The `numberToProcess` should be the one that needs stripping.
            numberAfterPrefix = numberToProcess.substring(prefix.getCode().length());
        }
        
        String numberForIndicatorLookup = numberAfterPrefix;
        Long typeForIndicatorLookup = currentTelephonyTypeId;
        int minLengthForType = currentTelephonyType != null ? cdrConfigService.getMinLengthForTelephonyType(currentTelephonyType.getId(), originCountryId) : 0;
        
        // PHP: if (_esLocal($tipotele_id))
        if (Objects.equals(currentTelephonyTypeId, TelephonyTypeConstants.LOCAL)) {
            typeForIndicatorLookup = TelephonyTypeConstants.NACIONAL;
            String localAreaCode = lookupService.findLocalAreaCodeForIndicator(commLocation.getIndicatorId());
            numberForIndicatorLookup = localAreaCode + numberAfterPrefix;
            minLengthForType = cdrConfigService.getMinLengthForTelephonyType(TelephonyTypeConstants.NACIONAL, originCountryId);
        }
        
        // PHP: if (strlen($telefono) > $tipotelemax) $telefono = substr($telefono, 0, $tipotelemax);
        // This needs max length for type.
        int maxLengthForType = currentTelephonyType != null ? cdrConfigService.getMaxLengthForTelephonyType(currentTelephonyType.getId(), originCountryId) : Integer.MAX_VALUE;
        if (numberForIndicatorLookup.length() > maxLengthForType) {
            numberForIndicatorLookup = numberForIndicatorLookup.substring(0, maxLengthForType);
        }


        List<SeriesMatchDto> seriesMatches = Collections.emptyList();
        boolean assumed = false;

        if (numberForIndicatorLookup.length() >= minLengthForType) {
             seriesMatches = lookupService.findIndicatorsByNumberAndType(
                numberForIndicatorLookup, typeForIndicatorLookup, originCountryId, commLocation, prefix.getId(), prefix.isBandOk()
            );
        }
        
        if (!seriesMatches.isEmpty()) {
            SeriesMatchDto bestMatch = seriesMatches.get(0);
            currentIndicator = bestMatch.getIndicator();
            currentDestinationDescription = bestMatch.getDestinationDescription();
            assumed = bestMatch.isApproximate();

            if (Objects.equals(currentTelephonyTypeId, TelephonyTypeConstants.LOCAL) &&
                commLocation.getIndicator() != null && currentIndicator != null &&
                !Objects.equals(commLocation.getIndicatorId(), currentIndicator.getId()) &&
                lookupService.isLocalExtended(commLocation.getIndicator(), currentIndicator)) {
                
                currentTelephonyTypeId = TelephonyTypeConstants.LOCAL_EXTENDIDA;
                currentTelephonyType = lookupService.findTelephonyTypeById(TelephonyTypeConstants.LOCAL_EXTENDIDA).orElse(currentTelephonyType);
                currentDestinationDescription = (currentTelephonyType != null ? currentTelephonyType.getName() : "Local Ext.") + " (" + bestMatch.getDestinationDescription() + ")";
            }
        } else if (Objects.equals(currentTelephonyTypeId, TelephonyTypeConstants.LOCAL)) {
            currentIndicator = commLocation.getIndicator();
            if (currentIndicator != null) {
                 currentDestinationDescription = buildDestinationDescription(currentIndicator, commLocation.getIndicator()) + " (Assumed)";
            } else {
                currentDestinationDescription = "Local (Assumed, No Comm Indicator)";
            }
            assumed = true;
        } else {
            log.debug("No indicator for prefix {}, number part {}, type {}. This path is not viable.", prefix.getCode(), numberAfterPrefix, currentTelephonyTypeId);
            // PHP logic: if ($arr_destino === false) then it tries to set default operator/TT if all prefixes shared same.
            // If not, it sets TT to 0 (error).
            // If no series match, this is an error path unless it's a local call (handled above).
             return EvaluatedDestinationDto.builder()
                .processedNumber(numberAfterPrefix)
                .telephonyType(lookupService.findTelephonyTypeById(TelephonyTypeConstants.ERRORES).orElse(null))
                .destinationDescription("No Indicator Match")
                .pricePerMinute(BigDecimal.ZERO).vatIncludedInPrice(false).vatRate(BigDecimal.ZERO)
                .initialPriceBeforeSpecialRates(BigDecimal.ZERO).initialPriceVatIncluded(false)
                .assumed(true) // Assumed error
                .build();
        }

        BigDecimal initialPriceExVat = currentCallValueExVat; // Base price from prefix (already ex-VAT)

        if (prefix.isBandOk() && currentIndicator != null) {
            List<Band> bands = lookupService.findBandsForPrefixAndIndicator(prefix.getId(), commLocation.getIndicatorId());
            if (!bands.isEmpty()) {
                Band matchedBand = bands.get(0);
                currentCallValueExVat = matchedBand.getValue() != null ? matchedBand.getValue() : BigDecimal.ZERO;
                currentVatIncluded = matchedBand.getVatIncluded(); // VAT status of the band's value
                bandIdForSpecialRate = matchedBand.getId();
                bandName = matchedBand.getName();
                
                if (currentVatIncluded && currentVatRate.compareTo(BigDecimal.ZERO) > 0) { // VatRate is still from prefix
                    currentCallValueExVat = currentCallValueExVat.divide(BigDecimal.ONE.add(currentVatRate.divide(BigDecimal.valueOf(100), 10, RoundingMode.HALF_UP)), 4, RoundingMode.HALF_UP);
                }
                initialPriceExVat = currentCallValueExVat; // Band price becomes the new initial price (ex-VAT)
            }
        }
        
        SpecialRateApplicationResultDto srResult = applySpecialRates(
            currentCallValueExVat, // Pass ex-VAT price
            callDateTime, currentTelephonyTypeId, currentOperatorId, bandIdForSpecialRate, commLocation.getIndicatorId()
        );
        if (srResult.isRateWasApplied()) {
            currentCallValueExVat = srResult.getNewPricePerMinute(); // SR returns ex-VAT price
            currentVatRate = srResult.getNewVatRate(); 
            // currentVatIncluded status is about the SRV.rate_value itself, not the final price.
            // The price returned (newPricePerMinute) is already ex-VAT.
        }

        return EvaluatedDestinationDto.builder()
            .processedNumber(numberAfterPrefix)
            .telephonyType(currentTelephonyType)
            .operator(currentOperator)
            .indicator(currentIndicator)
            .destinationDescription(currentDestinationDescription)
            .pricePerMinute(currentCallValueExVat) // This is ex-VAT
            .vatIncludedInPrice(false) // PricePerMinute is now consistently ex-VAT
            .vatRate(currentVatRate)
            .initialPriceBeforeSpecialRates(initialPriceExVat) // Stored ex-VAT
            .initialPriceVatIncluded(prefix.isVatIncluded()) // Original VAT status of the price from prefix/band
            .billedInSeconds(currentBilledInSeconds)
            .billingUnitInSeconds(currentBillingUnit)
            .fromTrunk(isTrunkCallContext)
            .bandUsed(bandIdForSpecialRate != null)
            .bandId(bandIdForSpecialRate)
            .bandName(bandName)
            .assumed(assumed)
            .build();
    }
    
    private SpecialRateApplicationResultDto applySpecialRates(
        BigDecimal priceBeforeSpecialRateExVat,
        LocalDateTime callTime, Long telephonyTypeId, Long operatorId, Long bandId, Long originIndicatorId) {

        List<SpecialRateValue> specialRates = lookupService.findSpecialRateValues(
            callTime, telephonyTypeId, operatorId, bandId, originIndicatorId
        );

        if (!specialRates.isEmpty()) {
            for (SpecialRateValue sr : specialRates) {
                if (isTimeInHoursSpecification(callTime, sr.getHoursSpecification())) {
                    BigDecimal newPriceExVat;
                    boolean srRateValueIncludesVat = sr.getIncludesVat();
                    BigDecimal srEffectiveVatRate = lookupService.findVatForTelephonyOperator(
                                                        sr.getTelephonyTypeId() != null ? sr.getTelephonyTypeId() : telephonyTypeId,
                                                        sr.getOperatorId() != null ? sr.getOperatorId() : operatorId,
                                                        originIndicatorId)
                                                        .orElse(BigDecimal.ZERO);

                    if (sr.getValueType() != null && sr.getValueType() == 1) { // Percentage discount
                        BigDecimal discountPercentage = sr.getRateValue().divide(BigDecimal.valueOf(100), 10, RoundingMode.HALF_UP);
                        newPriceExVat = priceBeforeSpecialRateExVat.multiply(BigDecimal.ONE.subtract(discountPercentage));
                    } else { // Absolute value
                        newPriceExVat = sr.getRateValue();
                        if (srRateValueIncludesVat && srEffectiveVatRate.compareTo(BigDecimal.ZERO) > 0) {
                            newPriceExVat = newPriceExVat.divide(
                                BigDecimal.ONE.add(srEffectiveVatRate.divide(BigDecimal.valueOf(100), 10, RoundingMode.HALF_UP)),
                                10, RoundingMode.HALF_UP);
                        }
                    }
                    return SpecialRateApplicationResultDto.builder()
                        .newPricePerMinute(newPriceExVat) // This is now ex-VAT
                        .newVatIncluded(false) // Standardize to return ex-VAT
                        .newVatRate(srEffectiveVatRate)
                        .appliedRule(sr)
                        .rateWasApplied(true)
                        .build();
                }
            }
        }
        // If no special rate applied, return the original price (which is already ex-VAT) and its original VAT rate context
        BigDecimal originalVatRate = lookupService.findVatForTelephonyOperator(telephonyTypeId, operatorId, originIndicatorId).orElse(BigDecimal.ZERO);
        return SpecialRateApplicationResultDto.builder().rateWasApplied(false)
            .newPricePerMinute(priceBeforeSpecialRateExVat).newVatIncluded(false).newVatRate(originalVatRate).build();
    }

    private boolean isTimeInHoursSpecification(LocalDateTime callTime, String hoursSpecification) {
        if (hoursSpecification == null || hoursSpecification.trim().isEmpty()) {
            return true;
        }
        int callHour = callTime.getHour();
        String[] parts = hoursSpecification.split(",");
        for (String part : parts) {
            part = part.trim();
            if (part.contains("-")) {
                String[] range = part.split("-");
                if (range.length == 2) {
                    try {
                        int startHour = Integer.parseInt(range[0].trim());
                        int endHour = Integer.parseInt(range[1].trim());
                        if (callHour >= startHour && callHour <= endHour) {
                            return true;
                        }
                    } catch (NumberFormatException e) {
                        log.warn("Invalid hour range in specification: {}", part);
                    }
                }
            } else {
                try {
                    if (callHour == Integer.parseInt(part)) {
                        return true;
                    }
                } catch (NumberFormatException e) {
                    log.warn("Invalid hour in specification: {}", part);
                }
            }
        }
        return false;
    }

    private TrunkRuleApplicationResultDto applyTrunkRules(CallRecord callRecord, EvaluatedDestinationDto currentEval, CommunicationLocation commLocation, Long originCountryId) {
         if (!currentEval.isFromTrunk() || callRecord.getTrunk() == null || callRecord.getTrunk().isEmpty() || currentEval.getIndicator() == null || currentEval.getTelephonyType() == null) {
            return TrunkRuleApplicationResultDto.builder().ruleWasApplied(false)
                .newPricePerMinute(currentEval.getPricePerMinute())
                .newVatIncluded(false)
                .newVatRate(currentEval.getVatRate())
                .newBillingUnitInSeconds(currentEval.getBillingUnitInSeconds())
                .build();
        }

        Optional<Trunk> trunkOpt = lookupService.findTrunkByNameAndCommLocation(callRecord.getTrunk(), commLocation.getId());
        if (trunkOpt.isEmpty()) {
             return TrunkRuleApplicationResultDto.builder().ruleWasApplied(false)
                .newPricePerMinute(currentEval.getPricePerMinute()).newVatIncluded(false).newVatRate(currentEval.getVatRate())
                .newBillingUnitInSeconds(currentEval.getBillingUnitInSeconds()).build();
        }

        List<TrunkRule> trunkRules = lookupService.findTrunkRules(
            trunkOpt.get().getId(),
            currentEval.getTelephonyType().getId(),
            String.valueOf(currentEval.getIndicator().getId()),
            commLocation.getIndicatorId()
        );

        if (!trunkRules.isEmpty()) {
            TrunkRule rule = trunkRules.get(0);
            log.debug("Applying trunk rule ID: {}", rule.getId());

            BigDecimal ruleRateValue = rule.getRateValue();
            boolean ruleRateIsVatIncluded = rule.getIncludesVat() != null && rule.getIncludesVat();
            
            TelephonyType ruleTelephonyType = rule.getNewTelephonyType() != null ? rule.getNewTelephonyType() : currentEval.getTelephonyType();
            Operator ruleOperator = rule.getNewOperator() != null ? rule.getNewOperator() : currentEval.getOperator();
            Indicator ruleOriginIndicator = rule.getOriginIndicator() != null ? rule.getOriginIndicator() : commLocation.getIndicator(); // Rule can change origin context
            
            BigDecimal ruleEffectiveVatRate = lookupService.findVatForTelephonyOperator(
                                                ruleTelephonyType.getId(), 
                                                ruleOperator != null ? ruleOperator.getId() : null, // Operator can be null
                                                ruleOriginIndicator != null ? ruleOriginIndicator.getOriginCountryId() : originCountryId)
                                                .orElse(BigDecimal.ZERO);
            
            BigDecimal priceAfterRuleExVat = ruleRateValue;
            if (ruleRateIsVatIncluded && ruleEffectiveVatRate.compareTo(BigDecimal.ZERO) > 0) {
                priceAfterRuleExVat = ruleRateValue.divide(
                    BigDecimal.ONE.add(ruleEffectiveVatRate.divide(BigDecimal.valueOf(100), 10, RoundingMode.HALF_UP)),
                    10, RoundingMode.HALF_UP);
            }

            Integer billingUnit = rule.getSeconds() != null && rule.getSeconds() > 0 ? rule.getSeconds() : currentEval.getBillingUnitInSeconds();

            return TrunkRuleApplicationResultDto.builder()
                .newPricePerMinute(priceAfterRuleExVat) // ex-VAT
                .newVatIncluded(false) 
                .newVatRate(ruleEffectiveVatRate)
                .newBillingUnitInSeconds(billingUnit)
                .newTelephonyType(ruleTelephonyType)
                .newOperator(ruleOperator)
                .newOriginIndicator(ruleOriginIndicator)
                .appliedRule(rule)
                .ruleWasApplied(true)
                .build();
        }
        return TrunkRuleApplicationResultDto.builder().ruleWasApplied(false)
            .newPricePerMinute(currentEval.getPricePerMinute()).newVatIncluded(false).newVatRate(currentEval.getVatRate())
            .newBillingUnitInSeconds(currentEval.getBillingUnitInSeconds()).build();
    }

    private void applyEvaluationToCallRecord(CallRecord callRecord, EvaluatedDestinationDto evalDto, CommunicationLocation commLocation, Long originCountryId) {
        callRecord.setInitialPrice(evalDto.getInitialPriceBeforeSpecialRates().setScale(4, RoundingMode.HALF_UP)); // Store ex-VAT initial price

        TrunkRuleApplicationResultDto trunkResult = applyTrunkRules(callRecord, evalDto, commLocation, originCountryId);
        
        BigDecimal finalPricePerUnitExVat;
        BigDecimal finalVatRate;
        Integer finalBillingUnit;
        TelephonyType finalTelephonyType = evalDto.getTelephonyType();
        Operator finalOperator = evalDto.getOperator();
        Indicator finalIndicator = evalDto.getIndicator();

        if (trunkResult.isRuleWasApplied()) {
            finalPricePerUnitExVat = trunkResult.getNewPricePerMinute();
            finalVatRate = trunkResult.getNewVatRate();
            finalBillingUnit = trunkResult.getNewBillingUnitInSeconds();
            if (trunkResult.getNewTelephonyType() != null) finalTelephonyType = trunkResult.getNewTelephonyType();
            if (trunkResult.getNewOperator() != null) finalOperator = trunkResult.getNewOperator();
            // If trunk rule changes origin indicator, it might affect other things, but for now, just use it for final pricing context
            // The `finalIndicator` remains the destination indicator.
        } else {
            finalPricePerUnitExVat = evalDto.getPricePerMinute();
            finalVatRate = evalDto.getVatRate();
            finalBillingUnit = evalDto.getBillingUnitInSeconds();
        }

        callRecord.setTelephonyType(finalTelephonyType);
        callRecord.setTelephonyTypeId(finalTelephonyType != null ? finalTelephonyType.getId() : TelephonyTypeConstants.ERRORES);
        callRecord.setOperator(finalOperator);
        callRecord.setOperatorId(finalOperator != null ? finalOperator.getId() : null);
        callRecord.setIndicator(finalIndicator);
        callRecord.setIndicatorId(finalIndicator != null ? finalIndicator.getId() : null);

        BigDecimal finalPricePerUnitWithVat = finalPricePerUnitExVat;
        if (finalVatRate.compareTo(BigDecimal.ZERO) > 0) {
            finalPricePerUnitWithVat = finalPricePerUnitExVat.multiply(
                BigDecimal.ONE.add(finalVatRate.divide(BigDecimal.valueOf(100), 10, RoundingMode.HALF_UP))
            );
        }
        callRecord.setPricePerMinute(finalPricePerUnitWithVat.setScale(4, RoundingMode.HALF_UP));

        int durationInSeconds = callRecord.getDuration() != null ? callRecord.getDuration() : 0;
        long billedUnits = (long) Math.ceil((double) durationInSeconds / (double) finalBillingUnit);
        if (billedUnits == 0 && durationInSeconds > 0) billedUnits = 1;
        
        callRecord.setBilledAmount(finalPricePerUnitWithVat.multiply(BigDecimal.valueOf(billedUnits)).setScale(2, RoundingMode.HALF_UP));
    }


    private void setDefaultErrorTelephonyType(CallRecord callRecord) {
        callRecord.setTelephonyTypeId(TelephonyTypeConstants.ERRORES);
        lookupService.findTelephonyTypeById(TelephonyTypeConstants.ERRORES).ifPresent(callRecord::setTelephonyType);
        callRecord.setBilledAmount(BigDecimal.ZERO);
        callRecord.setPricePerMinute(BigDecimal.ZERO);
        callRecord.setInitialPrice(BigDecimal.ZERO);
    }

    private void setTelephonyTypeAndOperator(CallRecord callRecord, Long telephonyTypeId, Long originCountryId, Indicator indicator, boolean forceOperatorUpdate) {
        lookupService.findTelephonyTypeById(telephonyTypeId).ifPresent(tt -> {
            callRecord.setTelephonyType(tt);
            callRecord.setTelephonyTypeId(tt.getId());
        });
        if (callRecord.getOperatorId() == null || forceOperatorUpdate) { 
            lookupService.findInternalOperatorByTelephonyType(telephonyTypeId, originCountryId)
                .ifPresent(op -> {
                    callRecord.setOperator(op);
                    callRecord.setOperatorId(op.getId());
                });
        }
        if (indicator != null) {
            callRecord.setIndicator(indicator);
            callRecord.setIndicatorId(indicator.getId());
        }
    }
    
    private String applyPbxSpecialRules(String dialedNumber, CommunicationLocation commLocation, boolean isIncoming) {
        if (dialedNumber == null) return null;
        List<PbxSpecialRule> rules = lookupService.findPbxSpecialRules(commLocation.getId());
        String currentNumber = dialedNumber;

        for (PbxSpecialRule rule : rules) {
            PbxSpecialRuleDirection ruleDirection = PbxSpecialRuleDirection.fromValue(rule.getDirection());
            boolean directionMatch = (ruleDirection == PbxSpecialRuleDirection.BOTH) ||
                                     (isIncoming && ruleDirection == PbxSpecialRuleDirection.INCOMING) ||
                                     (!isIncoming && ruleDirection == PbxSpecialRuleDirection.OUTGOING);

            if (!directionMatch) continue;
            
            if (currentNumber.length() < rule.getMinLength()) continue;

            // PHP: PBXESPECIAL_BUSCAR is a prefix match
            if (currentNumber.startsWith(rule.getSearchPattern())) {
                boolean ignore = false;
                if (rule.getIgnorePattern() != null && !rule.getIgnorePattern().isEmpty()) {
                    String[] ignorePatternsText = rule.getIgnorePattern().split(",");
                    for (String ignorePatStr : ignorePatternsText) {
                        if (ignorePatStr.trim().isEmpty()) continue;
                        // PHP: PBXESPECIAL_IGNORAR is a "contains" match on the original dialedNumber
                        if (dialedNumber.contains(ignorePatStr.trim())) { 
                            ignore = true;
                            break;
                        }
                    }
                }
                if (ignore) continue;
                
                String replacement = rule.getReplacement() != null ? rule.getReplacement() : "";
                currentNumber = replacement + currentNumber.substring(rule.getSearchPattern().length());
                log.debug("Applied PBX rule ID {}: {} -> {}", rule.getId(), dialedNumber, currentNumber);
                return currentNumber; 
            }
        }
        return currentNumber;
    }

    private String buildDestinationDescription(Indicator matchedIndicator, Indicator originCommIndicator) {
        if (matchedIndicator == null) return "Unknown";
        String city = matchedIndicator.getCityName();
        String deptCountry = matchedIndicator.getDepartmentCountry();
        if (city != null && !city.isEmpty()) {
            if (deptCountry != null && !deptCountry.isEmpty() && !city.equalsIgnoreCase(deptCountry)) {
                 // Check if it's local extended only if origin and destination indicators are different
                if (originCommIndicator != null && !Objects.equals(originCommIndicator.getId(), matchedIndicator.getId()) &&
                    lookupService.isLocalExtended(originCommIndicator, matchedIndicator)) {
                    return city + " (" + deptCountry + " - Local Ext.)";
                }
                return city + " (" + deptCountry + ")";
            }
            return city;
        }
        return deptCountry != null ? deptCountry : "N/A";
    }
}