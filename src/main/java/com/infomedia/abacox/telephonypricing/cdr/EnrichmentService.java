// FILE: com/infomedia/abacox/telephonypricing/cdr/EnrichmentService.java
package com.infomedia.abacox.telephonypricing.cdr;

import com.infomedia.abacox.telephonypricing.entity.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
@RequiredArgsConstructor
@Log4j2
public class EnrichmentService {

    private final LookupService lookupService;
    private final CdrConfigService cdrConfigService;

    public void enrichCallRecord(CallRecord callRecord, RawCiscoCdrData rawData, CommunicationLocation commLocation) {
        InternalExtensionLimitsDto internalLimits = cdrConfigService.getInternalExtensionLimits(
            commLocation.getIndicator() != null ? commLocation.getIndicator().getOriginCountryId() : null,
            commLocation.getId()
        );

        assignEmployee(callRecord, rawData, commLocation, internalLimits);
        determineCallTypeAndPricing(callRecord, rawData, commLocation, internalLimits);
    }

    private void assignEmployee(CallRecord callRecord, RawCiscoCdrData rawData, CommunicationLocation commLocation, InternalExtensionLimitsDto limits) {
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
        
        // PHP logic for assigning based on transfer (funcionario_redir)
        // If primary assignment failed and it's a transfer, check lastRedirectDn
        if (employeeOpt.isEmpty() && rawData.getImdexTransferCause() != ImdexTransferCause.NO_TRANSFER &&
            rawData.getLastRedirectDn() != null && !rawData.getLastRedirectDn().isEmpty()) {
            
            // In PHP, if ext-redir is an internal extension, it might be considered the responsible party.
            // This is complex as "responsibility" depends on call flow.
            // For now, if the call was transferred *by* an internal party (lastRedirectDn),
            // and no primary originator was found, we might assign this transferring party.
            // This part of PHP logic (ObtenerFuncionario_Arreglo with tipo_fun = 'redir') is subtle.
            // Let's assume if the original "callingPartyNumber" didn't yield an employee,
            // and this call involved a redirect by an internal party, that internal party is logged.
            // However, callRecord.employeeId should be the true originator if possible.
            // This might be better handled by a separate "transferring_employee_id" field if needed.
            // For now, if no primary employee, and lastRedirectDn is an employee, consider it.
             Optional<Employee> redirectingEmployeeOpt = lookupService.findEmployeeByExtension(rawData.getLastRedirectDn(), commLocation.getId(), limits);
             if (redirectingEmployeeOpt.isPresent()) {
                 employeeOpt = redirectingEmployeeOpt;
                 assignmentCause = ImdexAssignmentCause.BY_TRANSFER; // Or a more specific "BY_REDIRECTING_EXTENSION"
             }
        }


        employeeOpt.ifPresent(emp -> {
            callRecord.setEmployee(emp);
            callRecord.setEmployeeId(emp.getId());
            // PHP's ActualizarFuncionarios logic: if emp.id is null (virtual from range), it might create one.
            // We are not creating employees here.
        });
        callRecord.setAssignmentCause(assignmentCause.getValue());
    }


