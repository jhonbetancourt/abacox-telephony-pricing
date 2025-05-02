// FILE: cdr/CdrEnrichmentService.java
package com.infomedia.abacox.telephonypricing.cdr;

import com.infomedia.abacox.telephonypricing.entity.*;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import lombok.extern.log4j.Log4j2;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Log4j2
public class CdrEnrichmentService {

    private final EmployeeLookupService employeeLookupService;
    private final PrefixLookupService prefixLookupService;
    private final IndicatorLookupService indicatorLookupService;
    private final BandLookupService bandLookupService;
    private final TrunkLookupService trunkLookupService;
    private final SpecialLookupService specialLookupService;
    private final PbxRuleLookupService pbxRuleLookupService;
    private final ConfigLookupService configLookupService;
    private final EntityLookupService entityLookupService;
    private final RateCalculationService rateCalculationService;

    private final CdrProcessingConfig configService; // Keep for config values

    private static final Long COLOMBIA_ORIGIN_COUNTRY_ID = 1L;
    private static final String ASSUMED_SUFFIX = " (Assumed)";

    public Optional<CallRecord> enrichCdr(StandardizedCallEventDto standardDto, CommunicationLocation commLocation) {
        log.info("Enriching Standardized CDR: {}, Hash: {}", standardDto.getGlobalCallId(), standardDto.getCdrHash());

        CallRecord.CallRecordBuilder callBuilder = CallRecord.builder();
        // --- Populate Builder from Standardized DTO ---
        callBuilder.commLocationId(commLocation.getId());
        callBuilder.commLocation(commLocation);
        callBuilder.serviceDate(standardDto.getCallStartTime());
        callBuilder.employeeExtension(standardDto.getCallingPartyNumber());
        callBuilder.employeeAuthCode(standardDto.getAuthCode());
        callBuilder.destinationPhone(standardDto.getCalledPartyNumber());
        callBuilder.dial(standardDto.getCalledPartyNumber()); // Initial value, may be updated
        callBuilder.duration(standardDto.getDurationSeconds());
        callBuilder.ringCount(standardDto.getRingDurationSeconds());
        callBuilder.isIncoming(standardDto.isIncoming());
        callBuilder.trunk(standardDto.getDestinationTrunkIdentifier());
        callBuilder.initialTrunk(standardDto.getSourceTrunkIdentifier());
        callBuilder.employeeTransfer(standardDto.getRedirectingPartyNumber());
        callBuilder.transferCause(Optional.ofNullable(standardDto.getRedirectReason()).orElse(CallTransferCause.NONE.getValue()));
        callBuilder.assignmentCause(CallAssignmentCause.UNKNOWN.getValue());
        callBuilder.fileInfoId(standardDto.getFileInfoId());
        callBuilder.billedAmount(BigDecimal.ZERO);
        callBuilder.pricePerMinute(BigDecimal.ZERO);
        callBuilder.initialPrice(BigDecimal.ZERO);
        callBuilder.indicatorId(null);
        callBuilder.cdrHash(standardDto.getCdrHash());

        // --- Conference Handling (Simplified) ---
        if (standardDto.isConference()) {
            log.debug("CDR {} identified as conference leg.", standardDto.getGlobalCallId());
            callBuilder.assignmentCause(CallAssignmentCause.CONFERENCE.getValue());

            Optional<Employee> confEmployeeOpt = employeeLookupService.findEmployeeByExtensionOrAuthCode(
                    standardDto.getCallingPartyNumber(),
                    standardDto.getAuthCode(),
                    commLocation.getId()
            );
            confEmployeeOpt.ifPresent(employee -> {
                callBuilder.employeeId(employee.getId());
                callBuilder.employee(employee);
            });
            if (confEmployeeOpt.isEmpty()) {
                log.warn("Conference employee lookup failed for effective caller: {}", standardDto.getCallingPartyNumber());
            }

            callBuilder.telephonyTypeId(CdrProcessingConfig.TIPOTELE_INTERNA_IP);
            callBuilder.operatorId(null);
            callBuilder.indicatorId(null);
            callBuilder.billedAmount(BigDecimal.ZERO);
            callBuilder.pricePerMinute(BigDecimal.ZERO);
            callBuilder.initialPrice(BigDecimal.ZERO);

            linkAssociatedEntities(callBuilder);
            CallRecord finalRecord = callBuilder.build();
            log.info("Processed conference CDR {}: Type={}, Billed=0", standardDto.getGlobalCallId(), finalRecord.getTelephonyTypeId());
            return Optional.of(finalRecord);
        }

        // --- Regular Call Processing ---

        // --- Employee Lookup ---
        Optional<Employee> employeeOpt = employeeLookupService.findEmployeeByExtensionOrAuthCode(
                standardDto.getCallingPartyNumber(),
                standardDto.getAuthCode(),
                commLocation.getId()
        );
        employeeOpt.ifPresent(employee -> {
            callBuilder.employeeId(employee.getId());
            callBuilder.employee(employee);
            if (StringUtils.hasText(standardDto.getAuthCode())) {
                callBuilder.assignmentCause(CallAssignmentCause.AUTH_CODE.getValue());
            } else {
                callBuilder.assignmentCause(CallAssignmentCause.EXTENSION.getValue());
            }
        });
        if (employeeOpt.isEmpty()) {
            log.warn("Employee lookup failed for CDR {} (Effective Caller: {}, Code: {})",
                    standardDto.getGlobalCallId(), standardDto.getCallingPartyNumber(), standardDto.getAuthCode());
            callBuilder.assignmentCause(CallAssignmentCause.UNKNOWN.getValue());
        }

        // --- Determine Call Type and Enrich ---
        try {
            if (standardDto.isIncoming()) {
                processIncomingCall(standardDto, commLocation, callBuilder, standardDto.getCallingPartyNumber());
            } else {
                processOutgoingCallRecursive(standardDto, commLocation, callBuilder,
                        standardDto.getCallingPartyNumber(), standardDto.getCalledPartyNumber(), 0);
            }
        } catch (Exception e) {
            log.error("Error during enrichment for CDR {}: {}", standardDto.getGlobalCallId(), e.getMessage(), e);
            callBuilder.telephonyTypeId(CdrProcessingConfig.TIPOTELE_ERRORES);
            callBuilder.billedAmount(BigDecimal.ZERO);
            callBuilder.pricePerMinute(BigDecimal.ZERO);
            callBuilder.initialPrice(BigDecimal.ZERO);
            callBuilder.indicatorId(null);
        }

        // --- Final Adjustments ---
        CallRecord tempRecord = callBuilder.build();
        if (tempRecord.getDuration() != null && tempRecord.getDuration() <= 0
                && tempRecord.getTelephonyTypeId() != null
                && tempRecord.getTelephonyTypeId() != CdrProcessingConfig.TIPOTELE_ERRORES) {
            log.debug("Call duration is zero or less, setting TelephonyType to SINCONSUMO ({})", CdrProcessingConfig.TIPOTELE_SINCONSUMO);
            callBuilder.telephonyTypeId(CdrProcessingConfig.TIPOTELE_SINCONSUMO);
            callBuilder.billedAmount(BigDecimal.ZERO);
            callBuilder.pricePerMinute(BigDecimal.ZERO);
            callBuilder.initialPrice(BigDecimal.ZERO);
        }

        linkAssociatedEntities(callBuilder);
        CallRecord finalRecord = callBuilder.build();

        log.info("Successfully enriched CDR {}: Type={}, Billed={}",
                standardDto.getGlobalCallId(), finalRecord.getTelephonyTypeId(), finalRecord.getBilledAmount());
        return Optional.of(finalRecord);
    }

