package com.infomedia.abacox.telephonypricing.cdr;

import com.infomedia.abacox.telephonypricing.entity.*;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import lombok.extern.log4j.Log4j2;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Log4j2
public class CdrEnrichmentService {

    private final LookupService lookupService;
    private final CdrProcessingConfig configService;

    private static final BigDecimal SIXTY = new BigDecimal("60");
    private static final Long COLOMBIA_ORIGIN_COUNTRY_ID = 1L; // Example, should be configurable or dynamic
    private static final String ASSUMED_SUFFIX = " (Assumed)";


    public Optional<CallRecord> enrichCdr(StandardizedCallEventDto standardDto, CommunicationLocation commLocation) {
        log.info("Enriching Standardized CDR: {}, Hash: {}", standardDto.getGlobalCallId(), standardDto.getCdrHash());

        CallRecord.CallRecordBuilder callBuilder = CallRecord.builder();
        callBuilder.commLocationId(commLocation.getId());
        callBuilder.commLocation(commLocation);
        callBuilder.serviceDate(standardDto.getCallStartTime());
        callBuilder.employeeExtension(standardDto.getCallingPartyNumber());
        callBuilder.employeeAuthCode(standardDto.getAuthCode());
        callBuilder.destinationPhone(standardDto.getCalledPartyNumber()); // Original called number from parser
        callBuilder.dial(standardDto.getCalledPartyNumber()); // Effective called number, might be updated
        callBuilder.duration(standardDto.getDurationSeconds());
        callBuilder.ringCount(standardDto.getRingDurationSeconds());
        callBuilder.isIncoming(standardDto.isIncoming());
        callBuilder.trunk(standardDto.getDestinationTrunkIdentifier());
        callBuilder.initialTrunk(standardDto.getSourceTrunkIdentifier());
        callBuilder.employeeTransfer(standardDto.getRedirectingPartyNumber());
        callBuilder.transferCause(CallTransferCause.fromValue(Optional.ofNullable(standardDto.getRedirectReason()).orElse(0)).getValue());
        callBuilder.assignmentCause(CallAssignmentCause.UNKNOWN.getValue());
        callBuilder.fileInfoId(standardDto.getFileInfoId());
        callBuilder.billedAmount(BigDecimal.ZERO);
        callBuilder.pricePerMinute(BigDecimal.ZERO);
        callBuilder.initialPrice(BigDecimal.ZERO);
        callBuilder.indicatorId(null);
        callBuilder.cdrHash(standardDto.getCdrHash());

        if (standardDto.isConference()) {
            log.debug("CDR {} identified as conference leg.", standardDto.getGlobalCallId());
            callBuilder.assignmentCause(CallAssignmentCause.CONFERENCE.getValue());
            Optional<Employee> confEmployeeOpt = lookupService.findEmployeeByExtensionOrAuthCode(
                    standardDto.getCallingPartyNumber(), standardDto.getAuthCode(), commLocation.getId()
            );
            confEmployeeOpt.ifPresent(employee -> {
                callBuilder.employeeId(employee.getId());
                callBuilder.employee(employee);
            });
            if (confEmployeeOpt.isEmpty()) {
                log.warn("Conference employee lookup failed for effective caller: {}", standardDto.getCallingPartyNumber());
            }
            callBuilder.telephonyTypeId(CdrProcessingConfig.TIPOTELE_INTERNA_IP); // Default for conference
            configService.getOperatorInternal(CdrProcessingConfig.TIPOTELE_INTERNA_IP, getOriginCountryId(commLocation))
                    .ifPresent(op -> callBuilder.operatorId(op.getId()));
            callBuilder.indicatorId(null); // Conferences typically don't have a destination indicator
            linkAssociatedEntities(callBuilder);
            CallRecord finalRecord = callBuilder.build();
            log.info("Processed conference CDR {}: Type={}, Billed=0", standardDto.getGlobalCallId(), finalRecord.getTelephonyTypeId());
            return Optional.of(finalRecord);
        }

        Optional<Employee> employeeOpt = lookupService.findEmployeeByExtensionOrAuthCode(
                standardDto.getCallingPartyNumber(), standardDto.getAuthCode(), commLocation.getId()
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
            // Try to assign by range if applicable (PHP logic: Validar_RangoExt)
            CdrProcessingConfig.ExtensionLengthConfig extConfig = configService.getExtensionLengthConfig(commLocation.getId());
            if (isLikelyExtension(standardDto.getCallingPartyNumber(), extConfig)) { // Check if it looks like an extension
                Optional<Map<String, Object>> rangeAssignment = lookupService.findRangeAssignment(standardDto.getCallingPartyNumber(), commLocation.getId(), standardDto.getCallStartTime());
                if (rangeAssignment.isPresent()) {
                    // Apply range assignment (e.g., cost center, subdivision)
                    // This part is simplified as employee ID from range is not directly available without creating a dummy employee
                    callBuilder.assignmentCause(CallAssignmentCause.RANGES.getValue());
                    log.debug("CDR {} assigned by extension range for caller {}", standardDto.getGlobalCallId(), standardDto.getCallingPartyNumber());
                } else {
                     callBuilder.assignmentCause(CallAssignmentCause.UNKNOWN.getValue());
                }
            } else {
                callBuilder.assignmentCause(CallAssignmentCause.UNKNOWN.getValue());
            }
        }

        try {
            if (standardDto.isIncoming()) {
                processIncomingCall(standardDto, commLocation, callBuilder, standardDto.getCallingPartyNumber());
            } else {
                processOutgoingCall(standardDto, commLocation, callBuilder,
                        standardDto.getCallingPartyNumber(), standardDto.getCalledPartyNumber());
            }
        } catch (Exception e) {
            log.error("Error during enrichment for CDR {}: {}", standardDto.getGlobalCallId(), e.getMessage(), e);
            callBuilder.telephonyTypeId(CdrProcessingConfig.TIPOTELE_ERRORES);
            callBuilder.billedAmount(BigDecimal.ZERO);
            callBuilder.pricePerMinute(BigDecimal.ZERO);
            callBuilder.initialPrice(BigDecimal.ZERO);
            callBuilder.indicatorId(null);
        }

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

    private void processOutgoingCall(StandardizedCallEventDto standardDto, CommunicationLocation commLocation, CallRecord.CallRecordBuilder callBuilder, String effectiveCallingNumber, String originalDialedNumber) {
        log.debug("Processing outgoing call for CDR {} (caller: {}, original dialed: {})", standardDto.getGlobalCallId(), effectiveCallingNumber, originalDialedNumber);

        List<String> pbxPrefixes = configService.getPbxPrefixes(commLocation.getId());
        Long originCountryId = getOriginCountryId(commLocation);

        // Step 1: PBX Special Rule transformation for outgoing
        Optional<PbxSpecialRule> pbxRuleOpt = lookupService.findPbxSpecialRule(
                originalDialedNumber, commLocation.getId(), CallDirection.OUTGOING.getValue()
        );
        String numberAfterPbxRule = originalDialedNumber;
        if (pbxRuleOpt.isPresent()) {
            PbxSpecialRule rule = pbxRuleOpt.get();
            String replacement = rule.getReplacement() != null ? rule.getReplacement() : "";
            String searchPattern = rule.getSearchPattern();
            if (StringUtils.hasText(searchPattern) && originalDialedNumber.startsWith(searchPattern)) {
                String numberAfterSearch = originalDialedNumber.substring(searchPattern.length());
                numberAfterPbxRule = replacement + numberAfterSearch;
                log.debug("Applied OUTGOING PBX rule {}, number changed from {} to {}", rule.getId(), originalDialedNumber, numberAfterPbxRule);
            }
        }
        callBuilder.dial(numberAfterPbxRule); // Update dial field with potentially transformed number

        // Step 2: Check if it's an internal call after PBX rule
        CdrProcessingConfig.ExtensionLengthConfig extConfig = configService.getExtensionLengthConfig(commLocation.getId());
        // Clean the number *after* PBX rule for internal check, but *without* PBX prefix removal yet
        String cleanedNumberForInternalCheck = cleanNumber(numberAfterPbxRule, Collections.emptyList(), false);
        if (isInternalCall(effectiveCallingNumber, cleanedNumberForInternalCheck, extConfig)) {
            log.debug("Processing as internal call (caller: {}, effective dialed for internal: {})", effectiveCallingNumber, cleanedNumberForInternalCheck);
            processInternalCall(standardDto, commLocation, callBuilder, cleanedNumberForInternalCheck, extConfig);
            return;
        }

        // Step 3: Special Service Check (on number *after* PBX rule, *before* PBX prefix removal)
        Long indicatorIdForSpecial = commLocation.getIndicatorId();
        if (indicatorIdForSpecial != null && originCountryId != null) {
            // For special service, we check the number *as dialed by user* (after PBX rule, but before PBX prefix removal)
            String numberForSpecialCheck = cleanNumber(numberAfterPbxRule, Collections.emptyList(), false);
            Optional<SpecialService> specialServiceOpt = lookupService.findSpecialService(numberForSpecialCheck, indicatorIdForSpecial, originCountryId);
            if (specialServiceOpt.isPresent()) {
                SpecialService specialService = specialServiceOpt.get();
                log.debug("Call matches Special Service: {}", specialService.getId());
                applySpecialServicePricing(specialService, callBuilder, standardDto.getDurationSeconds());
                callBuilder.dial(numberForSpecialCheck); // Ensure dial field reflects the matched special number
                return;
            }
        }

        // Step 4: General Outgoing Call (External)
        // Now, remove PBX prefix if applicable for external lookups
        boolean shouldRemovePbxPrefix = getPrefixLength(numberAfterPbxRule, pbxPrefixes) > 0;
        String numberForExternalLookup = cleanNumber(numberAfterPbxRule, pbxPrefixes, shouldRemovePbxPrefix);
        if (!numberForExternalLookup.equals(numberAfterPbxRule) && shouldRemovePbxPrefix) {
            log.debug("PBX prefix removed for external lookup: {} -> {}", numberAfterPbxRule, numberForExternalLookup);
        } else if (numberForExternalLookup.isEmpty() && shouldRemovePbxPrefix) {
            log.warn("Number {} became empty after attempting PBX prefix removal. Treating as error.", numberAfterPbxRule);
            callBuilder.telephonyTypeId(CdrProcessingConfig.TIPOTELE_ERRORES);
            callBuilder.dial(numberAfterPbxRule); // Keep original problematic number
            return;
        }
        callBuilder.dial(numberForExternalLookup); // Update dial field

        FieldWrapper<Long> forcedTelephonyType = new FieldWrapper<>(null);
        String preprocessedNumber = preprocessNumberForLookup(numberForExternalLookup, originCountryId, forcedTelephonyType, commLocation);
        if (!preprocessedNumber.equals(numberForExternalLookup)) {
            log.debug("Number preprocessed for lookup: {} -> {}", numberForExternalLookup, preprocessedNumber);
            callBuilder.dial(preprocessedNumber); // Update dial field again if preprocessed
        }

        log.debug("Processing as external outgoing call (caller: {}, final number for lookup: {})", effectiveCallingNumber, preprocessedNumber);
        evaluateDestinationAndRate(standardDto, commLocation, callBuilder, preprocessedNumber, pbxPrefixes, forcedTelephonyType.getValue());
    }


    private void processIncomingCall(StandardizedCallEventDto standardDto, CommunicationLocation commLocation, CallRecord.CallRecordBuilder callBuilder, String originalIncomingNumber) {
        log.debug("Processing incoming call for CDR {} (original caller ID: {})", standardDto.getGlobalCallId(), originalIncomingNumber);

        Long originCountryId = getOriginCountryId(commLocation);
        Long commIndicatorId = commLocation.getIndicatorId();

        // Step 1: PBX Special Rule transformation for incoming
        Optional<PbxSpecialRule> pbxRuleOpt = lookupService.findPbxSpecialRule(
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
                log.debug("Applied INCOMING PBX rule {} to incoming number, result: {} -> {}", rule.getId(), originalIncomingNumber, numberAfterPbxRule);
            }
        }
        callBuilder.dial(cleanNumber(numberAfterPbxRule, Collections.emptyList(), false)); // DIAL field for incoming is the (transformed) caller ID

        // Step 2: Preprocess (e.g., Colombian number formatting)
        FieldWrapper<Long> forcedTelephonyType = new FieldWrapper<>(null);
        String preprocessedNumber = preprocessNumberForLookup(numberAfterPbxRule, originCountryId, forcedTelephonyType, commLocation);
        if (!preprocessedNumber.equals(numberAfterPbxRule)) {
            log.debug("Incoming number preprocessed for lookup: {} -> {}", numberAfterPbxRule, preprocessedNumber);
            callBuilder.dial(cleanNumber(preprocessedNumber, Collections.emptyList(), false));
        }

        // Step 3: Determine Origin
        if (originCountryId != null && commIndicatorId != null) {
            List<Map<String, Object>> prefixes = lookupService.findPrefixesByNumber(preprocessedNumber, originCountryId);

            if (forcedTelephonyType.getValue() != null) {
                Long forcedType = forcedTelephonyType.getValue();
                prefixes = prefixes.stream()
                        .filter(p -> forcedType.equals(p.get("telephony_type_id")))
                        .collect(Collectors.toList());
                if (prefixes.isEmpty()) {
                    log.warn("Forced TelephonyType ID {} has no matching prefixes for incoming lookup number {}", forcedType, preprocessedNumber);
                } else {
                    log.debug("Filtered prefixes to forced TelephonyType ID {}", forcedType);
                }
            }

            Optional<Map<String, Object>> bestPrefix = prefixes.stream().findFirst();

            if (bestPrefix.isPresent()) {
                Map<String, Object> prefixInfo = bestPrefix.get();
                Long telephonyTypeId = (Long) prefixInfo.get("telephony_type_id");
                Long operatorId = (Long) prefixInfo.get("operator_id");
                String prefixCode = (String) prefixInfo.get("prefix_code");
                Long currentPrefixId = (Long) prefixInfo.get("prefix_id");
                boolean isBandOk = (Boolean) prefixInfo.get("prefix_band_ok");

                callBuilder.telephonyTypeId(telephonyTypeId);
                callBuilder.operatorId(operatorId);

                String numberWithoutPrefix = preprocessedNumber;
                if (StringUtils.hasText(prefixCode) && preprocessedNumber.startsWith(prefixCode)) {
                    numberWithoutPrefix = preprocessedNumber.substring(prefixCode.length());
                }

                Optional<Map<String, Object>> indicatorInfoOpt = lookupService.findIndicatorByNumber(
                        numberWithoutPrefix, telephonyTypeId, originCountryId, isBandOk, currentPrefixId, commIndicatorId
                );
                Long indicatorId = indicatorInfoOpt
                        .map(ind -> (Long) ind.get("indicator_id"))
                        .filter(id -> id != null && id > 0)
                        .orElse(null); // Use null if not found or invalid

                // For incoming, the "destination" indicator is the commLocation's indicator
                callBuilder.indicatorId(commIndicatorId);

                log.debug("Incoming call origin classified as Type ID: {}, Operator ID: {}. Destination Indicator (CommLocation): {}",
                        telephonyTypeId, operatorId, commIndicatorId);

            } else {
                log.warn("Could not classify incoming call origin for {}, assuming LOCAL", preprocessedNumber);
                callBuilder.telephonyTypeId(CdrProcessingConfig.TIPOTELE_LOCAL);
                callBuilder.indicatorId(commIndicatorId); // Destination is local
                configService.getOperatorInternal(CdrProcessingConfig.TIPOTELE_LOCAL, originCountryId)
                        .ifPresent(op -> callBuilder.operatorId(op.getId()));
            }
        } else {
            log.warn("Missing Origin Country or Indicator for CommunicationLocation {}, cannot classify incoming call origin.", commLocation.getId());
            callBuilder.telephonyTypeId(CdrProcessingConfig.TIPOTELE_ERRORES);
            callBuilder.indicatorId(null);
        }

        callBuilder.billedAmount(BigDecimal.ZERO); // Incoming calls usually not billed this way
        callBuilder.pricePerMinute(BigDecimal.ZERO);
        callBuilder.initialPrice(BigDecimal.ZERO);
    }