    private void determineCallTypeAndPricing(CallRecord callRecord, RawCiscoCdrData rawData, CommunicationLocation commLocation, InternalExtensionLimitsDto limits) {
        String effectiveDialedNumber = rawData.getEffectiveDestinationNumber();
        
        effectiveDialedNumber = applyPbxSpecialRules(effectiveDialedNumber, commLocation, callRecord.isIncoming());
        callRecord.setDial(CdrHelper.cleanPhoneNumber(effectiveDialedNumber));

        setDefaultErrorTelephonyType(callRecord); // Default until a valid type is found

        if (effectiveDialedNumber == null || effectiveDialedNumber.isEmpty()) {
            log.warn("Effective dialed number is empty for CDR hash: {}", callRecord.getCdrHash());
            return;
        }

        Long originCountryId = commLocation.getIndicator() != null ? commLocation.getIndicator().getOriginCountryId() : null;
        if (originCountryId == null) {
            log.error("Origin Country ID is null for commLocationId: {}. Cannot proceed with pricing.", commLocation.getId());
            return;
        }

        // 1. Special Services (PHP: procesaServespecial)
        if (handleSpecialServices(callRecord, effectiveDialedNumber, commLocation, originCountryId)) {
            return;
        }

        // 2. Internal Calls (PHP: procesaInterna -> tipo_llamada_interna)
        InternalCallTypeResultDto internalCallResult = evaluateInternalCallType(
            rawData.getEffectiveOriginatingNumber(), // Use effective numbers
            effectiveDialedNumber,
            commLocation, limits, originCountryId
        );

        if (internalCallResult != null) {
            if (internalCallResult.isIgnoreCall()) {
                log.info("Ignoring internal call as per logic (e.g., inter-plant global): {}", callRecord.getCdrHash());
                // How to mark as ignored? For now, might keep error type or a specific "ignored" type.
                // PHP might just not insert it. Here we might need a status on CallRecord.
                callRecord.setTelephonyTypeId(TelephonyTypeConstants.SIN_CONSUMO); // Or a specific "IGNORED_INTERNAL"
                lookupService.findTelephonyTypeById(TelephonyTypeConstants.SIN_CONSUMO).ifPresent(callRecord::setTelephonyType);
                return;
            }
            setTelephonyTypeAndOperator(callRecord, internalCallResult.getTelephonyType().getId(), originCountryId, internalCallResult.getDestinationIndicator());
            callRecord.setOperator(internalCallResult.getOperator()); // Set the specific internal operator
            callRecord.setOperatorId(internalCallResult.getOperator() != null ? internalCallResult.getOperator().getId() : null);
            callRecord.setDestinationEmployee(internalCallResult.getDestinationEmployee());
            callRecord.setDestinationEmployeeId(internalCallResult.getDestinationEmployee() != null ? internalCallResult.getDestinationEmployee().getId() : null);
            
            // If it's an internal call that should be treated as incoming to this commLocation
            if (internalCallResult.isIncomingInternal() && !callRecord.isIncoming()) {
                 callRecord.setIncoming(true);
                 // PHP's InvertirLlamada also swaps trunks.
                 String tempTrunk = callRecord.getTrunk();
                 callRecord.setTrunk(callRecord.getInitialTrunk());
                 callRecord.setInitialTrunk(tempTrunk);
            }

            // Pricing for internal calls (usually zero, but PHP checks PREFIJO table)
            Optional<Prefix> internalPrefixOpt = lookupService.findInternalPrefixForType(internalCallResult.getTelephonyType().getId(), originCountryId);
            if (internalPrefixOpt.isPresent()) {
                Prefix internalPrefix = internalPrefixOpt.get();
                BigDecimal price = internalPrefix.getBaseValue() != null ? internalPrefix.getBaseValue() : BigDecimal.ZERO;
                BigDecimal vatRate = internalPrefix.getVatValue() != null ? internalPrefix.getVatValue() : BigDecimal.ZERO;
                boolean vatIncluded = internalPrefix.isVatIncluded();

                if (!vatIncluded && vatRate.compareTo(BigDecimal.ZERO) > 0) {
                    price = price.multiply(BigDecimal.ONE.add(vatRate.divide(BigDecimal.valueOf(100)))).setScale(4, RoundingMode.HALF_UP);
                }
                callRecord.setPricePerMinute(price);
                callRecord.setInitialPrice(price); // Assuming initial price is same for internal
                
                int durationInSeconds = callRecord.getDuration() != null ? callRecord.getDuration() : 0;
                long billedUnits = (long) Math.ceil((double) durationInSeconds / 60.0);
                if (billedUnits == 0 && durationInSeconds > 0) billedUnits = 1;
                callRecord.setBilledAmount(price.multiply(BigDecimal.valueOf(billedUnits)).setScale(2, RoundingMode.HALF_UP));

            } else {
                callRecord.setBilledAmount(BigDecimal.ZERO);
                callRecord.setPricePerMinute(BigDecimal.ZERO);
                callRecord.setInitialPrice(BigDecimal.ZERO);
            }
            log.debug("Call identified as Internal (Type: {})", internalCallResult.getTelephonyType().getName());
            return;
        }
        
        // 3. External Calls (Outgoing/Incoming) (PHP: procesaSaliente_Complementar -> evaluarDestino_pos)
        handleExternalCallPricing(callRecord, rawData, effectiveDialedNumber, commLocation, originCountryId);
    }

    private boolean handleSpecialServices(CallRecord callRecord, String dialedNumber, CommunicationLocation commLocation, Long originCountryId) {
        List<String> pbxPrefixes = cdrConfigService.getPbxOutputPrefixes(commLocation);
        // PHP's procesaServespecial first cleans with PBX prefix (safeMode=false, meaning if prefix defined but not found, number becomes empty)
        String numberForSpecialLookup = CdrHelper.cleanAndStripPhoneNumber(dialedNumber, pbxPrefixes, false);

        if (numberForSpecialLookup.isEmpty() && !pbxPrefixes.isEmpty()) { // If prefixes were mandatory and not found
            return false;
        }
        if (numberForSpecialLookup.isEmpty() && pbxPrefixes.isEmpty()) { // No prefixes, but number itself was empty after cleaning
             numberForSpecialLookup = CdrHelper.cleanAndStripPhoneNumber(dialedNumber, null, true); // Try cleaning without prefix expectation
        }


        Optional<SpecialService> specialServiceOpt = lookupService.findSpecialService(
            numberForSpecialLookup, // Use the number potentially stripped of PBX prefix
            commLocation.getIndicatorId(),
            originCountryId
        );

        if (specialServiceOpt.isPresent()) {
            SpecialService ss = specialServiceOpt.get();
            setTelephonyTypeAndOperator(callRecord, TelephonyTypeConstants.NUMEROS_ESPECIALES, originCountryId, ss.getIndicator());
            
            BigDecimal value = ss.getValue() != null ? ss.getValue() : BigDecimal.ZERO;
            BigDecimal vat = ss.getVatAmount() != null ? ss.getVatAmount() : BigDecimal.ZERO;
            if (ss.getVatIncluded() != null && ss.getVatIncluded()) {
                callRecord.setBilledAmount(value);
            } else {
                callRecord.setBilledAmount(value.add(vat));
            }
            callRecord.setPricePerMinute(value);
            callRecord.setInitialPrice(value);
            log.debug("Call identified as Special Service: {}", ss.getDescription());
            return true;
        }
        return false;
    }