    // ========================================================================
    // Recursive Outgoing Call Processing
    // ========================================================================
    private void processOutgoingCallRecursive(StandardizedCallEventDto standardDto, CommunicationLocation commLocation, CallRecord.CallRecordBuilder callBuilder, String effectiveCallingNumber, String effectiveDialedNumber, int depth) {
        log.debug("Processing outgoing call (Depth: {}) for CDR {} (effective: {} -> {})", depth, standardDto.getGlobalCallId(), effectiveCallingNumber, effectiveDialedNumber);
        final int MAX_RECURSION_DEPTH = 5;

        if (depth > MAX_RECURSION_DEPTH) {
            log.error("Max recursion depth ({}) reached for CDR {}. Aborting processing for this call.", MAX_RECURSION_DEPTH, standardDto.getGlobalCallId());
            callBuilder.telephonyTypeId(CdrProcessingConfig.TIPOTELE_ERRORES);
            return;
        }

        List<String> pbxPrefixes = configService.getPbxPrefixes(commLocation.getId());
        Long originCountryId = configLookupService.getOriginCountryIdFromCommLocation(commLocation);
        Long indicatorIdForSpecial = commLocation.getIndicatorId();

        // --- 1. Check Special Service ---
        // PHP's procesaServespecial uses the number *after* cleaning but *before* PBX prefix removal
        String numberForSpecialCheck = cleanNumber(effectiveDialedNumber, Collections.emptyList(), false);
        if (indicatorIdForSpecial != null && originCountryId != null) {
            Optional<SpecialService> specialServiceOpt = specialLookupService.findSpecialService(numberForSpecialCheck, indicatorIdForSpecial, originCountryId);
            if (specialServiceOpt.isPresent()) {
                SpecialService specialService = specialServiceOpt.get();
                log.debug("Call matches Special Service: {}", specialService.getId());
                rateCalculationService.applySpecialServicePricing(specialService, callBuilder, standardDto.getDurationSeconds());
                callBuilder.dial(numberForSpecialCheck); // Use the number matched against special service
                return;
            }
        }

        // --- 2. Check if Internal ---
        // PHP's es_llamada_interna checks the number *after* PBX prefix removal (if applicable)
        boolean shouldRemovePrefixForInternal = getPrefixLength(effectiveDialedNumber, pbxPrefixes) > 0;
        String cleanedNumberForInternalCheck = cleanNumber(effectiveDialedNumber, pbxPrefixes, shouldRemovePrefixForInternal);
        CdrProcessingConfig.ExtensionLengthConfig extConfig = configService.getExtensionLengthConfig(commLocation.getId());
        if (isInternalCall(effectiveCallingNumber, cleanedNumberForInternalCheck, extConfig)) {
            log.debug("Processing as internal call (effective: {} -> cleaned: {})", effectiveCallingNumber, cleanedNumberForInternalCheck);
            processInternalCall(standardDto, commLocation, callBuilder, cleanedNumberForInternalCheck, extConfig);
            return;
        }

        // --- 3. Check PBX Rule ---
        // PBX rules operate on the *original* effectiveDialedNumber
        Optional<PbxSpecialRule> pbxRuleOpt = pbxRuleLookupService.findPbxSpecialRule(
                effectiveDialedNumber, commLocation.getId(), CallDirection.OUTGOING.getValue()
        );
        String numberAfterPbxRule = effectiveDialedNumber;
        boolean ruleApplied = false;
        if (pbxRuleOpt.isPresent()) {
            PbxSpecialRule rule = pbxRuleOpt.get();
            String replacement = rule.getReplacement() != null ? rule.getReplacement() : "";
            String searchPattern = rule.getSearchPattern();
            if (StringUtils.hasText(searchPattern) && effectiveDialedNumber.startsWith(searchPattern)) {
                String numberAfterSearch = effectiveDialedNumber.substring(searchPattern.length());
                numberAfterPbxRule = replacement + numberAfterSearch;
                if (!numberAfterPbxRule.equals(effectiveDialedNumber)) {
                    log.debug("Applied OUTGOING PBX rule {}, number changed from {} to {}", rule.getId(), effectiveDialedNumber, numberAfterPbxRule);
                    ruleApplied = true;
                } else {
                     log.trace("PBX rule {} matched but resulted in no change to number {}", rule.getId(), effectiveDialedNumber);
                }
            }
        }

        // --- 4. Recursive Call if PBX Rule Applied ---
        if (ruleApplied) {
            log.debug("Re-evaluating call processing with number modified by PBX rule: {}", numberAfterPbxRule);
            processOutgoingCallRecursive(standardDto, commLocation, callBuilder, effectiveCallingNumber, numberAfterPbxRule, depth + 1);
            return;
        }

        // --- 5. Evaluate Destination and Rate (External Call) ---
        // Preprocessing happens *after* PBX rules, on the potentially modified number
        String preprocessedNumber = preprocessNumberForLookup(numberAfterPbxRule, originCountryId);
        FieldWrapper<Long> forcedTelephonyType = new FieldWrapper<>(null);
        if (!preprocessedNumber.equals(numberAfterPbxRule)) {
            log.debug("Number preprocessed for lookup: {} -> {}", numberAfterPbxRule, preprocessedNumber);
            // Set forced type based on preprocessed number characteristics (Colombian logic)
            if (preprocessedNumber.startsWith("03") && preprocessedNumber.length() == 12) {
                forcedTelephonyType.setValue(CdrProcessingConfig.TIPOTELE_CELULAR);
            } else if (preprocessedNumber.matches("^\\d{7,8}$")) { // 7 or 8 digits after removing 60 prefix
                forcedTelephonyType.setValue(CdrProcessingConfig.TIPOTELE_LOCAL);
            }
        }
        log.debug("Processing as external outgoing call (effective: {} -> preprocessed: {})", effectiveCallingNumber, preprocessedNumber);
        evaluateDestinationAndRate(standardDto, commLocation, callBuilder, preprocessedNumber, pbxPrefixes, forcedTelephonyType.getValue());
    }


    // ========================================================================
    // Incoming Call Processing
    // ========================================================================
    private void processIncomingCall(StandardizedCallEventDto standardDto, CommunicationLocation commLocation, CallRecord.CallRecordBuilder callBuilder, String effectiveCallingNumber) {
        log.debug("Processing incoming call for CDR {} (effective caller: {})", standardDto.getGlobalCallId(), effectiveCallingNumber);

        String originalIncomingNumber = effectiveCallingNumber;
        Long originCountryId = configLookupService.getOriginCountryIdFromCommLocation(commLocation);
        Long commIndicatorId = commLocation.getIndicatorId();
        List<String> pbxPrefixes = configService.getPbxPrefixes(commLocation.getId());

        // --- Apply PBX Rule ---
        Optional<PbxSpecialRule> pbxRuleOpt = pbxRuleLookupService.findPbxSpecialRule(
                originalIncomingNumber, commLocation.getId(), CallDirection.INCOMING.getValue()
        );
        String numberAfterPbxRule = originalIncomingNumber;
        if (pbxRuleOpt.isPresent()) {
             PbxSpecialRule rule = pbxRuleOpt.get();
            String replacement = rule.getReplacement() != null ? rule.getReplacement() : "";
            String searchPattern = rule.getSearchPattern();
            if (StringUtils.hasText(searchPattern) && originalIncomingNumber.startsWith(searchPattern)) {
                String numberAfterSearch = originalIncomingNumber.substring(searchPattern.length());
                numberAfterPbxRule = replacement + numberAfterSearch;
                log.debug("Applied INCOMING PBX rule {} to incoming number, result: {}", rule.getId(), numberAfterPbxRule);
            }
        }

        // --- Preprocess ---
        String preprocessedNumber = preprocessNumberForLookup(numberAfterPbxRule, originCountryId);
        if (!preprocessedNumber.equals(numberAfterPbxRule)) {
            log.debug("Incoming number preprocessed for lookup: {} -> {}", numberAfterPbxRule, preprocessedNumber);
        }

        // --- Clean Number (PHP logic in buscarOrigen checks number *with* potential PBX prefix if present) ---
        String numberForLookup = cleanNumber(preprocessedNumber, Collections.emptyList(), false); // Clean but don't remove PBX prefix yet
        log.trace("Incoming call number for origin lookup: {}", numberForLookup);

        callBuilder.dial(numberForLookup); // Set DIAL field to the number used for lookup

        // --- Origin Lookup ---
        if (originCountryId != null && commIndicatorId != null) {
            Optional<Map<String, Object>> originInfoOpt = findIncomingOrigin(numberForLookup, originCountryId, commIndicatorId);

            if (originInfoOpt.isPresent()) {
                Map<String, Object> originInfo = originInfoOpt.get();
                Long telephonyTypeId = (Long) originInfo.get("telephony_type_id");
                Long operatorId = (Long) originInfo.get("operator_id");
                Long indicatorId = (Long) originInfo.get("indicator_id");

                callBuilder.telephonyTypeId(telephonyTypeId);
                callBuilder.operatorId(operatorId);
                callBuilder.indicatorId(indicatorId);

                log.debug("Incoming call classified as Type ID: {}, Operator ID: {}, Origin Indicator ID: {}",
                        telephonyTypeId, operatorId, indicatorId);
            } else {
                log.warn("Could not classify incoming call origin for {}, assuming LOCAL", numberForLookup);
                callBuilder.telephonyTypeId(CdrProcessingConfig.TIPOTELE_LOCAL);
                callBuilder.indicatorId(commIndicatorId);
                configService.getOperatorInternal(CdrProcessingConfig.TIPOTELE_LOCAL, originCountryId)
                        .ifPresent(op -> callBuilder.operatorId(op.getId()));
            }
        } else {
            log.warn("Missing Origin Country or Indicator for CommunicationLocation {}, cannot classify incoming call origin.", commLocation.getId());
            callBuilder.telephonyTypeId(CdrProcessingConfig.TIPOTELE_ERRORES);
            callBuilder.indicatorId(null);
        }

        // Incoming calls zero cost
        callBuilder.billedAmount(BigDecimal.ZERO);
        callBuilder.pricePerMinute(BigDecimal.ZERO);
        callBuilder.initialPrice(BigDecimal.ZERO);
    }

