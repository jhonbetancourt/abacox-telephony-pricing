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

        // Priority 1: Auth Code (if not ignored)
        if (rawData.getAuthCodeDescription() != null && !rawData.getAuthCodeDescription().isEmpty() &&
            !ignoredAuthCodes.contains(rawData.getAuthCodeDescription())) {
            employeeOpt = lookupService.findEmployeeByAuthCode(rawData.getAuthCodeDescription(), commLocation.getId());
            if (employeeOpt.isPresent()) {
                assignmentCause = ImdexAssignmentCause.BY_AUTH_CODE;
            }
        }

        // Priority 2: Direct Extension Match
        if (employeeOpt.isEmpty() && rawData.getCallingPartyNumber() != null && !rawData.getCallingPartyNumber().isEmpty()) {
            employeeOpt = lookupService.findEmployeeByExtension(rawData.getCallingPartyNumber(), commLocation.getId(), limits);
            if (employeeOpt.isPresent()) {
                assignmentCause = (employeeOpt.get().getId() == null) ? ImdexAssignmentCause.BY_EXTENSION_RANGE : ImdexAssignmentCause.BY_EXTENSION;
            }
        }
        
        // PHP logic for assigning based on transfer (funcionario_redir)
        // This is complex. PHP's `ObtenerFuncionario_Arreglo` has a `tipo` parameter.
        // If `employeeOpt` is still empty, and it's a transfer, PHP might check `ext-redir`.
        // The `callRecord.employeeId` should be the *originator*.
        // If the call was transferred *by* an internal employee (rawData.callingPartyNumber) *to* another internal employee (rawData.lastRedirectDn),
        // the originator is still rawData.callingPartyNumber.
        // If an external call was transferred *by* an internal employee (rawData.lastRedirectDn) *to* another party,
        // then rawData.lastRedirectDn might be considered the "responsible" internal party for that leg.
        // This needs very careful mapping. For now, we assume assignEmployee focuses on the primary originating party.
        // Destination employee is handled separately.

        employeeOpt.ifPresent(emp -> {
            callRecord.setEmployee(emp);
            callRecord.setEmployeeId(emp.getId());
        });
        callRecord.setAssignmentCause(assignmentCause.getValue());
    }


    private void determineCallTypeAndPricing(CallRecord callRecord, RawCiscoCdrData rawData, CommunicationLocation commLocation, InternalExtensionLimitsDto limits) {
        String dialedNumber = rawData.getFinalCalledPartyNumber(); // This is already processed by Cisco processor
        if (dialedNumber == null || dialedNumber.isEmpty()) {
             // This case should ideally be caught by the parser if finalCalled and originalCalled are both empty
            dialedNumber = rawData.getOriginalCalledPartyNumber();
        }
        
        dialedNumber = applyPbxSpecialRules(dialedNumber, commLocation, callRecord.isIncoming());
        callRecord.setDial(CdrHelper.cleanPhoneNumber(dialedNumber)); // Store cleaned, potentially modified number

        // Default values
        setDefaultErrorTelephonyType(callRecord);

        if (dialedNumber == null || dialedNumber.isEmpty()) {
            log.warn("Dialed number is empty for CDR hash: {}", callRecord.getCdrHash());
            return;
        }

        Long originCountryId = commLocation.getIndicator() != null ? commLocation.getIndicator().getOriginCountryId() : null;
        if (originCountryId == null) {
            log.error("Origin Country ID is null for commLocationId: {}. Cannot proceed with pricing.", commLocation.getId());
            return; // Cannot price without origin country
        }

        // 1. Special Services
        if (handleSpecialServices(callRecord, dialedNumber, commLocation, originCountryId)) {
            return;
        }

        // 2. Internal Calls
        if (handleInternalCalls(callRecord, rawData, dialedNumber, commLocation, limits, originCountryId)) {
            return;
        }
        
        // 3. External Calls (Outgoing/Incoming)
        handleExternalCalls(callRecord, rawData, dialedNumber, commLocation, originCountryId);
    }

    private boolean handleSpecialServices(CallRecord callRecord, String dialedNumber, CommunicationLocation commLocation, Long originCountryId) {
        Optional<SpecialService> specialServiceOpt = lookupService.findSpecialService(
            dialedNumber,
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
            callRecord.setPricePerMinute(value); // Assuming value is per call
            callRecord.setInitialPrice(value);
            log.debug("Call identified as Special Service: {}", ss.getDescription());
            return true;
        }
        return false;
    }

    private boolean handleInternalCalls(CallRecord callRecord, RawCiscoCdrData rawData, String dialedNumber, CommunicationLocation commLocation, InternalExtensionLimitsDto limits, Long originCountryId) {
        // PHP's `procesaInterna` and `tipo_llamada_interna`
        String originatingExtension = rawData.getCallingPartyNumber();

        boolean isOriginPotentiallyInternal = CdrHelper.isPotentialExtension(originatingExtension, limits);
        boolean isDestinationPotentiallyInternal = CdrHelper.isPotentialExtension(dialedNumber, limits);

        if (isOriginPotentiallyInternal && isDestinationPotentiallyInternal) {
            // Both parties look like internal extensions.
            // Further checks are needed to confirm they are *known* internal extensions
            // and to determine the specific internal call type.
            Optional<Employee> originEmployeeOpt = lookupService.findEmployeeByExtension(originatingExtension, commLocation.getId(), limits);
            Optional<Employee> destEmployeeOpt = lookupService.findEmployeeByExtension(dialedNumber, commLocation.getId(), limits);

            if (originEmployeeOpt.isPresent() && destEmployeeOpt.isPresent()) {
                Employee originEmp = originEmployeeOpt.get();
                Employee destEmp = destEmployeeOpt.get();
                callRecord.setDestinationEmployee(destEmp);
                callRecord.setDestinationEmployeeId(destEmp.getId());

                // Determine specific internal type based on locations
                Long internalTelephonyTypeId = TelephonyTypeConstants.INTERNA; // Default
                CommunicationLocation originCommLoc = originEmp.getCommunicationLocation() != null ? originEmp.getCommunicationLocation() : commLocation;
                CommunicationLocation destCommLoc = destEmp.getCommunicationLocation() != null ? destEmp.getCommunicationLocation() : commLocation; // Assume same if destEmp is virtual/range

                if (originCommLoc != null && destCommLoc != null) {
                    Indicator originIndicator = originCommLoc.getIndicator();
                    Indicator destIndicator = destCommLoc.getIndicator();

                    if (originIndicator != null && destIndicator != null) {
                        if (!Objects.equals(originIndicator.getOriginCountryId(), destIndicator.getOriginCountryId())) {
                            internalTelephonyTypeId = TelephonyTypeConstants.INTERNA_INTERNACIONAL;
                        } else if (!Objects.equals(originIndicator.getId(), destIndicator.getId())) {
                            internalTelephonyTypeId = TelephonyTypeConstants.INTERNA_NACIONAL;
                        } else if (!Objects.equals(originCommLoc.getId(), destCommLoc.getId())) {
                            // Same indicator (city) but different physical locations/plants
                            internalTelephonyTypeId = TelephonyTypeConstants.INTERNA_LOCAL;
                        } else {
                            internalTelephonyTypeId = TelephonyTypeConstants.INTERNA; // Same plant
                        }
                    }
                }
                setTelephonyTypeAndOperator(callRecord, internalTelephonyTypeId, originCountryId, commLocation.getIndicator());
                callRecord.setBilledAmount(BigDecimal.ZERO);
                callRecord.setPricePerMinute(BigDecimal.ZERO);
                callRecord.setInitialPrice(BigDecimal.ZERO);
                log.debug("Call identified as Internal (Type: {})", internalTelephonyTypeId);
                return true;
            }
        }
        return false;
    }

    private void handleExternalCalls(CallRecord callRecord, RawCiscoCdrData rawData, String dialedNumber, CommunicationLocation commLocation, Long originCountryId) {
        List<String> pbxPrefixes = cdrConfigService.getPbxOutputPrefixes(commLocation);
        String numberToLookup = CdrHelper.stripPbxPrefix(dialedNumber, pbxPrefixes);
        
        List<Prefix> prefixes = lookupService.findMatchingPrefixes(numberToLookup, originCountryId, callRecord.getTrunk() != null && !callRecord.getTrunk().isEmpty());

        if (prefixes.isEmpty()) {
            log.warn("No matching prefix for external call: {} (orig: {})", numberToLookup, dialedNumber);
            setDefaultErrorTelephonyType(callRecord);
            return;
        }

        // PHP iterates through prefixes. We'll take the first (longest match).
        // A more faithful implementation would loop and try to find the "best" indicator/rate.
        Prefix matchedPrefix = prefixes.get(0);
        setTelephonyTypeAndOperator(callRecord, matchedPrefix.getTelephoneTypeId(), originCountryId, null); // Indicator set later
        callRecord.setOperator(matchedPrefix.getOperator()); // Set the operator object too
        callRecord.setOperatorId(matchedPrefix.getOperatorId());


        String numberAfterPrefix = numberToLookup;
        if (matchedPrefix.getCode() != null && !matchedPrefix.getCode().isEmpty() && numberToLookup.startsWith(matchedPrefix.getCode())) {
            numberAfterPrefix = numberToLookup.substring(matchedPrefix.getCode().length());
        }
        
        List<Indicator> indicators = lookupService.findIndicatorsByNumberAndType(numberAfterPrefix, matchedPrefix.getTelephoneTypeId(), originCountryId, commLocation);
        Indicator matchedIndicator = null;
        if (!indicators.isEmpty()) {
            matchedIndicator = indicators.get(0); // Simplification: take first. PHP might have more complex selection.
            callRecord.setIndicator(matchedIndicator);
            callRecord.setIndicatorId(matchedIndicator.getId());
        } else if (Long.valueOf(TelephonyTypeConstants.LOCAL).equals(matchedPrefix.getTelephoneTypeId())) {
            callRecord.setIndicator(commLocation.getIndicator());
            callRecord.setIndicatorId(commLocation.getIndicatorId());
            matchedIndicator = commLocation.getIndicator();
        } else {
            log.warn("No indicator found for number part: {} and type: {}. CDR Hash: {}", numberAfterPrefix, matchedPrefix.getTelephoneTypeId(), callRecord.getCdrHash());
            setDefaultErrorTelephonyType(callRecord); // Fallback to error if no indicator
            return;
        }

        // --- Pricing Logic ---
        BigDecimal baseValue = matchedPrefix.getBaseValue() != null ? matchedPrefix.getBaseValue() : BigDecimal.ZERO;
        boolean vatIncludedInBase = matchedPrefix.isVatIncluded();
        BigDecimal vatRate = matchedPrefix.getVatValue() != null ? matchedPrefix.getVatValue() : BigDecimal.ZERO;
        
        BigDecimal callValue = baseValue;
        Long bandIdForSpecialRate = null;

        if (matchedPrefix.isBandOk() && matchedIndicator != null) {
            List<Band> bands = lookupService.findBandsForPrefixAndIndicator(matchedPrefix.getId(), commLocation.getIndicatorId());
            if (!bands.isEmpty()) {
                Band matchedBand = bands.get(0);
                callValue = matchedBand.getValue();
                vatIncludedInBase = matchedBand.getVatIncluded();
                bandIdForSpecialRate = matchedBand.getId();
                log.debug("Using band value from Band ID: {}", matchedBand.getId());
            }
        }
        
        // Store initial price before special rates (PHP's PRECIOINICIAL logic)
        BigDecimal initialPriceNoVat = callValue;
        if (vatIncludedInBase && vatRate.compareTo(BigDecimal.ZERO) > 0) {
            initialPriceNoVat = callValue.divide(BigDecimal.ONE.add(vatRate.divide(BigDecimal.valueOf(100))), 4, RoundingMode.HALF_UP);
        }
        callRecord.setInitialPrice(initialPriceNoVat.setScale(4, RoundingMode.HALF_UP));


        if (matchedIndicator != null) {
            List<SpecialRateValue> specialRates = lookupService.findSpecialRateValues(
                callRecord.getServiceDate(),
                callRecord.getTelephonyTypeId(),
                callRecord.getOperatorId(),
                bandIdForSpecialRate, 
                commLocation.getIndicatorId()
            );
            if (!specialRates.isEmpty()) {
                SpecialRateValue sr = specialRates.get(0); 
                BigDecimal currentRateNoVat = callValue;
                if (vatIncludedInBase && vatRate.compareTo(BigDecimal.ZERO) > 0) {
                     currentRateNoVat = callValue.divide(BigDecimal.ONE.add(vatRate.divide(BigDecimal.valueOf(100))), 10, RoundingMode.HALF_UP); // Higher precision for intermediate
                }

                if (sr.getValueType() != null && sr.getValueType() == 1) { // Percentage
                    BigDecimal discountPercentage = sr.getRateValue().divide(BigDecimal.valueOf(100), 10, RoundingMode.HALF_UP);
                    callValue = currentRateNoVat.multiply(BigDecimal.ONE.subtract(discountPercentage));
                } else { // Absolute value
                    callValue = sr.getRateValue(); // This value is assumed to be ex-VAT if sr.includesVat is false
                }
                vatIncludedInBase = sr.getIncludesVat(); // VAT status now determined by special rate
                // If special rate defines a new VAT rate (not directly supported by SpecialRateValue entity, but PHP implies it via PREFIJO_IVA lookup)
                // For now, assume VAT rate remains from the prefix.
                log.debug("Applied special rate: {}", sr.getName());
            }
        }

        BigDecimal pricePerMinute = callValue; // This is now the potentially special-rated value
        if (!vatIncludedInBase && vatRate.compareTo(BigDecimal.ZERO) > 0) {
            pricePerMinute = callValue.multiply(BigDecimal.ONE.add(vatRate.divide(BigDecimal.valueOf(100))));
        }
        callRecord.setPricePerMinute(pricePerMinute.setScale(4, RoundingMode.HALF_UP));

        int durationInSeconds = callRecord.getDuration() != null ? callRecord.getDuration() : 0;
        long billedUnits = (long) Math.ceil((double) durationInSeconds / 60.0); // Default to minutes
        // PHP: $ensegundos = ($infovalor['ensegundos'] !== false);
        // This 'ensegundos' flag comes from TrunkRule or TrunkRate in PHP.
        // For now, assume minute billing. Trunk rule logic below might change this.

        if (billedUnits == 0 && durationInSeconds > 0) billedUnits = 1;

        callRecord.setBilledAmount(pricePerMinute.multiply(BigDecimal.valueOf(billedUnits)).setScale(2, RoundingMode.HALF_UP));
        
        // Trunk rule application
        if (callRecord.getTrunk() != null && !callRecord.getTrunk().isEmpty() && matchedIndicator != null) {
            Optional<Trunk> trunkOpt = lookupService.findTrunkByNameAndCommLocation(callRecord.getTrunk(), commLocation.getId());
            if (trunkOpt.isPresent()) {
                List<TrunkRule> trunkRules = lookupService.findTrunkRules(
                    trunkOpt.get().getId(),
                    callRecord.getTelephonyTypeId(), // Original type before rule
                    String.valueOf(matchedIndicator.getId()),
                    commLocation.getIndicatorId()
                );
                if (!trunkRules.isEmpty()) {
                    TrunkRule rule = trunkRules.get(0);
                    log.debug("Applying trunk rule ID: {}", rule.getId());

                    BigDecimal ruleRateValue = rule.getRateValue();
                    boolean ruleVatIncluded = rule.getIncludesVat() != null && rule.getIncludesVat();
                    
                    // If rule specifies new telephony type, get its VAT rate
                    BigDecimal ruleVatRate = vatRate; // Default to original prefix VAT
                    if (rule.getNewTelephonyTypeId() != null) {
                        Optional<TelephonyType> newTt = lookupService.findTelephonyTypeById(rule.getNewTelephonyTypeId());
                        if (newTt.isPresent()) {
                            // Need to find prefix for this new type and new operator to get VAT
                            Long operatorForVat = rule.getNewOperatorId() != null ? rule.getNewOperatorId() : callRecord.getOperatorId();
                            List<Prefix> newPrefixes = lookupService.findMatchingPrefixes("", originCountryId, true); // Find generic prefix for type/op
                            Optional<Prefix> newPrefixForVat = newPrefixes.stream()
                                .filter(p -> p.getTelephoneTypeId().equals(rule.getNewTelephonyTypeId()) && p.getOperatorId().equals(operatorForVat))
                                .findFirst();
                            if (newPrefixForVat.isPresent()) {
                                ruleVatRate = newPrefixForVat.get().getVatValue() != null ? newPrefixForVat.get().getVatValue() : BigDecimal.ZERO;
                            }
                        }
                    }


                    BigDecimal finalPricePerUnit = ruleRateValue;
                    if (!ruleVatIncluded && ruleVatRate.compareTo(BigDecimal.ZERO) > 0) {
                         finalPricePerUnit = ruleRateValue.multiply(BigDecimal.ONE.add(ruleVatRate.divide(BigDecimal.valueOf(100))));
                    }
                    callRecord.setPricePerMinute(finalPricePerUnit.setScale(4, RoundingMode.HALF_UP)); // Assuming rule rate is per minute

                    long ruleBilledUnits = billedUnits; // Default to minutes
                    if (rule.getSeconds() != null && rule.getSeconds() > 0) { // Rule bills per N seconds
                        ruleBilledUnits = (long) Math.ceil((double) durationInSeconds / (double) rule.getSeconds());
                        if (ruleBilledUnits == 0 && durationInSeconds > 0) ruleBilledUnits = 1;
                    }
                    callRecord.setBilledAmount(finalPricePerUnit.multiply(BigDecimal.valueOf(ruleBilledUnits)).setScale(2, RoundingMode.HALF_UP));

                    if (rule.getNewTelephonyTypeId() != null) {
                        setTelephonyTypeAndOperator(callRecord, rule.getNewTelephonyTypeId(), originCountryId, callRecord.getIndicator()); // Keep current indicator unless rule changes it
                    }
                    if (rule.getNewOperatorId() != null) {
                        lookupService.findOperatorById(rule.getNewOperatorId()).ifPresent(op -> {
                            callRecord.setOperator(op);
                            callRecord.setOperatorId(op.getId());
                        });
                    }
                    // PHP also has logic for REGLATRONCAL_INDICAORIGEN_ID which might change the indicator context.
                    if (rule.getOriginIndicatorId() != null && !rule.getOriginIndicatorId().equals(callRecord.getIndicatorId())) {
                        lookupService.findIndicatorById(rule.getOriginIndicatorId()).ifPresent(ind -> {
                            callRecord.setIndicator(ind); // This might be the "new" origin indicator for the rerouted call
                            callRecord.setIndicatorId(ind.getId());
                        });
                    }
                }
            }
        }
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
        // Set default operator for this type if not already set by prefix/rule
        if (callRecord.getOperatorId() == null) {
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
            
            // Ensure currentNumber is not null or empty before length check
            if (currentNumber == null || currentNumber.length() < rule.getMinLength()) continue;


            Pattern searchPattern;
            try {
                 searchPattern = Pattern.compile("^" + rule.getSearchPattern());
            } catch (Exception e) {
                log.warn("Invalid search pattern in PbxSpecialRule ID {}: {}", rule.getId(), rule.getSearchPattern(), e);
                continue;
            }
            Matcher matcher = searchPattern.matcher(currentNumber);

            if (matcher.find()) {
                boolean ignore = false;
                if (rule.getIgnorePattern() != null && !rule.getIgnorePattern().isEmpty()) {
                    String[] ignorePatternsText = rule.getIgnorePattern().split(",");
                    for (String ignorePatStr : ignorePatternsText) {
                        if (ignorePatStr.trim().isEmpty()) continue;
                        try {
                            Pattern ignorePat = Pattern.compile(ignorePatStr.trim());
                            if (ignorePat.matcher(currentNumber).find()) {
                                ignore = true;
                                break;
                            }
                        } catch (Exception e) {
                           log.warn("Invalid ignore pattern in PbxSpecialRule ID {}: {}", rule.getId(), ignorePatStr.trim(), e);
                           // Decide if this should skip the rule or just this ignore pattern
                        }
                    }
                }
                if (ignore) continue;
                
                String replacement = rule.getReplacement() != null ? rule.getReplacement() : "";
                currentNumber = replacement + currentNumber.substring(matcher.end()); // Use matcher.end() to get end of matched search_pattern
                log.debug("Applied PBX rule ID {}: {} -> {}", rule.getId(), dialedNumber, currentNumber);
                return currentNumber; // PHP applies first matching rule
            }
        }
        return currentNumber;
    }
}