    private InternalCallTypeResultDto evaluateInternalCallType(String originExtension, String destinationExtension,
                                                              CommunicationLocation currentCommLocation,
                                                              InternalExtensionLimitsDto limits, Long currentOriginCountryId) {
        // This is a complex replication of PHP's tipo_llamada_interna
        if (!CdrHelper.isPotentialExtension(originExtension, limits) || !CdrHelper.isPotentialExtension(destinationExtension, limits)) {
            return null; // Not both look like internal extensions
        }

        Optional<Employee> originEmpOpt = lookupService.findEmployeeByExtension(originExtension, currentCommLocation.getId(), limits);
        Optional<Employee> destEmpOpt = lookupService.findEmployeeByExtension(destinationExtension, currentCommLocation.getId(), limits);

        // PHP: if ($subdireccionDestino == '') { /* No existe el destino ... */ }
        // This means if destination employee is not found, it might still be an "assumed" internal call to an unprovisioned extension
        // or a call to an internal prefix.
        // For now, require both to be found for a confirmed internal call.
        // The PHP logic for "assumed" internal or internal-via-prefix is very specific and might need separate handling.

        if (originEmpOpt.isPresent() && destEmpOpt.isPresent()) {
            Employee originEmp = originEmpOpt.get();
            Employee destEmp = destEmpOpt.get();

            CommunicationLocation originCommLoc = originEmp.getCommunicationLocation() != null ? originEmp.getCommunicationLocation() : currentCommLocation;
            CommunicationLocation destCommLoc = destEmp.getCommunicationLocation() != null ? destEmp.getCommunicationLocation() : currentCommLocation;

            Indicator originIndicator = originCommLoc.getIndicator();
            Indicator destIndicator = destCommLoc.getIndicator();
            Long originCountry = originIndicator != null ? originIndicator.getOriginCountryId() : currentOriginCountryId;
            Long destCountry = destIndicator != null ? destIndicator.getOriginCountryId() : currentOriginCountryId;

            Long telephonyTypeId;
            if (!Objects.equals(originCountry, destCountry)) {
                telephonyTypeId = TelephonyTypeConstants.INTERNA_INTERNACIONAL;
            } else if (!Objects.equals(originIndicator != null ? originIndicator.getId() : null, destIndicator != null ? destIndicator.getId() : null)) {
                telephonyTypeId = TelephonyTypeConstants.INTERNA_NACIONAL;
            } else if (!Objects.equals(originCommLoc.getId(), destCommLoc.getId())) {
                telephonyTypeId = TelephonyTypeConstants.INTERNA_LOCAL;
            } else {
                telephonyTypeId = TelephonyTypeConstants.INTERNA;
            }

            TelephonyType tt = lookupService.findTelephonyTypeById(telephonyTypeId).orElse(null);
            Operator op = lookupService.findInternalOperatorByTelephonyType(telephonyTypeId, currentOriginCountryId).orElse(null);

            // PHP's ValidarOrigenDestino logic for ignoring calls
            boolean ignoreCall = false;
            boolean isIncomingInternal = false;
            // Simplified: if origin is not current plant but dest is, treat as incoming internal. If both not current, ignore.
            // PHP's ext_globales plays a role here. Assuming ext_globales = true for now.
            if (!Objects.equals(originCommLoc.getId(), currentCommLocation.getId()) &&
                Objects.equals(destCommLoc.getId(), currentCommLocation.getId())) {
                isIncomingInternal = true; // Origin is another plant, destination is current.
            } else if (!Objects.equals(originCommLoc.getId(), currentCommLocation.getId()) &&
                       !Objects.equals(destCommLoc.getId(), currentCommLocation.getId()) &&
                       !Objects.equals(originCommLoc.getId(), destCommLoc.getId())) { // Both different and not same remote plant
                 // This condition is complex in PHP with ValidarOrigenDestino.
                 // If both are external to the current commLocation and are in different commLocations themselves, PHP might ignore.
                 ignoreCall = true;
            }


            return InternalCallTypeResultDto.builder()
                .telephonyType(tt)
                .operator(op)
                .destinationIndicator(destIndicator)
                .destinationEmployee(destEmp)
                .ignoreCall(ignoreCall)
                .isIncomingInternal(isIncomingInternal)
                .build();
        }
        // PHP logic for calls to internal prefixes (PREFIJO_TIPOTELE_ID in tt_internas)
        // This would require checking destinationExtension against Prefix table for internal types.
        // For now, this is not fully replicated.

        return null;
    }