    // ========================================================================
    // Internal Call Processing
    // ========================================================================
    private void processInternalCall(StandardizedCallEventDto standardDto, CommunicationLocation commLocation, CallRecord.CallRecordBuilder callBuilder, String destinationExtension, CdrProcessingConfig.ExtensionLengthConfig extConfig) {
        String sourceExtension = standardDto.getCallingPartyNumber();
        log.debug("Processing internal call from {} to {}", sourceExtension, destinationExtension);

        // Find source/destination employees
        Optional<Employee> sourceEmployeeOpt = Optional.ofNullable(callBuilder.build().getEmployee());
        Optional<Employee> destEmployeeOpt = employeeLookupService.findEmployeeByExtensionOrAuthCode(destinationExtension, null, commLocation.getId());

        // Get location info for comparison
        LocationInfo sourceLoc = getLocationInfo(sourceEmployeeOpt.orElse(null), commLocation);
        LocationInfo destLoc = getLocationInfo(destEmployeeOpt.orElse(null), commLocation);

        Long internalCallTypeId = null;
        Long operatorId = null;
        Long indicatorId = null;
        String assumedInfo = "";

        if (destEmployeeOpt.isPresent()) {
            callBuilder.destinationEmployeeId(destEmployeeOpt.get().getId());
            callBuilder.destinationEmployee(destEmployeeOpt.get());
            indicatorId = destLoc.indicatorId; // Use destination's indicator

            // Determine type based on location comparison
            if (sourceLoc.originCountryId != null && destLoc.originCountryId != null && !sourceLoc.originCountryId.equals(destLoc.originCountryId)) {
                internalCallTypeId = CdrProcessingConfig.TIPOTELE_INTERNACIONAL_IP;
            } else if (sourceLoc.indicatorId != null && destLoc.indicatorId != null && !sourceLoc.indicatorId.equals(destLoc.indicatorId)) {
                internalCallTypeId = CdrProcessingConfig.TIPOTELE_NACIONAL_IP;
            } else if (sourceLoc.officeId != null && destLoc.officeId != null && !sourceLoc.officeId.equals(destLoc.officeId)) {
                internalCallTypeId = CdrProcessingConfig.TIPOTELE_LOCAL_IP;
            } else {
                internalCallTypeId = CdrProcessingConfig.TIPOTELE_INTERNA_IP;
                if (sourceEmployeeOpt.isEmpty()) {
                    assumedInfo = ASSUMED_SUFFIX + "/Origin";
                }
            }
            log.debug("Internal call type determined by location comparison: {}", internalCallTypeId);

        } else {
            log.warn("Internal call destination extension {} not found as employee. Checking internal prefixes.", destinationExtension);
            // PHP: Checks internal prefixes if destination employee not found
            Optional<Map<String, Object>> internalPrefixOpt = prefixLookupService.findInternalPrefixMatch(destinationExtension, sourceLoc.originCountryId);
            if (internalPrefixOpt.isPresent()) {
                internalCallTypeId = (Long) internalPrefixOpt.get().get("telephony_type_id");
                operatorId = (Long) internalPrefixOpt.get().get("operator_id");
                // Find indicator based on the matched internal type
                Optional<Map<String, Object>> indInfo = indicatorLookupService.findIndicatorAndSeries(destinationExtension, internalCallTypeId, sourceLoc.originCountryId, sourceLoc.indicatorId, false, null);
                indicatorId = indInfo.map(m -> (Long) m.get("indicator_id")).filter(id -> id != null && id > 0).orElse(sourceLoc.indicatorId);
                log.debug("Destination {} matched internal prefix for type {}, Op {}, Ind {}", destinationExtension, internalCallTypeId, operatorId, indicatorId);
                assumedInfo = ASSUMED_SUFFIX + "/Prefix";
            } else {
                // PHP: Falls back to default internal type if no employee or prefix found
                internalCallTypeId = CdrProcessingConfig.getDefaultInternalCallTypeId();
                indicatorId = sourceLoc.indicatorId; // Use source indicator
                log.debug("Destination {} not found and no internal prefix matched, defaulting to type {}, Ind {}", destinationExtension, internalCallTypeId, indicatorId);
                // PHP: Sets assignment cause to RANGOS if destination employee not found
                callBuilder.assignmentCause(CallAssignmentCause.RANGES.getValue());
                assumedInfo = ASSUMED_SUFFIX;
            }
        }

        callBuilder.telephonyTypeId(internalCallTypeId);
        callBuilder.indicatorId(indicatorId != null && indicatorId > 0 ? indicatorId : null);

        // Set operator (usually internal operator for the type)
        if (operatorId == null) {
            configService.getOperatorInternal(internalCallTypeId, sourceLoc.originCountryId)
                    .ifPresent(op -> callBuilder.operatorId(op.getId()));
        } else {
            callBuilder.operatorId(operatorId);
        }

        // Apply internal pricing
        rateCalculationService.applyInternalPricing(internalCallTypeId, callBuilder, standardDto.getDurationSeconds());

        if (!assumedInfo.isEmpty()) {
            log.debug("Internal call classification included assumption: {}", assumedInfo);
        }
    }

    // ========================================================================
    // evaluateDestinationAndRate
    // ========================================================================
    private void evaluateDestinationAndRate(StandardizedCallEventDto standardDto, CommunicationLocation commLocation, CallRecord.CallRecordBuilder callBuilder, String initialNumberToLookup, List<String> pbxPrefixes, Long forcedTelephonyTypeId) {
        Optional<Trunk> trunkOpt = trunkLookupService.findTrunkByCode(standardDto.getDestinationTrunkIdentifier(), commLocation.getId());
        boolean usesTrunk = trunkOpt.isPresent();
        Long originIndicatorId = commLocation.getIndicatorId();
        Long originCountryId = configLookupService.getOriginCountryIdFromCommLocation(commLocation);

        Optional<Map<String, Object>> finalRateInfoOpt = attemptRateLookup(standardDto, commLocation, callBuilder, initialNumberToLookup, pbxPrefixes, trunkOpt, forcedTelephonyTypeId);
        boolean lookupFailed = finalRateInfoOpt.isEmpty();

        // PHP: Fallback logic if trunk lookup fails (PHP: $existe_troncal !== false && evaluarDestino_novalido($infovalor))
        if (lookupFailed && usesTrunk) {
            log.warn("Initial rate lookup failed for trunk call {}, attempting fallback (no trunk info)", standardDto.getGlobalCallId());
            // Retry without trunk information
            finalRateInfoOpt = attemptRateLookup(standardDto, commLocation, callBuilder, initialNumberToLookup, pbxPrefixes, Optional.empty(), forcedTelephonyTypeId);

            if (finalRateInfoOpt.isPresent()) {
                Map<String, Object> fallbackRateInfo = finalRateInfoOpt.get();
                CallRecord tempRecord = callBuilder.build(); // Get state *before* fallback attempt
                Long originalType = tempRecord.getTelephonyTypeId();
                Long fallbackType = (Long) fallbackRateInfo.get("telephony_type_id");
                Long originalIndicator = tempRecord.getIndicatorId();
                Long fallbackIndicator = (Long) fallbackRateInfo.get("indicator_id");

                // PHP: Only use fallback if type or indicator differs and fallback is valid
                if ((originalType == null || !originalType.equals(fallbackType) || originalIndicator == null || !originalIndicator.equals(fallbackIndicator))
                        && fallbackType != null && fallbackType > 0 && fallbackType != CdrProcessingConfig.TIPOTELE_ERRORES) {
                    log.info("Using fallback rate info for trunk call {} as it differs from initial failed attempt.", standardDto.getGlobalCallId());
                    lookupFailed = false; // Fallback succeeded and is different
                } else {
                    log.warn("Fallback rate info for trunk call {} is same as failed attempt or invalid, discarding fallback.", standardDto.getGlobalCallId());
                    finalRateInfoOpt = Optional.empty(); // Discard fallback result
                    lookupFailed = true; // Remain in failed state
                }
            }
        }

        if (finalRateInfoOpt.isPresent() && !lookupFailed) {
            Map<String, Object> finalRateInfo = finalRateInfoOpt.get();
            // Update builder with final determined values
            callBuilder.telephonyTypeId((Long) finalRateInfo.get("telephony_type_id"));
            callBuilder.operatorId((Long) finalRateInfo.get("operator_id"));
            callBuilder.indicatorId((Long) finalRateInfo.get("indicator_id"));
            callBuilder.dial((String) finalRateInfo.getOrDefault("effective_number", initialNumberToLookup));

            // Check if trunk pricing was applied during the attempt
            boolean appliedTrunkPricing = finalRateInfo.containsKey("applied_trunk_pricing") && (Boolean) finalRateInfo.get("applied_trunk_pricing");

            // Apply final rate calculation
            if (appliedTrunkPricing && usesTrunk) {
                // If trunk pricing was determined, apply it (includes rules)
                rateCalculationService.applyTrunkPricing(trunkOpt.get(), finalRateInfo, standardDto.getDurationSeconds(), originIndicatorId, originCountryId, standardDto.getCallStartTime(), callBuilder);
            } else {
                // Otherwise, apply base/band/special pricing
                rateCalculationService.applySpecialPricing(finalRateInfo, standardDto.getCallStartTime(), standardDto.getDurationSeconds(), originIndicatorId, callBuilder);
            }
        } else {
            log.error("Could not determine rate for number: {} (effective: {}) after fallback.", standardDto.getCalledPartyNumber(), initialNumberToLookup);
            callBuilder.telephonyTypeId(CdrProcessingConfig.TIPOTELE_ERRORES);
            callBuilder.dial(initialNumberToLookup);
            callBuilder.indicatorId(null);
            callBuilder.billedAmount(BigDecimal.ZERO);
            callBuilder.pricePerMinute(BigDecimal.ZERO);
            callBuilder.initialPrice(BigDecimal.ZERO);
        }
    }