    private void processInternalCall(StandardizedCallEventDto standardDto, CommunicationLocation commLocation, CallRecord.CallRecordBuilder callBuilder, String destinationExtension, CdrProcessingConfig.ExtensionLengthConfig extConfig) {
        String sourceExtension = standardDto.getCallingPartyNumber();
        log.debug("Processing internal call from {} to {}", sourceExtension, destinationExtension);

        Optional<Employee> sourceEmployeeOpt = Optional.ofNullable(callBuilder.build().getEmployee());
        Optional<Employee> destEmployeeOpt = lookupService.findEmployeeByExtensionOrAuthCode(destinationExtension, null, commLocation.getId());

        LocationInfo sourceLoc = getLocationInfo(sourceEmployeeOpt.orElse(null), commLocation);
        LocationInfo destLoc = getLocationInfo(destEmployeeOpt.orElse(null), commLocation);

        Long internalCallTypeId = null;
        Long operatorId = null;
        Long indicatorId = null; // For internal, this is often the destination's indicator

        if (destEmployeeOpt.isPresent()) {
            callBuilder.destinationEmployeeId(destEmployeeOpt.get().getId());
            callBuilder.destinationEmployee(destEmployeeOpt.get());
            indicatorId = destLoc.indicatorId; // Destination employee's location indicator

            if (sourceLoc.originCountryId != null && destLoc.originCountryId != null && !sourceLoc.originCountryId.equals(destLoc.originCountryId)) {
                internalCallTypeId = CdrProcessingConfig.TIPOTELE_INTERNACIONAL_IP;
            } else if (sourceLoc.indicatorId != null && destLoc.indicatorId != null && !sourceLoc.indicatorId.equals(destLoc.indicatorId)) {
                internalCallTypeId = CdrProcessingConfig.TIPOTELE_NACIONAL_IP;
            } else if (sourceLoc.officeId != null && destLoc.officeId != null && !sourceLoc.officeId.equals(destLoc.officeId)) {
                internalCallTypeId = CdrProcessingConfig.TIPOTELE_LOCAL_IP;
            } else {
                internalCallTypeId = CdrProcessingConfig.TIPOTELE_INTERNA_IP;
            }
            log.debug("Internal call type determined by location comparison: {}", internalCallTypeId);

        } else {
            log.warn("Internal call destination extension {} not found as employee.", destinationExtension);
            // PHP: Busca en prefijos internos (PREFIJO_TIPOTELE_ID in _tipotele_Internas)
            Optional<Map<String, Object>> internalPrefixOpt = lookupService.findInternalPrefixMatch(destinationExtension, sourceLoc.originCountryId);
            if (internalPrefixOpt.isPresent()) {
                internalCallTypeId = (Long) internalPrefixOpt.get().get("telephony_type_id");
                operatorId = (Long) internalPrefixOpt.get().get("operator_id"); // Operator from prefix

                // For internal calls via prefix, the indicator might be less defined or could be based on the prefix's typical coverage
                // Or it could be the source's indicator if the prefix implies routing from source.
                // PHP logic for this case is not very explicit on indicator. Defaulting to source's.
                indicatorId = sourceLoc.indicatorId;
                log.debug("Destination {} matched internal prefix for type {}, Op {}, using source Ind {}", destinationExtension, internalCallTypeId, operatorId, indicatorId);
            } else {
                internalCallTypeId = CdrProcessingConfig.getDefaultInternalCallTypeId();
                indicatorId = sourceLoc.indicatorId; // Default to source's indicator
                log.debug("Destination {} not found and no internal prefix matched, defaulting to type {}, using source Ind {}", destinationExtension, internalCallTypeId, indicatorId);
            }
        }

        callBuilder.telephonyTypeId(internalCallTypeId);
        callBuilder.indicatorId(indicatorId != null && indicatorId > 0 ? indicatorId : null);

        if (operatorId == null && internalCallTypeId != null) { // Ensure internalCallTypeId is not null
            configService.getOperatorInternal(internalCallTypeId, sourceLoc.originCountryId)
                    .ifPresent(op -> callBuilder.operatorId(op.getId()));
        } else if (operatorId != null) {
            callBuilder.operatorId(operatorId);
        }

        applyInternalPricing(internalCallTypeId, callBuilder, standardDto.getDurationSeconds());
    }