    private void handleExternalCallPricing(CallRecord callRecord, RawCiscoCdrData rawData, String dialedNumber, CommunicationLocation commLocation, Long originCountryId) {
        List<String> pbxPrefixes = cdrConfigService.getPbxOutputPrefixes(commLocation);
        boolean usesTrunk = callRecord.getTrunk() != null && !callRecord.getTrunk().isEmpty();
        
        // PHP: if ($existe_troncal === false) { $telefono = $info_destino_limpio; }
        // If not using a trunk, the number is cleaned of PBX prefixes (safeMode=true means keep original if no prefix match)
        // If using a trunk, PHP keeps original $info_destino for prefix matching, then cleans later if trunk rule says `noprefijopbx`.
        String numberForPrefixLookup = usesTrunk ? dialedNumber : CdrHelper.cleanAndStripPhoneNumber(dialedNumber, pbxPrefixes, true);

        List<Prefix> prefixes = lookupService.findMatchingPrefixes(numberForPrefixLookup, originCountryId, usesTrunk);

        EvaluatedDestinationDto bestEval = null;

        for (Prefix prefix : prefixes) {
            String currentNumberToProcess = numberForPrefixLookup;
            boolean currentVatIncluded = prefix.isVatIncluded();
            BigDecimal currentVatRate = prefix.getVatValue() != null ? prefix.getVatValue() : BigDecimal.ZERO;
            BigDecimal currentCallValue = prefix.getBaseValue() != null ? prefix.getBaseValue() : BigDecimal.ZERO;
            Long currentTelephonyTypeId = prefix.getTelephoneTypeId();
            TelephonyType currentTelephonyType = prefix.getTelephonyType();
            Operator currentOperator = prefix.getOperator();
            Long currentOperatorId = prefix.getOperatorId();
            String currentDestinationDescription = currentTelephonyType != null ? currentTelephonyType.getName() : "Unknown";
            Indicator currentIndicator = null;
            Long bandIdForSpecialRate = null;
            String bandName = null;
            boolean currentBilledInSeconds = false;
            Integer currentBillingUnit = 60;


            // PHP: if ($existe_troncal !== false && $existe_troncal['noprefijopbx']) { $telefono = $info_destino_limpio; }
            // If trunk exists and its main config says no pbx prefix, clean the number now.
            // Trunk rules might override this.
            if (usesTrunk) {
                Optional<Trunk> trunkOpt = lookupService.findTrunkByNameAndCommLocation(callRecord.getTrunk(), commLocation.getId());
                if (trunkOpt.isPresent() && trunkOpt.get().getNoPbxPrefix() != null && trunkOpt.get().getNoPbxPrefix()) {
                    currentNumberToProcess = CdrHelper.cleanAndStripPhoneNumber(dialedNumber, pbxPrefixes, true);
                }
            }

            String numberAfterPrefix = currentNumberToProcess;
            if (prefix.getCode() != null && !prefix.getCode().isEmpty() && currentNumberToProcess.startsWith(prefix.getCode())) {
                numberAfterPrefix = currentNumberToProcess.substring(prefix.getCode().length());
            }
            
            // PHP: if (_esLocal($tipotele_id)) { $tipotele_id = _TIPOTELE_NACIONAL; $telefono = $indicativo_origen.$telefono; }
            String numberForIndicatorLookup = numberAfterPrefix;
            Long typeForIndicatorLookup = currentTelephonyTypeId;

            if (Objects.equals(currentTelephonyTypeId, TelephonyTypeConstants.LOCAL)) {
                typeForIndicatorLookup = TelephonyTypeConstants.NACIONAL;
                String localAreaCode = lookupService.findLocalAreaCodeForIndicator(commLocation.getIndicatorId());
                numberForIndicatorLookup = localAreaCode + numberAfterPrefix;
            }

            List<Indicator> indicators = lookupService.findIndicatorsByNumberAndType(numberForIndicatorLookup, typeForIndicatorLookup, originCountryId, commLocation);
            
            if (!indicators.isEmpty()) {
                currentIndicator = indicators.get(0); // Simplification
                currentDestinationDescription = currentIndicator.getCityName() != null && !currentIndicator.getCityName().isEmpty() ?
                                                currentIndicator.getCityName() : currentIndicator.getDepartmentCountry();
                
                // Local Extended check
                if (Objects.equals(currentTelephonyTypeId, TelephonyTypeConstants.LOCAL) &&
                    commLocation.getIndicator() != null && currentIndicator != null &&
                    !Objects.equals(commLocation.getIndicatorId(), currentIndicator.getId()) &&
                    lookupService.isLocalExtended(commLocation.getIndicator(), currentIndicator)) {
                    
                    currentTelephonyTypeId = TelephonyTypeConstants.LOCAL_EXTENDIDA;
                    currentTelephonyType = lookupService.findTelephonyTypeById(TelephonyTypeConstants.LOCAL_EXTENDIDA).orElse(currentTelephonyType);
                    currentDestinationDescription = currentTelephonyType.getName() + " (" + currentDestinationDescription + ")";
                    // PHP might re-fetch prefix for LOCAL_EXTENDIDA here. For simplicity, assume base pricing remains.
                }


                if (prefix.isBandOk()) {
                    List<Band> bands = lookupService.findBandsForPrefixAndIndicator(prefix.getId(), commLocation.getIndicatorId());
                    if (!bands.isEmpty()) {
                        Band matchedBand = bands.get(0);
                        currentCallValue = matchedBand.getValue();
                        currentVatIncluded = matchedBand.getVatIncluded();
                        bandIdForSpecialRate = matchedBand.getId();
                        bandName = matchedBand.getName();
                    }
                }
            } else if (Objects.equals(currentTelephonyTypeId, TelephonyTypeConstants.LOCAL)) {
                // Fallback to commLocation's indicator for local calls if no specific series match
                currentIndicator = commLocation.getIndicator();
                if (currentIndicator != null) {
                     currentDestinationDescription = currentIndicator.getCityName() != null && !currentIndicator.getCityName().isEmpty() ?
                                                currentIndicator.getCityName() : currentIndicator.getDepartmentCountry();
                }
            } else {
                // No indicator found for non-local, this prefix path is likely not viable
                // PHP's `evaluarDestino_pos` would continue to next prefix.
                log.debug("No indicator for prefix {}, number part {}, type {}. Skipping this prefix.", prefix.getCode(), numberAfterPrefix, currentTelephonyTypeId);
                continue;
            }

            BigDecimal initialPriceNoVat = currentCallValue;
            if (currentVatIncluded && currentVatRate.compareTo(BigDecimal.ZERO) > 0) {
                initialPriceNoVat = currentCallValue.divide(BigDecimal.ONE.add(currentVatRate.divide(BigDecimal.valueOf(100), 10, RoundingMode.HALF_UP)), 4, RoundingMode.HALF_UP);
            }

            // Special Rates
            if (currentIndicator != null) {
                 List<SpecialRateValue> specialRates = lookupService.findSpecialRateValues(
                    callRecord.getServiceDate(), currentTelephonyTypeId, currentOperatorId, bandIdForSpecialRate, commLocation.getIndicatorId()
                );
                if (!specialRates.isEmpty()) {
                    SpecialRateValue sr = specialRates.get(0); // PHP takes first matching
                    BigDecimal rateBeforeSpecial = currentCallValue;
                    if (currentVatIncluded && currentVatRate.compareTo(BigDecimal.ZERO) > 0) {
                        rateBeforeSpecial = rateBeforeSpecial.divide(BigDecimal.ONE.add(currentVatRate.divide(BigDecimal.valueOf(100), 10, RoundingMode.HALF_UP)), 10, RoundingMode.HALF_UP);
                    }

                    if (sr.getValueType() != null && sr.getValueType() == 1) { // Percentage
                        BigDecimal discountPercentage = sr.getRateValue().divide(BigDecimal.valueOf(100), 10, RoundingMode.HALF_UP);
                        currentCallValue = rateBeforeSpecial.multiply(BigDecimal.ONE.subtract(discountPercentage));
                    } else { // Absolute value
                        currentCallValue = sr.getRateValue();
                    }
                    currentVatIncluded = sr.getIncludesVat();
                    // PHP: $infovalor['iva'] = $tarifas['iva']; (from PREFIJO via VALORESPECIAL join)
                    // This implies special rate might use a different VAT rate if its associated prefix (via TT/Op) has a different VAT.
                    // For simplicity, assume VAT rate from original prefix unless special rate links to a new operator/TT with different VAT.
                    // This needs a more robust VAT lookup if SpecialRateValue can change operator/TT context for VAT.
                }
            }
            
            EvaluatedDestinationDto currentEval = EvaluatedDestinationDto.builder()
                .processedNumber(numberAfterPrefix)
                .telephonyType(currentTelephonyType)
                .operator(currentOperator)
                .indicator(currentIndicator)
                .destinationDescription(currentDestinationDescription)
                .pricePerMinute(currentCallValue) // This is pre-VAT if vatIncluded is false
                .vatIncludedInPrice(currentVatIncluded)
                .vatRate(currentVatRate)
                .initialPriceBeforeSpecialRates(initialPriceNoVat)
                .initialPriceVatIncluded(prefix.isVatIncluded()) // VAT status of the initial price
                .billedInSeconds(false) // Default, may be overridden by trunk rule
                .billingUnitInSeconds(60)
                .fromTrunk(usesTrunk)
                .bandUsed(bandIdForSpecialRate != null)
                .bandId(bandIdForSpecialRate)
                .bandName(bandName)
                .assumed(false) // Not assumed if derived from a prefix
                .build();

            // PHP logic: if a valid destination is found, it breaks.
            // We are simulating this by taking the first valid one.
            // A more complex scoring might be needed if multiple prefixes could be valid.
            bestEval = currentEval;
            break; 
        }


        // PHP: if ($existe_troncal !== false && evaluarDestino_novalido($infovalor))
        // This is the "normalizar" logic. If trunk processing failed to find a good rate, try without trunk.
        if (usesTrunk && (bestEval == null || bestEval.getTelephonyType() == null || Objects.equals(bestEval.getTelephonyType().getId(), TelephonyTypeConstants.ERRORES) || bestEval.isAssumed())) {
            log.debug("Trunk call pricing was not definitive (or error). Attempting non-trunk 'normalization' for {}", dialedNumber);
            // Clean number of PBX prefixes (safeMode=true) as if it's not a trunk call for this lookup
            String normalizedNumberLookup = CdrHelper.cleanAndStripPhoneNumber(dialedNumber, pbxPrefixes, true);
            List<Prefix> normalizedPrefixes = lookupService.findMatchingPrefixes(normalizedNumberLookup, originCountryId, false); // false for non-trunk

            if (!normalizedPrefixes.isEmpty()) {
                // Re-evaluate with the best non-trunk prefix (logic similar to above loop, simplified here)
                Prefix normalizedPrefix = normalizedPrefixes.get(0);
                // ... (Repeat the evaluation logic with normalizedPrefix and fromTrunk=false)
                // This part needs to be a recursive call or a helper method to avoid code duplication.
                // For brevity, let's assume if a normalized prefix is found, it might yield a better result.
                // The actual PHP logic would re-run the whole `evaluarDestino_pos` with `existe_troncal = false`.
                // Here, we'll just log and potentially set a flag or a basic pricing.
                // A full re-evaluation is too verbose for this spot.
                EvaluatedDestinationDto normalizedEval = evaluateDestinationWithPrefix(normalizedNumberLookup, normalizedPrefix, commLocation, originCountryId, false, pbxPrefixes);
                if (normalizedEval != null && (bestEval == null || (!Objects.equals(normalizedEval.getTelephonyType().getId(), TelephonyTypeConstants.ERRORES) && !normalizedEval.isAssumed()))) {
                    log.info("Normalized pricing provided a better result for {}. Original trunk eval: {}", dialedNumber, bestEval);
                    bestEval = normalizedEval;
                    bestEval.setFromTrunk(true); // Still mark as originally from trunk for context
                    bestEval.setDestinationDescription(bestEval.getDestinationDescription() + " (Normalized)");
                }
            }
        }


        if (bestEval == null) {
            log.warn("No viable prefix/indicator combination found for {}. Falling back to error.", dialedNumber);
            setDefaultErrorTelephonyType(callRecord);
            return;
        }

        // Apply the best evaluation to the CallRecord
        callRecord.setTelephonyType(bestEval.getTelephonyType());
        callRecord.setTelephonyTypeId(bestEval.getTelephonyType() != null ? bestEval.getTelephonyType().getId() : TelephonyTypeConstants.ERRORES);
        callRecord.setOperator(bestEval.getOperator());
        callRecord.setOperatorId(bestEval.getOperator() != null ? bestEval.getOperator().getId() : null);
        callRecord.setIndicator(bestEval.getIndicator());
        callRecord.setIndicatorId(bestEval.getIndicator() != null ? bestEval.getIndicator().getId() : null);
        
        // Store initial price (PHP: ACUMTOTAL_PRECIOINICIAL)
        callRecord.setInitialPrice(bestEval.getInitialPriceBeforeSpecialRates().setScale(4, RoundingMode.HALF_UP));

        BigDecimal finalPricePerUnit = bestEval.getPricePerMinute();
        if (!bestEval.isVatIncludedInPrice() && bestEval.getVatRate().compareTo(BigDecimal.ZERO) > 0) {
            finalPricePerUnit = finalPricePerUnit.multiply(BigDecimal.ONE.add(bestEval.getVatRate().divide(BigDecimal.valueOf(100), 10, RoundingMode.HALF_UP)));
        }
        callRecord.setPricePerMinute(finalPricePerUnit.setScale(4, RoundingMode.HALF_UP));

        int durationInSeconds = callRecord.getDuration() != null ? callRecord.getDuration() : 0;
        long billedUnits = (long) Math.ceil((double) durationInSeconds / (double) bestEval.getBillingUnitInSeconds());
        if (billedUnits == 0 && durationInSeconds > 0) billedUnits = 1;
        
        callRecord.setBilledAmount(finalPricePerUnit.multiply(BigDecimal.valueOf(billedUnits)).setScale(2, RoundingMode.HALF_UP));

        // Trunk rule application (PHP: Calcular_Valor_Reglas)
        if (bestEval.isFromTrunk() && callRecord.getTrunk() != null && !callRecord.getTrunk().isEmpty() && bestEval.getIndicator() != null) {
            Optional<Trunk> trunkOpt = lookupService.findTrunkByNameAndCommLocation(callRecord.getTrunk(), commLocation.getId());
            if (trunkOpt.isPresent()) {
                List<TrunkRule> trunkRules = lookupService.findTrunkRules(
                    trunkOpt.get().getId(),
                    bestEval.getTelephonyType().getId(), // Use type determined *before* this rule for matching
                    String.valueOf(bestEval.getIndicator().getId()),
                    commLocation.getIndicatorId()
                );
                if (!trunkRules.isEmpty()) {
                    TrunkRule rule = trunkRules.get(0); // PHP takes first matching
                    log.debug("Applying trunk rule ID: {}", rule.getId());

                    BigDecimal ruleRateValue = rule.getRateValue();
                    boolean ruleVatIncluded = rule.getIncludesVat() != null && rule.getIncludesVat();
                    
                    BigDecimal ruleVatRate = bestEval.getVatRate(); // Default to current VAT
                    if (rule.getNewTelephonyTypeId() != null) {
                         Optional<BigDecimal> newVatRate = lookupService.findVatForTelephonyOperator(rule.getNewTelephonyTypeId(), rule.getNewOperatorId() != null ? rule.getNewOperatorId() : bestEval.getOperator().getId(), originCountryId);
                         if (newVatRate.isPresent()) ruleVatRate = newVatRate.get();
                    }

                    BigDecimal finalRulePricePerUnit = ruleRateValue;
                    if (!ruleVatIncluded && ruleVatRate.compareTo(BigDecimal.ZERO) > 0) {
                         finalRulePricePerUnit = ruleRateValue.multiply(BigDecimal.ONE.add(ruleVatRate.divide(BigDecimal.valueOf(100), 10, RoundingMode.HALF_UP)));
                    }
                    callRecord.setPricePerMinute(finalRulePricePerUnit.setScale(4, RoundingMode.HALF_UP));

                    long ruleBilledUnitsValue = billedUnits; // Default to prior units
                    Integer ruleSeconds = rule.getSeconds();
                    if (ruleSeconds != null && ruleSeconds > 0) {
                        ruleBilledUnitsValue = (long) Math.ceil((double) durationInSeconds / (double) ruleSeconds);
                        if (ruleBilledUnitsValue == 0 && durationInSeconds > 0) ruleBilledUnitsValue = 1;
                    }
                    callRecord.setBilledAmount(finalRulePricePerUnit.multiply(BigDecimal.valueOf(ruleBilledUnitsValue)).setScale(2, RoundingMode.HALF_UP));

                    if (rule.getNewTelephonyTypeId() != null) {
                        setTelephonyTypeAndOperator(callRecord, rule.getNewTelephonyTypeId(), originCountryId, callRecord.getIndicator());
                    }
                    if (rule.getNewOperatorId() != null) {
                        lookupService.findOperatorById(rule.getNewOperatorId()).ifPresent(op -> {
                            callRecord.setOperator(op);
                            callRecord.setOperatorId(op.getId());
                        });
                    }
                    if (rule.getOriginIndicatorId() != null && !rule.getOriginIndicatorId().equals(callRecord.getIndicatorId())) {
                        lookupService.findIndicatorById(rule.getOriginIndicatorId()).ifPresent(ind -> {
                            callRecord.setIndicator(ind);
                            callRecord.setIndicatorId(ind.getId());
                        });
                    }
                }
            }
        }
    }
    