    // ========================================================================
    // attemptRateLookup
    // ========================================================================
    private Optional<Map<String, Object>> attemptRateLookup(StandardizedCallEventDto standardDto, CommunicationLocation commLocation, CallRecord.CallRecordBuilder callBuilder, String initialNumberToLookup, List<String> pbxPrefixes, Optional<Trunk> trunkOpt, Long forcedTelephonyTypeId) {
        Long originCountryId = configLookupService.getOriginCountryIdFromCommLocation(commLocation);
        Long originIndicatorId = commLocation.getIndicatorId();
        boolean usesTrunk = trunkOpt.isPresent();
        Trunk trunk = trunkOpt.orElse(null);

        if (originCountryId == null || originIndicatorId == null) {
            log.error("Cannot attempt rate lookup: Missing Origin Country ({}) or Indicator ID ({}) for Location {}", originCountryId, originIndicatorId, commLocation.getId());
            return Optional.empty();
        }

        // Determine the effective number based on trunk settings (PHP: $existe_troncal['noprefijopbx'])
        String effectiveNumber = initialNumberToLookup;
        boolean removePrefixForLookup = true; // Default: remove prefix if found
        if (usesTrunk) {
            if (trunk.getNoPbxPrefix() != null && trunk.getNoPbxPrefix()) {
                // Trunk ignores PBX prefix, use the number *before* PBX prefix removal
                effectiveNumber = cleanNumber(standardDto.getCalledPartyNumber(), Collections.emptyList(), false);
                log.trace("Attempting lookup (Trunk {} ignores PBX prefix): {}", trunk.getId(), effectiveNumber);
                removePrefixForLookup = false; // Don't remove prefix later
            } else {
                // Trunk uses PBX prefix, use the number *after* potential PBX prefix removal
                effectiveNumber = cleanNumber(initialNumberToLookup, pbxPrefixes, true);
                log.trace("Attempting lookup (Trunk {} uses PBX prefix, effective number): {}", trunk.getId(), effectiveNumber);
                removePrefixForLookup = true; // Allow prefix removal later if needed
            }
        } else {
            // No trunk, use number after potential PBX prefix removal
            effectiveNumber = cleanNumber(initialNumberToLookup, pbxPrefixes, true);
            log.trace("Attempting lookup (No trunk, effective number): {}", effectiveNumber);
            removePrefixForLookup = true;
        }


        List<Map<String, Object>> prefixes = prefixLookupService.findPrefixesByNumber(effectiveNumber, originCountryId);

        // Filter by forced type if provided
        if (forcedTelephonyTypeId != null) {
            prefixes = prefixes.stream()
                    .filter(p -> forcedTelephonyTypeId.equals(p.get("telephony_type_id")))
                    .collect(Collectors.toList());
             if (!prefixes.isEmpty()) {
                log.debug("Lookup filtered to forced TelephonyType ID: {}", forcedTelephonyTypeId);
            } else {
                log.warn("Forced TelephonyType ID {} has no matching prefixes for number {}", forcedTelephonyTypeId, effectiveNumber);
                 // If forced type has no prefixes, we cannot proceed with this forced type.
                 // PHP logic would likely continue without the forced type, so we clear it.
                 // If the requirement is to *only* use the forced type, return empty here.
                 // For now, let's clear the forced type and allow other prefixes.
                 // forcedTelephonyTypeId = null; // Re-enable if fallback to other types is desired
                 return Optional.empty(); // Strict: if forced type has no prefix, fail the lookup.
            }
        }

        Map<String, Object> assumedRateInfo = null;
        boolean allPrefixesConsistent = !prefixes.isEmpty();
        Long firstOperatorId = null;
        Long firstTelephonyTypeId = null;

        for (Map<String, Object> prefixInfo : prefixes) {
            Long currentTelephonyTypeId = (Long) prefixInfo.get("telephony_type_id");
            Long currentOperatorId = (Long) prefixInfo.get("operator_id");
            String currentPrefixCode = (String) prefixInfo.get("prefix_code");
            Long currentPrefixId = (Long) prefixInfo.get("prefix_id");
            int typeMinLength = (Integer) prefixInfo.get("telephony_type_min");
            int typeMaxLength = (Integer) prefixInfo.get("telephony_type_max");
            boolean bandOk = (Boolean) prefixInfo.get("prefix_band_ok");

            // Check for consistency (PHP: $todosok logic)
            if (firstOperatorId == null) {
                firstOperatorId = currentOperatorId;
                firstTelephonyTypeId = currentTelephonyTypeId;
            } else if (!firstOperatorId.equals(currentOperatorId) || !firstTelephonyTypeId.equals(currentTelephonyTypeId)) {
                allPrefixesConsistent = false;
            }

            String numberWithoutPrefix = effectiveNumber;
            boolean prefixRemoved = false;
            boolean removePrefixNow = removePrefixForLookup; // Start with the default based on trunk setting

            // Check if TrunkRate overrides prefix removal for indicator lookup (PHP: $existe_troncal['operador_destino'][$operador_troncal][$tipotele_id]['noprefijo'])
            if (usesTrunk && StringUtils.hasText(currentPrefixCode) && effectiveNumber.startsWith(currentPrefixCode)) {
                Long operatorTroncal = findEffectiveTrunkOperator(trunk, currentTelephonyTypeId, currentPrefixCode, currentOperatorId);
                if (operatorTroncal != null && operatorTroncal >= 0) {
                    Optional<TrunkRate> trOpt = trunkLookupService.findTrunkRate(trunk.getId(), operatorTroncal, currentTelephonyTypeId);
                    if (trOpt.isPresent() && trOpt.get().getNoPrefix() != null && trOpt.get().getNoPrefix()) {
                        removePrefixNow = false; // TrunkRate says DO NOT remove prefix
                        log.trace("TrunkRate for prefix {} (effective op {}) prevents prefix removal during indicator lookup", currentPrefixCode, operatorTroncal);
                    }
                }
            }

            // Remove prefix if appropriate for this type and allowed
            if (removePrefixNow && StringUtils.hasText(currentPrefixCode) && effectiveNumber.startsWith(currentPrefixCode) &&
                !CdrProcessingConfig.isLocalTelephonyType(currentTelephonyTypeId)) { // Don't remove prefix for local types
                numberWithoutPrefix = effectiveNumber.substring(currentPrefixCode.length());
                prefixRemoved = true;
                log.trace("Prefix {} removed for indicator lookup (Type: {}), remaining: {}", currentPrefixCode, currentTelephonyTypeId, numberWithoutPrefix);
            } else if (removePrefixNow) {
                 log.trace("Prefix {} not removed for indicator lookup (Type: {})", currentPrefixCode, currentTelephonyTypeId);
            }

            // Calculate effective length requirements after potential prefix removal
            int prefixLength = prefixRemoved ? (currentPrefixCode != null ? currentPrefixCode.length() : 0) : 0;
            int effectiveMinLength = Math.max(0, typeMinLength - prefixLength);
            int effectiveMaxLength = (typeMaxLength > 0) ? Math.max(0, typeMaxLength - prefixLength) : 0;

            // Check minimum length (PHP: strlen($telefono) >= $tipotelemin)
            if (numberWithoutPrefix.length() < effectiveMinLength) {
                log.trace("Skipping prefix {} - number part {} too short (min {})", currentPrefixCode, numberWithoutPrefix, effectiveMinLength);
                continue;
            }

            // Trim to max length if needed (PHP: substr($telefono, 0, $tipotelemax))
            String numberForIndicatorLookup = numberWithoutPrefix;
            if (effectiveMaxLength > 0 && numberWithoutPrefix.length() > effectiveMaxLength) {
                log.trace("Trimming number part {} to max length {}", numberWithoutPrefix, effectiveMaxLength);
                numberForIndicatorLookup = numberWithoutPrefix.substring(0, effectiveMaxLength);
            }

            // Find matching indicator/series
            Optional<Map<String, Object>> indicatorInfoOpt = indicatorLookupService.findIndicatorAndSeries(
                    numberForIndicatorLookup, currentTelephonyTypeId, originCountryId, originIndicatorId, bandOk, currentPrefixId
            );

            boolean destinationFound = indicatorInfoOpt.isPresent();
            // PHP fallback: if no indicator found but number length matches max length for type
            boolean lengthMatch = (!destinationFound && effectiveMaxLength > 0 && numberForIndicatorLookup.length() == effectiveMaxLength);
            boolean considerMatch = destinationFound || lengthMatch;

            if (considerMatch) {
                Long destinationIndicatorId = indicatorInfoOpt
                        .map(ind -> (Long) ind.get("indicator_id"))
                        .filter(id -> id != null && id > 0)
                        .orElse(null); // Use null if no indicator found (for length match case)
                Integer destinationNdc = indicatorInfoOpt.map(ind -> (Integer) ind.get("series_ndc")).orElse(null);

                Long finalTelephonyTypeId = currentTelephonyTypeId;
                Long finalPrefixId = currentPrefixId;
                boolean finalBandOk = bandOk;
                String typeSuffix = "";

                // Check for Local Extended (PHP: BuscarLocalExtendida)
                if (CdrProcessingConfig.isLocalTelephonyType(currentTelephonyTypeId) && destinationNdc != null) {
                    boolean isExtended = indicatorLookupService.isLocalExtended(destinationNdc, originIndicatorId);
                    if (isExtended) {
                        finalTelephonyTypeId = CdrProcessingConfig.TIPOTELE_LOCAL_EXT;
                        typeSuffix = " (Local Extended)";
                        log.debug("Reclassified call to {} as LOCAL_EXTENDED based on NDC {} and origin {}", effectiveNumber, destinationNdc, originIndicatorId);
                        // Find the prefix associated with LOCAL_EXTENDED type
                        Optional<Operator> localExtOpOpt = configService.getOperatorInternal(finalTelephonyTypeId, originCountryId);
                        if (localExtOpOpt.isPresent()) {
                            Optional<Prefix> localExtPrefixOpt = prefixLookupService.findPrefixByTypeOperatorOrigin(finalTelephonyTypeId, localExtOpOpt.get().getId(), originCountryId);
                            if (localExtPrefixOpt.isPresent()) {
                                finalPrefixId = localExtPrefixOpt.get().getId();
                                finalBandOk = localExtPrefixOpt.get().isBandOk();
                                log.trace("Using Prefix ID {} for LOCAL_EXTENDED rate lookup", finalPrefixId);
                            } else {
                                log.warn("Could not find prefix for LOCAL_EXTENDED type {}, operator {}, country {}. Rate lookup might be incorrect.", finalTelephonyTypeId, localExtOpOpt.get().getId(), originCountryId);
                            }
                        } else {
                            log.warn("Could not find internal operator for LOCAL_EXTENDED type {}. Rate lookup might be incorrect.", finalTelephonyTypeId);
                        }
                    }
                }

                // Find rate info (base or band)
                Optional<Map<String, Object>> rateInfoOpt = findRateInfo(finalPrefixId, destinationIndicatorId, originIndicatorId, finalBandOk);

                if (rateInfoOpt.isPresent()) {
                    Map<String, Object> rateInfo = rateInfoOpt.get();
                    // Populate the map with all necessary info for rate calculation
                    rateInfo.put("telephony_type_id", finalTelephonyTypeId);
                    rateInfo.put("operator_id", currentOperatorId);
                    rateInfo.put("indicator_id", destinationIndicatorId); // Can be null if length match
                    rateInfo.put("telephony_type_name", entityLookupService.findTelephonyTypeById(finalTelephonyTypeId).map(TelephonyType::getName).orElse("Unknown Type") + typeSuffix);
                    rateInfo.put("operator_name", entityLookupService.findOperatorById(currentOperatorId).map(Operator::getName).orElse("Unknown Operator"));
                    rateInfo.put("destination_name", indicatorInfoOpt.map(this::formatDestinationName).orElse(lengthMatch ? "Unknown (Length Match)" : "Unknown Destination"));
                    rateInfo.put("band_name", rateInfo.get("band_name"));
                    rateInfo.put("effective_number", effectiveNumber);
                    rateInfo.put("applied_trunk_pricing", usesTrunk); // Mark if trunk was involved in this attempt
                    rateInfo.put("origin_country_id", originCountryId); // Pass origin country for special pricing

                    log.debug("Attempt successful: Found rate for prefix {}, indicator {}", currentPrefixCode, destinationIndicatorId);
                    return Optional.of(rateInfo); // Return the first successful match based on PHP's prefix order
                } else {
                    log.warn("Rate info not found for prefix {}, indicator {}", currentPrefixCode, destinationIndicatorId);
                    // PHP: Store assumed info if prefixes are consistent
                    if (assumedRateInfo == null && allPrefixesConsistent) {
                        assumedRateInfo = new HashMap<>();
                        assumedRateInfo.put("telephony_type_id", finalTelephonyTypeId);
                        assumedRateInfo.put("operator_id", currentOperatorId);
                        assumedRateInfo.put("indicator_id", null);
                        assumedRateInfo.put("telephony_type_name", entityLookupService.findTelephonyTypeById(finalTelephonyTypeId).map(TelephonyType::getName).orElse("Unknown Type") + ASSUMED_SUFFIX);
                        assumedRateInfo.put("operator_name", entityLookupService.findOperatorById(currentOperatorId).map(Operator::getName).orElse("Unknown Operator"));
                        assumedRateInfo.put("destination_name", "Assumed Destination");
                        assumedRateInfo.put("effective_number", effectiveNumber);
                        assumedRateInfo.put("applied_trunk_pricing", usesTrunk);
                        assumedRateInfo.put("origin_country_id", originCountryId);
                        // Get base rate info for the assumed prefix
                        Optional<Map<String, Object>> baseRateOpt = prefixLookupService.findBaseRateForPrefix(finalPrefixId);
                        baseRateOpt.ifPresent(assumedRateInfo::putAll);
                        log.debug("Storing assumed rate info based on consistent prefix {} (Type: {}, Op: {})", currentPrefixCode, finalTelephonyTypeId, currentOperatorId);
                    }
                }
            } else {
                log.trace("No indicator found and length mismatch for prefix {}, number part {}", currentPrefixCode, numberForIndicatorLookup);
            }
        } // End prefix loop

        // PHP: Fallback to LOCAL if no prefix matched and not using a trunk
        if (prefixes.isEmpty() && !usesTrunk) {
            log.debug("No prefix found for non-trunk call, attempting lookup as LOCAL for {}", effectiveNumber);
            Optional<Map<String, Object>> localRateInfoOpt = findRateInfoForLocal(commLocation, effectiveNumber);
            if (localRateInfoOpt.isPresent()) {
                Map<String, Object> localRateInfo = localRateInfoOpt.get();
                localRateInfo.put("effective_number", effectiveNumber);
                localRateInfo.put("applied_trunk_pricing", false); // Not a trunk call
                return localRateInfoOpt; // Return LOCAL result
            } else {
                log.warn("LOCAL fallback failed for number: {}", effectiveNumber);
            }
        }

        // PHP: Use assumed info if prefixes were consistent but no indicator match found
        if (assumedRateInfo != null && allPrefixesConsistent) {
            log.warn("Using assumed rate info for number {} based on consistent prefix type/operator.", effectiveNumber);
            return Optional.of(assumedRateInfo);
        }

        log.warn("Attempt failed: No matching rate found for number: {}", effectiveNumber);
        return Optional.empty();
    }

