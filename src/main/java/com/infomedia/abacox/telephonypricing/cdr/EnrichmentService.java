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
    private final ConfigurationService configurationService;

    public void enrichCallRecord(CallRecord callRecord, RawCiscoCdrData rawData, CommunicationLocation commLocation) {
        assignEmployee(callRecord, rawData, commLocation);
        determineCallTypeAndPricing(callRecord, rawData, commLocation);
        // Further enrichments can be added here
    }

    private void assignEmployee(CallRecord callRecord, RawCiscoCdrData rawData, CommunicationLocation commLocation) {
        Optional<Employee> employeeOpt = Optional.empty();
        ImdexAssignmentCause assignmentCause = ImdexAssignmentCause.NOT_ASSIGNED;

        List<String> ignoredAuthCodes = configurationService.getIgnoredAuthCodes();

        if (rawData.getAuthCodeDescription() != null && !rawData.getAuthCodeDescription().isEmpty() &&
            !ignoredAuthCodes.contains(rawData.getAuthCodeDescription())) {
            employeeOpt = lookupService.findEmployeeByAuthCode(rawData.getAuthCodeDescription(), commLocation.getId());
            if (employeeOpt.isPresent()) {
                assignmentCause = ImdexAssignmentCause.BY_AUTH_CODE;
            }
        }

        if (employeeOpt.isEmpty() && rawData.getCallingPartyNumber() != null && !rawData.getCallingPartyNumber().isEmpty()) {
            employeeOpt = lookupService.findEmployeeByExtension(rawData.getCallingPartyNumber(), commLocation.getId());
             if (employeeOpt.isPresent()) {
                // Check if it came from a range or direct lookup
                if (employeeOpt.get().getId() == null) { // Virtual employee from range
                     assignmentCause = ImdexAssignmentCause.BY_EXTENSION_RANGE;
                } else {
                    assignmentCause = ImdexAssignmentCause.BY_EXTENSION;
                }
            }
        }
        
        // PHP logic for assigning based on transfer (funcionario_redir)
        if (employeeOpt.isEmpty() && rawData.getImdexTransferCause() != ImdexTransferCause.NO_TRANSFER &&
            rawData.getLastRedirectDn() != null && !rawData.getLastRedirectDn().isEmpty()) {
            // If the call was transferred, the lastRedirectDn might be an internal extension
            Optional<Employee> transferredToEmployeeOpt = lookupService.findEmployeeByExtension(rawData.getLastRedirectDn(), commLocation.getId());
            if (transferredToEmployeeOpt.isPresent()) {
                // This logic is tricky: PHP's `procesaSaliente` assigns based on `funcionario_funid` (original caller)
                // or `funcionario_redir` (if original not found and redir is in same commLocation).
                // For simplicity here, if original employee not found and it's a transfer,
                // we might associate with the transfer target if it's an internal known employee.
                // However, CallRecord.employeeId is for the *originating* employee.
                // This part needs careful mapping to PHP's intent.
                // For now, we stick to originating employee. Destination employee is separate.
            }
        }


        employeeOpt.ifPresent(emp -> {
            callRecord.setEmployee(emp);
            callRecord.setEmployeeId(emp.getId()); // May be null if virtual employee from range
        });
        callRecord.setAssignmentCause(assignmentCause.getValue());
    }


    private void determineCallTypeAndPricing(CallRecord callRecord, RawCiscoCdrData rawData, CommunicationLocation commLocation) {
        // This is a major simplification of PHP's `evaluarDestino` and related functions.
        // A full translation would be extremely large.

        String dialedNumber = rawData.getFinalCalledPartyNumber();
        if (dialedNumber == null || dialedNumber.isEmpty()) {
            dialedNumber = rawData.getOriginalCalledPartyNumber();
        }
        
        // Apply PBX Special Rules first, as they can change the dialed number
        dialedNumber = applyPbxSpecialRules(dialedNumber, commLocation, callRecord.isIncoming());

        callRecord.setDial(dialedNumber); // Store potentially modified number

        // Default values
        callRecord.setTelephonyTypeId(TelephonyTypeConstants.ERRORES); // Default to error/unknown
        lookupService.findTelephonyTypeById(TelephonyTypeConstants.ERRORES).ifPresent(callRecord::setTelephonyType);
        callRecord.setBilledAmount(BigDecimal.ZERO);
        callRecord.setPricePerMinute(BigDecimal.ZERO);
        callRecord.setInitialPrice(BigDecimal.ZERO);

        if (dialedNumber == null || dialedNumber.isEmpty()) {
            log.warn("Dialed number is empty for CDR hash: {}", callRecord.getCdrHash());
            return;
        }

        // 1. Handle Special Services (e.g., 113, emergency numbers)
        Optional<SpecialService> specialServiceOpt = lookupService.findSpecialService(
            dialedNumber,
            commLocation.getIndicatorId(),
            commLocation.getIndicator().getOriginCountryId() // Assuming indicator is always present on commLocation
        );

        if (specialServiceOpt.isPresent()) {
            SpecialService ss = specialServiceOpt.get();
            callRecord.setTelephonyTypeId(TelephonyTypeConstants.NUMEROS_ESPECIALES); // Or a more specific type if available
            lookupService.findTelephonyTypeById(TelephonyTypeConstants.NUMEROS_ESPECIALES).ifPresent(callRecord::setTelephonyType);
            lookupService.findInternalOperatorByTelephonyType(TelephonyTypeConstants.NUMEROS_ESPECIALES, commLocation.getIndicator().getOriginCountryId())
                .ifPresent(op -> {
                    callRecord.setOperator(op);
                    callRecord.setOperatorId(op.getId());
                });
            callRecord.setIndicator(commLocation.getIndicator());
            callRecord.setIndicatorId(commLocation.getIndicatorId());
            
            BigDecimal value = ss.getValue() != null ? ss.getValue() : BigDecimal.ZERO;
            BigDecimal vat = ss.getVatAmount() != null ? ss.getVatAmount() : BigDecimal.ZERO;
            if (ss.getVatIncluded() != null && ss.getVatIncluded()) {
                callRecord.setBilledAmount(value);
            } else {
                callRecord.setBilledAmount(value.add(vat));
            }
            callRecord.setPricePerMinute(value); // Assuming value is per call for special services
            log.debug("Call identified as Special Service: {}", ss.getDescription());
            return;
        }

        // 2. Determine if Internal Call (simplified)
        // PHP's `es_llamada_interna` and `procesaInterna` are complex.
        // A simple check: if dialedNumber is a known internal extension in the same commLocation.
        Optional<Employee> destinationEmployeeOpt = lookupService.findEmployeeByExtension(dialedNumber, commLocation.getId());
        if (destinationEmployeeOpt.isPresent() && callRecord.getEmployeeId() != null) { // Originating employee must also be internal
            callRecord.setDestinationEmployee(destinationEmployeeOpt.get());
            callRecord.setDestinationEmployeeId(destinationEmployeeOpt.get().getId());
            
            // Determine internal call type (INTERNA, INTERNA_LOCAL, INTERNA_NACIONAL, INTERNA_INTERNACIONAL)
            // This depends on comparing origin and destination subdivisions/cost_centers/indicators
            // For simplicity, defaulting to INTERNA
            callRecord.setTelephonyTypeId(TelephonyTypeConstants.INTERNA);
            lookupService.findTelephonyTypeById(TelephonyTypeConstants.INTERNA).ifPresent(callRecord::setTelephonyType);
            lookupService.findInternalOperatorByTelephonyType(TelephonyTypeConstants.INTERNA, commLocation.getIndicator().getOriginCountryId())
                .ifPresent(op -> {
                    callRecord.setOperator(op);
                    callRecord.setOperatorId(op.getId());
                });
            callRecord.setIndicator(commLocation.getIndicator()); // Assuming internal calls use local indicator
            callRecord.setIndicatorId(commLocation.getIndicatorId());
            // Internal calls usually have zero cost
            callRecord.setBilledAmount(BigDecimal.ZERO);
            callRecord.setPricePerMinute(BigDecimal.ZERO);
            log.debug("Call identified as Internal");
            return;
        }

        // 3. External Call (Outgoing/Incoming based on callRecord.isIncoming)
        // This is where the main `evaluarDestino` logic from PHP applies.
        // It involves looking up Prefix, Band, Indicator, Operator, TelephonyTypeConfig, SpecialRateValue.

        List<String> pbxPrefixes = configurationService.getPbxOutputPrefixes(commLocation);
        String numberToLookup = CdrHelper.stripPbxPrefix(dialedNumber, pbxPrefixes);
        
        Long originCountryId = commLocation.getIndicator().getOriginCountryId();
        List<Prefix> prefixes = lookupService.findMatchingPrefixes(numberToLookup, originCountryId, callRecord.getTrunk() != null && !callRecord.getTrunk().isEmpty());

        if (prefixes.isEmpty()) {
            log.warn("No matching prefix found for number: {} (orig: {})", numberToLookup, dialedNumber);
            // Default to error or a generic "unknown external" type
            return;
        }

        Prefix matchedPrefix = prefixes.get(0); // Longest prefix matched
        callRecord.setTelephonyType(matchedPrefix.getTelephonyType());
        callRecord.setTelephonyTypeId(matchedPrefix.getTelephoneTypeId());
        callRecord.setOperator(matchedPrefix.getOperator());
        callRecord.setOperatorId(matchedPrefix.getOperatorId());

        // Find Indicator based on the remaining part of the number after prefix
        String numberAfterPrefix = numberToLookup;
        if (matchedPrefix.getCode() != null && !matchedPrefix.getCode().isEmpty() && numberToLookup.startsWith(matchedPrefix.getCode())) {
            numberAfterPrefix = numberToLookup.substring(matchedPrefix.getCode().length());
        }
        
        List<Indicator> indicators = lookupService.findIndicatorsByNumberAndType(numberAfterPrefix, matchedPrefix.getTelephoneTypeId(), originCountryId);
        Indicator matchedIndicator = null;
        if (!indicators.isEmpty()) {
            // PHP logic for selecting the best indicator (e.g. based on series specificity)
            // For now, take the first one (longest NDC match)
            matchedIndicator = indicators.get(0);
            callRecord.setIndicator(matchedIndicator);
            callRecord.setIndicatorId(matchedIndicator.getId());
        } else if (Long.valueOf(TelephonyTypeConstants.LOCAL).equals(matchedPrefix.getTelephoneTypeId())) {
            // If local and no specific indicator found, use the commLocation's indicator
            callRecord.setIndicator(commLocation.getIndicator());
            callRecord.setIndicatorId(commLocation.getIndicatorId());
            matchedIndicator = commLocation.getIndicator();
        } else {
            log.warn("No indicator found for number part: {} and type: {}", numberAfterPrefix, matchedPrefix.getTelephoneTypeId());
            // Keep callRecord.telephonyTypeId as ERRORES or a more specific error type
            return;
        }

        // Pricing logic (simplified)
        BigDecimal baseValue = matchedPrefix.getBaseValue();
        boolean vatIncludedInBase = matchedPrefix.isVatIncluded();
        BigDecimal vatRate = matchedPrefix.getVatValue() != null ? matchedPrefix.getVatValue() : BigDecimal.ZERO; // %
        
        BigDecimal callValue = baseValue;

        if (matchedPrefix.isBandOk() && matchedIndicator != null) {
            List<Band> bands = lookupService.findBandsForPrefixAndIndicator(matchedPrefix.getId(), commLocation.getIndicatorId());
            if (!bands.isEmpty()) {
                Band matchedBand = bands.get(0); // Assuming first is best match (e.g. most specific origin)
                callValue = matchedBand.getValue();
                vatIncludedInBase = matchedBand.getVatIncluded();
                log.debug("Using band value: {}", matchedBand.getName());
            }
        }
        
        // Apply SpecialRateValue if any
        if (matchedIndicator != null) {
            List<SpecialRateValue> specialRates = lookupService.findSpecialRateValues(
                callRecord.getServiceDate(),
                callRecord.getTelephonyTypeId(),
                callRecord.getOperatorId(),
                (callValue == baseValue ? null : callRecord.getIndicator().getTelephonyType().getCallCategory().getId()), // This is a guess for bandId logic
                commLocation.getIndicatorId()
            );
            if (!specialRates.isEmpty()) {
                SpecialRateValue sr = specialRates.get(0); // Assuming most specific applies
                if (sr.getValueType() != null && sr.getValueType() == 1) { // Percentage
                    BigDecimal discountPercentage = sr.getRateValue().divide(BigDecimal.valueOf(100));
                    BigDecimal currentRateNoVat = callValue;
                    if (vatIncludedInBase) {
                        currentRateNoVat = callValue.divide(BigDecimal.ONE.add(vatRate.divide(BigDecimal.valueOf(100))), 4, RoundingMode.HALF_UP);
                    }
                    callValue = currentRateNoVat.multiply(BigDecimal.ONE.subtract(discountPercentage));
                } else { // Absolute value
                    callValue = sr.getRateValue();
                }
                vatIncludedInBase = sr.getIncludesVat();
                log.debug("Applied special rate: {}", sr.getName());
            }
        }

        // Calculate final billed amount
        BigDecimal pricePerMinute = callValue;
        if (!vatIncludedInBase && vatRate.compareTo(BigDecimal.ZERO) > 0) {
            pricePerMinute = callValue.multiply(BigDecimal.ONE.add(vatRate.divide(BigDecimal.valueOf(100))));
        }
        callRecord.setPricePerMinute(pricePerMinute.setScale(4, RoundingMode.HALF_UP));

        int durationInSeconds = callRecord.getDuration() != null ? callRecord.getDuration() : 0;
        // PHP Duracion_Minuto rounds up to the next minute unless ensegundos is true
        long billedMinutes = (long) Math.ceil((double) durationInSeconds / 60.0);
        if (billedMinutes == 0 && durationInSeconds > 0) billedMinutes = 1; // Min 1 minute billing

        callRecord.setBilledAmount(pricePerMinute.multiply(BigDecimal.valueOf(billedMinutes)).setScale(2, RoundingMode.HALF_UP));
        callRecord.setInitialPrice(callRecord.getPricePerMinute()); // Simplified, PHP might have different initial price logic

        // Trunk rule application (simplified - PHP's Calcular_Valor_Reglas)
        if (callRecord.getTrunk() != null && !callRecord.getTrunk().isEmpty() && matchedIndicator != null) {
            Optional<Trunk> trunkOpt = lookupService.findTrunkByNameAndCommLocation(callRecord.getTrunk(), commLocation.getId());
            if (trunkOpt.isPresent()) {
                List<TrunkRule> trunkRules = lookupService.findTrunkRules(
                    trunkOpt.get().getId(),
                    callRecord.getTelephonyTypeId(),
                    String.valueOf(matchedIndicator.getId()), // Assuming single indicator ID for matching
                    commLocation.getIndicatorId()
                );
                if (!trunkRules.isEmpty()) {
                    TrunkRule appliedRule = trunkRules.get(0); // Most specific rule
                    // Apply rule: change operator, telephony type, rate
                    log.debug("Applying trunk rule: {}", appliedRule.getId());
                    // Re-calculate pricing based on rule (this is a simplification)
                    BigDecimal ruleRate = appliedRule.getRateValue();
                    if (appliedRule.getIncludesVat() != null && !appliedRule.getIncludesVat() && vatRate.compareTo(BigDecimal.ZERO) > 0) {
                         ruleRate = ruleRate.multiply(BigDecimal.ONE.add(vatRate.divide(BigDecimal.valueOf(100))));
                    }
                    callRecord.setPricePerMinute(ruleRate.setScale(4, RoundingMode.HALF_UP));
                    callRecord.setBilledAmount(ruleRate.multiply(BigDecimal.valueOf(billedMinutes)).setScale(2, RoundingMode.HALF_UP));

                    if (appliedRule.getNewTelephonyTypeId() != null) {
                        lookupService.findTelephonyTypeById(appliedRule.getNewTelephonyTypeId()).ifPresent(tt -> {
                            callRecord.setTelephonyType(tt);
                            callRecord.setTelephonyTypeId(tt.getId());
                        });
                    }
                     if (appliedRule.getNewOperatorId() != null) {
                        lookupService.findOperatorById(appliedRule.getNewOperatorId()).ifPresent(op -> {
                            callRecord.setOperator(op);
                            callRecord.setOperatorId(op.getId());
                        });
                    }
                }
            }
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
            if (currentNumber.length() < rule.getMinLength()) continue;

            // Search Pattern
            Pattern searchPattern = Pattern.compile("^" + rule.getSearchPattern()); // Anchor at the beginning
            Matcher matcher = searchPattern.matcher(currentNumber);

            if (matcher.find()) {
                // Ignore Pattern
                boolean ignore = false;
                if (rule.getIgnorePattern() != null && !rule.getIgnorePattern().isEmpty()) {
                    String[] ignorePatterns = rule.getIgnorePattern().split(","); // Assuming comma-separated
                    for (String ignorePatStr : ignorePatterns) {
                        Pattern ignorePat = Pattern.compile(ignorePatStr.trim());
                        if (ignorePat.matcher(currentNumber).find()) {
                            ignore = true;
                            break;
                        }
                    }
                }
                if (ignore) continue;

                // Apply Replacement
                // Regex replace might be needed if search_pattern uses groups for replacement.
                // Simple prefix replacement:
                currentNumber = rule.getReplacement() + currentNumber.substring(rule.getSearchPattern().length());
                log.debug("Applied PBX rule '{}': {} -> {}", rule.getName(), dialedNumber, currentNumber);
                // PHP logic might imply only one rule applies, or they chain. Assuming first match.
                return currentNumber;
            }
        }
        return currentNumber; // Return original or modified number
    }
}