    // Helper for the "normalization" path to avoid too much duplication
    private EvaluatedDestinationDto evaluateDestinationWithPrefix(String numberToLookup, Prefix prefix, CommunicationLocation commLocation, Long originCountryId, boolean isTrunkCall, List<String> pbxPrefixes) {
        // This method would contain the core logic from the loop inside handleExternalCallPricing
        // For brevity, this is a simplified placeholder. A full implementation would mirror the main loop's logic.
        String currentNumberToProcess = numberToLookup;
        boolean currentVatIncluded = prefix.isVatIncluded();
        BigDecimal currentVatRate = prefix.getVatValue() != null ? prefix.getVatValue() : BigDecimal.ZERO;
        BigDecimal currentCallValue = prefix.getBaseValue() != null ? prefix.getBaseValue() : BigDecimal.ZERO;
        Long currentTelephonyTypeId = prefix.getTelephoneTypeId();
        TelephonyType currentTelephonyType = prefix.getTelephonyType();
        Operator currentOperator = prefix.getOperator();
        Indicator currentIndicator = null;
        String currentDestinationDescription = currentTelephonyType != null ? currentTelephonyType.getName() : "Unknown";
        Long bandIdForSpecialRate = null; String bandName = null;

        String numberAfterPrefix = currentNumberToProcess;
        if (prefix.getCode() != null && !prefix.getCode().isEmpty() && currentNumberToProcess.startsWith(prefix.getCode())) {
            numberAfterPrefix = currentNumberToProcess.substring(prefix.getCode().length());
        }
        
        String numberForIndicatorLookup = numberAfterPrefix;
        Long typeForIndicatorLookup = currentTelephonyTypeId;
        if (Objects.equals(currentTelephonyTypeId, TelephonyTypeConstants.LOCAL)) {
            typeForIndicatorLookup = TelephonyTypeConstants.NACIONAL;
            String localAreaCode = lookupService.findLocalAreaCodeForIndicator(commLocation.getIndicatorId());
            numberForIndicatorLookup = localAreaCode + numberAfterPrefix;
        }

        List<Indicator> indicators = lookupService.findIndicatorsByNumberAndType(numberForIndicatorLookup, typeForIndicatorLookup, originCountryId, commLocation);
        if (!indicators.isEmpty()) {
            currentIndicator = indicators.get(0);
            currentDestinationDescription = currentIndicator.getCityName() != null && !currentIndicator.getCityName().isEmpty() ?
                                            currentIndicator.getCityName() : currentIndicator.getDepartmentCountry();
        } else if (Objects.equals(currentTelephonyTypeId, TelephonyTypeConstants.LOCAL)) {
            currentIndicator = commLocation.getIndicator();
             if (currentIndicator != null) {
                 currentDestinationDescription = currentIndicator.getCityName() != null && !currentIndicator.getCityName().isEmpty() ?
                                            currentIndicator.getCityName() : currentIndicator.getDepartmentCountry();
            }
        } else {
            return null; // No indicator, prefix not viable
        }
        
        // Simplified band and special rate logic for this helper
        if (prefix.isBandOk() && currentIndicator != null) {
            List<Band> bands = lookupService.findBandsForPrefixAndIndicator(prefix.getId(), commLocation.getIndicatorId());
            if (!bands.isEmpty()) {
                Band matchedBand = bands.get(0);
                currentCallValue = matchedBand.getValue(); currentVatIncluded = matchedBand.getVatIncluded();
                bandIdForSpecialRate = matchedBand.getId(); bandName = matchedBand.getName();
            }
        }
        BigDecimal initialPriceNoVat = currentCallValue;
        if (currentVatIncluded && currentVatRate.compareTo(BigDecimal.ZERO) > 0) {
            initialPriceNoVat = currentCallValue.divide(BigDecimal.ONE.add(currentVatRate.divide(BigDecimal.valueOf(100), 10, RoundingMode.HALF_UP)), 4, RoundingMode.HALF_UP);
        }

        return EvaluatedDestinationDto.builder()
            .processedNumber(numberAfterPrefix)
            .telephonyType(currentTelephonyType).operator(currentOperator).indicator(currentIndicator)
            .destinationDescription(currentDestinationDescription)
            .pricePerMinute(currentCallValue).vatIncludedInPrice(currentVatIncluded).vatRate(currentVatRate)
            .initialPriceBeforeSpecialRates(initialPriceNoVat).initialPriceVatIncluded(prefix.isVatIncluded())
            .billedInSeconds(false).billingUnitInSeconds(60)
            .fromTrunk(isTrunkCall)
            .bandUsed(bandIdForSpecialRate != null).bandId(bandIdForSpecialRate).bandName(bandName)
            .assumed(currentIndicator == null && !Objects.equals(currentTelephonyTypeId, TelephonyTypeConstants.LOCAL)) // Basic "assumed" logic
            .build();
    }