    // ========================================================================
    // findRateInfoForLocal
    // ========================================================================
     private Optional<Map<String, Object>> findRateInfoForLocal(CommunicationLocation commLocation, String effectiveNumber) {
        Long originCountryId = configLookupService.getOriginCountryIdFromCommLocation(commLocation);
        Long originIndicatorId = commLocation.getIndicatorId();
        Long localType = CdrProcessingConfig.TIPOTELE_LOCAL;

        if (originCountryId == null || originIndicatorId == null) {
            log.error("Cannot find LOCAL rate: Missing Origin Country ({}) or Indicator ID ({}) for Location {}", originCountryId, originIndicatorId, commLocation.getId());
            return Optional.empty();
        }

        Optional<Operator> internalOpOpt = configService.getOperatorInternal(localType, originCountryId);
        if (internalOpOpt.isEmpty()) {
            log.warn("Cannot find internal operator for LOCAL type ({}) in country {}", localType, originCountryId);
            return Optional.empty();
        }
        Long internalOperatorId = internalOpOpt.get().getId();

        Optional<Prefix> localPrefixOpt = prefixLookupService.findPrefixByTypeOperatorOrigin(localType, internalOperatorId, originCountryId);
        if (localPrefixOpt.isEmpty()) {
            log.warn("Cannot find Prefix entity for LOCAL type ({}) and Operator {} in Country {}", localType, internalOperatorId, originCountryId);
            return Optional.empty();
        }
        Prefix localPrefix = localPrefixOpt.get();

        // Find indicator/series for the local number
        Optional<Map<String, Object>> indicatorInfoOpt = indicatorLookupService.findIndicatorAndSeries(
                effectiveNumber, localType, originCountryId, originIndicatorId, localPrefix.isBandOk(), localPrefix.getId()
        );
        if (indicatorInfoOpt.isEmpty()) {
            log.warn("Could not find LOCAL indicator for number {}", effectiveNumber);
            return Optional.empty();
        }
        Long destinationIndicatorId = indicatorInfoOpt
            .map(ind -> (Long) ind.get("indicator_id"))
            .filter(id -> id != null && id > 0)
            .orElse(null);
        Integer destinationNdc = indicatorInfoOpt.map(ind -> (Integer) ind.get("series_ndc")).orElse(null);

        Long finalTelephonyTypeId = localType;
        Long finalPrefixId = localPrefix.getId();
        boolean finalBandOk = localPrefix.isBandOk();
        String typeSuffix = "";

        // Check for Local Extended
        if (destinationNdc != null) {
            boolean isExtended = indicatorLookupService.isLocalExtended(destinationNdc, originIndicatorId);
            if (isExtended) {
                finalTelephonyTypeId = CdrProcessingConfig.TIPOTELE_LOCAL_EXT;
                typeSuffix = " (Local Extended)";
                log.debug("Reclassified LOCAL fallback call to {} as LOCAL_EXTENDED based on NDC {} and origin {}", effectiveNumber, destinationNdc, originIndicatorId);
                // Find prefix for LOCAL_EXTENDED
                Optional<Operator> localExtOpOpt = configService.getOperatorInternal(finalTelephonyTypeId, originCountryId);
                if (localExtOpOpt.isPresent()) {
                    Optional<Prefix> localExtPrefixOpt = prefixLookupService.findPrefixByTypeOperatorOrigin(finalTelephonyTypeId, localExtOpOpt.get().getId(), originCountryId);
                    if (localExtPrefixOpt.isPresent()) {
                        finalPrefixId = localExtPrefixOpt.get().getId();
                        finalBandOk = localExtPrefixOpt.get().isBandOk();
                        log.trace("Using Prefix ID {} for LOCAL_EXTENDED rate lookup", finalPrefixId);
                    } else {
                        log.warn("Could not find prefix for LOCAL_EXTENDED type {}, operator {}, country {}. Rate lookup might be incorrect.", finalTelephonyTypeId, localExtOpOpt.get().getId(), originCountryId);
                    }
                } else {
                    log.warn("Could not find internal operator for LOCAL_EXTENDED type {}. Rate lookup might be incorrect.", finalTelephonyTypeId);
                }
            }
        }

        // Find rate info (base or band) using the final prefix/indicator
        Optional<Map<String, Object>> rateInfoOpt = findRateInfo(finalPrefixId, destinationIndicatorId, originIndicatorId, finalBandOk);

        if (rateInfoOpt.isPresent()) {
            Map<String, Object> rateInfo = rateInfoOpt.get();
            // Populate with necessary details
            rateInfo.put("telephony_type_id", finalTelephonyTypeId);
            rateInfo.put("operator_id", internalOperatorId);
            rateInfo.put("indicator_id", destinationIndicatorId);
            rateInfo.put("telephony_type_name", entityLookupService.findTelephonyTypeById(finalTelephonyTypeId).map(TelephonyType::getName).orElse("Unknown Type") + typeSuffix);
            rateInfo.put("operator_name", internalOpOpt.get().getName());
            rateInfo.put("destination_name", formatDestinationName(indicatorInfoOpt.get()));
            rateInfo.put("band_name", rateInfo.get("band_name"));
            rateInfo.put("origin_country_id", originCountryId); // Add origin country
            return Optional.of(rateInfo);
        } else {
            log.warn("Rate info not found for LOCAL fallback (Type: {}, Prefix: {}, Indicator: {})", finalTelephonyTypeId, finalPrefixId, destinationIndicatorId);
        }

        return Optional.empty();
    }

    // ========================================================================
    // findRateInfo
    // ========================================================================
    private Optional<Map<String, Object>> findRateInfo(Long prefixId, Long indicatorId, Long originIndicatorId, boolean bandOk) {
        Optional<Map<String, Object>> baseRateOpt = prefixLookupService.findBaseRateForPrefix(prefixId);
        if (baseRateOpt.isEmpty()) {
            log.warn("Base rate info not found for prefixId: {}", prefixId);
            return Optional.empty();
        }
        Map<String, Object> rateInfo = new HashMap<>(baseRateOpt.get());
        rateInfo.put("band_id", 0L);
        rateInfo.put("band_name", "");
        // Ensure defaults if base rate lookup returned nulls
        rateInfo.putIfAbsent("base_value", BigDecimal.ZERO);
        rateInfo.putIfAbsent("vat_included", false);
        rateInfo.putIfAbsent("vat_value", BigDecimal.ZERO);

        // Determine if band lookup should be performed
        // PHP: $usar_bandas = (1 * $info_indica['PREFIJO_BANDAOK'] > 0);
        // PHP: if ($usar_bandas && ($indicadestino > 0 || $es_local))
        // $es_local check is implicitly handled because this function is called with the correct prefixId
        boolean useBands = bandOk && indicatorId != null && indicatorId > 0;
        log.trace("findRateInfo: prefixId={}, indicatorId={}, originIndicatorId={}, bandOk={}, useBands={}",
                prefixId, indicatorId, originIndicatorId, bandOk, useBands);

        if (useBands) {
            Optional<Map<String, Object>> bandOpt = bandLookupService.findBandByPrefixAndIndicator(prefixId, indicatorId, originIndicatorId);
            if (bandOpt.isPresent()) {
                 Map<String, Object> bandInfo = bandOpt.get();
                // Override base rate values with band values
                rateInfo.put("base_value", bandInfo.get("band_value"));
                rateInfo.put("vat_included", bandInfo.get("band_vat_included"));
                rateInfo.put("band_id", bandInfo.get("band_id"));
                rateInfo.put("band_name", bandInfo.get("band_name"));
                // Keep the VAT percentage from the prefix
                // rateInfo.put("vat_value", rateInfo.get("vat_value")); // Already present from baseRateOpt
                log.trace("Using band rate for prefix {}, indicator {}: BandID={}, Value={}, VatIncluded={}",
                        prefixId, indicatorId, bandInfo.get("band_id"), bandInfo.get("band_value"), bandInfo.get("band_vat_included"));
            } else {
                log.trace("Band lookup enabled for prefix {} but no matching band found for indicator {}", prefixId, indicatorId);
                 // Keep base rate values if no band found
            }
        } else {
            log.trace("Using base rate for prefix {} (Bands not applicable or indicator missing/null)", prefixId);
             // Keep base rate values
        }
        // Standardize output keys for RateCalculationService
        rateInfo.put("valor_minuto", rateInfo.get("base_value"));
        rateInfo.put("valor_minuto_iva", rateInfo.get("vat_included"));
        rateInfo.put("iva", rateInfo.get("vat_value"));

        return Optional.of(rateInfo);
    }

