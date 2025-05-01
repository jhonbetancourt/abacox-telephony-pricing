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
    private final ConfigurationService configService;

    private static final BigDecimal SIXTY = new BigDecimal("60");
    private static final String CONFERENCE_PREFIX = "b"; // From PHP include_cm.php
    private static final Long COLOMBIA_ORIGIN_COUNTRY_ID = 1L; // Assuming 1 is Colombia

    // --- Main Enrichment Method ---
    public Optional<CallRecord> enrichCdr(RawCdrDto rawCdr, CommunicationLocation commLocation) {
        log.info("Enriching CDR: {}", rawCdr.getGlobalCallId());

        CallRecord.CallRecordBuilder callBuilder = CallRecord.builder();
        // Initial field setting from DTO
        callBuilder.commLocationId(commLocation.getId());
        callBuilder.commLocation(commLocation);
        callBuilder.serviceDate(rawCdr.getDateTimeOrigination()); // Use Origination time as service start
        callBuilder.employeeExtension(rawCdr.getCallingPartyNumber());
        callBuilder.employeeAuthCode(rawCdr.getAuthCodeDescription());
        callBuilder.destinationPhone(rawCdr.getFinalCalledPartyNumber()); // Original dialed number
        callBuilder.dial(rawCdr.getFinalCalledPartyNumber()); // Initially same as destination, might change
        callBuilder.duration(rawCdr.getDuration());
        callBuilder.ringCount(rawCdr.getRingDuration());
        callBuilder.isIncoming(rawCdr.isIncoming()); // Initial incoming status from parser
        callBuilder.trunk(rawCdr.getDestDeviceName());
        callBuilder.initialTrunk(rawCdr.getOrigDeviceName());
        callBuilder.employeeTransfer(rawCdr.getLastRedirectDn());
        callBuilder.transferCause(CallTransferCause.NONE.getValue()); // Default transfer cause
        callBuilder.assignmentCause(CallAssignmentCause.UNKNOWN.getValue()); // Default assignment cause
        callBuilder.fileInfoId(rawCdr.getFileInfoId());
        callBuilder.originIp(rawCdr.getSourceIp());
        callBuilder.billedAmount(BigDecimal.ZERO);
        callBuilder.pricePerMinute(BigDecimal.ZERO);
        callBuilder.initialPrice(BigDecimal.ZERO);

        // --- Conference Handling & Field Adjustment (PHP: CM_FormatoCDR conference logic) ---
        boolean isConference = isConferenceCall(rawCdr);
        boolean isPostConference = !isConference && isConferenceCallIndicator(rawCdr.getLastRedirectDn());

        // Use wrappers to easily manage effective values after potential swaps
        FieldWrapper<String> effectiveCallingNumber = new FieldWrapper<>(rawCdr.getCallingPartyNumber());
        FieldWrapper<String> effectiveDialedNumber = new FieldWrapper<>(rawCdr.getFinalCalledPartyNumber());
        FieldWrapper<String> effectiveCallingPartition = new FieldWrapper<>(rawCdr.getCallingPartyNumberPartition());
        FieldWrapper<String> effectiveDialedPartition = new FieldWrapper<>(rawCdr.getFinalCalledPartyNumberPartition());
        FieldWrapper<String> effectiveTrunk = new FieldWrapper<>(rawCdr.getDestDeviceName());
        FieldWrapper<String> effectiveInitialTrunk = new FieldWrapper<>(rawCdr.getOrigDeviceName());
        FieldWrapper<Boolean> effectiveIncoming = new FieldWrapper<>(rawCdr.isIncoming()); // Use parser's initial detection
        FieldWrapper<String> effectiveRedirectNumber = new FieldWrapper<>(rawCdr.getLastRedirectDn());
        FieldWrapper<String> effectiveRedirectPartition = new FieldWrapper<>(rawCdr.getLastRedirectDnPartition());

        if (isConference) {
            log.debug("CDR {} identified as part of a conference.", rawCdr.getGlobalCallId());
            // Determine the correct transfer cause based on joinOnBehalfOf
            CallTransferCause confTransferCause = (rawCdr.getJoinOnBehalfOf() != null && rawCdr.getJoinOnBehalfOf() == 7)
                    ? CallTransferCause.CONFERENCE_NOW
                    : CallTransferCause.CONFERENCE;
            callBuilder.transferCause(confTransferCause.getValue());

            // Invert fields as per PHP logic for conferences (originator becomes destination)
            // Use the redirect number/partition as the effective *caller*
            effectiveCallingNumber.setValue(rawCdr.getLastRedirectDn());
            effectiveCallingPartition.setValue(rawCdr.getLastRedirectDnPartition());
            effectiveDialedNumber.setValue(rawCdr.getCallingPartyNumber());
            effectiveDialedPartition.setValue(rawCdr.getCallingPartyNumberPartition());
            effectiveIncoming.setValue(false); // Conferences are treated as outgoing from the initiator

            // PHP logic in CM_FormatoCDR conditionally swaps trunks based on joinOnBehalfOf
            if (confTransferCause != CallTransferCause.CONFERENCE_NOW) {
                log.trace("Swapping trunks for conference call {} (not CONFERENCE_NOW)", rawCdr.getGlobalCallId());
                swapFields(effectiveTrunk, effectiveInitialTrunk);
            } else {
                 log.trace("Not swapping trunks for conference call {} (is CONFERENCE_NOW)", rawCdr.getGlobalCallId());
            }

            // Update builder fields with effective values *after* swap/conference logic
            callBuilder.employeeExtension(effectiveCallingNumber.getValue()); // Now the redirect number
            callBuilder.destinationPhone(effectiveDialedNumber.getValue()); // Now the original caller
            callBuilder.dial(effectiveDialedNumber.getValue()); // Initially same as destination
            callBuilder.isIncoming(effectiveIncoming.getValue()); // Now false
            callBuilder.trunk(effectiveTrunk.getValue());
            callBuilder.initialTrunk(effectiveInitialTrunk.getValue());

            // Re-lookup employee based on the *effective* calling number (which is the redirect number)
            Optional<Employee> confEmployeeOpt = lookupService.findEmployeeByExtensionOrAuthCode(
                    effectiveCallingNumber.getValue(), null, commLocation.getId()
            );
            if (confEmployeeOpt.isPresent()) {
                callBuilder.employeeId(confEmployeeOpt.get().getId());
                callBuilder.employee(confEmployeeOpt.get());
                // Assignment cause specifically for conference
                callBuilder.assignmentCause(CallAssignmentCause.CONFERENCE.getValue());
            } else {
                log.warn("Originating employee ({}) for conference call {} not found.", effectiveCallingNumber.getValue(), rawCdr.getGlobalCallId());
                callBuilder.employeeId(null); // Ensure previous lookup is cleared if this one fails
                callBuilder.employee(null);
                // Keep assignment cause as CONFERENCE even if lookup fails, as we know it's a conference CDR type
            }
        } else if (isPostConference) {
            log.debug("CDR {} identified as post-conference.", rawCdr.getGlobalCallId());
            callBuilder.transferCause(CallTransferCause.CONFERENCE_END.getValue());
            // PHP logic didn't swap trunks here, so we don't either.
            // Employee assignment might need adjustment if the remaining parties are external,
            // but that requires complex state/lookup omitted for now.
        } else {
             // If not conference or post-conference, set transfer cause based on raw CDR reason
             callBuilder.transferCause(CallTransferCause.fromValue(rawCdr.getLastRedirectRedirectReason()).getValue());
        }

        // --- Initial Employee Lookup (if not already set by conference logic) ---
        if (callBuilder.build().getEmployeeId() == null) {
            Optional<Employee> employeeOpt = lookupService.findEmployeeByExtensionOrAuthCode(
                    effectiveCallingNumber.getValue(), // Use the potentially swapped caller
                    rawCdr.getAuthCodeDescription(),
                    commLocation.getId()
            );
            employeeOpt.ifPresent(employee -> {
                callBuilder.employeeId(employee.getId());
                callBuilder.employee(employee);
                // Set assignment cause based on how the employee was found
                if (StringUtils.hasText(rawCdr.getAuthCodeDescription())) {
                    callBuilder.assignmentCause(CallAssignmentCause.AUTH_CODE.getValue());
                } else {
                    callBuilder.assignmentCause(CallAssignmentCause.EXTENSION.getValue());
                }
            });
            if (employeeOpt.isEmpty()) {
                log.warn("Initial employee lookup failed for CDR {} (Effective Caller: {}, Code: {})",
                        rawCdr.getGlobalCallId(), effectiveCallingNumber.getValue(), rawCdr.getAuthCodeDescription());
                // Assignment cause remains UNKNOWN or potentially RANGES if range lookup is implemented later
            }
        }


        // --- Determine Call Type and Enrich ---
        try {
            // Apply PBX Special Rule *after* initial checks but *before* main processing
            // This mirrors PHP's procesaSaliente/procesaInterna applying it after failing other checks
            String numberForPbx = effectiveIncoming.getValue() ? effectiveCallingNumber.getValue() : effectiveDialedNumber.getValue();
            CallDirection directionForPbx = effectiveIncoming.getValue() ? CallDirection.INCOMING : CallDirection.OUTGOING;
            Optional<PbxSpecialRule> pbxRuleOpt = lookupService.findPbxSpecialRule(
                    numberForPbx, commLocation.getId(), directionForPbx.getValue()
            );

            if (pbxRuleOpt.isPresent()) {
                PbxSpecialRule rule = pbxRuleOpt.get();
                String replacement = rule.getReplacement() != null ? rule.getReplacement() : "";
                String searchPattern = rule.getSearchPattern();
                if (StringUtils.hasText(searchPattern) && numberForPbx.startsWith(searchPattern)) {
                    String numberAfterSearch = numberForPbx.substring(searchPattern.length());
                    String numberAfterPbxRule = replacement + numberAfterSearch; // Apply replacement
                    log.debug("Applied PBX rule {}, number changed from {} to {}", rule.getId(), numberForPbx, numberAfterPbxRule);
                    // Update the effective number based on direction
                    if (effectiveIncoming.getValue()) {
                        effectiveCallingNumber.setValue(numberAfterPbxRule);
                    } else {
                        effectiveDialedNumber.setValue(numberAfterPbxRule);
                        // Update dial field as well if outgoing
                        callBuilder.dial(numberAfterPbxRule);
                    }
                }
            }

            // Now, determine call type using potentially modified numbers
            if (effectiveIncoming.getValue()) {
                processIncomingCall(rawCdr, commLocation, callBuilder, effectiveCallingNumber.getValue());
            } else {
                // Pass effective numbers to outgoing processing
                processOutgoingCall(rawCdr, commLocation, callBuilder,
                        effectiveCallingNumber.getValue(), effectiveDialedNumber.getValue());
            }
        } catch (Exception e) {
            log.error("Error during enrichment for CDR {}: {}", rawCdr.getGlobalCallId(), e.getMessage(), e);
            // Create a failure record here? Or let the main processing loop handle it?
            // Let main loop handle it for consistency.
            return Optional.empty();
        }

        // --- Final Adjustments ---
        // Transfer cause is already set based on conference/post-conference or raw value

        // Handle zero duration calls (PHP: acumtotal_Insertar logic)
        CallRecord tempRecord = callBuilder.build(); // Build temporary to check duration and type
        if (tempRecord.getDuration() != null && tempRecord.getDuration() <= 0
                && tempRecord.getTelephonyTypeId() != null
                && tempRecord.getTelephonyTypeId() != ConfigurationService.TIPOTELE_ERRORES) {
            log.debug("Call duration is zero or less, setting TelephonyType to SINCONSUMO ({})", ConfigurationService.TIPOTELE_SINCONSUMO);
            callBuilder.telephonyTypeId(ConfigurationService.TIPOTELE_SINCONSUMO);
            // Reset costs for zero duration calls
            callBuilder.billedAmount(BigDecimal.ZERO);
            callBuilder.pricePerMinute(BigDecimal.ZERO);
            callBuilder.initialPrice(BigDecimal.ZERO);
        }

        // Link associated entities based on IDs set during enrichment
        linkAssociatedEntities(callBuilder);

        CallRecord finalRecord = callBuilder.build();
        log.info("Successfully enriched CDR {}: Type={}, Billed={}",
                rawCdr.getGlobalCallId(), finalRecord.getTelephonyTypeId(), finalRecord.getBilledAmount());
        return Optional.of(finalRecord);
    }

    // ========================================================================
    // Processing Logic Methods (Refined based on review)
    // ========================================================================

    private void processOutgoingCall(RawCdrDto rawCdr, CommunicationLocation commLocation, CallRecord.CallRecordBuilder callBuilder, String effectiveCallingNumber, String effectiveDialedNumber) {
        log.debug("Processing outgoing call for CDR {} (effective: {} -> {})", rawCdr.getGlobalCallId(), effectiveCallingNumber, effectiveDialedNumber);
        // Use the effectiveDialedNumber which might have been modified by PBX rules
        String numberToProcess = effectiveDialedNumber;
        List<String> pbxPrefixes = configService.getPbxPrefixes(commLocation.getId());
        Long originCountryId = getOriginCountryId(commLocation);

        // 1. Preprocess Number (Colombian rules for lookup)
        String preprocessedNumber = preprocessNumberForLookup(numberToProcess, originCountryId);
        FieldWrapper<Long> forcedTelephonyType = new FieldWrapper<>(null);
        if (!preprocessedNumber.equals(numberToProcess)) {
            log.debug("Number preprocessed for lookup: {} -> {}", numberToProcess, preprocessedNumber);
            // Check if preprocessing resulted in a mobile number format
            if (preprocessedNumber.startsWith("03") && preprocessedNumber.length() == 12) { // 03 + 10 digits
                forcedTelephonyType.setValue(ConfigurationService.TIPOTELE_CELULAR);
            } else if (preprocessedNumber.matches("^\\d{7,8}$")) { // 7 or 8 digits after preprocessing might indicate LOCAL
                forcedTelephonyType.setValue(ConfigurationService.TIPOTELE_LOCAL);
            }
            // Add checks for fixed line if needed based on _esNacional logic results
        }

        // 2. Clean Dialed Number (using preprocessed number for consistency in cleaning)
        // Determine if prefix *should* be removed based on original number containing a prefix
        boolean shouldRemovePrefix = getPrefixLength(numberToProcess, pbxPrefixes) > 0;
        String cleanedNumber = cleanNumber(preprocessedNumber, pbxPrefixes, shouldRemovePrefix);
        // Set the 'dial' field to the cleaned number (which might still have prefixes if !shouldRemovePrefix)
        callBuilder.dial(cleanedNumber);
        log.trace("Initial cleaned number (dial field): {}", cleanedNumber);

        // 3. Check Special Services (using the potentially modified cleanedNumber)
        Long indicatorIdForSpecial = commLocation.getIndicatorId();
        if (indicatorIdForSpecial != null && originCountryId != null) {
            Optional<SpecialService> specialServiceOpt = lookupService.findSpecialService(cleanedNumber, indicatorIdForSpecial, originCountryId);
            if (specialServiceOpt.isPresent()) {
                SpecialService specialService = specialServiceOpt.get();
                log.debug("Call matches Special Service: {}", specialService.getId());
                applySpecialServicePricing(specialService, callBuilder, rawCdr.getDuration());
                return; // Processing finished for special service
            }
        }

        // 4. Check if Internal Call (using cleanedNumber)
        if (isInternalCall(effectiveCallingNumber, cleanedNumber, commLocation)) {
            log.debug("Processing as internal call (effective: {} -> {})", effectiveCallingNumber, cleanedNumber);
            processInternalCall(rawCdr, commLocation, callBuilder, cleanedNumber);
            return; // Processing finished for internal call
        }

        // 5. Process as External Outgoing Call (using cleanedNumber)
        log.debug("Processing as external outgoing call (effective: {} -> {})", effectiveCallingNumber, cleanedNumber);
        evaluateDestinationAndRate(rawCdr, commLocation, callBuilder, cleanedNumber, pbxPrefixes, forcedTelephonyType.getValue());
    }

    private void processIncomingCall(RawCdrDto rawCdr, CommunicationLocation commLocation, CallRecord.CallRecordBuilder callBuilder, String effectiveCallingNumber) {
        log.debug("Processing incoming call for CDR {} (effective caller: {})", rawCdr.getGlobalCallId(), effectiveCallingNumber);

        // PHP: Checks if internal call redirected outward (`info_interna`)
        // This check is complex as `info_interna` relies on `es_llamada_interna` which uses lookups.
        // For simplicity now, we assume incoming CDRs processed here are truly incoming.
        // A more advanced state machine might be needed to perfectly replicate the PHP's ability
        // to re-classify an incoming as outgoing based on internal checks *during* incoming processing.
        // if (isInternalCall(effectiveCallingNumber, ???, commLocation)) { // Need destination for this check
        //     log.debug("Incoming CDR {} might be an internal call redirected outward, attempting outgoing processing.", rawCdr.getGlobalCallId());
        //     processOutgoingCall(rawCdr, commLocation, callBuilder, effectiveCallingNumber, ???); // Need destination
        //     return;
        // }

        // Use the effectiveCallingNumber which might have been modified by PBX rules
        String numberToProcess = effectiveCallingNumber;
        Long originCountryId = getOriginCountryId(commLocation);

        // Apply PBX Special Rules (Direction INCOMING) to the CALLING number
        Optional<PbxSpecialRule> pbxRuleOpt = lookupService.findPbxSpecialRule(
                numberToProcess, commLocation.getId(), CallDirection.INCOMING.getValue()
        );
        if (pbxRuleOpt.isPresent()) {
            PbxSpecialRule rule = pbxRuleOpt.get();
            String replacement = rule.getReplacement() != null ? rule.getReplacement() : "";
            String searchPattern = rule.getSearchPattern();
            if (StringUtils.hasText(searchPattern) && numberToProcess.startsWith(searchPattern)) {
                String numberAfterSearch = numberToProcess.substring(searchPattern.length());
                numberToProcess = replacement + numberAfterSearch; // Apply replacement
                log.debug("Applied PBX rule {} to incoming number, result: {}", rule.getId(), numberToProcess);
            }
        }

        // 1. Preprocess Number (Colombian rules for lookup)
        String preprocessedNumber = preprocessNumberForLookup(numberToProcess, originCountryId);
        FieldWrapper<Long> forcedTelephonyType = new FieldWrapper<>(null);
        if (!preprocessedNumber.equals(numberToProcess)) {
            log.debug("Incoming number preprocessed for lookup: {} -> {}", numberToProcess, preprocessedNumber);
            if (preprocessedNumber.startsWith("03") && preprocessedNumber.length() == 12) {
                forcedTelephonyType.setValue(ConfigurationService.TIPOTELE_CELULAR);
            } else if (preprocessedNumber.matches("^\\d{7,8}$")) { // 7 or 8 digits might indicate LOCAL
                forcedTelephonyType.setValue(ConfigurationService.TIPOTELE_LOCAL);
            }
            // Add checks for fixed line if needed based on _esNacional logic results
        }

        // 2. Clean Calling Number (using preprocessed number for consistency)
        // Incoming numbers shouldn't have PBX prefixes removed typically
        String cleanedCallingNumber = cleanNumber(preprocessedNumber, Collections.emptyList(), false);
        callBuilder.dial(cleanedCallingNumber); // 'dial' for incoming is the caller's number

        // 3. Determine Origin (PHP: buscarOrigen logic)
        Long commIndicatorId = commLocation.getIndicatorId();
        if (originCountryId != null && commIndicatorId != null) {
            String numberForLookup = cleanedCallingNumber; // Use the cleaned (and potentially rule-modified) number

            // PHP logic checks for PBX prefix on original number for incoming calls too
            List<String> pbxPrefixes = configService.getPbxPrefixes(commLocation.getId());
            int prefixLen = getPrefixLength(numberToProcess, pbxPrefixes); // Check original (potentially rule-modified) number for prefix
            if (prefixLen > 0) {
                // If original had prefix, lookup origin using number *after* prefix
                numberForLookup = numberToProcess.substring(prefixLen);
                // Re-clean this potentially different number (without removing prefix again)
                numberForLookup = cleanNumber(numberForLookup, Collections.emptyList(), false);
                log.trace("Incoming call had PBX prefix, looking up origin for: {}", numberForLookup);
            }

            List<Map<String, Object>> prefixes = lookupService.findPrefixesByNumber(numberForLookup, originCountryId);
            // Filter if type was forced by preprocessing
            if(forcedTelephonyType.getValue() != null) {
                Long forcedType = forcedTelephonyType.getValue();
                prefixes = prefixes.stream()
                        .filter(p -> forcedType.equals(p.get("telephony_type_id")))
                        .collect(Collectors.toList());
                if(prefixes.isEmpty()) log.warn("Forced TelephonyType ID {} has no matching prefixes for incoming number {}", forcedType, numberForLookup);
            }
            Optional<Map<String, Object>> bestPrefix = prefixes.stream().findFirst(); // Prefixes are ordered by specificity

            if (bestPrefix.isPresent()) {
                Map<String, Object> prefixInfo = bestPrefix.get();
                Long telephonyTypeId = (Long) prefixInfo.get("telephony_type_id"); // Use type from matched prefix
                Long operatorId = (Long) prefixInfo.get("operator_id");
                String prefixCode = (String) prefixInfo.get("prefix_code");

                callBuilder.telephonyTypeId(telephonyTypeId);
                callBuilder.operatorId(operatorId);

                String numberWithoutPrefix = numberForLookup;
                if (StringUtils.hasText(prefixCode) && numberForLookup.startsWith(prefixCode)) {
                    numberWithoutPrefix = numberForLookup.substring(prefixCode.length());
                }

                // Find indicator based on number part after prefix
                Optional<Map<String, Object>> indicatorInfoOpt = lookupService.findIndicatorByNumber(numberWithoutPrefix, telephonyTypeId, originCountryId);
                indicatorInfoOpt.ifPresent(indInfo -> callBuilder.indicatorId((Long) indInfo.get("indicator_id")));

                log.debug("Incoming call classified as Type ID: {}, Operator ID: {}, Indicator ID: {}",
                        telephonyTypeId, operatorId, callBuilder.build().getIndicatorId());

            } else {
                // Fallback logic from PHP buscarOrigen - if no prefix matches, assume LOCAL
                callBuilder.telephonyTypeId(ConfigurationService.TIPOTELE_LOCAL);
                callBuilder.indicatorId(commIndicatorId); // Use the location's own indicator
                configService.getOperatorInternal(ConfigurationService.TIPOTELE_LOCAL, originCountryId)
                        .ifPresent(op -> callBuilder.operatorId(op.getId()));
                log.warn("Could not classify incoming call origin for {}, assuming LOCAL", cleanedCallingNumber);
            }
        } else {
            log.warn("Missing Origin Country or Indicator for CommunicationLocation {}, cannot classify incoming call origin.", commLocation.getId());
            callBuilder.telephonyTypeId(ConfigurationService.TIPOTELE_ERRORES);
        }

        // Incoming calls typically zero cost
        callBuilder.billedAmount(BigDecimal.ZERO);
        callBuilder.pricePerMinute(BigDecimal.ZERO);
        callBuilder.initialPrice(BigDecimal.ZERO);
    }

    private void processInternalCall(RawCdrDto rawCdr, CommunicationLocation commLocation, CallRecord.CallRecordBuilder callBuilder, String destinationExtension) {
        String sourceExtension = callBuilder.build().getEmployeeExtension();
        log.debug("Processing internal call from {} to {}", sourceExtension, destinationExtension);

        Optional<Employee> sourceEmployeeOpt = Optional.ofNullable(callBuilder.build().getEmployee());
        // Use the effective commLocationId for lookup (could be different if global extensions are used, though omitted here)
        Optional<Employee> destEmployeeOpt = lookupService.findEmployeeByExtensionOrAuthCode(destinationExtension, null, commLocation.getId());

        LocationInfo sourceLoc = getLocationInfo(sourceEmployeeOpt.orElse(null), commLocation);
        LocationInfo destLoc = getLocationInfo(destEmployeeOpt.orElse(null), commLocation);

        Long internalCallTypeId = null;
        Long operatorId = null;
        Long indicatorId = sourceLoc.indicatorId; // Default to source indicator

        if (destEmployeeOpt.isPresent()) {
            callBuilder.destinationEmployeeId(destEmployeeOpt.get().getId());
            callBuilder.destinationEmployee(destEmployeeOpt.get());
            indicatorId = destLoc.indicatorId; // Use destination indicator if employee found

            // Determine internal call type based on location comparison (PHP: tipo_llamada_interna)
            if (sourceLoc.originCountryId != null && destLoc.originCountryId != null && !sourceLoc.originCountryId.equals(destLoc.originCountryId)) {
                internalCallTypeId = ConfigurationService.TIPOTELE_INTERNACIONAL_IP;
            } else if (sourceLoc.indicatorId != null && destLoc.indicatorId != null && !sourceLoc.indicatorId.equals(destLoc.indicatorId)) {
                internalCallTypeId = ConfigurationService.TIPOTELE_NACIONAL_IP;
            } else if (sourceLoc.officeId != null && destLoc.officeId != null && !sourceLoc.officeId.equals(destLoc.officeId)) {
                // PHP logic compares subdireccion, which is mapped to officeId here
                internalCallTypeId = ConfigurationService.TIPOTELE_LOCAL_IP;
            } else {
                internalCallTypeId = ConfigurationService.TIPOTELE_INTERNA_IP; // Default if same office/city/country
            }
            log.debug("Internal call type determined by location comparison: {}", internalCallTypeId);
        } else {
            log.warn("Internal call destination extension {} not found as employee.", destinationExtension);
            // PHP logic: If destination not found, check internal prefixes
            Optional<Map<String, Object>> internalPrefixOpt = lookupService.findInternalPrefixMatch(destinationExtension, sourceLoc.originCountryId);
            if (internalPrefixOpt.isPresent()) {
                internalCallTypeId = (Long) internalPrefixOpt.get().get("telephony_type_id");
                operatorId = (Long) internalPrefixOpt.get().get("operator_id");
                log.debug("Destination {} matched internal prefix for type {}", destinationExtension, internalCallTypeId);
            } else {
                // PHP logic: If no employee and no prefix, default to a configured internal type (e.g., INTERNA_IP)
                // Use the default internal type from configuration
                internalCallTypeId = ConfigurationService.getDefaultInternalCallTypeId();
                log.debug("Destination {} not found and no internal prefix matched, defaulting to type {}", destinationExtension, internalCallTypeId);
            }
        }

        callBuilder.telephonyTypeId(internalCallTypeId);
        callBuilder.indicatorId(indicatorId); // Use determined indicator

        // Find the internal operator if not determined by prefix lookup
        if (operatorId == null) {
            configService.getOperatorInternal(internalCallTypeId, sourceLoc.originCountryId)
                    .ifPresent(op -> callBuilder.operatorId(op.getId()));
        } else {
            callBuilder.operatorId(operatorId);
        }

        applyInternalPricing(internalCallTypeId, callBuilder, rawCdr.getDuration());
    }

    // Overload for convenience
    private void evaluateDestinationAndRate(RawCdrDto rawCdr, CommunicationLocation commLocation, CallRecord.CallRecordBuilder callBuilder, String cleanedNumber, List<String> pbxPrefixes) {
        evaluateDestinationAndRate(rawCdr, commLocation, callBuilder, cleanedNumber, pbxPrefixes, null);
    }

    // Added forcedTelephonyTypeId parameter
    private void evaluateDestinationAndRate(RawCdrDto rawCdr, CommunicationLocation commLocation, CallRecord.CallRecordBuilder callBuilder, String cleanedNumber, List<String> pbxPrefixes, Long forcedTelephonyTypeId) {
        Optional<Trunk> trunkOpt = lookupService.findTrunkByCode(rawCdr.getDestDeviceName(), commLocation.getId());
        boolean usesTrunk = trunkOpt.isPresent();
        Long originIndicatorId = commLocation.getIndicatorId();

        // Attempt 1: Lookup with current context (trunk info if present)
        Optional<Map<String, Object>> finalRateInfoOpt = attemptRateLookup(rawCdr, commLocation, callBuilder, cleanedNumber, pbxPrefixes, trunkOpt, forcedTelephonyTypeId);

        // Fallback (Normalizar): If attempt 1 failed AND it was a trunk call, try again without trunk info
        if (finalRateInfoOpt.isEmpty() && usesTrunk) {
            log.warn("Initial rate lookup failed for trunk call {}, attempting fallback (no trunk info)", rawCdr.getGlobalCallId());
            // Pass empty Optional for trunk to simulate non-trunk lookup
            finalRateInfoOpt = attemptRateLookup(rawCdr, commLocation, callBuilder, cleanedNumber, pbxPrefixes, Optional.empty(), forcedTelephonyTypeId);
        }

        // Apply results
        if (finalRateInfoOpt.isPresent()) {
            Map<String, Object> finalRateInfo = finalRateInfoOpt.get();
            // Update CallRecord fields based on the successful lookup result
            callBuilder.telephonyTypeId((Long) finalRateInfo.get("telephony_type_id"));
            callBuilder.operatorId((Long) finalRateInfo.get("operator_id"));
            callBuilder.indicatorId((Long) finalRateInfo.get("indicator_id"));
            // Update 'dial' field if the lookup used a different effective number (e.g., after prefix removal)
            callBuilder.dial((String) finalRateInfo.getOrDefault("effective_number", cleanedNumber));

            // Decide which pricing logic to apply
            boolean appliedTrunkPricing = finalRateInfo.containsKey("applied_trunk_pricing") && (Boolean) finalRateInfo.get("applied_trunk_pricing");
            if (appliedTrunkPricing && usesTrunk) {
                // Apply pricing based on TrunkRate or TrunkRule
                applyTrunkPricing(trunkOpt.get(), finalRateInfo, rawCdr.getDuration(), originIndicatorId, callBuilder);
            } else {
                // Apply pricing based on Prefix/Band and potentially SpecialRateValue
                applySpecialPricing(finalRateInfo, rawCdr.getDateTimeOrigination(), rawCdr.getDuration(), originIndicatorId, callBuilder);
            }
        } else {
            log.error("Could not determine rate for number: {} (effective: {}) after fallback.", rawCdr.getFinalCalledPartyNumber(), cleanedNumber);
            callBuilder.telephonyTypeId(ConfigurationService.TIPOTELE_ERRORES); // Mark as error
            callBuilder.dial(cleanedNumber); // Keep the cleaned number in dial field
            // Set zero costs for errors
            callBuilder.billedAmount(BigDecimal.ZERO);
            callBuilder.pricePerMinute(BigDecimal.ZERO);
            callBuilder.initialPrice(BigDecimal.ZERO);
        }
    }

    // Added forcedTelephonyTypeId parameter
    private Optional<Map<String, Object>> attemptRateLookup(RawCdrDto rawCdr, CommunicationLocation commLocation, CallRecord.CallRecordBuilder callBuilder, String initialCleanedNumber, List<String> pbxPrefixes, Optional<Trunk> trunkOpt, Long forcedTelephonyTypeId) {
        Long originCountryId = getOriginCountryId(commLocation);
        Long originIndicatorId = commLocation.getIndicatorId();
        boolean usesTrunk = trunkOpt.isPresent();
        Trunk trunk = trunkOpt.orElse(null);

        if (originCountryId == null || originIndicatorId == null) {
            log.error("Cannot attempt rate lookup: Missing Origin Country ({}) or Indicator ID ({}) for Location {}", originCountryId, originIndicatorId, commLocation.getId());
            return Optional.empty();
        }

        // Determine the effective number for lookup based on trunk settings (PHP: limpiar_numero variations)
        String effectiveNumber = initialCleanedNumber;
        if (usesTrunk && trunk.getNoPbxPrefix() != null && trunk.getNoPbxPrefix()) {
            // If trunk ignores PBX prefix, clean the *original* number without removing any PBX prefix
            effectiveNumber = cleanNumber(rawCdr.getFinalCalledPartyNumber(), Collections.emptyList(), false);
            log.trace("Attempting lookup (Trunk {} ignores PBX prefix): {}", trunk.getId(), effectiveNumber);
        } else {
            // Use the already cleaned number (which might have had prefix removed earlier)
            log.trace("Attempting lookup (Effective number): {}", effectiveNumber);
        }

        // Find potential prefixes matching the effective number
        List<Map<String, Object>> prefixes = lookupService.findPrefixesByNumber(effectiveNumber, originCountryId);

        // Filter prefixes if a type was forced by preprocessing
        if (forcedTelephonyTypeId != null) {
            prefixes = prefixes.stream()
                    .filter(p -> forcedTelephonyTypeId.equals(p.get("telephony_type_id")))
                    .collect(Collectors.toList());
            if (!prefixes.isEmpty()) {
                log.debug("Lookup filtered to forced TelephonyType ID: {}", forcedTelephonyTypeId);
            } else {
                log.warn("Forced TelephonyType ID {} has no matching prefixes for number {}", forcedTelephonyTypeId, effectiveNumber);
                // Continue with empty list, might trigger fallback later
            }
        }

        // --- Iterate through found prefixes (ordered by length DESC) ---
        Map<String, Object> assumedRateInfo = null; // For fallback logic
        boolean allPrefixesConsistent = !prefixes.isEmpty(); // Assume consistent until proven otherwise
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

            // Check for consistency (for fallback logic)
            if (firstOperatorId == null) {
                firstOperatorId = currentOperatorId;
                firstTelephonyTypeId = currentTelephonyTypeId;
            } else if (!firstOperatorId.equals(currentOperatorId) || !firstTelephonyTypeId.equals(currentTelephonyTypeId)) {
                allPrefixesConsistent = false;
            }

            // Determine the number to use for indicator lookup (remove prefix?)
            String numberWithoutPrefix = effectiveNumber;
            boolean prefixRemoved = false;
            boolean removePrefixForLookup = true; // Default: remove prefix

            if (usesTrunk) {
                // Check TrunkRate config for this specific trunk/op/type
                Optional<TrunkRate> trOpt = lookupService.findTrunkRate(trunk.getId(), currentOperatorId, currentTelephonyTypeId);
                if (trOpt.isPresent() && trOpt.get().getNoPrefix() != null && trOpt.get().getNoPrefix()) {
                    removePrefixForLookup = false; // TrunkRate overrides default prefix removal
                    log.trace("TrunkRate for prefix {} prevents prefix removal during indicator lookup", currentPrefixCode);
                }
            }

            if (removePrefixForLookup && StringUtils.hasText(currentPrefixCode) && effectiveNumber.startsWith(currentPrefixCode)) {
                numberWithoutPrefix = effectiveNumber.substring(currentPrefixCode.length());
                prefixRemoved = true;
                log.trace("Prefix {} removed for indicator lookup, remaining: {}", currentPrefixCode, numberWithoutPrefix);
            }

            // Calculate effective min/max lengths for the number *without* the prefix
            int prefixLength = (currentPrefixCode != null ? currentPrefixCode.length() : 0);
            int effectiveMinLength = prefixRemoved ? Math.max(0, typeMinLength - prefixLength) : typeMinLength;
            int effectiveMaxLength = prefixRemoved ? Math.max(0, typeMaxLength - prefixLength) : typeMaxLength;

            // Validate length against *effective* min/max for the number part
            if (numberWithoutPrefix.length() < effectiveMinLength) {
                log.trace("Skipping prefix {} - number part {} too short (min {})", currentPrefixCode, numberWithoutPrefix, effectiveMinLength);
                continue; // Try next prefix
            }
            // Trim number part if it exceeds the *effective* max length
            if (effectiveMaxLength > 0 && numberWithoutPrefix.length() > effectiveMaxLength) {
                log.trace("Trimming number part {} to max length {}", numberWithoutPrefix, effectiveMaxLength);
                numberWithoutPrefix = numberWithoutPrefix.substring(0, effectiveMaxLength);
            }

            // Find the destination indicator based on the (potentially prefix-removed and trimmed) number
            Optional<Map<String, Object>> indicatorInfoOpt = lookupService.findIndicatorByNumber(
                    numberWithoutPrefix, currentTelephonyTypeId, originCountryId
            );

            boolean destinationFound = indicatorInfoOpt.isPresent();
            // PHP logic: If indicator not found, still consider a match if length is exactly the max expected length
            boolean lengthMatch = (effectiveMaxLength > 0 && numberWithoutPrefix.length() == effectiveMaxLength && !destinationFound);
            boolean considerMatch = destinationFound || lengthMatch;

            if (considerMatch) {
                Long destinationIndicatorId = indicatorInfoOpt.map(ind -> (Long) ind.get("indicator_id")).orElse(0L);
                Integer destinationNdc = indicatorInfoOpt.map(ind -> (Integer) ind.get("series_ndc")).orElse(null);

                // --- Local Extended Check (PHP: BuscarLocalExtendida) ---
                Long finalTelephonyTypeId = currentTelephonyTypeId;
                Long finalPrefixId = currentPrefixId;
                boolean finalBandOk = bandOk;
                // Check only if the *current* type being evaluated is LOCAL
                if (currentTelephonyTypeId == ConfigurationService.TIPOTELE_LOCAL && destinationNdc != null) {
                    boolean isExtended = lookupService.isLocalExtended(destinationNdc, originIndicatorId);
                    if (isExtended) {
                        finalTelephonyTypeId = ConfigurationService.TIPOTELE_LOCAL_EXT;
                        log.debug("Reclassified call to {} as LOCAL_EXTENDED based on NDC {} and origin {}", effectiveNumber, destinationNdc, originIndicatorId);
                        // Find the appropriate prefix for the LOCAL_EXTENDED type
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
                // --- End Local Extended Check ---

                // Find the rate info (base or band) using the *final* determined prefix and type
                Optional<Map<String, Object>> rateInfoOpt = findRateInfo(finalPrefixId, destinationIndicatorId, originIndicatorId, finalBandOk);

                if (rateInfoOpt.isPresent()) {
                    Map<String, Object> rateInfo = rateInfoOpt.get();
                    // Populate the result map with all necessary info for pricing
                    rateInfo.put("telephony_type_id", finalTelephonyTypeId); // Use potentially updated type
                    rateInfo.put("operator_id", currentOperatorId);
                    rateInfo.put("indicator_id", destinationIndicatorId);
                    // Lookup names based on final IDs
                    rateInfo.put("telephony_type_name", lookupService.findTelephonyTypeById(finalTelephonyTypeId).map(TelephonyType::getName).orElse("Unknown Type"));
                    rateInfo.put("operator_name", lookupService.findOperatorById(currentOperatorId).map(Operator::getName).orElse("Unknown Operator"));
                    rateInfo.put("destination_name", indicatorInfoOpt.map(this::formatDestinationName).orElse(lengthMatch ? "Unknown (Length Match)" : "Unknown Destination"));
                    rateInfo.put("band_name", rateInfo.get("band_name")); // Comes from findRateInfo
                    rateInfo.put("effective_number", effectiveNumber); // The number used for this successful lookup
                    rateInfo.put("applied_trunk_pricing", usesTrunk); // Flag if trunk logic was involved

                    log.debug("Attempt successful: Found rate for prefix {}, indicator {}", currentPrefixCode, destinationIndicatorId);
                    return Optional.of(rateInfo); // Return the first successful match
                } else {
                    log.warn("Rate info not found for prefix {}, indicator {}", currentPrefixCode, destinationIndicatorId);
                    // If this was the only potential match and it failed rate lookup, store it for fallback
                    if (assumedRateInfo == null && allPrefixesConsistent) {
                        assumedRateInfo = new HashMap<>();
                        assumedRateInfo.put("telephony_type_id", finalTelephonyTypeId);
                        assumedRateInfo.put("operator_id", currentOperatorId);
                        assumedRateInfo.put("indicator_id", 0L); // No specific indicator found
                        assumedRateInfo.put("telephony_type_name", lookupService.findTelephonyTypeById(finalTelephonyTypeId).map(TelephonyType::getName).orElse("Unknown Type") + " (Assumed)");
                        assumedRateInfo.put("operator_name", lookupService.findOperatorById(currentOperatorId).map(Operator::getName).orElse("Unknown Operator"));
                        assumedRateInfo.put("destination_name", "Assumed Destination");
                        assumedRateInfo.put("effective_number", effectiveNumber);
                        assumedRateInfo.put("applied_trunk_pricing", usesTrunk);
                        // Add base rate info for assumed case
                        Optional<Map<String, Object>> baseRateOpt = lookupService.findBaseRateForPrefix(finalPrefixId);
                        baseRateOpt.ifPresent(assumedRateInfo::putAll);
                        log.debug("Storing assumed rate info based on consistent prefix {} (Type: {}, Op: {})", currentPrefixCode, finalTelephonyTypeId, currentOperatorId);
                    }
                    // Continue to the next prefix if rate info wasn't found for this one
                }
            } else {
                log.trace("No indicator found and length mismatch for prefix {}, number part {}", currentPrefixCode, numberWithoutPrefix);
                // Continue to the next prefix
            }
        } // End prefix loop

        // --- Fallback Logics ---

        // 1. PHP LOCAL Fallback (Only if not using trunk)
        if (prefixes.isEmpty() && !usesTrunk) {
            log.debug("No prefix found for non-trunk call, attempting lookup as LOCAL for {}", effectiveNumber);
            Optional<Map<String, Object>> localRateInfoOpt = findRateInfoForLocal(commLocation, effectiveNumber);
            if (localRateInfoOpt.isPresent()) {
                Map<String, Object> localRateInfo = localRateInfoOpt.get();
                localRateInfo.put("effective_number", effectiveNumber); // Record the number used for lookup
                localRateInfo.put("applied_trunk_pricing", false); // Indicate trunk pricing wasn't used
                return localRateInfoOpt;
            } else {
                log.warn("LOCAL fallback failed for number: {}", effectiveNumber);
                // Continue to other fallbacks or error
            }
        }

        // 2. Assumed Rate Fallback (PHP: if ($arr_destino === false) block)
        if (assumedRateInfo != null && allPrefixesConsistent) {
            log.warn("Using assumed rate info for number {} based on consistent prefix type/operator.", effectiveNumber);
            return Optional.of(assumedRateInfo);
        }

        log.warn("Attempt failed: No matching rate found for number: {}", effectiveNumber);
        return Optional.empty(); // No prefix resulted in a valid rate after all checks
    }

    /**
     * Helper specifically for the LOCAL fallback in attemptRateLookup.
     * Attempts to find rate information assuming the call is local.
     *
     * @param commLocation The communication location of the call origin.
     * @param effectiveNumber The number being looked up (already cleaned).
     * @return Optional containing rate information if found, empty otherwise.
     */
    private Optional<Map<String, Object>> findRateInfoForLocal(CommunicationLocation commLocation, String effectiveNumber) {
        Long originCountryId = getOriginCountryId(commLocation);
        Long originIndicatorId = commLocation.getIndicatorId();
        Long localType = ConfigurationService.TIPOTELE_LOCAL;

        if (originCountryId == null || originIndicatorId == null) {
            log.error("Cannot find LOCAL rate: Missing Origin Country ({}) or Indicator ID ({}) for Location {}", originCountryId, originIndicatorId, commLocation.getId());
            return Optional.empty();
        }

        // Find the internal operator configured for LOCAL calls in this country
        Optional<Operator> internalOpOpt = configService.getOperatorInternal(localType, originCountryId);
        if (internalOpOpt.isEmpty()) {
            log.warn("Cannot find internal operator for LOCAL type ({}) in country {}", localType, originCountryId);
            return Optional.empty();
        }
        Long internalOperatorId = internalOpOpt.get().getId();

        // Find the prefix associated with LOCAL type and the internal operator
        Optional<Prefix> localPrefixOpt = lookupService.findPrefixByTypeOperatorOrigin(localType, internalOperatorId, originCountryId);
        if(localPrefixOpt.isEmpty()){
            log.warn("Cannot find Prefix entity for LOCAL type ({}) and Operator {} in Country {}", localType, internalOperatorId, originCountryId);
            return Optional.empty();
        }
        Prefix localPrefix = localPrefixOpt.get();

        // Find the destination indicator based on the number and LOCAL type
        Optional<Map<String, Object>> indicatorInfoOpt = lookupService.findIndicatorByNumber(
                effectiveNumber, localType, originCountryId
        );
        if(indicatorInfoOpt.isEmpty()){
            log.warn("Could not find LOCAL indicator for number {}", effectiveNumber);
            return Optional.empty(); // No indicator means no destination match
        }
        Long destinationIndicatorId = (Long) indicatorInfoOpt.get().get("indicator_id");
        Integer destinationNdc = indicatorInfoOpt.map(ind -> (Integer) ind.get("series_ndc")).orElse(null); // Get NDC for extended check

        // --- Local Extended Check (PHP: BuscarLocalExtendida) ---
        Long finalTelephonyTypeId = localType;
        Long finalPrefixId = localPrefix.getId();
        boolean finalBandOk = localPrefix.isBandOk();
        if (destinationNdc != null) { // Check if destination NDC was found
            boolean isExtended = lookupService.isLocalExtended(destinationNdc, originIndicatorId);
            if (isExtended) {
                finalTelephonyTypeId = ConfigurationService.TIPOTELE_LOCAL_EXT;
                log.debug("Reclassified LOCAL fallback call to {} as LOCAL_EXTENDED based on NDC {} and origin {}", effectiveNumber, destinationNdc, originIndicatorId);
                // Find the appropriate prefix for the LOCAL_EXTENDED type
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
        // --- End Local Extended Check ---


        // Find the rate info (base or band) using the *final* determined prefix and type
        Optional<Map<String, Object>> rateInfoOpt = findRateInfo(finalPrefixId, destinationIndicatorId, originIndicatorId, finalBandOk);

        if (rateInfoOpt.isPresent()) {
            Map<String, Object> rateInfo = rateInfoOpt.get();
            // Populate the result map with all necessary info for pricing
            rateInfo.put("telephony_type_id", finalTelephonyTypeId); // Use final type
            rateInfo.put("operator_id", internalOperatorId);
            rateInfo.put("indicator_id", destinationIndicatorId);
            // Lookup names based on final IDs
            rateInfo.put("telephony_type_name", lookupService.findTelephonyTypeById(finalTelephonyTypeId).map(TelephonyType::getName).orElse("Unknown Type"));
            rateInfo.put("operator_name", internalOpOpt.get().getName());
            rateInfo.put("destination_name", formatDestinationName(indicatorInfoOpt.get()));
            rateInfo.put("band_name", rateInfo.get("band_name")); // Comes from findRateInfo
            // Note: effective_number and applied_trunk_pricing are added by the caller (attemptRateLookup)
            return Optional.of(rateInfo);
        } else {
            log.warn("Rate info not found for LOCAL fallback (Type: {}, Prefix: {}, Indicator: {})", finalTelephonyTypeId, finalPrefixId, destinationIndicatorId);
        }

        return Optional.empty(); // Fallback failed
    }


    // --- Helper Methods ---

    private String cleanNumber(String number, List<String> pbxPrefixes, boolean removePrefix) {
        if (!StringUtils.hasText(number)) return "";
        String cleaned = number.trim();
        int prefixLength = 0;

        // Only remove prefix if requested AND a matching prefix is found
        if (removePrefix && pbxPrefixes != null && !pbxPrefixes.isEmpty()) {
            prefixLength = getPrefixLength(cleaned, pbxPrefixes);
            if (prefixLength > 0) {
                cleaned = cleaned.substring(prefixLength);
                log.trace("Removed PBX prefix (length {}) from {}, result: {}", prefixLength, number, cleaned);
            } else {
                log.trace("Prefix removal requested but no matching prefix found in {}", number);
            }
        }

        // Remove non-numeric characters except #*+, preserving leading + if present
        boolean hasPlus = cleaned.startsWith("+");
        // Allow digits, #, *
        cleaned = cleaned.replaceAll("[^0-9#*]", "");
        // Restore leading + if it was removed by the regex
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
                    // Keep track of the longest matching prefix found
                    if (trimmedPrefix.length() > longestMatchLength) {
                        longestMatchLength = trimmedPrefix.length();
                    }
                }
            }
        }
        return longestMatchLength; // Return length of the longest match (or 0 if none)
    }

    private boolean isInternalCall(String callingNumber, String dialedNumber, CommunicationLocation commLocation) {
        ConfigurationService.ExtensionLengthConfig extConfig = configService.getExtensionLengthConfig(commLocation.getId());
        boolean callingIsExt = isLikelyExtension(callingNumber, extConfig);
        boolean dialedIsExt = isLikelyExtension(dialedNumber, extConfig);
        log.trace("isInternalCall check: Caller '{}' (isExt: {}) -> Dialed '{}' (isExt: {})", callingNumber, callingIsExt, dialedNumber, dialedIsExt);
        return callingIsExt && dialedIsExt;
    }

    // Uses the configuration service for accurate length checks
    private boolean isLikelyExtension(String number, ConfigurationService.ExtensionLengthConfig extConfig) {
        if (!StringUtils.hasText(number)) return false;
        // Handle potential leading '+' before cleaning/checking
        String effectiveNumber = number.startsWith("+") ? number.substring(1) : number;

        // Allow digits, hash, asterisk (as per PHP preg_replace)
        if (!effectiveNumber.matches("[\\d#*]+")) {
            log.trace("isLikelyExtension: '{}' contains invalid characters.", number);
            return false;
        }

        int numLength = effectiveNumber.length();
        // Check length range using config
        if (numLength < extConfig.getMinLength() || numLength > extConfig.getMaxLength()) {
            log.trace("isLikelyExtension: '{}' length {} outside range ({}-{}).", number, numLength, extConfig.getMinLength(), extConfig.getMaxLength());
            return false;
        }

        // Check against max possible value if purely numeric
        try {
            // Only check max value if it consists only of digits
            if (effectiveNumber.matches("\\d+")) {
                long numValue = Long.parseLong(effectiveNumber);
                // Use <= for max value check as it represents the upper bound
                if (numValue > extConfig.getMaxExtensionValue()) {
                    log.trace("isLikelyExtension: '{}' value {} exceeds max value {}.", number, numValue, extConfig.getMaxExtensionValue());
                    return false;
                }
            }
        } catch (NumberFormatException e) {
            // Ignore if contains # or * - length check is sufficient
            log.trace("isLikelyExtension: '{}' contains non-digits, skipping max value check.", number);
        }
        log.trace("isLikelyExtension: '{}' is considered a likely extension.", number);
        return true;
    }

    private boolean isConferenceCall(RawCdrDto rawCdr) {
        boolean finalIsConf = isConferenceCallIndicator(rawCdr.getFinalCalledPartyNumber());
        // joinOnBehalfOf == 7 indicates a "Conference Now" scenario according to Cisco docs/PHP comments
        boolean isConferenceNow = rawCdr.getJoinOnBehalfOf() != null && rawCdr.getJoinOnBehalfOf() == 7;
        return finalIsConf || isConferenceNow;
    }

    private boolean isConferenceCallIndicator(String number) {
        if (number == null || number.length() <= CONFERENCE_PREFIX.length()) return false;
        String prefix = number.substring(0, CONFERENCE_PREFIX.length());
        String rest = number.substring(CONFERENCE_PREFIX.length());
        return CONFERENCE_PREFIX.equalsIgnoreCase(prefix) && rest.matches("\\d+");
    }

    @Getter @Setter
    private static class FieldWrapper<T> { T value; FieldWrapper(T v) { this.value = v; } }

    private <T> void swapFields(FieldWrapper<T> field1, FieldWrapper<T> field2) {
        T temp = field1.getValue();
        field1.setValue(field2.getValue());
        field2.setValue(temp);
        log.trace("Swapped fields: {} <-> {}", field1.getValue(), field2.getValue());
    }

    private record LocationInfo(Long indicatorId, Long originCountryId, Long officeId) {}

    private LocationInfo getLocationInfo(Employee employee, CommunicationLocation defaultLocation) {
        Long defaultIndicatorId = defaultLocation.getIndicatorId();
        Long defaultOriginCountryId = getOriginCountryId(defaultLocation); // Use helper
        Long defaultOfficeId = null; // Office (Subdivision) is primarily from Employee

        if (employee != null) {
            Long empOfficeId = employee.getSubdivisionId(); // Subdivision ID represents the office/department
            Long empOriginCountryId = defaultOriginCountryId; // Start with default
            Long empIndicatorId = defaultIndicatorId; // Start with default

            // 1. Check Employee's assigned Communication Location (overrides default)
            if (employee.getCommunicationLocationId() != null) {
                Optional<CommunicationLocation> empLocOpt = lookupService.findCommunicationLocationById(employee.getCommunicationLocationId());
                if (empLocOpt.isPresent()) {
                    CommunicationLocation empLoc = empLocOpt.get();
                    // Use employee's location's indicator if available, else keep default
                    empIndicatorId = empLoc.getIndicatorId() != null ? empLoc.getIndicatorId() : defaultIndicatorId;
                    // Use employee's location's country if available, else keep default
                    Long empLocCountryId = getOriginCountryId(empLoc);
                    empOriginCountryId = empLocCountryId != null ? empLocCountryId : defaultOriginCountryId;
                    log.trace("Using location info from Employee's CommLocation {}: Indicator={}, Country={}", employee.getCommunicationLocationId(), empIndicatorId, empOriginCountryId);
                    // Return immediately if found via specific CommLocation
                    return new LocationInfo(empIndicatorId, empOriginCountryId, empOfficeId);
                } else {
                    log.warn("Employee {} has CommLocationId {} assigned, but location not found.", employee.getId(), employee.getCommunicationLocationId());
                }
            }

            // 2. Check Employee's Cost Center for Origin Country (if no specific CommLocation)
            if (employee.getCostCenterId() != null) {
                Optional<CostCenter> ccOpt = lookupService.findCostCenterById(employee.getCostCenterId());
                if (ccOpt.isPresent() && ccOpt.get().getOriginCountryId() != null) {
                    empOriginCountryId = ccOpt.get().getOriginCountryId(); // Override country based on Cost Center
                    log.trace("Using OriginCountry {} from Employee's CostCenter {}", empOriginCountryId, employee.getCostCenterId());
                }
            }

            // 3. Check Employee's Subdivision for Office ID (already retrieved)
            // Subdivision doesn't directly provide indicator/country in the current model

            log.trace("Final location info for Employee {}: Indicator={}, Country={}, Office={}", employee.getId(), empIndicatorId, empOriginCountryId, empOfficeId);
            return new LocationInfo(empIndicatorId, empOriginCountryId, empOfficeId);
        }

        // If no employee, return defaults from the CDR's CommunicationLocation
        log.trace("Using default location info: Indicator={}, Country={}, Office={}", defaultIndicatorId, defaultOriginCountryId, defaultOfficeId);
        return new LocationInfo(defaultIndicatorId, defaultOriginCountryId, defaultOfficeId);
    }

    private Long getOriginCountryId(CommunicationLocation commLocation) {
        if (commLocation == null) return null;
        // Try getting from linked Indicator entity first (if eager loaded or cached)
        Indicator indicator = commLocation.getIndicator();
        if (indicator != null && indicator.getOriginCountryId() != null) {
            return indicator.getOriginCountryId();
        }
        // If not available, lookup Indicator by ID
        if (commLocation.getIndicatorId() != null) {
            Optional<Indicator> indicatorOpt = lookupService.findIndicatorById(commLocation.getIndicatorId());
            if (indicatorOpt.isPresent() && indicatorOpt.get().getOriginCountryId() != null) {
                return indicatorOpt.get().getOriginCountryId();
            } else {
                log.warn("Indicator {} linked to CommLocation {} not found or has no OriginCountryId.", commLocation.getIndicatorId(), commLocation.getId());
            }
        } else {
            log.warn("CommLocation {} has no IndicatorId.", commLocation.getId());
        }
        // Fallback or return null if not found
        return null;
    }

    private void linkAssociatedEntities(CallRecord.CallRecordBuilder callBuilder) {
        CallRecord record = callBuilder.build(); // Build temporary record to access IDs

        // Link Telephony Type
        if (record.getTelephonyTypeId() != null && record.getTelephonyType() == null) {
            lookupService.findTelephonyTypeById(record.getTelephonyTypeId())
                    .ifPresentOrElse(
                            callBuilder::telephonyType,
                            () -> log.warn("Could not link TelephonyType entity for ID: {}", record.getTelephonyTypeId())
                    );
        }

        // Link Operator
        if (record.getOperatorId() != null && record.getOperator() == null) {
            lookupService.findOperatorById(record.getOperatorId())
                    .ifPresentOrElse(
                            callBuilder::operator,
                            () -> log.warn("Could not link Operator entity for ID: {}", record.getOperatorId())
                    );
        }

        // Link Indicator
        if (record.getIndicatorId() != null && record.getIndicator() == null) {
            lookupService.findIndicatorById(record.getIndicatorId())
                    .ifPresentOrElse(
                            callBuilder::indicator,
                            () -> log.warn("Could not link Indicator entity for ID: {}", record.getIndicatorId())
                    );
        }

        // Link Destination Employee (if ID exists and entity is not already set)
        if (record.getDestinationEmployeeId() != null && record.getDestinationEmployee() == null) {
            lookupService.findEmployeeById(record.getDestinationEmployeeId())
                    .ifPresentOrElse(
                            callBuilder::destinationEmployee,
                            () -> log.warn("Could not link destination employee entity for ID: {}", record.getDestinationEmployeeId())
                    );
        }

        // Note: Source Employee and CommunicationLocation are typically linked earlier or passed in.
    }

    private BigDecimal calculateBilledAmount(int durationSeconds, BigDecimal rateValue, boolean rateVatIncluded, BigDecimal vatPercentage, boolean chargePerSecond, BigDecimal initialRateValue, boolean initialRateVatIncluded) {
        // This method remains the same as before, calculating the final cost based on rates, VAT, and duration units.
        if (durationSeconds <= 0) return BigDecimal.ZERO;

        BigDecimal effectiveRateValue = Optional.ofNullable(rateValue).orElse(BigDecimal.ZERO);
        BigDecimal effectiveInitialRateValue = Optional.ofNullable(initialRateValue).orElse(BigDecimal.ZERO);

        BigDecimal durationUnits;
        if (chargePerSecond) {
            durationUnits = new BigDecimal(durationSeconds);
            log.trace("Calculating cost per second for {} seconds", durationSeconds);
        } else {
            durationUnits = new BigDecimal(durationSeconds).divide(SIXTY, 0, RoundingMode.CEILING);
            if (durationUnits.compareTo(BigDecimal.ZERO) == 0 && durationSeconds > 0) {
                durationUnits = BigDecimal.ONE;
            }
            log.trace("Calculating cost per minute for {} seconds -> {} minutes", durationSeconds, durationUnits);
        }

        BigDecimal totalCost = effectiveRateValue.multiply(durationUnits);
        log.trace("Base cost (rate * duration): {} * {} = {}", effectiveRateValue, durationUnits, totalCost);

        if (!rateVatIncluded && vatPercentage != null && vatPercentage.compareTo(BigDecimal.ZERO) > 0) {
            BigDecimal vatMultiplier = BigDecimal.ONE.add(vatPercentage.divide(new BigDecimal("100"), 4, RoundingMode.HALF_UP));
            totalCost = totalCost.multiply(vatMultiplier);
            log.trace("Applied VAT ({}%), new total: {}", vatPercentage, totalCost);
        } else {
            log.trace("VAT already included in rate or VAT is zero/null.");
        }

        return totalCost.setScale(4, RoundingMode.HALF_UP);
    }

    private BigDecimal calculateValueWithoutVat(BigDecimal value, BigDecimal vatPercentage, boolean vatIncluded) {
        // This method remains the same as before.
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
        // This method remains the same as before.
        callBuilder.telephonyTypeId(ConfigurationService.TIPOTELE_ESPECIALES);
        callBuilder.indicatorId(specialService.getIndicatorId());
        callBuilder.operatorId(null); // Special services don't usually have a specific operator rate

        BigDecimal price = Optional.ofNullable(specialService.getValue()).orElse(BigDecimal.ZERO);
        boolean vatIncluded = Optional.ofNullable(specialService.getVatIncluded()).orElse(false);
        BigDecimal vatPercentage = Optional.ofNullable(specialService.getVatAmount()).orElse(BigDecimal.ZERO);

        BigDecimal billedAmount = price;
        if (!vatIncluded && vatPercentage.compareTo(BigDecimal.ZERO) > 0) {
            BigDecimal vatMultiplier = BigDecimal.ONE.add(vatPercentage.divide(new BigDecimal("100"), 4, RoundingMode.HALF_UP));
            billedAmount = billedAmount.multiply(vatMultiplier);
            log.trace("Applied VAT {}% to special service price {}", vatPercentage, price);
        }

        callBuilder.pricePerMinute(price.setScale(4, RoundingMode.HALF_UP));
        callBuilder.initialPrice(BigDecimal.ZERO);
        callBuilder.billedAmount(billedAmount.setScale(4, RoundingMode.HALF_UP));
        log.debug("Applied Special Service pricing: Rate={}, Billed={}", price, billedAmount);
    }

    private void applyInternalPricing(Long internalCallTypeId, CallRecord.CallRecordBuilder callBuilder, int duration) {
        // This method remains the same as before.
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
        // This method remains the same as before.
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
            baseRateInfo.put("valor_inicial", BigDecimal.ZERO); // Trunk rates don't have initial value in this model
            baseRateInfo.put("valor_inicial_iva", false);
            // Find IVA from the corresponding Prefix entry
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
                baseRateInfo.put("valor_inicial", BigDecimal.ZERO); // Trunk rules don't have initial value
                baseRateInfo.put("valor_inicial_iva", false);

                Long finalOperatorId = operatorId;
                Long finalTelephonyTypeId = telephonyTypeId;

                if (rule.getNewOperatorId() != null && rule.getNewOperatorId() > 0) {
                    finalOperatorId = rule.getNewOperatorId();
                    callBuilder.operatorId(finalOperatorId);
                    lookupService.findOperatorById(finalOperatorId).ifPresent(op -> baseRateInfo.put("operator_name", op.getName()));
                }
                if (rule.getNewTelephonyTypeId() != null && rule.getNewTelephonyTypeId() > 0) {
                    finalTelephonyTypeId = rule.getNewTelephonyTypeId();
                    callBuilder.telephonyTypeId(finalTelephonyTypeId);
                    lookupService.findTelephonyTypeById(finalTelephonyTypeId).ifPresent(tt -> baseRateInfo.put("telephony_type_name", tt.getName()));
                }

                // Find IVA based on the potentially *new* type and operator
                Long finalTelephonyTypeId1 = finalTelephonyTypeId;
                Long finalOperatorId1 = finalOperatorId;
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
                applySpecialPricing(baseRateInfo, callBuilder.build().getServiceDate(), duration, originIndicatorId, callBuilder);
            }
        }
    }

    private void applySpecialPricing(Map<String, Object> currentRateInfo, LocalDateTime callDateTime, int duration, Long originIndicatorId, CallRecord.CallRecordBuilder callBuilder) {
        // This method remains the same as before.
        Long telephonyTypeId = (Long) currentRateInfo.get("telephony_type_id");
        Long operatorId = (Long) currentRateInfo.get("operator_id");
        Long bandId = (Long) currentRateInfo.get("band_id");

        List<SpecialRateValue> specialRates = lookupService.findSpecialRateValues(
                telephonyTypeId, operatorId, bandId, originIndicatorId, callDateTime
        );
        Optional<SpecialRateValue> applicableRate = findApplicableSpecialRate(specialRates, callDateTime);

        if (applicableRate.isPresent()) {
            SpecialRateValue rate = applicableRate.get();
            log.debug("Applying SpecialRateValue {}", rate.getId());
            BigDecimal originalRate = (BigDecimal) currentRateInfo.get("valor_minuto");
            boolean originalVatIncluded = (Boolean) currentRateInfo.get("valor_minuto_iva");

            // PHP: Guardar_ValorInicial - Store original rate before modification
            currentRateInfo.put("valor_inicial", originalRate);
            currentRateInfo.put("valor_inicial_iva", originalVatIncluded);

            if (rate.getValueType() != null && rate.getValueType() == 1) { // Percentage discount
                BigDecimal discountPercentage = Optional.ofNullable(rate.getRateValue()).orElse(BigDecimal.ZERO);
                BigDecimal currentRateNoVat = calculateValueWithoutVat(originalRate, (BigDecimal) currentRateInfo.get("iva"), originalVatIncluded);
                BigDecimal discountMultiplier = BigDecimal.ONE.subtract(discountPercentage.divide(new BigDecimal("100"), 4, RoundingMode.HALF_UP));
                currentRateInfo.put("valor_minuto", currentRateNoVat.multiply(discountMultiplier));
                currentRateInfo.put("valor_minuto_iva", originalVatIncluded); // VAT status doesn't change with percentage discount
                currentRateInfo.put("descuento_p", discountPercentage);
                log.trace("Applied percentage discount {}% from SpecialRateValue {}", discountPercentage, rate.getId());
            } else { // Fixed value override
                currentRateInfo.put("valor_minuto", Optional.ofNullable(rate.getRateValue()).orElse(BigDecimal.ZERO));
                currentRateInfo.put("valor_minuto_iva", Optional.ofNullable(rate.getIncludesVat()).orElse(false));
                log.trace("Applied fixed rate {} from SpecialRateValue {}", currentRateInfo.get("valor_minuto"), rate.getId());
            }
            currentRateInfo.put("ensegundos", false); // Special rates are assumed per minute unless specified otherwise (not in model)
            applyFinalPricing(currentRateInfo, duration, callBuilder);
        } else {
            log.debug("No applicable special rate found, applying current rate.");
            currentRateInfo.put("valor_inicial", BigDecimal.ZERO); // No special rate, so no "initial" value needed
            currentRateInfo.put("valor_inicial_iva", false);
            currentRateInfo.put("ensegundos", false); // Assume per minute if no special rate
            applyFinalPricing(currentRateInfo, duration, callBuilder);
        }
    }

    private void applyFinalPricing(Map<String, Object> rateInfo, int duration, CallRecord.CallRecordBuilder callBuilder) {
        // This method remains the same as before.
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

    private Optional<Map<String, Object>> findRateInfo(Long prefixId, Long indicatorId, Long originIndicatorId, boolean bandOk) {
        // This method remains the same as before.
        Optional<Map<String, Object>> baseRateOpt = lookupService.findBaseRateForPrefix(prefixId);
        if (baseRateOpt.isEmpty()) {
            log.warn("Base rate info not found for prefixId: {}", prefixId);
            return Optional.empty();
        }
        Map<String, Object> rateInfo = new HashMap<>(baseRateOpt.get());
        rateInfo.put("band_id", 0L);
        rateInfo.put("band_name", "");
        rateInfo.putIfAbsent("base_value", BigDecimal.ZERO);
        rateInfo.putIfAbsent("vat_included", false);
        rateInfo.putIfAbsent("vat_value", BigDecimal.ZERO);

        boolean useBands = bandOk && indicatorId != null && indicatorId > 0;
        log.trace("findRateInfo: prefixId={}, indicatorId={}, originIndicatorId={}, bandOk={}, useBands={}",
                prefixId, indicatorId, originIndicatorId, bandOk, useBands);

        if (useBands) {
            Optional<Map<String, Object>> bandOpt = lookupService.findBandByPrefixAndIndicator(prefixId, indicatorId, originIndicatorId);
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
            }
        } else {
            log.trace("Using base rate for prefix {} (Bands not applicable or indicator missing)", prefixId);
        }
        rateInfo.put("valor_minuto", rateInfo.get("base_value"));
        rateInfo.put("valor_minuto_iva", rateInfo.get("vat_included"));
        rateInfo.put("iva", rateInfo.get("vat_value"));

        return Optional.of(rateInfo);
    }

    private String formatDestinationName(Map<String, Object> indicatorInfo) {
        // This method remains the same as before.
        String city = (String) indicatorInfo.get("city_name");
        String country = (String) indicatorInfo.get("department_country");
        if (StringUtils.hasText(city) && StringUtils.hasText(country)) return city + " (" + country + ")";
        return StringUtils.hasText(city) ? city : (StringUtils.hasText(country) ? country : "Unknown Destination");
    }

    private Optional<SpecialRateValue> findApplicableSpecialRate(List<SpecialRateValue> candidates, LocalDateTime callDateTime) {
        // This method remains the same as before.
        int callHour = callDateTime.getHour();
        return candidates.stream()
                .filter(rate -> isHourApplicable(rate.getHoursSpecification(), callHour))
                .findFirst();
    }

    private boolean isHourApplicable(String hoursSpecification, int callHour) {
        // This method remains the same as before.
        if (!StringUtils.hasText(hoursSpecification)) return true;
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
            return false;
        }
        return false;
    }

    /**
     * Preprocesses a phone number based on Colombian numbering plan rules,
     * mimicking PHP functions _esCelular_fijo and _esEntrante_60.
     * This version is specifically for preparing the number for LOOKUP.
     * Includes logic from _esNacional based on SERIE_EMPRESA.
     *
     * @param number The phone number to preprocess.
     * @param originCountryId The origin country ID (used to check if rules apply).
     * @return The potentially modified phone number for lookup.
     */
    private String preprocessNumberForLookup(String number, Long originCountryId) {
        // This method remains the same as before.
        if (number == null || originCountryId == null || !originCountryId.equals(COLOMBIA_ORIGIN_COUNTRY_ID)) {
            return number;
        }

        int len = number.length();
        String originalNumber = number;
        String processedNumber = number;

        if (len == 10) {
            if (number.startsWith("3")) {
                if (number.matches("^3[0-4][0-9]\\d{7}$")) {
                    processedNumber = "03" + number;
                }
            } else if (number.startsWith("60")) {
                String nationalPrefix = determineNationalPrefix(number);
                if (nationalPrefix != null) {
                    processedNumber = nationalPrefix + number.substring(2);
                }
            }
        } else if (len == 11) {
            if (number.startsWith("03")) {
                if (number.matches("^03[0-4][0-9]\\d{7}$")) {
                    // Keep as is for lookup (03 prefix is handled by prefix lookup)
                    // processedNumber = number.substring(1); // Remove leading 0
                    // processedNumber = "03" + processedNumber; // Add 03 back - redundant
                }
            } else if (number.startsWith("604")) {
                if (number.matches("^604\\d{8}$")) {
                    processedNumber = number.substring(3); // Remove 604 for local lookup? PHP logic is unclear here, assume remove
                }
            }
        } else if (len == 12) {
            if (number.startsWith("573") || number.startsWith("603")) {
                if (number.matches("^(57|60)3[0-4][0-9]\\d{7}$")) {
                    processedNumber = number.substring(2); // Remove 57 or 60
                    processedNumber = "03" + processedNumber; // Add 03 prefix
                }
            } else if (number.startsWith("6060") || number.startsWith("5760")) {
                if (number.matches("^(57|60)60\\d{8}$")) {
                     // PHP logic removes 4 digits (5760 or 6060) leaving 8 digits
                     // This likely implies it becomes a local number lookup
                    processedNumber = number.substring(4);
                }
            }
        } else if (len == 9 && number.startsWith("60")) {
            if (number.matches("^60\\d{7}$")) {
                // Try to determine national prefix based on the implied full 10-digit number
                 String impliedFullNumber = "60" + number.substring(2, 3) + number.substring(3); // Reconstruct potential 10-digit
                 String nationalPrefix = determineNationalPrefix(impliedFullNumber);
                 if (nationalPrefix != null) {
                     processedNumber = nationalPrefix + number.substring(2); // Apply national prefix + 7 digits
                 } else {
                     // If no national prefix found, assume it's local (remove 60)
                     processedNumber = number.substring(2);
                 }
            }
        }

        if (!originalNumber.equals(processedNumber)) {
            log.debug("Preprocessed number for lookup: {} -> {}", originalNumber, processedNumber);
        }
        return processedNumber;
    }

    /**
     * Helper to determine the national prefix (09, 07, 05, 0456) based on the
     * SERIE_EMPRESA associated with a 10-digit fixed-line number starting with '60'.
     * Mimics the PHP _esNacional function.
     *
     * @param number10Digit The 10-digit number starting with '60'.
     * @return The national prefix string or null if not determinable.
     */
    private String determineNationalPrefix(String number10Digit) {
        // This method remains the same as before.
        if (number10Digit == null || !number10Digit.startsWith("60") || number10Digit.length() != 10) {
            return null;
        }
        String ndcStr = number10Digit.substring(2, 3);
        String subscriberNumberStr = number10Digit.substring(3);

        if (!ndcStr.matches("\\d") || !subscriberNumberStr.matches("\\d+")) {
            return null;
        }
        int ndc = Integer.parseInt(ndcStr);
        long subscriberNumber = Long.parseLong(subscriberNumberStr);

        Optional<Map<String, Object>> seriesInfoOpt = lookupService.findSeriesInfoForNationalLookup(ndc, subscriberNumber);

        if (seriesInfoOpt.isPresent()) {
            String company = (String) seriesInfoOpt.get().get("series_company");
            if (company != null) {
                company = company.toUpperCase();
                if (company.contains("TELMEX")) return "0456";
                if (company.contains("COLOMBIA TELECOMUNICACIONES")) return "09";
                if (company.contains("UNE EPM")) return "05";
                if (company.contains("EMPRESA DE TELECOMUNICACIONES DE BOGOTÁ")) return "07";
            }
        }
        return null;
    }
}