    private void setDefaultErrorTelephonyType(CallRecord callRecord) {
        callRecord.setTelephonyTypeId(TelephonyTypeConstants.ERRORES);
        lookupService.findTelephonyTypeById(TelephonyTypeConstants.ERRORES).ifPresent(callRecord::setTelephonyType);
        callRecord.setBilledAmount(BigDecimal.ZERO);
        callRecord.setPricePerMinute(BigDecimal.ZERO);
        callRecord.setInitialPrice(BigDecimal.ZERO);
    }

    private void setTelephonyTypeAndOperator(CallRecord callRecord, Long telephonyTypeId, Long originCountryId, Indicator indicator) {
        lookupService.findTelephonyTypeById(telephonyTypeId).ifPresent(tt -> {
            callRecord.setTelephonyType(tt);
            callRecord.setTelephonyTypeId(tt.getId());
        });
        if (callRecord.getOperatorId() == null) { // Only set if not already determined
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
        List<PbxSpecialRule> rules = lookupService.findPbxSpecialRules(commLocation.getId());
        String currentNumber = dialedNumber;

        for (PbxSpecialRule rule : rules) {
            PbxSpecialRuleDirection ruleDirection = PbxSpecialRuleDirection.fromValue(rule.getDirection());
            boolean directionMatch = (ruleDirection == PbxSpecialRuleDirection.BOTH) ||
                                     (isIncoming && ruleDirection == PbxSpecialRuleDirection.INCOMING) ||
                                     (!isIncoming && ruleDirection == PbxSpecialRuleDirection.OUTGOING);

            if (!directionMatch) continue;
            
            if (currentNumber == null || currentNumber.length() < rule.getMinLength()) continue;

            Pattern searchPattern;
            try {
                 searchPattern = Pattern.compile("^" + rule.getSearchPattern()); // PHP logic implies startsWith
            } catch (Exception e) {
                log.warn("Invalid search pattern in PbxSpecialRule ID {}: {}", rule.getId(), rule.getSearchPattern(), e);
                continue;
            }
            Matcher matcher = searchPattern.matcher(currentNumber);

            if (matcher.find()) { // If search_pattern matches the beginning of currentNumber
                boolean ignore = false;
                if (rule.getIgnorePattern() != null && !rule.getIgnorePattern().isEmpty()) {
                    String[] ignorePatternsText = rule.getIgnorePattern().split(",");
                    for (String ignorePatStr : ignorePatternsText) {
                        if (ignorePatStr.trim().isEmpty()) continue;
                        try {
                            // Ignore pattern can match anywhere in the *original* number if PHP's logic implies that.
                            // Or, if it's meant to match on the *currentNumber* state.
                            // PHP's `evaluarPBXEspecial` uses $tmp_alkosto (original dialed_number) for ignore check.
                            Pattern ignorePat = Pattern.compile(ignorePatStr.trim()); // Matches anywhere
                            if (ignorePat.matcher(dialedNumber).find()) { // Check against original dialedNumber
                                ignore = true;
                                break;
                            }
                        } catch (Exception e) {
                           log.warn("Invalid ignore pattern in PbxSpecialRule ID {}: {}", rule.getId(), ignorePatStr.trim(), e);
                        }
                    }
                }
                if (ignore) continue;
                
                String replacement = rule.getReplacement() != null ? rule.getReplacement() : "";
                // Replace only the matched part (search_pattern) with replacement, and append the rest.
                currentNumber = replacement + currentNumber.substring(matcher.end());
                log.debug("Applied PBX rule ID {}: {} -> {}", rule.getId(), dialedNumber, currentNumber);
                return currentNumber; // PHP applies first matching rule
            }
        }
        return currentNumber;
    }
}