    // ========================================================================
    // findIncomingOrigin
    // ========================================================================
    private Optional<Map<String, Object>> findIncomingOrigin(String numberForLookup, Long originCountryId, Long commIndicatorId) {
        log.debug("Finding origin for incoming number: {}, originCountryId: {}, commIndicatorId: {}", numberForLookup, originCountryId, commIndicatorId);

        // Get all type configs for the country, ordered by max length desc (PHP: prefijos_OrdenarEntrantes)
        List<TelephonyTypeConfig> typeConfigs = configLookupService.findAllTelephonyTypeConfigsByCountry(originCountryId);
        typeConfigs.sort(Comparator.<TelephonyTypeConfig, Integer>comparing(cfg -> Optional.ofNullable(cfg.getMaxValue()).orElse(0)).reversed()
                .thenComparing(cfg -> Optional.ofNullable(cfg.getMinValue()).orElse(0)));

        int numberLength = numberForLookup.length();

        for (TelephonyTypeConfig config : typeConfigs) {
            Long currentTelephonyTypeId = config.getTelephonyTypeId();
            int minLength = Optional.ofNullable(config.getMinValue()).orElse(0);
            int maxLength = Optional.ofNullable(config.getMaxValue()).orElse(0);

            // PHP: Skips internal types, special, celufijo
            if (configService.isInternalTelephonyType(currentTelephonyTypeId) ||
                currentTelephonyTypeId.equals(CdrProcessingConfig.TIPOTELE_ESPECIALES) ||
                currentTelephonyTypeId.equals(CdrProcessingConfig.TIPOTELE_CELUFIJO) ||
                numberLength < minLength ||
                (maxLength > 0 && numberLength > maxLength)) {
                log.trace("Skipping type {} for incoming lookup (Internal/Special/Celufijo or length mismatch: {} not in [{}, {}])", currentTelephonyTypeId, numberLength, minLength, maxLength);
                continue;
            }

            log.trace("Attempting incoming origin lookup for type {}: Number={}, Length Range=[{}, {}]", currentTelephonyTypeId, numberForLookup, minLength, maxLength);

            // Find indicator/series for this type. For incoming, bandOk and prefixId are irrelevant.
            Optional<Map<String, Object>> indicatorInfoOpt = indicatorLookupService.findIndicatorAndSeries(
                    numberForLookup, currentTelephonyTypeId, originCountryId, commIndicatorId, false, null
            );

            if (indicatorInfoOpt.isPresent()) {
                Map<String, Object> indicatorInfo = indicatorInfoOpt.get();
                Long foundIndicatorId = (Long) indicatorInfo.get("indicator_id");
                Long foundOperatorId = (Long) indicatorInfo.get("operator_id");
                Integer foundNdc = (Integer) indicatorInfo.get("series_ndc");

                Long finalTelephonyTypeId = currentTelephonyTypeId;
                String destinationName = formatDestinationName(indicatorInfo);

                // PHP: Check for Local Extended
                if (CdrProcessingConfig.isLocalTelephonyType(currentTelephonyTypeId) && foundNdc != null) {
                    boolean isExtended = indicatorLookupService.isLocalExtended(foundNdc, commIndicatorId);
                    if (isExtended) {
                        finalTelephonyTypeId = CdrProcessingConfig.TIPOTELE_LOCAL_EXT;
                        destinationName += " (Local Extended)";
                        log.debug("Incoming origin classified as LOCAL_EXTENDED based on NDC {} and comm indicator {}", foundNdc, commIndicatorId);
                    }
                }

                Map<String, Object> originResult = new HashMap<>();
                originResult.put("telephony_type_id", finalTelephonyTypeId);
                originResult.put("operator_id", foundOperatorId);
                originResult.put("indicator_id", foundIndicatorId);
                originResult.put("destination_name", destinationName);

                log.debug("Found incoming origin: Type={}, Op={}, Ind={}", finalTelephonyTypeId, foundOperatorId, foundIndicatorId);
                return Optional.of(originResult); // Return the first match based on PHP's length ordering
            } else {
                 log.trace("No indicator match found for incoming number {} with type {}", numberForLookup, currentTelephonyTypeId);
            }
        }

        log.warn("Could not determine origin for incoming number {}", numberForLookup);
        return Optional.empty();
    }


    // --- Helper Methods ---

    @Getter @Setter
    private static class FieldWrapper<T> { T value; FieldWrapper(T v) { this.value = v; } }

    private record LocationInfo(Long indicatorId, Long originCountryId, Long officeId) {}

    private LocationInfo getLocationInfo(Employee employee, CommunicationLocation defaultLocation) {
        Long defaultIndicatorId = defaultLocation.getIndicatorId();
        Long defaultOriginCountryId = configLookupService.getOriginCountryIdFromCommLocation(defaultLocation);
        Long defaultOfficeId = null; // Office ID is derived from subdivision hierarchy

        if (employee != null) {
            Long empOfficeId = null; // Determined below
            Long empOriginCountryId = defaultOriginCountryId;
            Long empIndicatorId = defaultIndicatorId;

            // 1. Check Employee's assigned CommunicationLocation (if different from default)
            if (employee.getCommunicationLocationId() != null && !employee.getCommunicationLocationId().equals(defaultLocation.getId())) {
                Optional<CommunicationLocation> empLocOpt = entityLookupService.findCommunicationLocationById(employee.getCommunicationLocationId());
                if (empLocOpt.isPresent()) {
                    CommunicationLocation empLoc = empLocOpt.get();
                    empIndicatorId = empLoc.getIndicatorId() != null ? empLoc.getIndicatorId() : defaultIndicatorId;
                    Long empLocCountryId = configLookupService.getOriginCountryIdFromCommLocation(empLoc);
                    empOriginCountryId = empLocCountryId != null ? empLocCountryId : defaultOriginCountryId;
                    log.trace("Using location info from Employee's specific CommLocation {}: Indicator={}, Country={}", employee.getCommunicationLocationId(), empIndicatorId, empOriginCountryId);
                } else {
                    log.warn("Employee {} has CommLocationId {} assigned, but location not found.", employee.getId(), employee.getCommunicationLocationId());
                }
            }

            // 2. Check Employee's Cost Center for Origin Country Override
            if (employee.getCostCenterId() != null) {
                Optional<CostCenter> ccOpt = entityLookupService.findCostCenterById(employee.getCostCenterId());
                if (ccOpt.isPresent() && ccOpt.get().getOriginCountryId() != null) {
                    empOriginCountryId = ccOpt.get().getOriginCountryId();
                    log.trace("Overriding OriginCountry to {} based on Employee's CostCenter {}", empOriginCountryId, employee.getCostCenterId());
                }
            }

            // 3. Determine Office ID from Subdivision Hierarchy
            if (employee.getSubdivisionId() != null) {
                 Optional<Subdivision> officeSubdivision = findOfficeSubdivision(employee.getSubdivisionId());
                 if (officeSubdivision.isPresent()) {
                     empOfficeId = officeSubdivision.get().getId();
                     log.trace("Determined Office ID {} for employee {} from subdivision hierarchy", empOfficeId, employee.getId());
                 } else {
                     log.warn("Could not determine office ID for employee {} from subdivision {}", employee.getId(), employee.getSubdivisionId());
                 }
            }


            log.trace("Final location info for Employee {}: Indicator={}, Country={}, Office={}", employee.getId(), empIndicatorId, empOriginCountryId, empOfficeId);
            return new LocationInfo(empIndicatorId, empOriginCountryId, empOfficeId);
        }

        // If no employee, determine default office ID if possible (might require more context)
        // For now, assume default office is null if no employee provided.
        log.trace("Using default location info: Indicator={}, Country={}, Office={}", defaultIndicatorId, defaultOriginCountryId, defaultOfficeId);
        return new LocationInfo(defaultIndicatorId, defaultOriginCountryId, defaultOfficeId);
    }

    private Optional<Subdivision> findOfficeSubdivision(Long subdivisionId) {
        if (subdivisionId == null) return Optional.empty();
        Subdivision current = entityLookupService.findSubdivisionById(subdivisionId).orElse(null);
        int depth = 0;
        final int MAX_DEPTH = 10; // Prevent infinite loops
        // Traverse up until the root (parent is null or 0) or max depth is reached
        while (current != null && current.getParentSubdivisionId() != null && current.getParentSubdivisionId() > 0 && depth < MAX_DEPTH) {
            // Fetch the parent using the ID stored in the current subdivision
            Subdivision parent = entityLookupService.findSubdivisionById(current.getParentSubdivisionId()).orElse(null);
            if (parent == null) {
                log.warn("Parent subdivision with ID {} not found for subdivision {}, stopping hierarchy traversal.", current.getParentSubdivisionId(), current.getId());
                break; // Stop if parent not found
            }
            current = parent; // Move up to the parent
            depth++;
        }
        if (depth >= MAX_DEPTH) {
             log.warn("Reached max depth ({}) while traversing subdivision hierarchy starting from {}. Returning last known parent.", MAX_DEPTH, subdivisionId);
        }
        // 'current' now holds the topmost subdivision found (the office)
        return Optional.ofNullable(current);
    }


