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
        // PHP logic for conference was complex (tracking parent calls).
        // This simplified version assigns internal type and zero cost.
        if (standardDto.isConference()) {
            log.debug("CDR {} identified as conference leg.", standardDto.getGlobalCallId());
            callBuilder.assignmentCause(CallAssignmentCause.CONFERENCE.getValue());

            // Try to find the employee initiating/involved in the conference leg
            Optional<Employee> confEmployeeOpt = employeeLookupService.findEmployeeByExtensionOrAuthCode(
                    standardDto.getCallingPartyNumber(), // Use the effective caller determined by parser
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

            callBuilder.telephonyTypeId(CdrProcessingConfig.TIPOTELE_INTERNA_IP); // Default conference type
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
            // PHP might assign based on ranges here, simplified to UNKNOWN for now
            callBuilder.assignmentCause(CallAssignmentCause.UNKNOWN.getValue());
        }

        // --- Determine Call Type and Enrich ---
        try {
            if (standardDto.isIncoming()) {
                processIncomingCall(standardDto, commLocation, callBuilder, standardDto.getCallingPartyNumber());
            } else {
                // Use a depth counter to prevent infinite recursion in case of rule loops
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

        // --- Final Adjustments (PHP: _TIPOTELE_SINCONSUMO logic) ---
        CallRecord tempRecord = callBuilder.build(); // Build temporary to check duration/type
        if (tempRecord.getDuration() != null && tempRecord.getDuration() <= 0
                && tempRecord.getTelephonyTypeId() != null
                && tempRecord.getTelephonyTypeId() != CdrProcessingConfig.TIPOTELE_ERRORES) {
            log.debug("Call duration is zero or less, setting TelephonyType to SINCONSUMO ({})", CdrProcessingConfig.TIPOTELE_SINCONSUMO);
            callBuilder.telephonyTypeId(CdrProcessingConfig.TIPOTELE_SINCONSUMO);
            callBuilder.billedAmount(BigDecimal.ZERO);
            callBuilder.pricePerMinute(BigDecimal.ZERO);
            callBuilder.initialPrice(BigDecimal.ZERO);
        }

        // Link associated entities (Operator, TelephonyType, Indicator)
        linkAssociatedEntities(callBuilder);

        // Build final record
        CallRecord finalRecord = callBuilder.build();

        log.info("Successfully enriched CDR {}: Type={}, Billed={}",
                standardDto.getGlobalCallId(), finalRecord.getTelephonyTypeId(), finalRecord.getBilledAmount());
        return Optional.of(finalRecord);
    }

    // ========================================================================
    // Recursive Outgoing Call Processing (Refined Order)
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

        // --- 1. Check Special Service (PHP: procesaServespecial) ---
        // Clean number *without* PBX prefix removal for special service check
        String numberForSpecialCheck = cleanNumber(effectiveDialedNumber, Collections.emptyList(), false);
        if (indicatorIdForSpecial != null && originCountryId != null) {
            Optional<SpecialService> specialServiceOpt = specialLookupService.findSpecialService(numberForSpecialCheck, indicatorIdForSpecial, originCountryId);
            if (specialServiceOpt.isPresent()) {
                SpecialService specialService = specialServiceOpt.get();
                log.debug("Call matches Special Service: {}", specialService.getId());
                rateCalculationService.applySpecialServicePricing(specialService, callBuilder);
                callBuilder.dial(numberForSpecialCheck); // Update DIAL
                return; // Special service processing complete
            }
        }

        // --- 2. Check if Internal (PHP: es_llamada_interna -> procesaInterna) ---
        // Clean number *with* PBX prefix removal for internal check
        boolean shouldRemovePrefixForInternal = getPrefixLength(effectiveDialedNumber, pbxPrefixes) > 0;
        String cleanedNumberForInternalCheck = cleanNumber(effectiveDialedNumber, pbxPrefixes, shouldRemovePrefixForInternal);
        CdrProcessingConfig.ExtensionLengthConfig extConfig = configService.getExtensionLengthConfig(commLocation.getId());
        if (isInternalCall(effectiveCallingNumber, cleanedNumberForInternalCheck, extConfig)) {
            log.debug("Processing as internal call (effective: {} -> cleaned: {})", effectiveCallingNumber, cleanedNumberForInternalCheck);
            processInternalCall(standardDto, commLocation, callBuilder, cleanedNumberForInternalCheck, extConfig);
            return; // Internal call processing complete
        }

        // --- 3. Check PBX Rule (PHP: evaluarPBXEspecial) ---
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
            return; // Stop processing at this level
        }

        // --- 5. Evaluate Destination and Rate (External Call - PHP: procesaSaliente_Complementar -> evaluarDestino) ---
        String preprocessedNumber = preprocessNumberForLookup(numberAfterPbxRule, originCountryId);
        FieldWrapper<Long> forcedTelephonyType = new FieldWrapper<>(null); // Reset forced type
        if (!preprocessedNumber.equals(numberAfterPbxRule)) {
            log.debug("Number preprocessed for lookup: {} -> {}", numberAfterPbxRule, preprocessedNumber);
            // Determine forced type based on preprocessing result
            if (preprocessedNumber.startsWith("03") && preprocessedNumber.length() == 12) {
                forcedTelephonyType.setValue(CdrProcessingConfig.TIPOTELE_CELULAR);
            } else if (preprocessedNumber.matches("^\\d{7,8}$")) { // Assuming 7 or 8 digits is local after preprocessing
                forcedTelephonyType.setValue(CdrProcessingConfig.TIPOTELE_LOCAL);
            }
        }
        log.debug("Processing as external outgoing call (effective: {} -> preprocessed: {})", effectiveCallingNumber, preprocessedNumber);
        evaluateDestinationAndRate(standardDto, commLocation, callBuilder, preprocessedNumber, pbxPrefixes, forcedTelephonyType.getValue());
    }

    // ========================================================================
    // Incoming Call Processing (Refined)
    // ========================================================================
    private void processIncomingCall(StandardizedCallEventDto standardDto, CommunicationLocation commLocation, CallRecord.CallRecordBuilder callBuilder, String effectiveCallingNumber) {
        log.debug("Processing incoming call for CDR {} (effective caller: {})", standardDto.getGlobalCallId(), effectiveCallingNumber);

        String originalIncomingNumber = effectiveCallingNumber;
        Long originCountryId = configLookupService.getOriginCountryIdFromCommLocation(commLocation);
        Long commIndicatorId = commLocation.getIndicatorId();
        List<String> pbxPrefixes = configService.getPbxPrefixes(commLocation.getId());

        // --- Apply PBX Rule (PHP: evaluarPBXEspecial) ---
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

        // --- Preprocess (PHP: _esEntrante_60) ---
        String preprocessedNumber = preprocessNumberForLookup(numberAfterPbxRule, originCountryId);
        if (!preprocessedNumber.equals(numberAfterPbxRule)) {
            log.debug("Incoming number preprocessed for lookup: {} -> {}", numberAfterPbxRule, preprocessedNumber);
        }

        // --- Clean Number (Remove PBX prefix if present - PHP: limpiar_numero within buscarOrigen) ---
        String numberForLookup;
        int prefixLen = getPrefixLength(preprocessedNumber, pbxPrefixes);
        if (prefixLen > 0) {
            numberForLookup = preprocessedNumber.substring(prefixLen);
            numberForLookup = cleanNumber(numberForLookup, Collections.emptyList(), false);
            log.trace("Incoming call had PBX prefix, looking up origin for: {}", numberForLookup);
        } else {
            numberForLookup = cleanNumber(preprocessedNumber, Collections.emptyList(), false);
            log.trace("Incoming call had no PBX prefix, looking up origin for: {}", numberForLookup);
        }

        callBuilder.dial(numberForLookup); // Set DIAL field

        // --- Origin Lookup (PHP: buscarOrigen) ---
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
                callBuilder.indicatorId(commIndicatorId); // Assume origin is same as comm location indicator
                configService.getOperatorInternal(CdrProcessingConfig.TIPOTELE_LOCAL, originCountryId)
                        .ifPresent(op -> callBuilder.operatorId(op.getId()));
            }
        } else {
            log.warn("Missing Origin Country or Indicator for CommunicationLocation {}, cannot classify incoming call origin.", commLocation.getId());
            callBuilder.telephonyTypeId(CdrProcessingConfig.TIPOTELE_ERRORES);
            callBuilder.indicatorId(null);
        }

        // Incoming calls typically zero cost
        callBuilder.billedAmount(BigDecimal.ZERO);
        callBuilder.pricePerMinute(BigDecimal.ZERO);
        callBuilder.initialPrice(BigDecimal.ZERO);
    }

    // ========================================================================
    // Internal Call Processing (Refined)
    // ========================================================================
    private void processInternalCall(StandardizedCallEventDto standardDto, CommunicationLocation commLocation, CallRecord.CallRecordBuilder callBuilder, String destinationExtension, CdrProcessingConfig.ExtensionLengthConfig extConfig) {
        String sourceExtension = standardDto.getCallingPartyNumber();
        log.debug("Processing internal call from {} to {}", sourceExtension, destinationExtension);

        // Lookup source and destination employees
        Optional<Employee> sourceEmployeeOpt = Optional.ofNullable(callBuilder.build().getEmployee());
        Optional<Employee> destEmployeeOpt = employeeLookupService.findEmployeeByExtensionOrAuthCode(destinationExtension, null, commLocation.getId());

        LocationInfo sourceLoc = getLocationInfo(sourceEmployeeOpt.orElse(null), commLocation);
        LocationInfo destLoc = getLocationInfo(destEmployeeOpt.orElse(null), commLocation);

        Long internalCallTypeId = null;
        Long operatorId = null;
        Long indicatorId = null;
        String assumedInfo = "";

        if (destEmployeeOpt.isPresent()) {
            callBuilder.destinationEmployeeId(destEmployeeOpt.get().getId());
            callBuilder.destinationEmployee(destEmployeeOpt.get());
            indicatorId = destLoc.indicatorId; // Use destination employee's location indicator

            // --- Determine internal type based on location comparison (PHP: tipo_llamada_interna) ---
            if (sourceLoc.originCountryId != null && destLoc.originCountryId != null && !sourceLoc.originCountryId.equals(destLoc.originCountryId)) {
                internalCallTypeId = CdrProcessingConfig.TIPOTELE_INTERNACIONAL_IP;
            } else if (sourceLoc.indicatorId != null && destLoc.indicatorId != null && !sourceLoc.indicatorId.equals(destLoc.indicatorId)) {
                internalCallTypeId = CdrProcessingConfig.TIPOTELE_NACIONAL_IP;
            } else if (sourceLoc.officeId != null && destLoc.officeId != null && !sourceLoc.officeId.equals(destLoc.officeId)) {
                // PHP: Different office, same city -> LOCAL_IP
                internalCallTypeId = CdrProcessingConfig.TIPOTELE_LOCAL_IP;
            } else {
                // PHP: Same office (or one unknown) -> INTERNA_IP
                internalCallTypeId = CdrProcessingConfig.TIPOTELE_INTERNA_IP;
                if (sourceEmployeeOpt.isEmpty()) {
                    assumedInfo = ASSUMED_SUFFIX + "/Origin"; // PHP: _ASUMIDO.'/'._ORIGEN
                }
            }
            log.debug("Internal call type determined by location comparison: {}", internalCallTypeId);

        } else {
            log.warn("Internal call destination extension {} not found as employee. Checking internal prefixes.", destinationExtension);
            // --- Internal Prefix Fallback (PHP: tipo_llamada_interna -> foreach ($_lista_Prefijos['ttin']) ---
            Optional<Map<String, Object>> internalPrefixOpt = prefixLookupService.findInternalPrefixMatch(destinationExtension, sourceLoc.originCountryId);
            if (internalPrefixOpt.isPresent()) {
                internalCallTypeId = (Long) internalPrefixOpt.get().get("telephony_type_id");
                operatorId = (Long) internalPrefixOpt.get().get("operator_id");
                // Try to find indicator based on the matched internal prefix type
                Optional<Map<String, Object>> indInfo = indicatorLookupService.findIndicatorByNumber(destinationExtension, internalCallTypeId, sourceLoc.originCountryId);
                indicatorId = indInfo.map(m -> (Long) m.get("indicator_id")).filter(id -> id != null && id > 0).orElse(sourceLoc.indicatorId); // Fallback to source indicator
                log.debug("Destination {} matched internal prefix for type {}, Op {}, Ind {}", destinationExtension, internalCallTypeId, operatorId, indicatorId);
                assumedInfo = ASSUMED_SUFFIX + "/Prefix"; // PHP: _ASUMIDO + _PREFIJO
            } else {
                // --- Default if no employee and no prefix match (PHP: $interna_defecto) ---
                internalCallTypeId = CdrProcessingConfig.getDefaultInternalCallTypeId();
                indicatorId = sourceLoc.indicatorId; // Default to source indicator
                log.debug("Destination {} not found and no internal prefix matched, defaulting to type {}, Ind {}", destinationExtension, internalCallTypeId, indicatorId);
                callBuilder.assignmentCause(CallAssignmentCause.RANGES.getValue()); // Or a new cause? PHP used _ASUMIDO
                assumedInfo = ASSUMED_SUFFIX;
            }
        }

        callBuilder.telephonyTypeId(internalCallTypeId);
        callBuilder.indicatorId(indicatorId != null && indicatorId > 0 ? indicatorId : null);

        // Assign operator (either from prefix match or default internal)
        if (operatorId == null) {
            configService.getOperatorInternal(internalCallTypeId, sourceLoc.originCountryId)
                    .ifPresent(op -> callBuilder.operatorId(op.getId()));
        } else {
            callBuilder.operatorId(operatorId);
        }

        // Use RateCalculationService
        rateCalculationService.applyInternalPricing(internalCallTypeId, callBuilder, standardDto.getDurationSeconds());

        // Append assumed info suffix if applicable (handled in linkAssociatedEntities or final build step)
        // For now, just log it
        if (!assumedInfo.isEmpty()) {
            log.debug("Internal call classification included assumption: {}", assumedInfo);
            // We might want to store this suffix in a dedicated field or append to type name later
        }
    }

    // ========================================================================
    // evaluateDestinationAndRate (Refined Fallback/Normalization)
    // ========================================================================
    private void evaluateDestinationAndRate(StandardizedCallEventDto standardDto, CommunicationLocation commLocation, CallRecord.CallRecordBuilder callBuilder, String initialNumberToLookup, List<String> pbxPrefixes, Long forcedTelephonyTypeId) {
        Optional<Trunk> trunkOpt = trunkLookupService.findTrunkByCode(standardDto.getDestinationTrunkIdentifier(), commLocation.getId());
        boolean usesTrunk = trunkOpt.isPresent();
        Long originIndicatorId = commLocation.getIndicatorId();
        Long originCountryId = configLookupService.getOriginCountryIdFromCommLocation(commLocation);

        Optional<Map<String, Object>> finalRateInfoOpt = attemptRateLookup(standardDto, commLocation, callBuilder, initialNumberToLookup, pbxPrefixes, trunkOpt, forcedTelephonyTypeId);
        boolean lookupFailed = finalRateInfoOpt.isEmpty();

        // --- Fallback Logic (PHP: Normalization - if lookup failed and trunk was used) ---
        if (lookupFailed && usesTrunk) {
            log.warn("Initial rate lookup failed for trunk call {}, attempting fallback (no trunk info)", standardDto.getGlobalCallId());
            finalRateInfoOpt = attemptRateLookup(standardDto, commLocation, callBuilder, initialNumberToLookup, pbxPrefixes, Optional.empty(), forcedTelephonyTypeId);

            // Check if fallback succeeded where trunk lookup failed
            if (finalRateInfoOpt.isPresent()) {
                Map<String, Object> fallbackRateInfo = finalRateInfoOpt.get();
                Long originalType = callBuilder.build().getTelephonyTypeId(); // Get type from initial (failed) attempt if set
                Long fallbackType = (Long) fallbackRateInfo.get("telephony_type_id");
                Long originalIndicator = callBuilder.build().getIndicatorId();
                Long fallbackIndicator = (Long) fallbackRateInfo.get("indicator_id");

                // PHP logic: Only use fallback if type or indicator differs and fallback type is valid
                if ((originalType == null || !originalType.equals(fallbackType) || originalIndicator == null || !originalIndicator.equals(fallbackIndicator))
                        && fallbackType != null && fallbackType > 0 && fallbackType != CdrProcessingConfig.TIPOTELE_ERRORES) {
                    log.info("Using fallback rate info for trunk call {} as it differs from initial failed attempt.", standardDto.getGlobalCallId());
                    lookupFailed = false; // Fallback succeeded and is different
                } else {
                    log.warn("Fallback rate info for trunk call {} is same as failed attempt or invalid, discarding fallback.", standardDto.getGlobalCallId());
                    finalRateInfoOpt = Optional.empty(); // Discard fallback result
                    lookupFailed = true; // Still considered failed
                }
            }
        }

        if (finalRateInfoOpt.isPresent() && !lookupFailed) {
            Map<String, Object> finalRateInfo = finalRateInfoOpt.get();
            callBuilder.telephonyTypeId((Long) finalRateInfo.get("telephony_type_id"));
            callBuilder.operatorId((Long) finalRateInfo.get("operator_id"));
            callBuilder.indicatorId((Long) finalRateInfo.get("indicator_id")); // Can be null
            callBuilder.dial((String) finalRateInfo.getOrDefault("effective_number", initialNumberToLookup));

            boolean appliedTrunkPricing = finalRateInfo.containsKey("applied_trunk_pricing") && (Boolean) finalRateInfo.get("applied_trunk_pricing");
            if (appliedTrunkPricing && usesTrunk) {
                rateCalculationService.applyTrunkPricing(trunkOpt.get(), finalRateInfo, standardDto.getDurationSeconds(), originIndicatorId, originCountryId, standardDto.getCallStartTime(), callBuilder);
            } else {
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
    // attemptRateLookup (Refined to handle 'Assumed' logic)
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

        // Determine effective number based on trunk rules (PHP: limpiar_numero logic within evaluarDestino_pos)
        String effectiveNumber = initialNumberToLookup;
        boolean removePrefixForLookup = true; // Default
        if (usesTrunk) {
            if (trunk.getNoPbxPrefix() != null && trunk.getNoPbxPrefix()) {
                // If trunk ignores PBX prefix, start with the raw called number for prefix matching
                effectiveNumber = cleanNumber(standardDto.getCalledPartyNumber(), Collections.emptyList(), false);
                log.trace("Attempting lookup (Trunk {} ignores PBX prefix): {}", trunk.getId(), effectiveNumber);
                removePrefixForLookup = false; // Don't remove PBX prefix again if trunk already ignores it
            } else {
                 // Trunk uses PBX prefix, clean the initial number (which might already be cleaned)
                effectiveNumber = cleanNumber(initialNumberToLookup, pbxPrefixes, true);
                log.trace("Attempting lookup (Trunk {} uses PBX prefix, effective number): {}", trunk.getId(), effectiveNumber);
                removePrefixForLookup = true; // Allow prefix removal by prefix rules
            }
        } else {
            // No trunk, clean the initial number allowing PBX prefix removal
            effectiveNumber = cleanNumber(initialNumberToLookup, pbxPrefixes, true);
            log.trace("Attempting lookup (No trunk, effective number): {}", effectiveNumber);
            removePrefixForLookup = true;
        }


        List<Map<String, Object>> prefixes = prefixLookupService.findPrefixesByNumber(effectiveNumber, originCountryId);

        if (forcedTelephonyTypeId != null) {
            prefixes = prefixes.stream()
                    .filter(p -> forcedTelephonyTypeId.equals(p.get("telephony_type_id")))
                    .collect(Collectors.toList());
             if (!prefixes.isEmpty()) {
                log.debug("Lookup filtered to forced TelephonyType ID: {}", forcedTelephonyTypeId);
            } else {
                log.warn("Forced TelephonyType ID {} has no matching prefixes for number {}", forcedTelephonyTypeId, effectiveNumber);
                 // If forced type has no prefixes, we likely can't rate it correctly. Return empty?
                 // PHP might have defaulted to LOCAL or ERRORES. Let's return empty for now.
                 return Optional.empty();
            }
        }

        Map<String, Object> assumedRateInfo = null;
        boolean allPrefixesConsistent = !prefixes.isEmpty();
        Long firstOperatorId = null;
        Long firstTelephonyTypeId = null;

        for (Map<String, Object> prefixInfo : prefixes) {
            // --- Extract prefix info (same as before) ---
            Long currentTelephonyTypeId = (Long) prefixInfo.get("telephony_type_id");
            Long currentOperatorId = (Long) prefixInfo.get("operator_id");
            String currentPrefixCode = (String) prefixInfo.get("prefix_code");
            Long currentPrefixId = (Long) prefixInfo.get("prefix_id");
            int typeMinLength = (Integer) prefixInfo.get("telephony_type_min");
            int typeMaxLength = (Integer) prefixInfo.get("telephony_type_max");
            boolean bandOk = (Boolean) prefixInfo.get("prefix_band_ok");

            // --- Check for consistency (same as before) ---
            if (firstOperatorId == null) {
                firstOperatorId = currentOperatorId;
                firstTelephonyTypeId = currentTelephonyTypeId;
            } else if (!firstOperatorId.equals(currentOperatorId) || !firstTelephonyTypeId.equals(currentTelephonyTypeId)) {
                allPrefixesConsistent = false;
            }

            // --- Determine number for indicator lookup (Refined prefix removal logic) ---
            String numberWithoutPrefix = effectiveNumber;
            boolean prefixRemoved = false;
            boolean removePrefixNow = removePrefixForLookup; // Start with initial decision

            if (StringUtils.hasText(currentPrefixCode) && effectiveNumber.startsWith(currentPrefixCode)) {
                // Check trunk rate rules if applicable
                if (usesTrunk) {
                    Long operatorTroncal = findEffectiveTrunkOperator(trunk, currentTelephonyTypeId, currentPrefixCode, currentOperatorId);
                    if (operatorTroncal != null && operatorTroncal >= 0) {
                        Optional<TrunkRate> trOpt = trunkLookupService.findTrunkRate(trunk.getId(), operatorTroncal, currentTelephonyTypeId);
                        if (trOpt.isPresent() && trOpt.get().getNoPrefix() != null && trOpt.get().getNoPrefix()) {
                            removePrefixNow = false; // Override: Trunk rate says DO NOT remove prefix
                            log.trace("TrunkRate for prefix {} (effective op {}) prevents prefix removal during indicator lookup", currentPrefixCode, operatorTroncal);
                        }
                    }
                }
                // Remove prefix if allowed AND it's not a LOCAL call (PHP logic)
                if (removePrefixNow && currentTelephonyTypeId != CdrProcessingConfig.TIPOTELE_LOCAL) {
                    numberWithoutPrefix = effectiveNumber.substring(currentPrefixCode.length());
                    prefixRemoved = true;
                    log.trace("Prefix {} removed for indicator lookup (Type: {}), remaining: {}", currentPrefixCode, currentTelephonyTypeId, numberWithoutPrefix);
                } else if (removePrefixNow) {
                     log.trace("Prefix {} not removed for indicator lookup (Type: {})", currentPrefixCode, currentTelephonyTypeId);
                }
            }

            // --- Adjust length checks based on whether prefix was removed ---
            int prefixLength = prefixRemoved ? (currentPrefixCode != null ? currentPrefixCode.length() : 0) : 0;
            int effectiveMinLength = Math.max(0, typeMinLength - prefixLength);
            int effectiveMaxLength = (typeMaxLength > 0) ? Math.max(0, typeMaxLength - prefixLength) : 0; // 0 means no max length check

            if (numberWithoutPrefix.length() < effectiveMinLength) {
                log.trace("Skipping prefix {} - number part {} too short (min {})", currentPrefixCode, numberWithoutPrefix, effectiveMinLength);
                continue;
            }
            String numberForIndicatorLookup = numberWithoutPrefix;
            if (effectiveMaxLength > 0 && numberWithoutPrefix.length() > effectiveMaxLength) {
                log.trace("Trimming number part {} to max length {}", numberWithoutPrefix, effectiveMaxLength);
                numberForIndicatorLookup = numberWithoutPrefix.substring(0, effectiveMaxLength);
            }

            // --- Indicator Lookup (same as before) ---
            Optional<Map<String, Object>> indicatorInfoOpt = indicatorLookupService.findIndicatorByNumber(
                    numberForIndicatorLookup, currentTelephonyTypeId, originCountryId
            );

            // --- Check Match (same as before) ---
            boolean destinationFound = indicatorInfoOpt.isPresent();
            // PHP logic: lengthMatch only matters if destination wasn't found
            boolean lengthMatch = (!destinationFound && effectiveMaxLength > 0 && numberForIndicatorLookup.length() == effectiveMaxLength);
            boolean considerMatch = destinationFound || lengthMatch;

            if (considerMatch) {
                // --- Process Match (same as before, including local extended check) ---
                Long destinationIndicatorId = indicatorInfoOpt
                        .map(ind -> (Long) ind.get("indicator_id"))
                        .filter(id -> id != null && id > 0)
                        .orElse(null);
                Integer destinationNdc = indicatorInfoOpt.map(ind -> (Integer) ind.get("series_ndc")).orElse(null);

                Long finalTelephonyTypeId = currentTelephonyTypeId;
                Long finalPrefixId = currentPrefixId;
                boolean finalBandOk = bandOk;
                String typeSuffix = "";

                if (currentTelephonyTypeId == CdrProcessingConfig.TIPOTELE_LOCAL && destinationNdc != null) {
                    boolean isExtended = indicatorLookupService.isLocalExtended(destinationNdc, originIndicatorId);
                    if (isExtended) {
                        finalTelephonyTypeId = CdrProcessingConfig.TIPOTELE_LOCAL_EXT;
                        typeSuffix = " (Local Extended)";
                        log.debug("Reclassified call to {} as LOCAL_EXTENDED based on NDC {} and origin {}", effectiveNumber, destinationNdc, originIndicatorId);
                        // Find prefix for local extended
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

                // --- Find Rate Info (same as before) ---
                Optional<Map<String, Object>> rateInfoOpt = findRateInfo(finalPrefixId, destinationIndicatorId, originIndicatorId, finalBandOk);

                if (rateInfoOpt.isPresent()) {
                    Map<String, Object> rateInfo = rateInfoOpt.get();
                    rateInfo.put("telephony_type_id", finalTelephonyTypeId);
                    rateInfo.put("operator_id", currentOperatorId);
                    rateInfo.put("indicator_id", destinationIndicatorId);
                    rateInfo.put("telephony_type_name", entityLookupService.findTelephonyTypeById(finalTelephonyTypeId).map(TelephonyType::getName).orElse("Unknown Type") + typeSuffix);
                    rateInfo.put("operator_name", entityLookupService.findOperatorById(currentOperatorId).map(Operator::getName).orElse("Unknown Operator"));
                    rateInfo.put("destination_name", indicatorInfoOpt.map(this::formatDestinationName).orElse(lengthMatch ? "Unknown (Length Match)" : "Unknown Destination"));
                    rateInfo.put("band_name", rateInfo.get("band_name"));
                    rateInfo.put("effective_number", effectiveNumber);
                    rateInfo.put("applied_trunk_pricing", usesTrunk);

                    log.debug("Attempt successful: Found rate for prefix {}, indicator {}", currentPrefixCode, destinationIndicatorId);
                    return Optional.of(rateInfo);
                } else {
                    // --- Handle Assumed Rate (PHP logic) ---
                    log.warn("Rate info not found for prefix {}, indicator {}", currentPrefixCode, destinationIndicatorId);
                    if (assumedRateInfo == null && allPrefixesConsistent) {
                        assumedRateInfo = new HashMap<>();
                        assumedRateInfo.put("telephony_type_id", finalTelephonyTypeId);
                        assumedRateInfo.put("operator_id", currentOperatorId);
                        assumedRateInfo.put("indicator_id", null); // Assumed has null indicator
                        assumedRateInfo.put("telephony_type_name", entityLookupService.findTelephonyTypeById(finalTelephonyTypeId).map(TelephonyType::getName).orElse("Unknown Type") + ASSUMED_SUFFIX);
                        assumedRateInfo.put("operator_name", entityLookupService.findOperatorById(currentOperatorId).map(Operator::getName).orElse("Unknown Operator"));
                        assumedRateInfo.put("destination_name", "Assumed Destination");
                        assumedRateInfo.put("effective_number", effectiveNumber);
                        assumedRateInfo.put("applied_trunk_pricing", usesTrunk);
                        // Get base rate for the assumed prefix
                        Optional<Map<String, Object>> baseRateOpt = prefixLookupService.findBaseRateForPrefix(finalPrefixId);
                        baseRateOpt.ifPresent(assumedRateInfo::putAll);
                        log.debug("Storing assumed rate info based on consistent prefix {} (Type: {}, Op: {})", currentPrefixCode, finalTelephonyTypeId, currentOperatorId);
                    }
                }
            } else {
                log.trace("No indicator found and length mismatch for prefix {}, number part {}", currentPrefixCode, numberForIndicatorLookup);
            }
        } // End prefix loop

        // --- Fallbacks (same as before) ---
        if (prefixes.isEmpty() && !usesTrunk) {
            log.debug("No prefix found for non-trunk call, attempting lookup as LOCAL for {}", effectiveNumber);
            Optional<Map<String, Object>> localRateInfoOpt = findRateInfoForLocal(commLocation, effectiveNumber);
            if (localRateInfoOpt.isPresent()) {
                Map<String, Object> localRateInfo = localRateInfoOpt.get();
                localRateInfo.put("effective_number", effectiveNumber);
                localRateInfo.put("applied_trunk_pricing", false);
                return localRateInfoOpt;
            } else {
                log.warn("LOCAL fallback failed for number: {}", effectiveNumber);
            }
        }

        // --- Return Assumed Rate if applicable (PHP logic) ---
        if (assumedRateInfo != null && allPrefixesConsistent) {
            log.warn("Using assumed rate info for number {} based on consistent prefix type/operator.", effectiveNumber);
            return Optional.of(assumedRateInfo);
        }

        log.warn("Attempt failed: No matching rate found for number: {}", effectiveNumber);
        return Optional.empty();
    }

    // ========================================================================
    // findRateInfoForLocal (Remains the same)
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

        Optional<Map<String, Object>> indicatorInfoOpt = indicatorLookupService.findIndicatorByNumber(
                effectiveNumber, localType, originCountryId
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

        if (destinationNdc != null) {
            boolean isExtended = indicatorLookupService.isLocalExtended(destinationNdc, originIndicatorId);
            if (isExtended) {
                finalTelephonyTypeId = CdrProcessingConfig.TIPOTELE_LOCAL_EXT;
                typeSuffix = " (Local Extended)";
                log.debug("Reclassified LOCAL fallback call to {} as LOCAL_EXTENDED based on NDC {} and origin {}", effectiveNumber, destinationNdc, originIndicatorId);
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

        Optional<Map<String, Object>> rateInfoOpt = findRateInfo(finalPrefixId, destinationIndicatorId, originIndicatorId, finalBandOk);

        if (rateInfoOpt.isPresent()) {
            Map<String, Object> rateInfo = rateInfoOpt.get();
            rateInfo.put("telephony_type_id", finalTelephonyTypeId);
            rateInfo.put("operator_id", internalOperatorId);
            rateInfo.put("indicator_id", destinationIndicatorId);
            rateInfo.put("telephony_type_name", entityLookupService.findTelephonyTypeById(finalTelephonyTypeId).map(TelephonyType::getName).orElse("Unknown Type") + typeSuffix);
            rateInfo.put("operator_name", internalOpOpt.get().getName());
            rateInfo.put("destination_name", formatDestinationName(indicatorInfoOpt.get()));
            rateInfo.put("band_name", rateInfo.get("band_name"));
            return Optional.of(rateInfo);
        } else {
            log.warn("Rate info not found for LOCAL fallback (Type: {}, Prefix: {}, Indicator: {})", finalTelephonyTypeId, finalPrefixId, destinationIndicatorId);
        }

        return Optional.empty();
    }

    // ========================================================================
    // findRateInfo (Remains the same)
    // ========================================================================
    private Optional<Map<String, Object>> findRateInfo(Long prefixId, Long indicatorId, Long originIndicatorId, boolean bandOk) {
        Optional<Map<String, Object>> baseRateOpt = prefixLookupService.findBaseRateForPrefix(prefixId);
        if (baseRateOpt.isEmpty()) {
            log.warn("Base rate info not found for prefixId: {}", prefixId);
            return Optional.empty();
        }
        Map<String, Object> rateInfo = new HashMap<>(baseRateOpt.get());
        rateInfo.put("band_id", 0L); // Default band ID
        rateInfo.put("band_name", ""); // Default band name
        rateInfo.putIfAbsent("base_value", BigDecimal.ZERO);
        rateInfo.putIfAbsent("vat_included", false);
        rateInfo.putIfAbsent("vat_value", BigDecimal.ZERO);

        boolean useBands = bandOk && indicatorId != null && indicatorId > 0; // Band lookup requires a valid indicator ID
        log.trace("findRateInfo: prefixId={}, indicatorId={}, originIndicatorId={}, bandOk={}, useBands={}",
                prefixId, indicatorId, originIndicatorId, bandOk, useBands);

        if (useBands) {
            Optional<Map<String, Object>> bandOpt = bandLookupService.findBandByPrefixAndIndicator(prefixId, indicatorId, originIndicatorId);
            if (bandOpt.isPresent()) {
                 Map<String, Object> bandInfo = bandOpt.get();
                rateInfo.put("base_value", bandInfo.get("band_value"));
                rateInfo.put("vat_included", bandInfo.get("band_vat_included"));
                rateInfo.put("band_id", bandInfo.get("band_id"));
                rateInfo.put("band_name", bandInfo.get("band_name"));
                log.trace("Using band rate for prefix {}, indicator {}: BandID={}, Value={}, VatIncluded={}",
                        prefixId, indicatorId, bandInfo.get("band_id"), bandInfo.get("band_value"), bandInfo.get("band_vat_included"));
            } else {
                log.trace("Band lookup enabled for prefix {} but no matching band found for indicator {}", prefixId, indicatorId);
                // Keep using base rate if no band found
            }
        } else {
            log.trace("Using base rate for prefix {} (Bands not applicable or indicator missing/null)", prefixId);
        }
        // Map final values for RateCalculationService
        rateInfo.put("valor_minuto", rateInfo.get("base_value"));
        rateInfo.put("valor_minuto_iva", rateInfo.get("vat_included"));
        rateInfo.put("iva", rateInfo.get("vat_value")); // Keep the VAT percentage

        return Optional.of(rateInfo);
    }

    // ========================================================================
    // findIncomingOrigin (Refined to match PHP logic)
    // ========================================================================
    private Optional<Map<String, Object>> findIncomingOrigin(String numberForLookup, Long originCountryId, Long commIndicatorId) {
        log.debug("Finding origin for incoming number: {}, originCountryId: {}, commIndicatorId: {}", numberForLookup, originCountryId, commIndicatorId);

        // Get all TelephonyTypeConfigs for the origin country
        List<TelephonyTypeConfig> typeConfigs = configLookupService.findAllTelephonyTypeConfigsByCountry(originCountryId);

        // Sort configs: prioritize longer max lengths, then shorter min lengths (PHP: prefijos_OrdenarEntrantes)
        typeConfigs.sort(Comparator.<TelephonyTypeConfig, Integer>comparing(cfg -> Optional.ofNullable(cfg.getMaxValue()).orElse(0)).reversed()
                .thenComparing(cfg -> Optional.ofNullable(cfg.getMinValue()).orElse(0)));

        int numberLength = numberForLookup.length();

        for (TelephonyTypeConfig config : typeConfigs) {
            Long currentTelephonyTypeId = config.getTelephonyTypeId();
            int minLength = Optional.ofNullable(config.getMinValue()).orElse(0);
            int maxLength = Optional.ofNullable(config.getMaxValue()).orElse(0);

            // Skip internal types, special services, and types where length doesn't match config
            if (configService.isInternalTelephonyType(currentTelephonyTypeId) ||
                currentTelephonyTypeId.equals(CdrProcessingConfig.TIPOTELE_ESPECIALES) ||
                currentTelephonyTypeId.equals(CdrProcessingConfig.TIPOTELE_CELUFIJO) || // PHP skipped Celufijo
                numberLength < minLength ||
                (maxLength > 0 && numberLength > maxLength)) {
                log.trace("Skipping type {} for incoming lookup (Internal/Special/Celufijo or length mismatch: {} not in [{}, {}])", currentTelephonyTypeId, numberLength, minLength, maxLength);
                continue;
            }

            log.trace("Attempting incoming origin lookup for type {}: Number={}, Length Range=[{}, {}]", currentTelephonyTypeId, numberForLookup, minLength, maxLength);

            // Try to find an indicator match for this type
            Optional<Map<String, Object>> indicatorInfoOpt = indicatorLookupService.findIndicatorByNumber(
                    numberForLookup, currentTelephonyTypeId, originCountryId
            );

            if (indicatorInfoOpt.isPresent()) {
                Map<String, Object> indicatorInfo = indicatorInfoOpt.get();
                Long foundIndicatorId = (Long) indicatorInfo.get("indicator_id");
                Long foundOperatorId = (Long) indicatorInfo.get("operator_id");
                Integer foundNdc = (Integer) indicatorInfo.get("series_ndc");

                Long finalTelephonyTypeId = currentTelephonyTypeId;
                String destinationName = formatDestinationName(indicatorInfo);

                // Check if it's local extended (PHP logic)
                if (currentTelephonyTypeId == CdrProcessingConfig.TIPOTELE_LOCAL && foundNdc != null) {
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
                return Optional.of(originResult); // Return the first valid match found based on sorted types
            } else {
                 log.trace("No indicator match found for incoming number {} with type {}", numberForLookup, currentTelephonyTypeId);
            }
        }

        log.warn("Could not determine origin for incoming number {}", numberForLookup);
        return Optional.empty(); // No match found after checking all relevant types
    }

    // --- Helper Methods (Keep relevant ones) ---

    @Getter @Setter
    private static class FieldWrapper<T> { T value; FieldWrapper(T v) { this.value = v; } }

    private record LocationInfo(Long indicatorId, Long originCountryId, Long officeId) {}

    private LocationInfo getLocationInfo(Employee employee, CommunicationLocation defaultLocation) {
        Long defaultIndicatorId = defaultLocation.getIndicatorId();
        Long defaultOriginCountryId = configLookupService.getOriginCountryIdFromCommLocation(defaultLocation);
        Long defaultOfficeId = null; // Office ID isn't directly on CommLocation

        if (employee != null) {
            Long empOfficeId = employee.getSubdivisionId(); // Direct subdivision ID acts as office ID here
            Long empOriginCountryId = defaultOriginCountryId;
            Long empIndicatorId = defaultIndicatorId;

            // 1. Check Employee's assigned CommunicationLocation
            if (employee.getCommunicationLocationId() != null && !employee.getCommunicationLocationId().equals(defaultLocation.getId())) {
                Optional<CommunicationLocation> empLocOpt = entityLookupService.findCommunicationLocationById(employee.getCommunicationLocationId());
                if (empLocOpt.isPresent()) {
                    CommunicationLocation empLoc = empLocOpt.get();
                    empIndicatorId = empLoc.getIndicatorId() != null ? empLoc.getIndicatorId() : defaultIndicatorId;
                    Long empLocCountryId = configLookupService.getOriginCountryIdFromCommLocation(empLoc);
                    empOriginCountryId = empLocCountryId != null ? empLocCountryId : defaultOriginCountryId;
                    log.trace("Using location info from Employee's specific CommLocation {}: Indicator={}, Country={}", employee.getCommunicationLocationId(), empIndicatorId, empOriginCountryId);
                    // Specific location found, return it (office ID remains employee's direct one)
                    return new LocationInfo(empIndicatorId, empOriginCountryId, empOfficeId);
                } else {
                    log.warn("Employee {} has CommLocationId {} assigned, but location not found.", employee.getId(), employee.getCommunicationLocationId());
                }
            }

            // 2. Check CostCenter for OriginCountry override
            if (employee.getCostCenterId() != null) {
                Optional<CostCenter> ccOpt = entityLookupService.findCostCenterById(employee.getCostCenterId());
                if (ccOpt.isPresent() && ccOpt.get().getOriginCountryId() != null) {
                    empOriginCountryId = ccOpt.get().getOriginCountryId();
                    log.trace("Overriding OriginCountry to {} based on Employee's CostCenter {}", empOriginCountryId, employee.getCostCenterId());
                }
            }

            // 3. Determine Office ID from Subdivision hierarchy (PHP: Subdireccion_Oficina)
            if (employee.getSubdivisionId() != null) {
                 Optional<Subdivision> officeSubdivision = findOfficeSubdivision(employee.getSubdivisionId());
                 if (officeSubdivision.isPresent()) {
                     empOfficeId = officeSubdivision.get().getId();
                     log.trace("Determined Office ID {} for employee {} from subdivision hierarchy", empOfficeId, employee.getId());
                 } else {
                     log.warn("Could not determine office ID for employee {} from subdivision {}", employee.getId(), employee.getSubdivisionId());
                     empOfficeId = null; // Fallback if hierarchy lookup fails
                 }
            }


            log.trace("Final location info for Employee {}: Indicator={}, Country={}, Office={}", employee.getId(), empIndicatorId, empOriginCountryId, empOfficeId);
            return new LocationInfo(empIndicatorId, empOriginCountryId, empOfficeId);
        }

        // No employee, use defaults from the call's CommunicationLocation
        log.trace("Using default location info: Indicator={}, Country={}, Office={}", defaultIndicatorId, defaultOriginCountryId, defaultOfficeId);
        return new LocationInfo(defaultIndicatorId, defaultOriginCountryId, defaultOfficeId);
    }

    // Helper to find the top-level subdivision (office)
    private Optional<Subdivision> findOfficeSubdivision(Long subdivisionId) {
        if (subdivisionId == null) return Optional.empty();
        Subdivision current = entityLookupService.findSubdivisionById(subdivisionId).orElse(null);
        int depth = 0;
        while (current != null && current.getParentSubdivisionId() != null && current.getParentSubdivisionId() > 0 && depth < 10) { // Depth limit
            Subdivision parent = entityLookupService.findSubdivisionById(current.getParentSubdivisionId()).orElse(null);
            if (parent == null) break; // Parent not found
            current = parent;
            depth++;
        }
        return Optional.ofNullable(current);
    }


    private String formatDestinationName(Map<String, Object> indicatorInfo) {
        String city = (String) indicatorInfo.get("city_name");
        String country = (String) indicatorInfo.get("department_country");
        if (StringUtils.hasText(city) && StringUtils.hasText(country)) return city + " (" + country + ")";
        return StringUtils.hasText(city) ? city : (StringUtils.hasText(country) ? country : "Unknown Destination");
    }

    private String preprocessNumberForLookup(String number, Long originCountryId) {
         if (number == null || originCountryId == null || !originCountryId.equals(COLOMBIA_ORIGIN_COUNTRY_ID)) {
            return number; // Apply rules only for Colombia
        }

        int len = number.length();
        String originalNumber = number;
        String processedNumber = number;

        // --- Logic from _esCelular_fijo ---
        if (len == 10) {
            if (number.startsWith("3")) {
                if (number.matches("^3[0-4][0-9]\\d{7}$")) {
                    processedNumber = "03" + number; // Add prefix for internal consistency
                    log.trace("Preprocessing Colombian 10-digit mobile: {} -> {}", originalNumber, processedNumber);
                }
            } else if (number.startsWith("60")) {
                String nationalPrefix = determineNationalPrefix(number);
                if (nationalPrefix != null) {
                    processedNumber = nationalPrefix + number.substring(2);
                    log.trace("Preprocessing Colombian 10-digit fixed (mapped): {} -> {}", originalNumber, processedNumber);
                } else {
                    if (number.matches("^60\\d{8}$")) {
                        processedNumber = number.substring(3); // Assume local: Keep 7 digits
                        log.trace("Preprocessing Colombian 10-digit fixed (local assumption): {} -> {}", originalNumber, processedNumber);
                    } else {
                         log.warn("Unexpected Colombian 10-digit number starting with 60: {}", number);
                    }
                }
            }
        }
        // --- Logic from _esEntrante_60 ---
        else if (len == 12) {
            if (number.startsWith("573") || number.startsWith("603")) {
                 if (number.matches("^(57|60)3[0-4][0-9]\\d{7}$")) {
                    processedNumber = number.substring(2); // Remove 57 or 60 prefix -> 10 digits
                    log.trace("Preprocessing Colombian 12-digit mobile: {} -> {}", originalNumber, processedNumber);
                 }
            } else if (number.startsWith("6060") || number.startsWith("5760")) {
                 if (number.matches("^(57|60)60\\d{8}$")) {
                    processedNumber = number.substring(4); // Remove 5760 or 6060 -> 8 digits
                    log.trace("Preprocessing Colombian 12-digit fixed: {} -> {}", originalNumber, processedNumber);
                }
            }
        } else if (len == 11) {
            if (number.startsWith("03")) {
                 if (number.matches("^03[0-4][0-9]\\d{7}$")) {
                    processedNumber = number.substring(1); // Remove leading 0 -> 10 digits
                    log.trace("Preprocessing Colombian 11-digit mobile (03): {} -> {}", originalNumber, processedNumber);
                 }
            } else if (number.startsWith("604")) { // Example specific NDC check
                 if (number.matches("^604\\d{8}$")) {
                    processedNumber = number.substring(3); // Remove 604 -> 8 digits
                    log.trace("Preprocessing Colombian 11-digit fixed (604): {} -> {}", originalNumber, processedNumber);
                }
            }
        } else if (len == 9 && number.startsWith("60")) {
             if (number.matches("^60\\d{7}$")) {
                processedNumber = number.substring(2); // Remove 60, keep 7 digits
                log.trace("Preprocessing Colombian 9-digit fixed (60): {} -> {}", originalNumber, processedNumber);
            }
        }

        // --- Logic from _es_Saliente (PHP) ---
        if (len == 11 && number.startsWith("03")) {
             if (number.matches("^03[0-4][0-9]\\d{7}$")) {
                 processedNumber = number.substring(1); // Remove leading 0 -> 10 digits
                 log.trace("Preprocessing Colombian 11-digit outgoing mobile (03): {} -> {}", originalNumber, processedNumber);
             }
        }

        if (!originalNumber.equals(processedNumber)) {
            log.debug("Preprocessed number for lookup: {} -> {}", originalNumber, processedNumber);
        }
        return processedNumber;
    }

    private String determineNationalPrefix(String number10Digit) {
         if (number10Digit == null || !number10Digit.startsWith("60") || number10Digit.length() != 10) {
            return null;
        }
        String ndcStr = number10Digit.substring(2, 3); // Assumes single digit NDC after 60
        String subscriberNumberStr = number10Digit.substring(3);

        if (!ndcStr.matches("\\d") || !subscriberNumberStr.matches("\\d+")) {
            return null;
        }
        int ndc = Integer.parseInt(ndcStr);
        long subscriberNumber = Long.parseLong(subscriberNumberStr);

        Optional<Map<String, Object>> seriesInfoOpt = indicatorLookupService.findSeriesInfoForNationalLookup(ndc, subscriberNumber);

        if (seriesInfoOpt.isPresent()) {
            String company = (String) seriesInfoOpt.get().get("series_company");
            if (company != null) {
                company = company.toUpperCase();
                // Map company names to legacy prefixes (adjust names as needed)
                if (company.contains("TELMEX") || company.contains("CLARO")) return "0456"; // Claro Fijo
                if (company.contains("COLOMBIA TELECOMUNICACIONES") || company.contains("MOVISTAR")) return "09"; // Movistar Fijo
                if (company.contains("UNE EPM") || company.contains("TIGO")) return "05"; // Tigo-Une Fijo
                if (company.contains("EMPRESA DE TELECOMUNICACIONES DE BOGOTÁ") || company.contains("ETB")) return "07"; // ETB Fijo
            }
        }
        log.warn("Could not map new 10-digit number {} to a legacy operator prefix.", number10Digit);
        return null; // Return null if no mapping found
    }

     private String cleanNumber(String number, List<String> pbxPrefixes, boolean removePrefix) {
        if (!StringUtils.hasText(number)) return "";
        String cleaned = number.trim();
        int prefixLength = 0;

        if (removePrefix && pbxPrefixes != null && !pbxPrefixes.isEmpty()) {
            prefixLength = getPrefixLength(cleaned, pbxPrefixes);
            if (prefixLength > 0) {
                cleaned = cleaned.substring(prefixLength);
                log.trace("Removed PBX prefix (length {}) from {}, result: {}", prefixLength, number, cleaned);
            } else {
                log.trace("Prefix removal requested but no matching prefix found in {}", number);
            }
        }

        boolean hasPlus = cleaned.startsWith("+");
        // Keep only digits, #, *
        cleaned = cleaned.replaceAll("[^0-9#*]", "");
        if (hasPlus && !cleaned.startsWith("+")) {
            cleaned = "+" + cleaned;
        }
        return cleaned;
    }

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

    private boolean isInternalCall(String callingNumber, String dialedNumber, CdrProcessingConfig.ExtensionLengthConfig extConfig) {
        boolean callingIsExt = isLikelyExtension(callingNumber, extConfig);
        boolean dialedIsExt = isLikelyExtension(dialedNumber, extConfig);
        log.trace("isInternalCall check: Caller '{}' (isExt: {}) -> Dialed '{}' (isExt: {})", callingNumber, callingIsExt, dialedIsExt);
        return callingIsExt && dialedIsExt;
    }

    private boolean isLikelyExtension(String number, CdrProcessingConfig.ExtensionLengthConfig extConfig) {
         if (!StringUtils.hasText(number)) return false;
        String effectiveNumber = number.startsWith("+") ? number.substring(1) : number;

        if (!effectiveNumber.matches("[\\d#*]+")) {
            log.trace("isLikelyExtension: '{}' contains invalid characters.", number);
            return false;
        }

        int numLength = effectiveNumber.length();
        if (numLength < extConfig.getMinLength() || numLength > extConfig.getMaxLength()) {
            log.trace("isLikelyExtension: '{}' length {} outside range ({}-{}).", number, numLength, extConfig.getMinLength(), extConfig.getMaxLength());
            return false;
        }

        try {
            if (effectiveNumber.matches("\\d+")) {
                long numValue = Long.parseLong(effectiveNumber);
                if (numValue > extConfig.getMaxExtensionValue()) {
                    log.trace("isLikelyExtension: '{}' value {} exceeds max value {}.", number, numValue, extConfig.getMaxExtensionValue());
                    return false;
                }
            }
        } catch (NumberFormatException e) {
            log.trace("isLikelyExtension: '{}' contains non-digits, skipping max value check.", number);
        }
        log.trace("isLikelyExtension: '{}' is considered a likely extension.", number);
        return true;
    }

     private Long findEffectiveTrunkOperator(Trunk trunk, Long telephonyTypeId, String prefixCode, Long actualOperatorId) {
        Optional<TrunkRate> specificRateOpt = trunkLookupService.findTrunkRate(trunk.getId(), actualOperatorId, telephonyTypeId);
        if (specificRateOpt.isPresent()) {
            log.trace("Found specific TrunkRate for trunk {}, op {}, type {}. Using operator {}.", trunk.getId(), actualOperatorId, telephonyTypeId, actualOperatorId);
            return actualOperatorId;
        }

        Optional<TrunkRate> globalRateOpt = trunkLookupService.findTrunkRate(trunk.getId(), 0L, telephonyTypeId);
        if (globalRateOpt.isPresent()) {
             if (globalRateOpt.get().getNoPrefix() != null && globalRateOpt.get().getNoPrefix()) {
                 Long originCountryId = configLookupService.getOriginCountryIdFromCommLocation(trunk.getCommLocation());
                 Long defaultOperatorId = configService.getOperatorInternal(telephonyTypeId, originCountryId)
                                                      .map(Operator::getId).orElse(null);
                 if (actualOperatorId != null && defaultOperatorId != null && !actualOperatorId.equals(defaultOperatorId)) {
                     List<Map<String, Object>> prefixesForCode = prefixLookupService.findPrefixesByNumber(prefixCode, originCountryId)
                         .stream().filter(p -> prefixCode.equals(p.get("prefix_code"))).toList();

                     if (prefixesForCode.size() == 1 && actualOperatorId.equals(prefixesForCode.get(0).get("operator_id"))) {
                        log.trace("Global TrunkRate ignores prefix, but actual operator {} is the *only* operator for prefix {}. Operator rule still not applicable.", actualOperatorId, prefixCode);
                        return null; // Indicate no applicable operator rule
                     } else {
                         log.trace("Global TrunkRate ignores prefix, and actual operator {} is not the default {} (or prefix {} has multiple operators). Using operator 0.", actualOperatorId, defaultOperatorId, prefixCode);
                         // Fall through to return 0L
                     }
                 } else {
                      log.trace("Global TrunkRate ignores prefix, and actual operator {} is the default {} (or null). Using operator 0.", actualOperatorId, defaultOperatorId);
                      // Fall through to return 0L
                 }
             } else {
                 log.trace("Found global TrunkRate for trunk {}, type {}. Using operator 0.", trunk.getId(), telephonyTypeId);
             }
             return 0L; // Use 0 to indicate global rule applies
        }

        log.trace("No specific or global TrunkRate found for trunk {}, op {}, type {}. Defaulting to operator 0 for potential rule lookup.", trunk.getId(), actualOperatorId, telephonyTypeId);
        return 0L; // Default to 0 for subsequent rule lookup
    }

    private void linkAssociatedEntities(CallRecord.CallRecordBuilder callBuilder) {
        CallRecord record = callBuilder.build(); // Build temporary record

        // Link Telephony Type
        if (record.getTelephonyTypeId() != null && record.getTelephonyType() == null) {
            entityLookupService.findTelephonyTypeById(record.getTelephonyTypeId())
                    .ifPresentOrElse(callBuilder::telephonyType, () -> log.warn("Could not link TelephonyType entity for ID: {}", record.getTelephonyTypeId()));
        }

        // Link Operator
        if (record.getOperatorId() != null && record.getOperator() == null) {
            entityLookupService.findOperatorById(record.getOperatorId())
                    .ifPresentOrElse(callBuilder::operator, () -> log.warn("Could not link Operator entity for ID: {}", record.getOperatorId()));
        }

        // Link Indicator
        if (record.getIndicatorId() != null && record.getIndicator() == null) {
            indicatorLookupService.findIndicatorById(record.getIndicatorId())
                    .ifPresentOrElse(callBuilder::indicator, () -> log.warn("Could not link Indicator entity for ID: {}", record.getIndicatorId()));
        } else if (record.getIndicatorId() == null) {
             log.trace("Indicator ID is null, skipping entity linking.");
        }

        // Link Destination Employee
        if (record.getDestinationEmployeeId() != null && record.getDestinationEmployee() == null) {
            employeeLookupService.findDestinationEmployeeById(record.getDestinationEmployeeId())
                    .ifPresentOrElse(callBuilder::destinationEmployee, () -> log.warn("Could not link destination employee entity for ID: {}", record.getDestinationEmployeeId()));
        }
    }

}