    private void evaluateDestinationAndRate(StandardizedCallEventDto standardDto, CommunicationLocation commLocation, CallRecord.CallRecordBuilder callBuilder, String initialNumberToLookup, List<String> pbxPrefixes, Long forcedTelephonyTypeId) {
        Optional<Trunk> trunkOpt = lookupService.findTrunkByCode(standardDto.getDestinationTrunkIdentifier(), commLocation.getId());
        boolean usesTrunk = trunkOpt.isPresent();
        Long originIndicatorId = commLocation.getIndicatorId();

        Optional<Map<String, Object>> finalRateInfoOpt = attemptRateLookup(standardDto, commLocation, callBuilder, initialNumberToLookup, pbxPrefixes, trunkOpt, forcedTelephonyTypeId, originIndicatorId);

        if (finalRateInfoOpt.isEmpty() && usesTrunk) {
            log.warn("Initial rate lookup failed for trunk call {}, attempting fallback (no trunk info)", standardDto.getGlobalCallId());
            // PHP: if ($existe_troncal !== false && evaluarDestino_novalido($infovalor))
            // This means if the first attempt (with trunk) resulted in an "invalid" or "assumed" rate,
            // it tries again *without* considering the trunk (as if it were a direct call).
            finalRateInfoOpt = attemptRateLookup(standardDto, commLocation, callBuilder, initialNumberToLookup, pbxPrefixes, Optional.empty(), forcedTelephonyTypeId, originIndicatorId);
        }

        if (finalRateInfoOpt.isPresent()) {
            Map<String, Object> finalRateInfo = finalRateInfoOpt.get();
            callBuilder.telephonyTypeId((Long) finalRateInfo.get("telephony_type_id"));
            callBuilder.operatorId((Long) finalRateInfo.get("operator_id"));
            callBuilder.indicatorId((Long) finalRateInfo.get("indicator_id"));
            callBuilder.dial((String) finalRateInfo.getOrDefault("effective_number", initialNumberToLookup));

            boolean appliedTrunkPricing = finalRateInfo.containsKey("applied_trunk_pricing") && (Boolean) finalRateInfo.get("applied_trunk_pricing");
            if (appliedTrunkPricing && usesTrunk) { // Check usesTrunk again as finalRateInfo might be from fallback
                applyTrunkPricing(trunkOpt.get(), finalRateInfo, standardDto.getDurationSeconds(), originIndicatorId, callBuilder);
            } else {
                applySpecialPricing(finalRateInfo, standardDto.getCallStartTime(), standardDto.getDurationSeconds(), originIndicatorId, callBuilder);
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

    private Optional<Map<String, Object>> attemptRateLookup(
            StandardizedCallEventDto standardDto, CommunicationLocation commLocation,
            CallRecord.CallRecordBuilder callBuilder, String initialNumberToLookup,
            List<String> pbxPrefixes, Optional<Trunk> trunkOpt, Long forcedTelephonyTypeId,
            Long originCommLocationIndicatorId) {

        Long originCountryId = getOriginCountryId(commLocation);
        boolean usesTrunk = trunkOpt.isPresent();
        Trunk trunk = trunkOpt.orElse(null);

        if (originCountryId == null || originCommLocationIndicatorId == null) {
            log.error("Cannot attempt rate lookup: Missing Origin Country ({}) or Comm Location Indicator ID ({}) for Location {}",
                    originCountryId, originCommLocationIndicatorId, commLocation.getId());
            return Optional.empty();
        }

        String effectiveNumber = initialNumberToLookup;
        // PHP: if ($existe_troncal === false) { $telefono = $info_destino_limpio; }
        // This means if not using a trunk, the number was already cleaned of PBX prefix.
        // If using a trunk, the original dialed number (after PBX rule, but *with* PBX prefix) is used initially.
        // The `cleanNumber` for `numberForLookup` inside `findPrefixesByNumber` loop in PHP handles prefix removal based on trunk rules.
        if (usesTrunk) {
            // If trunk is used, start with the number that might still have PBX prefix,
            // prefix removal logic for trunk is handled per-prefix.
            // The `initialNumberToLookup` for trunked calls should be the one *before* general PBX prefix removal
            // if the trunk itself dictates prefix handling.
            // However, `initialNumberToLookup` passed here has already had PBX prefix removed if it was an outgoing non-internal, non-special call.
            // Let's assume `initialNumberToLookup` is the number to start with for prefix matching.
            // The PHP logic: `if ($existe_troncal === false) { $telefono = $info_destino_limpio; }`
            // implies that if there's a trunk, `$telefono` (which is `info_destino`) is used.
            // `info_destino` in PHP is the number *after* PBX special rule, but *before* PBX prefix removal.
            // So, if a trunk is present, we should ideally use the number *before* general PBX prefix removal.
            // This is tricky because `initialNumberToLookup` has already undergone one stage of cleaning.
            // For now, we proceed with `initialNumberToLookup`.
            log.trace("Attempting rate lookup for TRUNKED call with number: {}", effectiveNumber);
        } else {
            log.trace("Attempting rate lookup for NON-TRUNKED call with number: {}", effectiveNumber);
        }


        List<Map<String, Object>> prefixes = lookupService.findPrefixesByNumber(effectiveNumber, originCountryId);

        if (forcedTelephonyTypeId != null) {
            prefixes = prefixes.stream()
                    .filter(p -> forcedTelephonyTypeId.equals(p.get("telephony_type_id")))
                    .collect(Collectors.toList());
            if (!prefixes.isEmpty()) {
                log.debug("Lookup filtered to forced TelephonyType ID: {}", forcedTelephonyTypeId);
            } else {
                log.warn("Forced TelephonyType ID {} has no matching prefixes for number {}", forcedTelephonyTypeId, effectiveNumber);
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
            boolean currentPrefixBandOk = (Boolean) prefixInfo.get("prefix_band_ok");

            if (firstOperatorId == null) {
                firstOperatorId = currentOperatorId;
                firstTelephonyTypeId = currentTelephonyTypeId;
            } else if (!firstOperatorId.equals(currentOperatorId) || !firstTelephonyTypeId.equals(currentTelephonyTypeId)) {
                allPrefixesConsistent = false;
            }

            String numberWithoutPrefix = effectiveNumber;
            boolean prefixRemoved = false;
            boolean removePrefixForLookupThisIteration = true; // Default to true

            // PHP: $reducir logic for trunks
            if (usesTrunk) {
                Long operatorTroncal = findEffectiveTrunkOperator(trunk, currentTelephonyTypeId, currentPrefixCode, currentOperatorId, originCountryId);
                if (operatorTroncal != null && operatorTroncal >= 0) { // Found a trunk rule for this operator (or global)
                    Optional<TrunkRate> trOpt = lookupService.findTrunkRate(trunk.getId(), operatorTroncal, currentTelephonyTypeId);
                    if (trOpt.isPresent() && trOpt.get().getNoPrefix() != null && trOpt.get().getNoPrefix()) {
                        removePrefixForLookupThisIteration = false; // TrunkRate explicitly says DO NOT remove prefix
                        log.trace("TrunkRate for prefix {} (effective op {}) prevents prefix removal for indicator lookup.", currentPrefixCode, operatorTroncal);
                    }
                }
            }

            if (StringUtils.hasText(currentPrefixCode) && effectiveNumber.startsWith(currentPrefixCode)) {
                if (removePrefixForLookupThisIteration && currentTelephonyTypeId != CdrProcessingConfig.TIPOTELE_LOCAL) { // Don't remove prefix for local type
                    numberWithoutPrefix = effectiveNumber.substring(currentPrefixCode.length());
                    prefixRemoved = true;
                    log.trace("Prefix {} removed for indicator lookup (Type: {}), remaining: {}", currentPrefixCode, currentTelephonyTypeId, numberWithoutPrefix);
                } else {
                    log.trace("Prefix {} not removed for indicator lookup (Type: {}, removeFlag: {})", currentPrefixCode, currentTelephonyTypeId, removePrefixForLookupThisIteration);
                }
            }

            int prefixLength = (currentPrefixCode != null ? currentPrefixCode.length() : 0);
            int effectiveMinLength = prefixRemoved ? Math.max(0, typeMinLength - prefixLength) : typeMinLength;
            int effectiveMaxLength = prefixRemoved ? Math.max(0, typeMaxLength - prefixLength) : typeMaxLength;

            if (numberWithoutPrefix.length() < effectiveMinLength) {
                log.trace("Skipping prefix {} - number part {} too short (min {})", currentPrefixCode, numberWithoutPrefix, effectiveMinLength);
                continue;
            }
            if (effectiveMaxLength > 0 && numberWithoutPrefix.length() > effectiveMaxLength) {
                log.trace("Trimming number part {} to max length {}", numberWithoutPrefix, effectiveMaxLength);
                numberWithoutPrefix = numberWithoutPrefix.substring(0, effectiveMaxLength);
            }

            Optional<Map<String, Object>> indicatorInfoOpt = lookupService.findIndicatorByNumber(
                    numberWithoutPrefix, currentTelephonyTypeId, originCountryId,
                    currentPrefixBandOk, currentPrefixId, originCommLocationIndicatorId
            );

            boolean destinationFound = indicatorInfoOpt.isPresent();
            // PHP: ($arr_destino_pre['INDICA_MAX'] <= 0 && strlen($info_destino) == $tipotelemax)
            // This means if max NDC length is 0 (e.g. for some local types) AND the original number length matches the type's max length
            boolean lengthMatchOnly = (maxNdcLength(currentTelephonyTypeId, originCountryId) == 0 &&
                                       effectiveNumber.length() == typeMaxLength &&
                                       !destinationFound &&
                                       prefixes.size() == 1); // Only if this is the sole prefix candidate

            boolean considerMatch = destinationFound || lengthMatchOnly;

            if (considerMatch) {
                Long destinationIndicatorId = indicatorInfoOpt
                        .map(ind -> (Long) ind.get("indicator_id"))
                        .filter(id -> id != null && id > 0)
                        .orElse(null);
                Integer destinationNdc = indicatorInfoOpt.map(ind -> (Integer) ind.get("series_ndc")).orElse(null);

                Long finalTelephonyTypeId = currentTelephonyTypeId;
                Long finalPrefixId = currentPrefixId;
                boolean finalBandOk = currentPrefixBandOk;
                String finalTelephonyTypeName = (String) prefixInfo.get("telephony_type_name");

                if (currentTelephonyTypeId == CdrProcessingConfig.TIPOTELE_LOCAL && destinationNdc != null) {
                    boolean isExtended = lookupService.isLocalExtended(destinationNdc, originCommLocationIndicatorId);
                    if (isExtended) {
                        finalTelephonyTypeId = CdrProcessingConfig.TIPOTELE_LOCAL_EXT;
                        finalTelephonyTypeName = configService.getTelephonyTypeById(finalTelephonyTypeId).map(TelephonyType::getName).orElse("Local Extended");
                        log.debug("Reclassified call to {} as LOCAL_EXTENDED based on NDC {} and origin {}", effectiveNumber, destinationNdc, originCommLocationIndicatorId);

                        Optional<Operator> localExtOpOpt = configService.getOperatorInternal(finalTelephonyTypeId, originCountryId);
                        if (localExtOpOpt.isPresent()) {
                            Optional<Prefix> localExtPrefixOpt = lookupService.findPrefixByTypeOperatorOrigin(finalTelephonyTypeId, localExtOpOpt.get().getId(), originCountryId);
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

                Optional<Map<String, Object>> rateInfoOpt = findRateInfo(finalPrefixId, destinationIndicatorId, originCommLocationIndicatorId, finalBandOk);

                if (rateInfoOpt.isPresent()) {
                    Map<String, Object> rateInfo = rateInfoOpt.get();
                    rateInfo.put("telephony_type_id", finalTelephonyTypeId);
                    rateInfo.put("operator_id", currentOperatorId);
                    rateInfo.put("indicator_id", destinationIndicatorId);
                    rateInfo.put("telephony_type_name", finalTelephonyTypeName);
                    rateInfo.put("operator_name", (String) prefixInfo.get("operator_name"));
                    rateInfo.put("destination_name", indicatorInfoOpt.map(this::formatDestinationName).orElse(lengthMatchOnly ? "Unknown (Length Match)" : "Unknown Destination"));
                    // band_name is already in rateInfo from findRateInfo
                    rateInfo.put("effective_number", effectiveNumber); // The number that led to this successful match
                    rateInfo.put("applied_trunk_pricing", usesTrunk); // Mark if trunk was involved in this attempt

                    log.debug("Attempt successful: Found rate for prefix {}, indicator {}", currentPrefixCode, destinationIndicatorId);
                    return Optional.of(rateInfo);
                } else {
                    log.warn("Rate info not found for prefix {}, indicator {}", currentPrefixCode, destinationIndicatorId);
                    if (assumedRateInfo == null && allPrefixesConsistent) { // PHP: if ($arr_destino === false) { if ($todosok) { ... } }
                        assumedRateInfo = new HashMap<>();
                        assumedRateInfo.put("telephony_type_id", finalTelephonyTypeId);
                        assumedRateInfo.put("operator_id", currentOperatorId);
                        assumedRateInfo.put("indicator_id", null);
                        assumedRateInfo.put("telephony_type_name", finalTelephonyTypeName + ASSUMED_SUFFIX);
                        assumedRateInfo.put("operator_name", (String) prefixInfo.get("operator_name"));
                        assumedRateInfo.put("destination_name", "Assumed Destination");
                        assumedRateInfo.put("effective_number", effectiveNumber);
                        assumedRateInfo.put("applied_trunk_pricing", usesTrunk);
                        Optional<Map<String, Object>> baseRateOpt = lookupService.findBaseRateForPrefix(finalPrefixId);
                        baseRateOpt.ifPresent(assumedRateInfo::putAll); // Puts base_value, vat_included, vat_value
                        // Ensure valor_minuto etc. are set for applyFinalPricing
                        assumedRateInfo.putIfAbsent("valor_minuto", assumedRateInfo.get("base_value"));
                        assumedRateInfo.putIfAbsent("valor_minuto_iva", assumedRateInfo.get("vat_included"));
                        assumedRateInfo.putIfAbsent("iva", assumedRateInfo.get("vat_value"));

                        log.debug("Storing assumed rate info based on consistent prefix {} (Type: {}, Op: {})", currentPrefixCode, finalTelephonyTypeId, currentOperatorId);
                    }
                }
            } else {
                log.trace("No indicator found and length mismatch for prefix {}, number part {}", currentPrefixCode, numberWithoutPrefix);
            }
        }

        // PHP: if ($arr_destino === false) { ... }
        // This block is for when no specific indicator/series match was found.
        // If all prefixes found pointed to the same type/operator, PHP would "assume" that type.
        // This is handled by `assumedRateInfo` and `allPrefixesConsistent`.

        // PHP LOCAL FALLBACK: if ($existe_troncal === false && $arr_destino === false && $id_local > 0)
        if (!usesTrunk && prefixes.isEmpty()) { // No prefixes found and not a trunk call
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

        if (assumedRateInfo != null && allPrefixesConsistent) {
            log.warn("Using assumed rate info for number {} based on consistent prefix type/operator.", effectiveNumber);
            return Optional.of(assumedRateInfo);
        }

        log.warn("Attempt failed: No matching rate found for number: {}", effectiveNumber);
        return Optional.empty();
    }

    private Optional<Map<String, Object>> findRateInfoForLocal(CommunicationLocation commLocation, String effectiveNumber) {
        Long originCountryId = getOriginCountryId(commLocation);
        Long originIndicatorId = commLocation.getIndicatorId();
        Long localType = CdrProcessingConfig.TIPOTELE_LOCAL;

        if (originCountryId == null || originIndicatorId == null) {
            log.error("Cannot find LOCAL rate: Missing Origin Country ({}) or Comm Location Indicator ID ({}) for Location {}",
                    originCountryId, originIndicatorId, commLocation.getId());
            return Optional.empty();
        }

        Optional<Operator> internalOpOpt = configService.getOperatorInternal(localType, originCountryId);
        if (internalOpOpt.isEmpty()) {
            log.warn("Cannot find internal operator for LOCAL type ({}) in country {}", localType, originCountryId);
            return Optional.empty();
        }
        Long internalOperatorId = internalOpOpt.get().getId();

        Optional<Prefix> localPrefixOpt = lookupService.findPrefixByTypeOperatorOrigin(localType, internalOperatorId, originCountryId);
        if (localPrefixOpt.isEmpty()) {
            log.warn("Cannot find Prefix entity for LOCAL type ({}) and Operator {} in Country {}", localType, internalOperatorId, originCountryId);
            return Optional.empty();
        }
        Prefix localPrefix = localPrefixOpt.get();

        Optional<Map<String, Object>> indicatorInfoOpt = lookupService.findIndicatorByNumber(
                effectiveNumber, localType, originCountryId,
                localPrefix.isBandOk(), localPrefix.getId(), originIndicatorId
        );
        // PHP logic: if ($arr_destino_pre['INDICATIVO_ID'] > 0 || ($arr_destino_pre['INDICA_MAX'] <= 0 && strlen($info_destino) == $tipotelemax))
        // For local, INDICA_MAX is often 0. So, if indicator not found, but length matches type's max, it's considered.
        boolean considerMatch = indicatorInfoOpt.isPresent();
        if (!considerMatch) {
            Map<String, Integer> typeMinMax = configService.getTelephonyTypeMinMax(localType, originCountryId);
            int typeMaxLength = typeMinMax.getOrDefault("max", 0);
            if (maxNdcLength(localType, originCountryId) == 0 && effectiveNumber.length() == typeMaxLength) {
                considerMatch = true; // Length match for local type with no NDC
                log.debug("LOCAL fallback: No specific indicator for {}, but length matches type max. Considering match.", effectiveNumber);
            }
        }

        if (!considerMatch) {
             log.warn("LOCAL fallback: Could not find LOCAL indicator for number {} and length does not match type max.", effectiveNumber);
             return Optional.empty();
        }

        Long destinationIndicatorId = indicatorInfoOpt
            .map(ind -> (Long) ind.get("indicator_id"))
            .filter(id -> id != null && id > 0)
            .orElse(null); // For local, if no specific series match, indicator might be the comm_location's one
        if (destinationIndicatorId == null && localPrefix.getCode().isEmpty()) { // If it's truly local (no prefix like "2" for Cali)
            destinationIndicatorId = originIndicatorId;
            log.trace("LOCAL fallback: Using originIndicatorId {} as destinationIndicatorId for number {}", originIndicatorId, effectiveNumber);
        }

        Integer destinationNdc = indicatorInfoOpt.map(ind -> (Integer) ind.get("series_ndc")).orElse(null);

        Long finalTelephonyTypeId = localType;
        Long finalPrefixId = localPrefix.getId();
        boolean finalBandOk = localPrefix.isBandOk();
        String finalTelephonyTypeName = configService.getTelephonyTypeById(localType).map(TelephonyType::getName).orElse("Local");

        if (destinationNdc != null) { // This implies an indicator was found with an NDC
            boolean isExtended = lookupService.isLocalExtended(destinationNdc, originIndicatorId);
            if (isExtended) {
                finalTelephonyTypeId = CdrProcessingConfig.TIPOTELE_LOCAL_EXT;
                finalTelephonyTypeName = configService.getTelephonyTypeById(finalTelephonyTypeId).map(TelephonyType::getName).orElse("Local Extended");
                log.debug("Reclassified LOCAL fallback call to {} as LOCAL_EXTENDED based on NDC {} and origin {}", effectiveNumber, destinationNdc, originIndicatorId);
                Optional<Operator> localExtOpOpt = configService.getOperatorInternal(finalTelephonyTypeId, originCountryId);
                if (localExtOpOpt.isPresent()) {
                    Optional<Prefix> localExtPrefixOpt = lookupService.findPrefixByTypeOperatorOrigin(finalTelephonyTypeId, localExtOpOpt.get().getId(), originCountryId);
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
            rateInfo.put("telephony_type_name", finalTelephonyTypeName);
            rateInfo.put("operator_name", internalOpOpt.get().getName());
            rateInfo.put("destination_name", indicatorInfoOpt.map(this::formatDestinationName).orElse(formatDestinationName(Map.of("city_name", "Local", "department_country", ""))));
            // band_name is already in rateInfo from findRateInfo
            return Optional.of(rateInfo);
        } else {
            log.warn("Rate info not found for LOCAL fallback (Type: {}, Prefix: {}, Indicator: {})", finalTelephonyTypeId, finalPrefixId, destinationIndicatorId);
        }
        return Optional.empty();
    }

    private Optional<Map<String, Object>> findRateInfo(Long prefixId, Long indicatorId, Long originIndicatorId, boolean bandOk) {
        Optional<Map<String, Object>> baseRateOpt = lookupService.findBaseRateForPrefix(prefixId);
        if (baseRateOpt.isEmpty()) {
            log.warn("Base rate info not found for prefixId: {}", prefixId);
            return Optional.empty();
        }
        Map<String, Object> rateInfo = new HashMap<>(baseRateOpt.get());
        rateInfo.put("band_id", 0L); // Default if no band found
        rateInfo.put("band_name", ""); // Default if no band found
        rateInfo.putIfAbsent("base_value", BigDecimal.ZERO);
        rateInfo.putIfAbsent("vat_included", false);
        rateInfo.putIfAbsent("vat_value", BigDecimal.ZERO);

        // Get telephony_type_id from the prefix to check if it's local for band lookup logic
        Long telephonyTypeIdFromPrefix = null;
        if (prefixId != null) {
            telephonyTypeIdFromPrefix = lookupService.findPrefixById(prefixId).map(Prefix::getTelephoneTypeId).orElse(null);
        }
        boolean isLocalTypeForBandCheck = (telephonyTypeIdFromPrefix != null &&
                telephonyTypeIdFromPrefix.equals(CdrProcessingConfig.TIPOTELE_LOCAL));

        boolean useEffectiveBandLookup = bandOk && ( (indicatorId != null && indicatorId > 0) || isLocalTypeForBandCheck );

        log.trace("findRateInfo: prefixId={}, indicatorId={}, originIndicatorId={}, bandOk={}, isLocalType={}, useEffectiveBandLookup={}",
                prefixId, indicatorId, originIndicatorId, bandOk, isLocalTypeForBandCheck, useEffectiveBandLookup);

        if (useEffectiveBandLookup) {
            Long indicatorForBandLookup = (indicatorId != null && indicatorId > 0) ? indicatorId : null;
            // If it's a local type and no specific indicator ID was found (e.g. direct local call without NDC),
            // PHP's `buscarValor` would still try to find a band if `BANDA_INDICAORIGEN_ID` matches.
            // The `findBandByPrefixAndIndicator` handles `indicatorForBandLookup` being null correctly for this case.
            Optional<Map<String, Object>> bandOpt = lookupService.findBandByPrefixAndIndicator(prefixId, indicatorForBandLookup, originIndicatorId);
            if (bandOpt.isPresent()) {
                 Map<String, Object> bandInfo = bandOpt.get();
                rateInfo.put("base_value", bandInfo.get("band_value"));
                rateInfo.put("vat_included", bandInfo.get("band_vat_included"));
                rateInfo.put("band_id", bandInfo.get("band_id"));
                rateInfo.put("band_name", bandInfo.get("band_name"));
                // VAT from prefix is still relevant even if band overrides rate
                // rateInfo.put("vat_value", baseRateOpt.get().get("vat_value")); // Keep prefix's VAT
                log.trace("Using band rate for prefix {}, indicator {}: BandID={}, Value={}, VatIncluded={}",
                        prefixId, indicatorForBandLookup, bandInfo.get("band_id"), bandInfo.get("band_value"), bandInfo.get("band_vat_included"));
            } else {
                log.trace("Band lookup enabled for prefix {} but no matching band found for indicator {}", prefixId, indicatorForBandLookup);
            }
        } else {
            log.trace("Using base rate for prefix {} (Bands not applicable or indicator missing/null for non-local)", prefixId);
        }
        // Ensure these are always present for applyFinalPricing
        rateInfo.putIfAbsent("valor_minuto", rateInfo.get("base_value"));
        rateInfo.putIfAbsent("valor_minuto_iva", rateInfo.get("vat_included"));
        rateInfo.putIfAbsent("iva", rateInfo.get("vat_value"));


        return Optional.of(rateInfo);
    }

    private void linkAssociatedEntities(CallRecord.CallRecordBuilder callBuilder) {
        CallRecord record = callBuilder.build(); // Build once to get current state

        if (record.getTelephonyTypeId() != null && record.getTelephonyType() == null) {
            lookupService.findTelephonyTypeById(record.getTelephonyTypeId())
                    .ifPresentOrElse(callBuilder::telephonyType, () -> log.warn("Could not link TelephonyType entity for ID: {}", record.getTelephonyTypeId()));
        }
        if (record.getOperatorId() != null && record.getOperator() == null) {
            lookupService.findOperatorById(record.getOperatorId())
                    .ifPresentOrElse(callBuilder::operator, () -> log.warn("Could not link Operator entity for ID: {}", record.getOperatorId()));
        }
        if (record.getIndicatorId() != null && record.getIndicator() == null) {
            lookupService.findIndicatorById(record.getIndicatorId())
                    .ifPresentOrElse(callBuilder::indicator, () -> log.warn("Could not link Indicator entity for ID: {}", record.getIndicatorId()));
        } else if (record.getIndicatorId() == null) {
             log.trace("Indicator ID is null, skipping entity linking.");
        }
        if (record.getDestinationEmployeeId() != null && record.getDestinationEmployee() == null) {
            lookupService.findEmployeeById(record.getDestinationEmployeeId())
                    .ifPresentOrElse(callBuilder::destinationEmployee, () -> log.warn("Could not link destination employee entity for ID: {}", record.getDestinationEmployeeId()));
        }
    }

    @Getter
    @Setter
    private static class FieldWrapper<T> {
        T value;
        FieldWrapper(T v) { this.value = v; }
    }

    private record LocationInfo(Long indicatorId, Long originCountryId, Long officeId) {}

    private String cleanNumber(String number, List<String> pbxPrefixes, boolean removePrefix) {
        if (!StringUtils.hasText(number)) return "";
        String cleaned = number.trim();
        int prefixLength = 0;

        if (removePrefix && pbxPrefixes != null && !pbxPrefixes.isEmpty()) {
            prefixLength = getPrefixLength(cleaned, pbxPrefixes);
            if (prefixLength > 0) {
                cleaned = cleaned.substring(prefixLength);
                log.trace("Removed PBX prefix (length {}) from {}, result: {}", prefixLength, number, cleaned);
            } else if (prefixLength == 0 && !pbxPrefixes.isEmpty()){
                // PHP: if ($modo_seguro && $nuevo == '') { $nuevo = trim($numero); }
                // If removePrefix is true (modo_seguro=false in PHP context), and no prefix found, PHP returns empty.
                log.trace("Prefix removal requested, PBX prefixes defined, but no matching prefix found in {}. Returning empty.", number);
                return "";
            }
            // If prefixLength == -1 (no pbxPrefixes defined), it falls through.
        }
        // PHP: $primercar = substr($nuevo, 0, 1); $parcial = substr($nuevo, 1);
        //      if ($parcial != '' && !is_numeric($parcial)) ...
        //      if ($primercar == '+') { $primercar = ''; }
        //      $nuevo = $primercar.$parcial;
        // This logic is to remove non-numeric characters *after* the first character,
        // unless the first char is '+', which is also removed.
        String firstChar = "";
        String restOfString = cleaned;
        if (!cleaned.isEmpty()) {
            firstChar = cleaned.substring(0, 1);
            restOfString = cleaned.substring(1);
        }
        if ("+".equals(firstChar)) {
            firstChar = ""; // Remove leading '+'
        }
        // Remove non-digits from the rest of the string
        restOfString = restOfString.replaceAll("[^0-9]", "");
        cleaned = firstChar + restOfString;

        return cleaned;
    }

    private int getPrefixLength(String number, List<String> pbxPrefixes) {
        int longestMatchLength = -1; // PHP returns -1 if no prefixes defined
        if (number == null || pbxPrefixes == null) { // PHP doesn't check for null pbxPrefixes here, but it's safer
            return -1;
        }
        if (pbxPrefixes.isEmpty()) return -1; // Explicitly handle empty prefix list

        longestMatchLength = 0; // PHP sets to 0 if prefixes exist but none match
        boolean prefixFound = false;
        for (String prefix : pbxPrefixes) {
            String trimmedPrefix = prefix != null ? prefix.trim() : "";
            if (!trimmedPrefix.isEmpty() && number.startsWith(trimmedPrefix)) {
                if (trimmedPrefix.length() > longestMatchLength) {
                    longestMatchLength = trimmedPrefix.length();
                }
                prefixFound = true;
            }
        }
        return prefixFound ? longestMatchLength : 0;
    }

    private boolean isInternalCall(String callingNumber, String dialedNumber, CdrProcessingConfig.ExtensionLengthConfig extConfig) {
        boolean callingIsExt = isLikelyExtension(callingNumber, extConfig);
        boolean dialedIsExt = isLikelyExtension(dialedNumber, extConfig);
        log.trace("isInternalCall check: Caller '{}' (isExt: {}) -> Dialed '{}' (isExt: {})", callingNumber, callingIsExt, dialedNumber, dialedIsExt);
        return callingIsExt && dialedIsExt;
    }

    private boolean isLikelyExtension(String number, CdrProcessingConfig.ExtensionLengthConfig extConfig) {
        if (!StringUtils.hasText(number)) return false;
        String effectiveNumber = number.startsWith("+") ? number.substring(1) : number;

        // PHP: ExtensionValida($extension, true) -> checks for non-numeric after first char.
        //      is_numeric($extension)
        //      $extension >= $_LIM_INTERNAS['min'] && $extension <= $_LIM_INTERNAS['max']
        //      ExtensionEspecial($extension) -> checks $_LIM_INTERNAS['full'] (omitted)

        // Check for non-numeric characters *after* the first char (if any)
        if (effectiveNumber.length() > 1) {
            if (!effectiveNumber.substring(1).matches("\\d*")) { // Only digits allowed after first char
                 log.trace("isLikelyExtension: '{}' contains invalid characters after first.", number);
                 return false;
            }
        } else if (effectiveNumber.length() == 1) {
            if (!effectiveNumber.matches("[\\d#*]")) { // Allow # or * for single char
                 log.trace("isLikelyExtension: single char '{}' is not digit, #, or *.", number);
                 return false;
            }
        }


        // If it's purely numeric (after potential first char handling)
        if (effectiveNumber.matches("\\d+")) {
            int numLength = effectiveNumber.length();
            if (numLength < extConfig.getMinLength() || numLength > extConfig.getMaxLength()) {
                log.trace("isLikelyExtension: '{}' length {} outside range ({}-{}).", number, numLength, extConfig.getMinLength(), extConfig.getMaxLength());
                return false;
            }
            try {
                long numValue = Long.parseLong(effectiveNumber);
                if (numValue > extConfig.getMaxExtensionValue()) {
                    log.trace("isLikelyExtension: '{}' value {} exceeds max value {}.", number, numValue, extConfig.getMaxExtensionValue());
                    return false;
                }
            } catch (NumberFormatException e) {
                // Should not happen if matches("\\d+")
                log.warn("isLikelyExtension: '{}' failed numeric parse despite regex match.", number);
                return false;
            }
        } else if (!effectiveNumber.matches("[\\d#*]+")) { // If not purely numeric, ensure only allowed chars
             log.trace("isLikelyExtension: '{}' contains invalid characters beyond digits, #, *.", number);
            return false;
        }
        // If it's not purely numeric but passed char checks (e.g. "0", "*123"), length check still applies
        // For non-purely-numeric, we might not have a strict min/max value comparison like for pure numbers.
        // The length check is the primary gate here for mixed strings.
        int numLength = effectiveNumber.length();
         if (numLength < extConfig.getMinLength() || numLength > extConfig.getMaxLength()) {
            log.trace("isLikelyExtension: non-numeric '{}' length {} outside range ({}-{}).", number, numLength, extConfig.getMinLength(), extConfig.getMaxLength());
            return false;
        }


        log.trace("isLikelyExtension: '{}' is considered a likely extension.", number);
        return true;
    }

    private String preprocessNumberForLookup(String number, Long originCountryId, FieldWrapper<Long> forcedTelephonyType, CommunicationLocation commLocation) {
        if (number == null || originCountryId == null || !originCountryId.equals(COLOMBIA_ORIGIN_COUNTRY_ID)) {
            return number;
        }
        int len = number.length();
        String originalNumber = number;
        String processedNumber = number;

        if (len == 10) {
            if (number.startsWith("3") && number.matches("^3[0-4][0-9]\\d{7}$")) { // Colombian Mobile 3xx xxx xxxx
                processedNumber = "03" + number;
                forcedTelephonyType.setValue(CdrProcessingConfig.TIPOTELE_CELULAR);
            } else if (number.startsWith("60")) { // Colombian Fixed Line 60X xxx xxxx
                String ndcFromNumber = number.substring(2, 3);
                Optional<Integer> localNdcOpt = Optional.ofNullable(commLocation.getIndicator())
                                                        .flatMap(ind -> lookupService.findLocalNdcForIndicator(ind.getId()));

                if (localNdcOpt.isPresent() && String.valueOf(localNdcOpt.get()).equals(ndcFromNumber)) {
                    if (number.length() >= 3) {
                        processedNumber = number.substring(3); // Remove "60X"
                        forcedTelephonyType.setValue(CdrProcessingConfig.TIPOTELE_LOCAL);
                    }
                } else {
                    // Not local by commLocation's NDC. Check if it's a known national prefix via company.
                    String nationalPrefixBasedOnCompany = determineNationalPrefix(number, originCountryId);
                    if (nationalPrefixBasedOnCompany != null) {
                        processedNumber = nationalPrefixBasedOnCompany + number.substring(2);
                        forcedTelephonyType.setValue(CdrProcessingConfig.TIPOTELE_NACIONAL);
                    } else {
                        // PHP logic: $numero = substr($numero, 2); $g_numero = $numero = '09'.$numero;
                        // This is a strong default to national with "09" if not specifically local or other national.
                        // Let's replicate this default if no other rule applies to "60X..."
                        processedNumber = "09" + number.substring(2);
                        forcedTelephonyType.setValue(CdrProcessingConfig.TIPOTELE_NACIONAL);
                        log.trace("Number {} (60X...) not local by NDC and no company match, defaulting to national with '09'.", number);
                    }
                }
            }
        } else if (len == 12) {
            if (number.startsWith("573") && number.matches("^573[0-4][0-9]\\d{7}$")) {
                processedNumber = "03" + number.substring(2); // Becomes 033...
                forcedTelephonyType.setValue(CdrProcessingConfig.TIPOTELE_CELULAR);
            } else if (number.startsWith("603") && number.matches("^603[0-4][0-9]\\d{7}$")) {
                processedNumber = "03" + number.substring(2); // Becomes 033...
                forcedTelephonyType.setValue(CdrProcessingConfig.TIPOTELE_CELULAR);
            } else if (number.startsWith("5760") && number.matches("^5760\\d{8}$")) {
                processedNumber = number.substring(4); // Leaves X + 7 digits
                // This implies it might be local or national, let prefix matching decide for "X..."
                // Or force to local if X matches local NDC
                 String ndcFromNumber = processedNumber.substring(0,1);
                 Optional<Integer> localNdcOpt = Optional.ofNullable(commLocation.getIndicator())
                                                        .flatMap(ind -> lookupService.findLocalNdcForIndicator(ind.getId()));
                 if(localNdcOpt.isPresent() && String.valueOf(localNdcOpt.get()).equals(ndcFromNumber)){
                    processedNumber = processedNumber.substring(1);
                    forcedTelephonyType.setValue(CdrProcessingConfig.TIPOTELE_LOCAL);
                 } else {
                    // It's not local by NDC, could be national. PHP defaults to 09 + X...
                    processedNumber = "09" + processedNumber;
                    forcedTelephonyType.setValue(CdrProcessingConfig.TIPOTELE_NACIONAL);
                 }

            } else if (number.startsWith("6060") && number.matches("^6060\\d{8}$")) {
                processedNumber = number.substring(4); // Leaves X + 7 digits
                // Similar logic to 5760
                 String ndcFromNumber = processedNumber.substring(0,1);
                 Optional<Integer> localNdcOpt = Optional.ofNullable(commLocation.getIndicator())
                                                        .flatMap(ind -> lookupService.findLocalNdcForIndicator(ind.getId()));
                 if(localNdcOpt.isPresent() && String.valueOf(localNdcOpt.get()).equals(ndcFromNumber)){
                    processedNumber = processedNumber.substring(1);
                    forcedTelephonyType.setValue(CdrProcessingConfig.TIPOTELE_LOCAL);
                 } else {
                    processedNumber = "09" + processedNumber;
                    forcedTelephonyType.setValue(CdrProcessingConfig.TIPOTELE_NACIONAL);
                 }
            }
        } else if (len == 11) {
            if (number.startsWith("03") && number.matches("^03[0-4][0-9]\\d{7}$")) {
                // Already in 033xx... format
                forcedTelephonyType.setValue(CdrProcessingConfig.TIPOTELE_CELULAR);
            } else if (number.startsWith("604") && number.matches("^604\\d{8}$")) { // Example specific NDC
                processedNumber = number.substring(3); // Becomes 4xxxxxxx
                // Let prefix matching handle "4..."
            }
        } else if (len == 9 && number.startsWith("60") && number.matches("^60\\d{7}$")) {
            processedNumber = number.substring(2); // Leaves X + 6 digits
            // Let prefix matching handle "X..."
        }


        if (!originalNumber.equals(processedNumber)) {
            log.debug("Preprocessed Colombian number for lookup: {} -> {}", originalNumber, processedNumber);
        }
        return processedNumber;
    }

    private String determineNationalPrefix(String number10DigitStartingWith60, Long originCountryId) {
        if (number10DigitStartingWith60 == null || !number10DigitStartingWith60.startsWith("60") || number10DigitStartingWith60.length() != 10) {
            return null;
        }
        String ndcStr = number10DigitStartingWith60.substring(2, 1); // PHP: substr($numero, 2, -7) -> this is wrong, should be 1 char for NDC
        if (number10DigitStartingWith60.length() >=3) { // Ensure there's an NDC char
            ndcStr = number10DigitStartingWith60.substring(2, 3);
        } else {
            return null; // Not enough chars for NDC
        }

        String subscriberNumberStr = number10DigitStartingWith60.substring(3);

        if (!ndcStr.matches("\\d") || !subscriberNumberStr.matches("\\d{7}")) {
            log.warn("Invalid NDC or subscriber number format in determineNationalPrefix: NDC={}, Sub={}", ndcStr, subscriberNumberStr);
            return null;
        }
        try {
            int ndc = Integer.parseInt(ndcStr);
            long subscriberNumber = Long.parseLong(subscriberNumberStr); // Not directly used for company lookup, but for series range

            Optional<String> companyOpt = lookupService.findCompanyForNationalSeries(ndc, subscriberNumber, originCountryId);

            if (companyOpt.isPresent()) {
                String company = companyOpt.get().toUpperCase();
                if (company.contains("TELMEX")) return "0456";
                if (company.contains("COLOMBIA TELECOMUNICACIONES")) return "09";
                if (company.contains("UNE EPM")) return "05";
                if (company.contains("EMPRESA DE TELECOMUNICACIONES DE BOGOTÁ") || company.contains("ETB")) return "07";
                log.trace("Company '{}' found for NDC {}, Sub {}, but no matching national prefix rule.", company, ndc, subscriberNumber);
            } else {
                log.trace("No company found for NDC {}, Sub {} to determine national prefix.", ndc, subscriberNumber);
            }
        } catch (NumberFormatException e) {
            log.warn("Error parsing NDC/Subscriber for national prefix determination: NDC={}, Sub={}", ndcStr, subscriberNumberStr, e);
        }
        return null; // No specific company match
    }

    private LocationInfo getLocationInfo(Employee employee, CommunicationLocation defaultLocation) {
        Long defaultIndicatorId = defaultLocation.getIndicatorId();
        Long defaultOriginCountryId = getOriginCountryId(defaultLocation);
        Long defaultOfficeId = null;

        if (employee != null) {
            Long empOfficeId = employee.getSubdivisionId();
            Long empOriginCountryId = defaultOriginCountryId;
            Long empIndicatorId = defaultIndicatorId;

            if (employee.getCommunicationLocationId() != null && employee.getCommunicationLocationId() > 0) {
                Optional<CommunicationLocation> empLocOpt = lookupService.findCommunicationLocationById(employee.getCommunicationLocationId());
                if (empLocOpt.isPresent()) {
                    CommunicationLocation empLoc = empLocOpt.get();
                    empIndicatorId = empLoc.getIndicatorId() != null ? empLoc.getIndicatorId() : defaultIndicatorId;
                    Long empLocCountryId = getOriginCountryId(empLoc);
                    empOriginCountryId = empLocCountryId != null ? empLocCountryId : defaultOriginCountryId;
                    log.trace("Using location info from Employee's CommLocation {}: Indicator={}, Country={}", employee.getCommunicationLocationId(), empIndicatorId, empOriginCountryId);
                    return new LocationInfo(empIndicatorId, empOriginCountryId, empOfficeId);
                } else {
                    log.warn("Employee {} has CommLocationId {} assigned, but location not found or inactive.", employee.getId(), employee.getCommunicationLocationId());
                }
            }

            if (employee.getCostCenterId() != null && employee.getCostCenterId() > 0) {
                Optional<CostCenter> ccOpt = lookupService.findCostCenterById(employee.getCostCenterId());
                if (ccOpt.isPresent() && ccOpt.get().getOriginCountryId() != null) {
                    // Only override if not already set by a more specific employee comm_location
                    if (employee.getCommunicationLocationId() == null || employee.getCommunicationLocationId() <= 0) {
                         empOriginCountryId = ccOpt.get().getOriginCountryId();
                         log.trace("Using OriginCountry {} from Employee's CostCenter {}", empOriginCountryId, employee.getCostCenterId());
                    }
                }
            }
            log.trace("Final location info for Employee {}: Indicator={}, Country={}, Office={}", employee.getId(), empIndicatorId, empOriginCountryId, empOfficeId);
            return new LocationInfo(empIndicatorId, empOriginCountryId, empOfficeId);
        }

        log.trace("Using default location info: Indicator={}, Country={}, Office={}", defaultIndicatorId, defaultOriginCountryId, defaultOfficeId);
        return new LocationInfo(defaultIndicatorId, defaultOriginCountryId, defaultOfficeId);
    }

    private Long getOriginCountryId(CommunicationLocation commLocation) {
        if (commLocation == null) return null;
        // Try to get from prefetched entities first
        Indicator indicatorEntity = commLocation.getIndicator();
        if (indicatorEntity != null && indicatorEntity.getOriginCountryId() != null) {
            return indicatorEntity.getOriginCountryId();
        }
        // Fallback to lookup if not prefetched or null
        if (commLocation.getIndicatorId() != null && commLocation.getIndicatorId() > 0) {
            Optional<Indicator> indicatorOpt = lookupService.findIndicatorById(commLocation.getIndicatorId());
            if (indicatorOpt.isPresent() && indicatorOpt.get().getOriginCountryId() != null) {
                return indicatorOpt.get().getOriginCountryId();
            } else {
                log.warn("Indicator {} linked to CommLocation {} not found or has no OriginCountryId.", commLocation.getIndicatorId(), commLocation.getId());
            }
        } else {
            log.warn("CommLocation {} has no IndicatorId.", commLocation.getId());
        }
        // Fallback to a system-wide default if necessary
        log.warn("Falling back to default OriginCountryId {} for CommLocation {}", COLOMBIA_ORIGIN_COUNTRY_ID, commLocation.getId());
        return COLOMBIA_ORIGIN_COUNTRY_ID;
    }

    private BigDecimal calculateBilledAmount(int durationSeconds, BigDecimal rateValue, boolean rateVatIncluded, BigDecimal vatPercentage, boolean chargePerSecond, BigDecimal initialRateValue, boolean initialRateVatIncluded) {
        if (durationSeconds <= 0) return BigDecimal.ZERO;
        BigDecimal effectiveRateValue = Optional.ofNullable(rateValue).orElse(BigDecimal.ZERO);
        BigDecimal durationUnits;
        if (chargePerSecond) {
            durationUnits = new BigDecimal(durationSeconds);
        } else {
            durationUnits = new BigDecimal(durationSeconds).divide(SIXTY, 0, RoundingMode.CEILING);
            if (durationUnits.compareTo(BigDecimal.ZERO) == 0 && durationSeconds > 0) durationUnits = BigDecimal.ONE;
        }
        BigDecimal totalCost = effectiveRateValue.multiply(durationUnits);
        if (!rateVatIncluded && vatPercentage != null && vatPercentage.compareTo(BigDecimal.ZERO) > 0) {
            BigDecimal vatMultiplier = BigDecimal.ONE.add(vatPercentage.divide(new BigDecimal("100"), 4, RoundingMode.HALF_UP));
            totalCost = totalCost.multiply(vatMultiplier);
        }
        // PHP logic for PREFIJO_CARGO_BASICO is omitted as per entity structure (no cargo_basico field)
        return totalCost.setScale(4, RoundingMode.HALF_UP);
    }

    private BigDecimal calculateValueWithoutVat(BigDecimal value, BigDecimal vatPercentage, boolean vatIncluded) {
        if (value == null) return BigDecimal.ZERO;
        if (!vatIncluded || vatPercentage == null || vatPercentage.compareTo(BigDecimal.ZERO) <= 0) {
            return value.setScale(4, RoundingMode.HALF_UP);
        }
        BigDecimal vatDivisor = BigDecimal.ONE.add(vatPercentage.divide(new BigDecimal("100"), 10, RoundingMode.HALF_UP));
        if (vatDivisor.compareTo(BigDecimal.ZERO) == 0) {
            log.warn("VAT divisor is zero, cannot remove VAT from {}", value);
            return value.setScale(4, RoundingMode.HALF_UP);
        }
        return value.divide(vatDivisor, 4, RoundingMode.HALF_UP);
    }

    private void applySpecialServicePricing(SpecialService specialService, CallRecord.CallRecordBuilder callBuilder, int duration) {
        callBuilder.telephonyTypeId(CdrProcessingConfig.TIPOTELE_ESPECIALES);
        callBuilder.indicatorId(specialService.getIndicatorId() != null && specialService.getIndicatorId() > 0 ? specialService.getIndicatorId() : null);
        // PHP: $infovalor['operador'] = operador_interno($link, _TIPOTELE_ESPECIALES, $resultado_directorio);
        configService.getOperatorInternal(CdrProcessingConfig.TIPOTELE_ESPECIALES, specialService.getOriginCountryId())
                .ifPresent(op -> callBuilder.operatorId(op.getId()));

        BigDecimal price = Optional.ofNullable(specialService.getValue()).orElse(BigDecimal.ZERO);
        boolean vatIncluded = Optional.ofNullable(specialService.getVatIncluded()).orElse(false);
        BigDecimal vatPercentage = Optional.ofNullable(specialService.getVatAmount()).orElse(BigDecimal.ZERO); // This is SERVESPECIAL_IVA, not PREFIJO_IVA
        
        BigDecimal billedAmount = price; // For special service, duration might not apply to the base value.
                                         // PHP's `Calcular_Valor` for special services uses `duracion_minuto = $duracion;`
                                         // and `precio_llamada_minuto = $infovalor['valor_minuto'];`
                                         // This implies the special service value is per-second if `ensegundos` is true, or per-minute.
                                         // Assuming special services are flat rate unless `ensegundos` is specifically handled for them.
                                         // For now, let's treat `specialService.getValue()` as the total value.
                                         // If it's per unit, `calculateBilledAmount` should be used.
                                         // PHP's `buscar_NumeroEspecial` returns a structure similar to `buscarValor`,
                                         // then `Calcular_Valor` is called. So, `specialService.getValue()` is like `valor_minuto`.

        BigDecimal calculatedBilledAmount = calculateBilledAmount(duration, price, vatIncluded, vatPercentage, false, BigDecimal.ZERO, false);


        callBuilder.pricePerMinute(price.setScale(4, RoundingMode.HALF_UP)); // This is the rate
        callBuilder.initialPrice(BigDecimal.ZERO); // Special services usually don't have a separate initial price
        callBuilder.billedAmount(calculatedBilledAmount.setScale(4, RoundingMode.HALF_UP));
        log.debug("Applied Special Service pricing: Rate={}, Billed={}", price, calculatedBilledAmount);
    }

    private void applyInternalPricing(Long internalCallTypeId, CallRecord.CallRecordBuilder callBuilder, int duration) {
        Optional<Map<String, Object>> tariffOpt = lookupService.findInternalTariff(internalCallTypeId);
        if (tariffOpt.isPresent()) {
            Map<String, Object> tariff = tariffOpt.get();
            tariff.putIfAbsent("valor_minuto", BigDecimal.ZERO);
            tariff.putIfAbsent("valor_minuto_iva", false);
            tariff.putIfAbsent("iva", BigDecimal.ZERO);
            tariff.putIfAbsent("valor_inicial", BigDecimal.ZERO);
            tariff.putIfAbsent("valor_inicial_iva", false);
            tariff.putIfAbsent("ensegundos", false);
            log.debug("Applying internal tariff for type {}: {}", internalCallTypeId, tariff);
            applyFinalPricing(tariff, duration, callBuilder);
        } else {
            log.warn("No internal tariff found for type {}, setting cost to zero.", internalCallTypeId);
            callBuilder.pricePerMinute(BigDecimal.ZERO);
            callBuilder.initialPrice(BigDecimal.ZERO);
            callBuilder.billedAmount(BigDecimal.ZERO);
        }
    }

    private void applyTrunkPricing(Trunk trunk, Map<String, Object> baseRateInfo, int duration, Long originIndicatorId, CallRecord.CallRecordBuilder callBuilder) {
        Long telephonyTypeId = (Long) baseRateInfo.get("telephony_type_id");
        Long operatorId = (Long) baseRateInfo.get("operator_id");
        Long indicatorId = (Long) baseRateInfo.get("indicator_id");
        Long originCountryId = getOriginCountryId(trunk.getCommLocation());

        Optional<TrunkRate> trunkRateOpt = lookupService.findTrunkRate(trunk.getId(), operatorId, telephonyTypeId);

        if (trunkRateOpt.isPresent()) {
            TrunkRate trunkRate = trunkRateOpt.get();
            log.debug("Applying TrunkRate {} for trunk {}", trunkRate.getId(), trunk.getId());
            baseRateInfo.put("valor_minuto", Optional.ofNullable(trunkRate.getRateValue()).orElse(BigDecimal.ZERO));
            baseRateInfo.put("valor_minuto_iva", Optional.ofNullable(trunkRate.getIncludesVat()).orElse(false));
            baseRateInfo.put("ensegundos", trunkRate.getSeconds() != null && trunkRate.getSeconds() > 0);
            baseRateInfo.put("valor_inicial", BigDecimal.ZERO); // Trunk rates generally don't have separate initial price
            baseRateInfo.put("valor_inicial_iva", false);
            // Get IVA from the prefix associated with the trunk rate's telephony type and operator
            lookupService.findPrefixByTypeOperatorOrigin(telephonyTypeId, operatorId, originCountryId)
                    .ifPresent(p -> baseRateInfo.put("iva", p.getVatValue()));
            applyFinalPricing(baseRateInfo, duration, callBuilder);
        } else {
            Optional<TrunkRule> trunkRuleOpt = lookupService.findTrunkRule(trunk.getName(), telephonyTypeId, indicatorId, originIndicatorId);
            if (trunkRuleOpt.isPresent()) {
                TrunkRule rule = trunkRuleOpt.get();
                log.debug("Applying TrunkRule {} for trunk {}", rule.getId(), trunk.getName());
                baseRateInfo.put("valor_minuto", Optional.ofNullable(rule.getRateValue()).orElse(BigDecimal.ZERO));
                baseRateInfo.put("valor_minuto_iva", Optional.ofNullable(rule.getIncludesVat()).orElse(false));
                baseRateInfo.put("ensegundos", rule.getSeconds() != null && rule.getSeconds() > 0);
                baseRateInfo.put("valor_inicial", BigDecimal.ZERO);
                baseRateInfo.put("valor_inicial_iva", false);

                Long finalOperatorId = operatorId;
                Long finalTelephonyTypeId = telephonyTypeId;

                if (rule.getNewOperatorId() != null && rule.getNewOperatorId() > 0) {
                    finalOperatorId = rule.getNewOperatorId();
                    callBuilder.operatorId(finalOperatorId); // Update record
                    lookupService.findOperatorById(finalOperatorId).ifPresent(op -> baseRateInfo.put("operator_name", op.getName()));
                }
                if (rule.getNewTelephonyTypeId() != null && rule.getNewTelephonyTypeId() > 0) {
                    finalTelephonyTypeId = rule.getNewTelephonyTypeId();
                    callBuilder.telephonyTypeId(finalTelephonyTypeId); // Update record
                    lookupService.findTelephonyTypeById(finalTelephonyTypeId).ifPresent(tt -> baseRateInfo.put("telephony_type_name", tt.getName()));
                }

                // Get IVA for the (potentially new) type and operator
                Long finalTelephonyTypeId1 = finalTelephonyTypeId; // effectively final for lambda
                Long finalOperatorId1 = finalOperatorId; // effectively final for lambda
                lookupService.findPrefixByTypeOperatorOrigin(finalTelephonyTypeId, finalOperatorId, originCountryId)
                        .ifPresentOrElse(
                                p -> baseRateInfo.put("iva", p.getVatValue()),
                                () -> {
                                    log.warn("No prefix found for rule-defined type {} / operator {}. Using default IVA 0.", finalTelephonyTypeId1, finalOperatorId1);
                                    baseRateInfo.put("iva", BigDecimal.ZERO);
                                }
                        );
                applyFinalPricing(baseRateInfo, duration, callBuilder);
            } else {
                log.debug("No specific TrunkRate or TrunkRule found for trunk {}, applying base/band/special pricing", trunk.getName());
                // Fallback to general pricing (prefix/band) potentially modified by special rates
                applySpecialPricing(baseRateInfo, callBuilder.build().getServiceDate(), duration, originIndicatorId, callBuilder);
            }
        }
    }

    private void applySpecialPricing(Map<String, Object> currentRateInfo, LocalDateTime callDateTime, int duration, Long originIndicatorId, CallRecord.CallRecordBuilder callBuilder) {
        Long telephonyTypeId = (Long) currentRateInfo.get("telephony_type_id");
        Long operatorId = (Long) currentRateInfo.get("operator_id");
        Long bandId = (Long) currentRateInfo.get("band_id"); // This comes from findRateInfo

        List<Map<String, Object>> specialRatesMaps = lookupService.findSpecialRateValues(
                telephonyTypeId, operatorId, bandId, originIndicatorId, callDateTime
        );

        Optional<Map<String, Object>> applicableRateMapOpt = specialRatesMaps.stream()
                .filter(rateMap -> isHourApplicable((String) rateMap.get("srv_hours_spec"), callDateTime.getHour()))
                .findFirst(); // PHP logic takes the first one matching criteria (ORDER BY in SQL query handles precedence)

        if (applicableRateMapOpt.isPresent()) {
            Map<String, Object> rateMap = applicableRateMapOpt.get();
            log.debug("Applying SpecialRateValue (ID: {})", rateMap.get("id")); // srv_id was alias
            BigDecimal originalRate = (BigDecimal) currentRateInfo.get("valor_minuto");
            boolean originalVatIncluded = (Boolean) currentRateInfo.get("valor_minuto_iva");

            // Store original rate as initial_price if special rate applies
            currentRateInfo.put("valor_inicial", originalRate);
            currentRateInfo.put("valor_inicial_iva", originalVatIncluded);

            Integer valueType = (Integer) rateMap.get("value_type"); // srv_value_type
            BigDecimal rateValue = (BigDecimal) rateMap.get("rate_value"); // srv_rate_value
            Boolean includesVat = (Boolean) rateMap.get("includes_vat"); // srv_includes_vat
            BigDecimal prefixVatValue = (BigDecimal) rateMap.get("prefix_vat_value"); // VAT from prefix

            if (valueType != null && valueType == 1) { // Percentage discount
                BigDecimal discountPercentage = Optional.ofNullable(rateValue).orElse(BigDecimal.ZERO);
                // PHP: $valor_minuto = ValorSinIVA($infovalor);
                BigDecimal currentRateNoVat = calculateValueWithoutVat(originalRate, (BigDecimal) currentRateInfo.get("iva"), originalVatIncluded);
                BigDecimal discountMultiplier = BigDecimal.ONE.subtract(discountPercentage.divide(new BigDecimal("100"), 4, RoundingMode.HALF_UP));
                currentRateInfo.put("valor_minuto", currentRateNoVat.multiply(discountMultiplier));
                currentRateInfo.put("valor_minuto_iva", false); // After discount, it's effectively without VAT, VAT will be added by calculateBilledAmount
                currentRateInfo.put("descuento_p", discountPercentage); // Store discount for reference
                log.trace("Applied percentage discount {}% from SpecialRateValue {}", discountPercentage, rateMap.get("id"));
            } else { // Fixed value override
                currentRateInfo.put("valor_minuto", Optional.ofNullable(rateValue).orElse(BigDecimal.ZERO));
                currentRateInfo.put("valor_minuto_iva", Optional.ofNullable(includesVat).orElse(false));
                log.trace("Applied fixed rate {} from SpecialRateValue {}", currentRateInfo.get("valor_minuto"), rateMap.get("id"));
            }
            currentRateInfo.put("iva", prefixVatValue); // Use VAT from the prefix associated with the special rate's type/op
            currentRateInfo.put("ensegundos", false); // Special rates are typically not per-second unless specified
            
            // Update TelephonyType name if a special rate is applied
            String currentTypeName = (String) currentRateInfo.getOrDefault("telephony_type_name", "Unknown Type");
            currentRateInfo.put("telephony_type_name", currentTypeName + " (xTarifaEsp)");

            applyFinalPricing(currentRateInfo, duration, callBuilder);
        } else {
            log.debug("No applicable special rate found, applying current rate from prefix/band.");
            currentRateInfo.put("valor_inicial", BigDecimal.ZERO); // No separate initial price if no special rate
            currentRateInfo.put("valor_inicial_iva", false);
            currentRateInfo.put("ensegundos", false); // Default not per-second
            applyFinalPricing(currentRateInfo, duration, callBuilder);
        }
    }

    private void applyFinalPricing(Map<String, Object> rateInfo, int duration, CallRecord.CallRecordBuilder callBuilder) {
        BigDecimal pricePerMinute = Optional.ofNullable((BigDecimal) rateInfo.get("valor_minuto")).orElse(BigDecimal.ZERO);
        boolean vatIncluded = Optional.ofNullable((Boolean) rateInfo.get("valor_minuto_iva")).orElse(false);
        BigDecimal vatPercentage = Optional.ofNullable((BigDecimal) rateInfo.get("iva")).orElse(BigDecimal.ZERO);
        boolean chargePerSecond = Optional.ofNullable((Boolean) rateInfo.get("ensegundos")).orElse(false);
        BigDecimal initialPrice = Optional.ofNullable((BigDecimal) rateInfo.get("valor_inicial")).orElse(BigDecimal.ZERO);
        boolean initialVatIncluded = Optional.ofNullable((Boolean) rateInfo.get("valor_inicial_iva")).orElse(false);

        BigDecimal calculatedBilledAmount = calculateBilledAmount(
                duration, pricePerMinute, vatIncluded, vatPercentage, chargePerSecond, initialPrice, initialVatIncluded
        );

        callBuilder.pricePerMinute(pricePerMinute.setScale(4, RoundingMode.HALF_UP));
        callBuilder.initialPrice(initialPrice.setScale(4, RoundingMode.HALF_UP));
        callBuilder.billedAmount(calculatedBilledAmount);
        log.trace("Final pricing applied: Rate={}, Initial={}, Billed={}", pricePerMinute, initialPrice, calculatedBilledAmount);
    }

    private String formatDestinationName(Map<String, Object> indicatorInfo) {
        String city = (String) indicatorInfo.get("city_name");
        String country = (String) indicatorInfo.get("department_country");
        if (StringUtils.hasText(city) && StringUtils.hasText(country)) return city + " (" + country + ")";
        return StringUtils.hasText(city) ? city : (StringUtils.hasText(country) ? country : "Unknown Destination");
    }

    private boolean isHourApplicable(String hoursSpecification, int callHour) {
        if (!StringUtils.hasText(hoursSpecification)) return true; // No spec means applicable all hours
        try {
            for (String part : hoursSpecification.split(",")) {
                String range = part.trim();
                if (range.contains("-")) {
                    String[] parts = range.split("-");
                    if (parts.length == 2) {
                        int start = Integer.parseInt(parts[0].trim());
                        int end = Integer.parseInt(parts[1].trim());
                        if (callHour >= start && callHour <= end) return true;
                    } else {
                        log.warn("Invalid hour range format: {}", range);
                    }
                } else if (!range.isEmpty()) {
                    if (callHour == Integer.parseInt(range)) return true;
                }
            }
        } catch (Exception e) {
            log.error("Error parsing hoursSpecification: '{}'. Assuming not applicable.", hoursSpecification, e);
            return false; // Be restrictive on error
        }
        return false; // Not found in any specified range/hour
    }

    private Long findEffectiveTrunkOperator(Trunk trunk, Long telephonyTypeId, String prefixCode, Long actualOperatorId, Long originCountryId) {
        if (trunk == null || telephonyTypeId == null || actualOperatorId == null || originCountryId == null) {
            return null;
        }

        Optional<TrunkRate> specificRateOpt = lookupService.findTrunkRate(trunk.getId(), actualOperatorId, telephonyTypeId);
        if (specificRateOpt.isPresent()) {
            log.trace("Found specific TrunkRate for trunk {}, op {}, type {}. Using operator {}.",
                    trunk.getId(), actualOperatorId, telephonyTypeId, actualOperatorId);
            return actualOperatorId;
        }

        Optional<TrunkRate> globalRateOpt = lookupService.findTrunkRate(trunk.getId(), 0L, telephonyTypeId);
        if (globalRateOpt.isPresent()) {
            TrunkRate globalRate = globalRateOpt.get();
            // PHP: if ($existe_troncal['operador_destino'][$operador_troncal][$tipotele_id]['noprefijo'] > 0)
            if (globalRate.getNoPrefix() != null && globalRate.getNoPrefix()) {
                // If global rate says "no prefix", it means the call is routed based on the trunk's default operator for this telephony type.
                // We need to check if the actualOperatorId (from the dialed prefix) matches this default.
                Optional<Operator> defaultTrunkOperatorOpt = configService.getOperatorInternal(telephonyTypeId, originCountryId);
                Long defaultTrunkOperatorId = defaultTrunkOperatorOpt.map(Operator::getId).orElse(null);

                // PHP: count($_lista_Prefijos['ctlope'][$prefijo]) == 1 --> is prefix unique to one operator for this type/country
                boolean isPrefixUnique = lookupService.isPrefixUniqueToOperator(prefixCode, telephonyTypeId, originCountryId);

                if (actualOperatorId > 0 &&
                        !actualOperatorId.equals(defaultTrunkOperatorId) &&
                        isPrefixUnique) {
                    log.trace("Global TrunkRate for trunk {} type {} has noPrefix. Actual op {} differs from default op {} for a unique prefix {}. Rule not applicable.",
                            trunk.getId(), telephonyTypeId, actualOperatorId, defaultTrunkOperatorId, prefixCode);
                    return null; // Rule not applicable due to operator mismatch for a unique prefix
                }
            }
            log.trace("Found global TrunkRate for trunk {}, type {}. Using operator 0.", trunk.getId(), telephonyTypeId);
            return 0L; // Use global operator rule (operator_id = 0)
        }

        log.trace("No specific or global TrunkRate found for trunk {}, op {}, type {}. No effective trunk operator rule.",
                trunk.getId(), actualOperatorId, telephonyTypeId);
        return null;
    }

    private int maxNdcLength(Long telephonyTypeId, Long originCountryId) {
        if (telephonyTypeId == null || originCountryId == null) return 0;
        return lookupService.findNdcMinMaxLength(telephonyTypeId, originCountryId).getOrDefault("max", 0);
    }

}