    private String formatDestinationName(Map<String, Object> indicatorInfo) {
        String city = (String) indicatorInfo.get("city_name");
        String country = (String) indicatorInfo.get("department_country");
        if (StringUtils.hasText(city) && StringUtils.hasText(country)) return city + " (" + country + ")";
        return StringUtils.hasText(city) ? city : (StringUtils.hasText(country) ? country : "Unknown Destination");
    }

    /**
     * Preprocesses numbers based on Colombian numbering plan changes.
     * Matches PHP logic in _esCelular_fijo, _esNacional, _esEntrante_60, _es_Saliente.
     */
    private String preprocessNumberForLookup(String number, Long originCountryId) {
         if (number == null || originCountryId == null || !originCountryId.equals(COLOMBIA_ORIGIN_COUNTRY_ID)) {
            return number; // Only apply to Colombia
        }

        int len = number.length();
        String originalNumber = number;
        String processedNumber = number;

        // --- Incoming / General Preprocessing (_esEntrante_60 logic) ---
        if (len == 10) {
            if (number.startsWith("3")) { // Potential mobile
                if (number.matches("^3[0-4][0-9]\\d{7}$")) { // Check if valid mobile range
                    processedNumber = "03" + number; // Add 03 prefix for internal consistency
                    log.trace("Preprocessing (Incoming/General) Colombian 10-digit mobile: {} -> {}", originalNumber, processedNumber);
                }
            } else if (number.startsWith("60")) { // Potential fixed line with new prefix
                String nationalPrefix = determineNationalPrefix(number); // Check if it maps to a legacy operator prefix
                if (nationalPrefix != null) {
                    // Map to legacy format (e.g., 09 + number)
                    processedNumber = nationalPrefix + number.substring(2);
                    log.trace("Preprocessing (Incoming/General) Colombian 10-digit fixed (mapped): {} -> {}", originalNumber, processedNumber);
                } else {
                    // If no mapping, assume it's a local call and remove the 60X prefix
                    if (number.matches("^60\\d{8}$")) { // Check format 60 + 1 digit indicativo + 7 digits number
                        processedNumber = number.substring(3); // Keep the 7 digits number
                        log.trace("Preprocessing (Incoming/General) Colombian 10-digit fixed (local assumption): {} -> {}", originalNumber, processedNumber);
                    } else {
                         log.warn("Unexpected Colombian 10-digit number starting with 60: {}", number);
                    }
                }
            }
        } else if (len == 12) { // Potential numbers with 57 or 60 prefix
            if (number.startsWith("573") || number.startsWith("603")) { // Mobile with prefix
                 if (number.matches("^(57|60)3[0-4][0-9]\\d{7}$")) {
                    processedNumber = number.substring(2); // Remove 57 or 60 prefix
                    log.trace("Preprocessing (Incoming/General) Colombian 12-digit mobile: {} -> {}", originalNumber, processedNumber);
                 }
            } else if (number.startsWith("6060") || number.startsWith("5760")) { // Fixed line with 6060/5760 prefix
                 if (number.matches("^(57|60)60\\d{8}$")) {
                    processedNumber = number.substring(4); // Remove 6060 or 5760 prefix
                    log.trace("Preprocessing (Incoming/General) Colombian 12-digit fixed: {} -> {}", originalNumber, processedNumber);
                }
            }
        } else if (len == 11) { // Potential mobile starting with 03 or fixed with 604
            if (number.startsWith("03")) { // Mobile with leading 0
                 if (number.matches("^03[0-4][0-9]\\d{7}$")) {
                    processedNumber = number.substring(1); // Remove leading 0
                    log.trace("Preprocessing (Incoming/General) Colombian 11-digit mobile (03): {} -> {}", originalNumber, processedNumber);
                 }
            } else if (number.startsWith("604")) { // Fixed line with 604 prefix (example)
                 if (number.matches("^604\\d{8}$")) { // Adjust regex if needed
                    processedNumber = number.substring(3); // Remove 604 prefix
                    log.trace("Preprocessing (Incoming/General) Colombian 11-digit fixed (604): {} -> {}", originalNumber, processedNumber);
                }
            }
        } else if (len == 9 && number.startsWith("60")) { // Potential 9-digit fixed line
             if (number.matches("^60\\d{7}$")) { // 60 + 7 digits
                processedNumber = number.substring(2); // Remove 60 prefix
                log.trace("Preprocessing (Incoming/General) Colombian 9-digit fixed (60): {} -> {}", originalNumber, processedNumber);
            }
        }

        // --- Outgoing Preprocessing (_es_Saliente logic) ---
        // This specifically checks for outgoing mobile numbers dialed with a leading 0
        if (len == 11 && number.startsWith("03")) {
             if (number.matches("^03[0-4][0-9]\\d{7}$")) {
                 // If it wasn't already processed above (unlikely but possible), process it now
                 if (processedNumber.equals(originalNumber)) {
                     processedNumber = number.substring(1); // Remove leading 0
                     log.trace("Preprocessing (Outgoing) Colombian 11-digit mobile (03): {} -> {}", originalNumber, processedNumber);
                 }
             }
        }

        if (!originalNumber.equals(processedNumber)) {
            log.debug("Preprocessed number for lookup: {} -> {}", originalNumber, processedNumber);
        }
        return processedNumber;
    }

    /**
     * Determines the legacy national operator prefix based on the new 10-digit format (60 + NDC + number).
     * Matches PHP logic in _esNacional.
     */
    private String determineNationalPrefix(String number10Digit) {
         if (number10Digit == null || !number10Digit.startsWith("60") || number10Digit.length() != 10) {
            return null; // Not a valid format to check
        }
        // Extract NDC (1 digit) and subscriber number (7 digits)
        String ndcStr = number10Digit.substring(2, 3);
        String subscriberNumberStr = number10Digit.substring(3);

        // Basic validation
        if (!ndcStr.matches("\\d") || !subscriberNumberStr.matches("\\d{7}")) {
            log.warn("Invalid format for national prefix determination: {}", number10Digit);
            return null;
        }
        int ndc = Integer.parseInt(ndcStr);
        long subscriberNumber = Long.parseLong(subscriberNumberStr);

        // Find the series containing this number
        Optional<Map<String, Object>> seriesInfoOpt = indicatorLookupService.findSeriesInfoForNationalLookup(ndc, subscriberNumber);

        if (seriesInfoOpt.isPresent()) {
            String company = (String) seriesInfoOpt.get().get("series_company");
            if (company != null) {
                company = company.toUpperCase();
                // Map company name (from Series table) to legacy prefix
                if (company.contains("TELMEX") || company.contains("CLARO")) return "0456";
                if (company.contains("COLOMBIA TELECOMUNICACIONES") || company.contains("MOVISTAR")) return "09";
                if (company.contains("UNE EPM") || company.contains("TIGO")) return "05";
                if (company.contains("EMPRESA DE TELECOMUNICACIONES DE BOGOTÁ") || company.contains("ETB")) return "07";
                // Add other mappings as needed
            }
        }
        // If no match or company not recognized, fallback (PHP used '09')
        log.warn("Could not map new 10-digit number {} to a legacy operator prefix based on series info. Falling back to '09'.", number10Digit);
        return "09"; // Fallback prefix
    }

     /**
      * Cleans a phone number string: removes PBX prefix if requested, then removes non-numeric characters (except #*+).
      * Matches PHP's limpiar_numero.
      * @param number The number string.
      * @param pbxPrefixes List of potential PBX prefixes.
      * @param removePrefix If true, attempts to remove a matching PBX prefix.
      * @return The cleaned number.
      */
     private String cleanNumber(String number, List<String> pbxPrefixes, boolean removePrefix) {
        if (!StringUtils.hasText(number)) return "";
        String cleaned = number.trim();
        int prefixLength = 0;

        // 1. Remove PBX prefix if requested and found
        if (removePrefix && pbxPrefixes != null && !pbxPrefixes.isEmpty()) {
            prefixLength = getPrefixLength(cleaned, pbxPrefixes);
            if (prefixLength > 0) {
                cleaned = cleaned.substring(prefixLength);
                log.trace("Removed PBX prefix (length {}) from {}, result: {}", prefixLength, number, cleaned);
            } else {
                log.trace("Prefix removal requested but no matching prefix found in {}", number);
            }
        }

        // 2. Remove invalid characters (allow digits, #, *, +)
        // Preserve leading '+' if present
        boolean hasPlus = cleaned.startsWith("+");
        // Remove anything NOT in the allowed set [0-9#*+]
        cleaned = cleaned.replaceAll("[^0-9#*+]", "");
        // Ensure leading '+' is restored if it was originally there and not removed by regex
        if (hasPlus && !cleaned.startsWith("+")) {
            cleaned = "+" + cleaned;
        }

        // PHP logic also truncated after the first non-numeric char found *after* the first char.
        // This is slightly different from the regex replace but achieves a similar goal for simple cases.
        // Let's refine the regex approach to be closer.
        // Alternative: Find first invalid char after pos 0 and truncate.
        /*
        String firstChar = cleaned.length() > 0 ? cleaned.substring(0, 1) : "";
        String rest = cleaned.length() > 1 ? cleaned.substring(1) : "";
        StringBuilder validRest = new StringBuilder();
        for (char c : rest.toCharArray()) {
            if (Character.isDigit(c) || c == '#' || c == '*') {
                validRest.append(c);
            } else {
                break; // Stop at the first invalid character after the first position
            }
        }
        cleaned = firstChar + validRest.toString();
        */
        // The regex approach is generally safer and covers more cases. Stick with regex.

        return cleaned;
    }

    /**
     * Finds the length of the longest matching PBX prefix from the list.
     * Matches PHP's Validar_prefijoSalida logic.
     * @param number The number to check.
     * @param pbxPrefixes List of prefixes.
     * @return Length of the longest matching prefix, or 0 if none match.
     */
    private int getPrefixLength(String number, List<String> pbxPrefixes) {
        int longestMatchLength = 0;
        if (number != null && pbxPrefixes != null && !pbxPrefixes.isEmpty()) {
            for (String prefix : pbxPrefixes) {
                String trimmedPrefix = prefix != null ? prefix.trim() : "";
                if (!trimmedPrefix.isEmpty() && number.startsWith(trimmedPrefix)) {
                    if (trimmedPrefix.length() > longestMatchLength) {
                        longestMatchLength = trimmedPrefix.length();
                    }
                }
            }
        }
        return longestMatchLength;
    }

    /**
     * Checks if a number is likely an internal extension based on length config.
     * Matches PHP's ExtensionPosible.
     * @param number The number string.
     * @param extConfig Configuration for min/max length and value.
     * @return True if it's likely an extension.
     */
    private boolean isInternalCall(String callingNumber, String dialedNumber, CdrProcessingConfig.ExtensionLengthConfig extConfig) {
        boolean callingIsExt = isLikelyExtension(callingNumber, extConfig);
        boolean dialedIsExt = isLikelyExtension(dialedNumber, extConfig);
        log.trace("isInternalCall check: Caller '{}' (isExt: {}) -> Dialed '{}' (isExt: {})", callingNumber, callingIsExt, dialedIsExt);
        // PHP logic implies both must be extensions for it to be internal
        return callingIsExt && dialedIsExt;
    }

    /**
     * Detailed check if a number string represents a valid extension.
     * Matches PHP's ExtensionPosible.
     * @param number The number string.
     * @param extConfig Configuration object.
     * @return True if valid extension format and within configured limits.
     */
    private boolean isLikelyExtension(String number, CdrProcessingConfig.ExtensionLengthConfig extConfig) {
         if (!StringUtils.hasText(number)) return false;
        // PHP logic ignores numbers starting with '0' unless they are in the special list (omitted here)
        // It also ignores numbers containing '-'
        if (number.startsWith("0") || number.contains("-")) {
             log.trace("isLikelyExtension: '{}' starts with 0 or contains '-', considered not an extension.", number);
             return false;
        }

        // Allow digits, #, * and potentially leading +
        String effectiveNumber = number.startsWith("+") ? number.substring(1) : number;
        if (!effectiveNumber.matches("[\\d#*]+")) {
            log.trace("isLikelyExtension: '{}' contains invalid characters.", number);
            return false;
        }

        int numLength = effectiveNumber.length();
        // Check length against configured min/max
        if (numLength < extConfig.getMinLength() || numLength > extConfig.getMaxLength()) {
            log.trace("isLikelyExtension: '{}' length {} outside range ({}-{}).", number, numLength, extConfig.getMinLength(), extConfig.getMaxLength());
            return false;
        }

        // Check numeric value against max allowed value (only if purely digits)
        try {
            if (effectiveNumber.matches("\\d+")) {
                long numValue = Long.parseLong(effectiveNumber);
                if (numValue > extConfig.getMaxExtensionValue()) {
                    log.trace("isLikelyExtension: '{}' value {} exceeds max value {}.", number, numValue, extConfig.getMaxExtensionValue());
                    return false;
                }
            }
        } catch (NumberFormatException e) {
            // If it contains # or *, skip the max value check
            log.trace("isLikelyExtension: '{}' contains non-digits, skipping max value check.", number);
        }
        log.trace("isLikelyExtension: '{}' is considered a likely extension.", number);
        return true;
    }

     /**
      * Finds the effective operator ID to use for trunk rate/rule lookup.
      * Matches PHP's buscarOperador_Troncal logic.
      * @param trunk The trunk entity.
      * @param telephonyTypeId The determined telephony type ID.
      * @param prefixCode The matched prefix code.
      * @param actualOperatorId The operator ID determined from the prefix lookup.
      * @return The operator ID to use (0 for global, specific ID, or null if no match).
      */
     private Long findEffectiveTrunkOperator(Trunk trunk, Long telephonyTypeId, String prefixCode, Long actualOperatorId) {
        // Check if a rate exists for the specific operator
        Optional<TrunkRate> specificRateOpt = trunkLookupService.findTrunkRate(trunk.getId(), actualOperatorId, telephonyTypeId);
        if (specificRateOpt.isPresent()) {
            log.trace("Found specific TrunkRate for trunk {}, op {}, type {}. Using operator {}.", trunk.getId(), actualOperatorId, telephonyTypeId, actualOperatorId);
            return actualOperatorId; // Found specific rate for this operator
        }

        // Check if a global rate (operator 0) exists for this type
        Optional<TrunkRate> globalRateOpt = trunkLookupService.findTrunkRate(trunk.getId(), 0L, telephonyTypeId);
        if (globalRateOpt.isPresent()) {
            // Global rate exists. Check the 'noprefijo' flag.
             if (globalRateOpt.get().getNoPrefix() != null && globalRateOpt.get().getNoPrefix()) {
                 // Global rate ignores prefix. Need to check if the actual operator is the default for this type/country.
                 Long originCountryId = configLookupService.getOriginCountryIdFromCommLocation(trunk.getCommLocation());
                 Long defaultOperatorId = configService.getOperatorInternal(telephonyTypeId, originCountryId)
                                                      .map(Operator::getId).orElse(null);

                 if (actualOperatorId != null && defaultOperatorId != null && !actualOperatorId.equals(defaultOperatorId)) {
                     // Actual operator is not the default. Check if the prefix is exclusive to the actual operator.
                     List<Map<String, Object>> prefixesForCode = prefixLookupService.findPrefixesByNumber(prefixCode, originCountryId)
                         .stream().filter(p -> prefixCode.equals(p.get("prefix_code"))).toList();

                     if (prefixesForCode.size() == 1 && actualOperatorId.equals(prefixesForCode.get(0).get("operator_id"))) {
                        // Prefix belongs *only* to the actual operator. The global rule with 'noprefijo' doesn't apply here.
                        log.trace("Global TrunkRate ignores prefix, but actual operator {} is the *only* operator for prefix {}. No trunk rate/rule applies.", actualOperatorId, prefixCode);
                        return null; // Indicate no applicable trunk rate/rule found
                     } else {
                         // Prefix has multiple operators, or the actual operator isn't the only one. Global rule applies.
                         log.trace("Global TrunkRate ignores prefix, and actual operator {} is not the default {} (or prefix {} has multiple operators). Using operator 0.", actualOperatorId, defaultOperatorId, prefixCode);
                         return 0L; // Use the global rule (operator 0)
                     }
                 } else {
                      // Actual operator is the default, or one of them is null. Global rule applies.
                      log.trace("Global TrunkRate ignores prefix, and actual operator {} is the default {} (or null). Using operator 0.", actualOperatorId, defaultOperatorId);
                      return 0L; // Use the global rule (operator 0)
                 }
             } else {
                 // Global rate exists and respects prefix. Use the global rule.
                 log.trace("Found global TrunkRate for trunk {}, type {}. Using operator 0.", trunk.getId(), telephonyTypeId);
                 return 0L; // Use the global rule (operator 0)
             }
        }

        // No specific or global rate found for this type/operator combination on this trunk
        log.trace("No specific or global TrunkRate found for trunk {}, op {}, type {}.", trunk.getId(), actualOperatorId, telephonyTypeId);
        return null; // Indicate no applicable trunk rate found
    }

    /**
     * Links associated entities (TelephonyType, Operator, Indicator, Employee, DestinationEmployee)
     * to the CallRecord based on their IDs stored in the builder.
     * @param callBuilder The builder containing the IDs.
     */
    private void linkAssociatedEntities(CallRecord.CallRecordBuilder callBuilder) {
        CallRecord record = callBuilder.build(); // Build temporary record to access IDs

        // Link TelephonyType if ID exists and entity is not already linked
        if (record.getTelephonyTypeId() != null && record.getTelephonyType() == null) {
            entityLookupService.findTelephonyTypeById(record.getTelephonyTypeId())
                    .ifPresentOrElse(callBuilder::telephonyType, // Set the entity in the builder
                            () -> log.warn("Could not link TelephonyType entity for ID: {}", record.getTelephonyTypeId()));
        }

        // Link Operator if ID exists and entity is not already linked
        if (record.getOperatorId() != null && record.getOperator() == null) {
            entityLookupService.findOperatorById(record.getOperatorId())
                    .ifPresentOrElse(callBuilder::operator,
                            () -> log.warn("Could not link Operator entity for ID: {}", record.getOperatorId()));
        }

        // Link Indicator if ID exists and entity is not already linked
        if (record.getIndicatorId() != null && record.getIndicator() == null) {
            indicatorLookupService.findIndicatorById(record.getIndicatorId())
                    .ifPresentOrElse(callBuilder::indicator,
                            () -> log.warn("Could not link Indicator entity for ID: {}", record.getIndicatorId()));
        } else if (record.getIndicatorId() == null && record.getIndicator() != null) {
             // If ID became null but entity was somehow set, clear the entity
             callBuilder.indicator(null);
             log.trace("Indicator ID is null, clearing linked Indicator entity.");
        } else if (record.getIndicatorId() == null) {
             log.trace("Indicator ID is null, skipping entity linking.");
        }


        // Link DestinationEmployee if ID exists and entity is not already linked
        if (record.getDestinationEmployeeId() != null && record.getDestinationEmployee() == null) {
            employeeLookupService.findDestinationEmployeeById(record.getDestinationEmployeeId())
                    .ifPresentOrElse(callBuilder::destinationEmployee,
                            () -> log.warn("Could not link destination employee entity for ID: {}", record.getDestinationEmployeeId()));
        }
    }

}