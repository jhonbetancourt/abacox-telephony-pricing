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
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.HashMap;
import java.util.stream.Collectors; // Added for stream operations

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
        callBuilder.isIncoming(rawCdr.isIncoming());
        callBuilder.trunk(rawCdr.getDestDeviceName());
        callBuilder.initialTrunk(rawCdr.getOrigDeviceName());
        callBuilder.employeeTransfer(rawCdr.getLastRedirectDn());
        // Transfer cause will be set later based on enum mapping
        callBuilder.assignmentCause(CallAssignmentCause.UNKNOWN.getValue()); // Default assignment cause
        callBuilder.fileInfoId(rawCdr.getFileInfoId());
        callBuilder.originIp(rawCdr.getSourceIp());
        callBuilder.billedAmount(BigDecimal.ZERO);
        callBuilder.pricePerMinute(BigDecimal.ZERO);
        callBuilder.initialPrice(BigDecimal.ZERO);

        // --- Employee Lookup ---
        Optional<Employee> employeeOpt = lookupService.findEmployeeByExtensionOrAuthCode(
                rawCdr.getCallingPartyNumber(),
                rawCdr.getAuthCodeDescription(),
                commLocation.getId()
        );
        employeeOpt.ifPresent(employee -> {
            callBuilder.employeeId(employee.getId());
            callBuilder.employee(employee);
            if (StringUtils.hasText(rawCdr.getAuthCodeDescription())) {
                callBuilder.assignmentCause(CallAssignmentCause.AUTH_CODE.getValue());
            } else {
                callBuilder.assignmentCause(CallAssignmentCause.EXTENSION.getValue());
            }
        });
        if (employeeOpt.isEmpty()) {
             log.warn("Initial employee lookup failed for CDR {} (Ext: {}, Code: {})",
                     rawCdr.getGlobalCallId(), rawCdr.getCallingPartyNumber(), rawCdr.getAuthCodeDescription());
             // Assignment cause remains UNKNOWN or potentially RANGES if range lookup is implemented later
        }

        // --- Conference Handling & Field Adjustment (PHP: CM_FormatoCDR conference logic) ---
        boolean isConference = isConferenceCall(rawCdr);
        boolean isPostConference = false; // Flag for post-conference cleanup calls
        // Use wrappers to easily manage effective values after potential swaps
        FieldWrapper<String> effectiveCallingNumber = new FieldWrapper<>(rawCdr.getCallingPartyNumber());
        FieldWrapper<String> effectiveDialedNumber = new FieldWrapper<>(rawCdr.getFinalCalledPartyNumber());
        FieldWrapper<String> effectiveCallingPartition = new FieldWrapper<>(rawCdr.getCallingPartyNumberPartition());
        FieldWrapper<String> effectiveDialedPartition = new FieldWrapper<>(rawCdr.getFinalCalledPartyNumberPartition());
        FieldWrapper<String> effectiveTrunk = new FieldWrapper<>(rawCdr.getDestDeviceName());
        FieldWrapper<String> effectiveInitialTrunk = new FieldWrapper<>(rawCdr.getOrigDeviceName());
        FieldWrapper<Boolean> effectiveIncoming = new FieldWrapper<>(rawCdr.isIncoming());
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

            // Swap trunks only if the original call direction was *outgoing*
            if (!rawCdr.isIncoming()) {
                 swapFields(effectiveTrunk, effectiveInitialTrunk);
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
        } else {
            // Check for post-conference calls only if it wasn't identified as an active conference
            isPostConference = isConferenceCallIndicator(rawCdr.getLastRedirectDn());
            if (isPostConference) {
                log.debug("CDR {} identified as post-conference.", rawCdr.getGlobalCallId());
                callBuilder.transferCause(CallTransferCause.CONFERENCE_END.getValue());
                // PHP logic didn't swap trunks here, so we don't either.
                // Employee assignment might need adjustment if the remaining parties are external,
                // but that requires complex state/lookup omitted for now.
            }
        }

        // --- Determine Call Type and Enrich ---
        try {
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
        // Set transfer cause enum value only if it wasn't overridden by conference/post-conference logic
        Integer currentTransferCause = callBuilder.build().getTransferCause();
        if (currentTransferCause == null || currentTransferCause == CallTransferCause.NONE.getValue()) { // Check if null or default (0)
             callBuilder.transferCause(CallTransferCause.fromValue(rawCdr.getLastRedirectRedirectReason()).getValue());
        }
        // Link associated entities based on IDs set during enrichment
        linkAssociatedEntities(callBuilder);

        CallRecord finalRecord = callBuilder.build();
        log.info("Successfully enriched CDR {}: Type={}, Billed={}",
                rawCdr.getGlobalCallId(), finalRecord.getTelephonyTypeId(), finalRecord.getBilledAmount());
        return Optional.of(finalRecord);
    }

    // ========================================================================
    // Processing Logic Methods
    // ========================================================================

    private void processOutgoingCall(RawCdrDto rawCdr, CommunicationLocation commLocation, CallRecord.CallRecordBuilder callBuilder, String effectiveCallingNumber, String effectiveDialedNumber) {
        log.debug("Processing outgoing call for CDR {} (effective: {} -> {})", rawCdr.getGlobalCallId(), effectiveCallingNumber, effectiveDialedNumber);
        String originalDialedNumber = rawCdr.getFinalCalledPartyNumber(); // Keep original for PBX rule check
        List<String> pbxPrefixes = configService.getPbxPrefixes(commLocation.getId());
        Long originCountryId = getOriginCountryId(commLocation);

        // 1. Preprocess Number (Colombian rules for lookup)
        String preprocessedNumber = preprocessNumberForLookup(effectiveDialedNumber, originCountryId);
        FieldWrapper<Long> forcedTelephonyType = new FieldWrapper<>(null);
        if (!preprocessedNumber.equals(effectiveDialedNumber)) {
            log.debug("Number preprocessed for lookup: {} -> {}", effectiveDialedNumber, preprocessedNumber);
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
        boolean shouldRemovePrefix = getPrefixLength(originalDialedNumber, pbxPrefixes) > 0;
        String cleanedNumber = cleanNumber(preprocessedNumber, pbxPrefixes, shouldRemovePrefix);
        // Set the 'dial' field to the cleaned number (which might still have prefixes if !shouldRemovePrefix)
        callBuilder.dial(cleanedNumber);
        log.trace("Initial cleaned number (dial field): {}", cleanedNumber);

        // 3. Check PBX Special Rules (applied to original number before any cleaning/preprocessing)
        Optional<PbxSpecialRule> pbxRuleOpt = lookupService.findPbxSpecialRule(
                originalDialedNumber, commLocation.getId(), CallDirection.OUTGOING.getValue()
        );
        if (pbxRuleOpt.isPresent()) {
            PbxSpecialRule rule = pbxRuleOpt.get();
            String replacement = rule.getReplacement() != null ? rule.getReplacement() : "";
            String searchPattern = rule.getSearchPattern();
            // Check if the original number starts with the search pattern
            if (StringUtils.hasText(searchPattern) && originalDialedNumber.startsWith(searchPattern)) {
                 String numberAfterSearch = originalDialedNumber.substring(searchPattern.length());
                 String modifiedNumber = replacement + numberAfterSearch; // Apply replacement
                 log.debug("Applied PBX rule {}, pre-processed number: {}", rule.getId(), modifiedNumber);

                 // Re-preprocess and re-clean the number *after* applying the rule
                 preprocessedNumber = preprocessNumberForLookup(modifiedNumber, originCountryId);
                 forcedTelephonyType.setValue(null); // Reset forced type
                 if (!preprocessedNumber.equals(modifiedNumber)) {
                    if (preprocessedNumber.startsWith("03") && preprocessedNumber.length() == 12) {
                        forcedTelephonyType.setValue(ConfigurationService.TIPOTELE_CELULAR);
                    } else if (preprocessedNumber.matches("^\\d{7,8}$")) {
                        forcedTelephonyType.setValue(ConfigurationService.TIPOTELE_LOCAL);
                    }
                 }
                 // Clean again, deciding prefix removal based on the *modified* number
                 shouldRemovePrefix = getPrefixLength(modifiedNumber, pbxPrefixes) > 0;
                 cleanedNumber = cleanNumber(preprocessedNumber, pbxPrefixes, shouldRemovePrefix);
                 callBuilder.dial(cleanedNumber); // Update dial field
                 log.debug("Number after PBX rule {} and re-cleaning: {}", rule.getId(), cleanedNumber);
            }
        }

        // 4. Check Special Services (using the potentially modified cleanedNumber)
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

        // 5. Check if Internal Call (using cleanedNumber)
        if (isInternalCall(effectiveCallingNumber, cleanedNumber, commLocation)) {
             log.debug("Processing as internal call (effective: {} -> {})", effectiveCallingNumber, cleanedNumber);
             processInternalCall(rawCdr, commLocation, callBuilder, cleanedNumber);
             return; // Processing finished for internal call
        }

        // 6. Process as External Outgoing Call (using cleanedNumber)
        log.debug("Processing as external outgoing call (effective: {} -> {})", effectiveCallingNumber, cleanedNumber);
        evaluateDestinationAndRate(rawCdr, commLocation, callBuilder, cleanedNumber, pbxPrefixes, forcedTelephonyType.getValue());
    }

    private void processIncomingCall(RawCdrDto rawCdr, CommunicationLocation commLocation, CallRecord.CallRecordBuilder callBuilder, String effectiveCallingNumber) {
        log.debug("Processing incoming call for CDR {} (effective caller: {})", rawCdr.getGlobalCallId(), effectiveCallingNumber);
        Long originCountryId = getOriginCountryId(commLocation);

        // 1. Preprocess Number (Colombian rules for lookup)
        String preprocessedNumber = preprocessNumberForLookup(effectiveCallingNumber, originCountryId);
        FieldWrapper<Long> forcedTelephonyType = new FieldWrapper<>(null);
        if (!preprocessedNumber.equals(effectiveCallingNumber)) {
            log.debug("Incoming number preprocessed for lookup: {} -> {}", effectiveCallingNumber, preprocessedNumber);
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

        // 3. Check PBX Special Rules (applied to original effective number)
         Optional<PbxSpecialRule> pbxRuleOpt = lookupService.findPbxSpecialRule(
                effectiveCallingNumber, commLocation.getId(), CallDirection.INCOMING.getValue()
         );
         if (pbxRuleOpt.isPresent()) {
             PbxSpecialRule rule = pbxRuleOpt.get();
             String replacement = rule.getReplacement() != null ? rule.getReplacement() : "";
             String searchPattern = rule.getSearchPattern();
             if (StringUtils.hasText(searchPattern) && effectiveCallingNumber.startsWith(searchPattern)) {
                 String numberAfterSearch = effectiveCallingNumber.substring(searchPattern.length());
                 String modifiedNumber = replacement + numberAfterSearch;
                 log.debug("Applied PBX rule {} to incoming number, pre-processed number: {}", rule.getId(), modifiedNumber);

                 // Re-preprocess and re-clean the number *after* applying the rule
                 preprocessedNumber = preprocessNumberForLookup(modifiedNumber, originCountryId);
                 forcedTelephonyType.setValue(null); // Reset forced type
                 if (!preprocessedNumber.equals(modifiedNumber)) {
                     if (preprocessedNumber.startsWith("03") && preprocessedNumber.length() == 12) {
                         forcedTelephonyType.setValue(ConfigurationService.TIPOTELE_CELULAR);
                     } else if (preprocessedNumber.matches("^\\d{7,8}$")) {
                         forcedTelephonyType.setValue(ConfigurationService.TIPOTELE_LOCAL);
                     }
                 }
                 cleanedCallingNumber = cleanNumber(preprocessedNumber, Collections.emptyList(), false);
                 callBuilder.dial(cleanedCallingNumber); // Update dial field
                 log.debug("Incoming number after PBX rule {} and re-cleaning: {}", rule.getId(), cleanedCallingNumber);
             }
         }

        // 4. Determine Origin (buscarOrigen logic)
        Long commIndicatorId = commLocation.getIndicatorId();
        if (originCountryId != null && commIndicatorId != null) {
            String numberForLookup = cleanedCallingNumber; // Use the cleaned (and potentially rule-modified) number
            // PHP logic checks for PBX prefix on original number for incoming calls too
            List<String> pbxPrefixes = configService.getPbxPrefixes(commLocation.getId());
            int prefixLen = getPrefixLength(effectiveCallingNumber, pbxPrefixes); // Check original for prefix
            if (prefixLen > 0) {
                // If original had prefix, lookup origin using number *after* prefix
                numberForLookup = effectiveCallingNumber.substring(prefixLen);
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
            Optional<Map<String, Object>> bestPrefix = prefixes.stream().findFirst();

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

        if (destEmployeeOpt.isPresent()) {
             callBuilder.destinationEmployeeId(destEmployeeOpt.get().getId());
             callBuilder.destinationEmployee(destEmployeeOpt.get());
        } else {
             log.warn("Internal call destination extension {} not found as employee.", destinationExtension);
             // PHP logic: If destination not found, check prefixes, then default to a configured internal type.
             // Let's default to INTERNA_IP for now, but this could be refined.
             callBuilder.telephonyTypeId(ConfigurationService.TIPOTELE_INTERNA_IP);
             callBuilder.indicatorId(sourceLoc.indicatorId); // Use source indicator if dest unknown
             configService.getOperatorInternal(callBuilder.build().getTelephonyTypeId(), sourceLoc.originCountryId)
                     .ifPresent(op -> callBuilder.operatorId(op.getId()));
             applyInternalPricing(callBuilder.build().getTelephonyTypeId(), callBuilder, rawCdr.getDuration());
             return;
        }

        // Determine internal call type based on location comparison (PHP: tipo_llamada_interna)
        Long internalCallTypeId;
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

        callBuilder.telephonyTypeId(internalCallTypeId);
        // For internal calls, the 'indicator' is typically the destination's indicator
        callBuilder.indicatorId(destLoc.indicatorId);
        // Find the internal operator for this type and origin country
        configService.getOperatorInternal(internalCallTypeId, sourceLoc.originCountryId)
                .ifPresent(op -> callBuilder.operatorId(op.getId()));

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


        // PHP LOCAL Fallback Logic (PHP: Adds local prefix if no other prefix matches)
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
                // Continue, might result in error later if no other match found
            }
        } else if (prefixes.isEmpty()) {
             log.warn("Attempt failed: No prefix found for number: {}", effectiveNumber);
             return Optional.empty(); // No prefixes and not eligible for LOCAL fallback
        }


        // --- Iterate through found prefixes (ordered by length DESC) ---
        for (Map<String, Object> prefixInfo : prefixes) {
            Long currentTelephonyTypeId = (Long) prefixInfo.get("telephony_type_id");
            Long currentOperatorId = (Long) prefixInfo.get("operator_id");
            String currentPrefixCode = (String) prefixInfo.get("prefix_code");
            Long currentPrefixId = (Long) prefixInfo.get("prefix_id");
            int typeMinLength = (Integer) prefixInfo.get("telephony_type_min");
            int typeMaxLength = (Integer) prefixInfo.get("telephony_type_max");
            boolean bandOk = (Boolean) prefixInfo.get("prefix_band_ok");

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
            // Check if length matches exactly if max length is defined (PHP logic implies this matters if indicator not found)
            boolean lengthMatch = (effectiveMaxLength > 0 && numberWithoutPrefix.length() == effectiveMaxLength);
            // Consider it a potential match if indicator is found OR if length matches expected max (even without indicator)
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
                    rateInfo.put("destination_name", indicatorInfoOpt.map(this::formatDestinationName).orElse("Unknown Destination"));
                    rateInfo.put("band_name", rateInfo.get("band_name")); // Comes from findRateInfo
                    rateInfo.put("effective_number", effectiveNumber); // The number used for this successful lookup
                    rateInfo.put("applied_trunk_pricing", usesTrunk); // Flag if trunk logic was involved

                    log.debug("Attempt successful: Found rate for prefix {}, indicator {}", currentPrefixCode, destinationIndicatorId);
                    return Optional.of(rateInfo); // Return the first successful match
                } else {
                     log.warn("Rate info not found for prefix {}, indicator {}", currentPrefixCode, destinationIndicatorId);
                     // Continue to the next prefix if rate info wasn't found for this one
                }
            } else {
                 log.trace("No indicator found and length mismatch for prefix {}, number part {}", currentPrefixCode, numberWithoutPrefix);
                 // Continue to the next prefix
            }
        } // End prefix loop

        log.warn("Attempt failed: No matching rate found for number: {}", effectiveNumber);
        return Optional.empty(); // No prefix resulted in a valid rate
    }

    // Helper specifically for the LOCAL fallback in attemptRateLookup
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
        if (durationSeconds <= 0) return BigDecimal.ZERO;

        // Use default zero if rate is null
        BigDecimal effectiveRateValue = Optional.ofNullable(rateValue).orElse(BigDecimal.ZERO);
        BigDecimal effectiveInitialRateValue = Optional.ofNullable(initialRateValue).orElse(BigDecimal.ZERO);

        // Determine the duration units (seconds or minutes rounded up)
        BigDecimal durationUnits;
        if (chargePerSecond) {
            durationUnits = new BigDecimal(durationSeconds);
            log.trace("Calculating cost per second for {} seconds", durationSeconds);
        } else {
            // Round up to the nearest minute
            durationUnits = new BigDecimal(durationSeconds).divide(SIXTY, 0, RoundingMode.CEILING);
            // Ensure minimum 1 minute is charged if duration > 0
            if (durationUnits.compareTo(BigDecimal.ZERO) == 0 && durationSeconds > 0) {
                durationUnits = BigDecimal.ONE;
            }
             log.trace("Calculating cost per minute for {} seconds -> {} minutes", durationSeconds, durationUnits);
        }

        // Calculate base cost
        BigDecimal totalCost = effectiveRateValue.multiply(durationUnits);
         log.trace("Base cost (rate * duration): {} * {} = {}", effectiveRateValue, durationUnits, totalCost);

        // Add VAT if applicable
        if (!rateVatIncluded && vatPercentage != null && vatPercentage.compareTo(BigDecimal.ZERO) > 0) {
            BigDecimal vatMultiplier = BigDecimal.ONE.add(vatPercentage.divide(new BigDecimal("100"), 4, RoundingMode.HALF_UP));
            totalCost = totalCost.multiply(vatMultiplier);
            log.trace("Applied VAT ({}%), new total: {}", vatPercentage, totalCost);
        } else {
             log.trace("VAT already included in rate or VAT is zero/null.");
        }

        // Note: PHP logic had a separate 'cargo_basico' which is omitted here as per entity structure.
        // If initialRateValue represents a base charge, it needs different handling.
        // Assuming here rateValue is per unit (second/minute).

        return totalCost.setScale(4, RoundingMode.HALF_UP); // Scale as per DB precision
    }

     private BigDecimal calculateValueWithoutVat(BigDecimal value, BigDecimal vatPercentage, boolean vatIncluded) {
        if (value == null) return BigDecimal.ZERO;
        // Return original value if VAT is not included or if VAT percentage is invalid
        if (!vatIncluded || vatPercentage == null || vatPercentage.compareTo(BigDecimal.ZERO) <= 0) {
            return value.setScale(4, RoundingMode.HALF_UP); // Ensure consistent scale
        }
        // Calculate the divisor (1 + VAT rate)
        BigDecimal vatDivisor = BigDecimal.ONE.add(vatPercentage.divide(new BigDecimal("100"), 10, RoundingMode.HALF_UP)); // Use higher precision for division
        // Avoid division by zero, although unlikely with (1 + rate)
        if (vatDivisor.compareTo(BigDecimal.ZERO) == 0) {
            log.warn("VAT divisor is zero, cannot remove VAT from {}", value);
            return value.setScale(4, RoundingMode.HALF_UP);
        }
        // Divide to remove VAT and scale result
        return value.divide(vatDivisor, 4, RoundingMode.HALF_UP);
    }

    private void applySpecialServicePricing(SpecialService specialService, CallRecord.CallRecordBuilder callBuilder, int duration) {
        callBuilder.telephonyTypeId(ConfigurationService.TIPOTELE_ESPECIALES); // Set type to Special
        callBuilder.indicatorId(specialService.getIndicatorId()); // Link to the specific indicator if applicable
        callBuilder.operatorId(null); // Special services usually don't have a standard operator

        BigDecimal price = Optional.ofNullable(specialService.getValue()).orElse(BigDecimal.ZERO);
        boolean vatIncluded = Optional.ofNullable(specialService.getVatIncluded()).orElse(false);
        // Use vatAmount as the percentage for calculation if needed
        BigDecimal vatPercentage = Optional.ofNullable(specialService.getVatAmount()).orElse(BigDecimal.ZERO);

        BigDecimal billedAmount = price;
        // Apply VAT only if it's not included in the base price and a VAT percentage is specified
        if (!vatIncluded && vatPercentage.compareTo(BigDecimal.ZERO) > 0) {
             BigDecimal vatMultiplier = BigDecimal.ONE.add(vatPercentage.divide(new BigDecimal("100"), 4, RoundingMode.HALF_UP));
             billedAmount = billedAmount.multiply(vatMultiplier);
             log.trace("Applied VAT {}% to special service price {}", vatPercentage, price);
        }

        // Special services are typically flat rate, duration doesn't affect cost.
        // PricePerMinute is set to the flat rate for reference. Initial price is zero.
        callBuilder.pricePerMinute(price.setScale(4, RoundingMode.HALF_UP));
        callBuilder.initialPrice(BigDecimal.ZERO);
        callBuilder.billedAmount(billedAmount.setScale(4, RoundingMode.HALF_UP));
        log.debug("Applied Special Service pricing: Rate={}, Billed={}", price, billedAmount);
    }

    private void applyInternalPricing(Long internalCallTypeId, CallRecord.CallRecordBuilder callBuilder, int duration) {
         Optional<Map<String, Object>> tariffOpt = lookupService.findInternalTariff(internalCallTypeId);
         if (tariffOpt.isPresent()) {
             Map<String, Object> tariff = tariffOpt.get();
             // Ensure defaults for missing keys before applying
             tariff.putIfAbsent("valor_minuto", BigDecimal.ZERO);
             tariff.putIfAbsent("valor_minuto_iva", false);
             tariff.putIfAbsent("iva", BigDecimal.ZERO);
             tariff.putIfAbsent("valor_inicial", BigDecimal.ZERO);
             tariff.putIfAbsent("valor_inicial_iva", false);
             tariff.putIfAbsent("ensegundos", false); // Internal calls usually per minute

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
        Long originCountryId = getOriginCountryId(trunk.getCommLocation()); // Get country from trunk's location

        // 1. Check for specific TrunkRate
        Optional<TrunkRate> trunkRateOpt = lookupService.findTrunkRate(trunk.getId(), operatorId, telephonyTypeId);

        if (trunkRateOpt.isPresent()) {
            TrunkRate trunkRate = trunkRateOpt.get();
            log.debug("Applying TrunkRate {} for trunk {}", trunkRate.getId(), trunk.getId());
            // Override base rate info with TrunkRate specifics
            baseRateInfo.put("valor_minuto", Optional.ofNullable(trunkRate.getRateValue()).orElse(BigDecimal.ZERO));
            baseRateInfo.put("valor_minuto_iva", Optional.ofNullable(trunkRate.getIncludesVat()).orElse(false));
            baseRateInfo.put("ensegundos", trunkRate.getSeconds() != null && trunkRate.getSeconds() > 0);
            // Trunk rates don't have an initial charge concept separate from per-unit rate
            baseRateInfo.put("valor_inicial", BigDecimal.ZERO);
            baseRateInfo.put("valor_inicial_iva", false);
            // Fetch IVA specific to this operator/type combination
            lookupService.findPrefixByTypeOperatorOrigin(telephonyTypeId, operatorId, originCountryId)
                       .ifPresent(p -> baseRateInfo.put("iva", p.getVatValue()));
            applyFinalPricing(baseRateInfo, duration, callBuilder);
        } else {
            // 2. Check for TrunkRule if no specific TrunkRate found
            Optional<TrunkRule> trunkRuleOpt = lookupService.findTrunkRule(trunk.getName(), telephonyTypeId, indicatorId, originIndicatorId);
            if (trunkRuleOpt.isPresent()) {
                TrunkRule rule = trunkRuleOpt.get();
                log.debug("Applying TrunkRule {} for trunk {}", rule.getId(), trunk.getName());
                // Override base rate info with TrunkRule specifics
                baseRateInfo.put("valor_minuto", Optional.ofNullable(rule.getRateValue()).orElse(BigDecimal.ZERO));
                baseRateInfo.put("valor_minuto_iva", Optional.ofNullable(rule.getIncludesVat()).orElse(false));
                baseRateInfo.put("ensegundos", rule.getSeconds() != null && rule.getSeconds() > 0);
                baseRateInfo.put("valor_inicial", BigDecimal.ZERO);
                baseRateInfo.put("valor_inicial_iva", false);

                // Apply overrides from the rule
                Long finalOperatorId = operatorId; // Start with original
                Long finalTelephonyTypeId = telephonyTypeId;

                if (rule.getNewOperatorId() != null && rule.getNewOperatorId() > 0) {
                    finalOperatorId = rule.getNewOperatorId();
                    callBuilder.operatorId(finalOperatorId);
                    // Update operator name in rateInfo for consistency if needed (optional)
                    lookupService.findOperatorById(finalOperatorId).ifPresent(op -> baseRateInfo.put("operator_name", op.getName()));
                }
                if (rule.getNewTelephonyTypeId() != null && rule.getNewTelephonyTypeId() > 0) {
                     finalTelephonyTypeId = rule.getNewTelephonyTypeId();
                    callBuilder.telephonyTypeId(finalTelephonyTypeId);
                    // Update type name in rateInfo (optional)
                     lookupService.findTelephonyTypeById(finalTelephonyTypeId).ifPresent(tt -> baseRateInfo.put("telephony_type_name", tt.getName()));
                }

                // Re-fetch IVA based on the *final* operator/type after rule application
                Long finalTelephonyTypeId1 = finalTelephonyTypeId;
                Long finalOperatorId1 = finalOperatorId;
                lookupService.findPrefixByTypeOperatorOrigin(finalTelephonyTypeId, finalOperatorId, originCountryId)
                           .ifPresentOrElse(
                               p -> baseRateInfo.put("iva", p.getVatValue()),
                               () -> { // If no prefix found for the new combo, default IVA to 0
                                   log.warn("No prefix found for rule-defined type {} / operator {}. Using default IVA 0.", finalTelephonyTypeId1, finalOperatorId1);
                                   baseRateInfo.put("iva", BigDecimal.ZERO);
                               }
                           );

                applyFinalPricing(baseRateInfo, duration, callBuilder);
            } else {
                // 3. No TrunkRate or TrunkRule found, use base/band rate + special rates
                log.debug("No specific TrunkRate or TrunkRule found for trunk {}, applying base/band/special pricing", trunk.getName());
                applySpecialPricing(baseRateInfo, callBuilder.build().getServiceDate(), duration, originIndicatorId, callBuilder);
            }
        }
    }

    private void applySpecialPricing(Map<String, Object> currentRateInfo, LocalDateTime callDateTime, int duration, Long originIndicatorId, CallRecord.CallRecordBuilder callBuilder) {
         Long telephonyTypeId = (Long) currentRateInfo.get("telephony_type_id");
         Long operatorId = (Long) currentRateInfo.get("operator_id");
         Long bandId = (Long) currentRateInfo.get("band_id"); // Can be 0 or null

         List<SpecialRateValue> specialRates = lookupService.findSpecialRateValues(
                 telephonyTypeId, operatorId, bandId, originIndicatorId, callDateTime
         );
         Optional<SpecialRateValue> applicableRate = findApplicableSpecialRate(specialRates, callDateTime);

         if (applicableRate.isPresent()) {
             SpecialRateValue rate = applicableRate.get();
             log.debug("Applying SpecialRateValue {}", rate.getId());
             BigDecimal originalRate = (BigDecimal) currentRateInfo.get("valor_minuto");
             boolean originalVatIncluded = (Boolean) currentRateInfo.get("valor_minuto_iva");

             // Store original rate as initial rate (PHP: Guardar_ValorInicial)
             currentRateInfo.put("valor_inicial", originalRate);
             currentRateInfo.put("valor_inicial_iva", originalVatIncluded);

             if (rate.getValueType() != null && rate.getValueType() == 1) { // Percentage discount
                 BigDecimal discountPercentage = Optional.ofNullable(rate.getRateValue()).orElse(BigDecimal.ZERO);
                 // Calculate discount on the rate *excluding* VAT
                 BigDecimal currentRateNoVat = calculateValueWithoutVat(originalRate, (BigDecimal) currentRateInfo.get("iva"), originalVatIncluded);
                 BigDecimal discountMultiplier = BigDecimal.ONE.subtract(discountPercentage.divide(new BigDecimal("100"), 4, RoundingMode.HALF_UP));
                 currentRateInfo.put("valor_minuto", currentRateNoVat.multiply(discountMultiplier));
                 // Keep original VAT inclusion status, as discount is applied pre-VAT
                 currentRateInfo.put("valor_minuto_iva", originalVatIncluded);
                 currentRateInfo.put("descuento_p", discountPercentage); // Store discount % for reference
                 log.trace("Applied percentage discount {}% from SpecialRateValue {}", discountPercentage, rate.getId());
             } else { // Fixed value override
                 currentRateInfo.put("valor_minuto", Optional.ofNullable(rate.getRateValue()).orElse(BigDecimal.ZERO));
                 currentRateInfo.put("valor_minuto_iva", Optional.ofNullable(rate.getIncludesVat()).orElse(false));
                 // Potentially update IVA if the special rate implies a different context (though IVA usually tied to prefix)
                 // currentRateInfo.put("iva", ...); // Re-fetch IVA if necessary based on rate context
                 log.trace("Applied fixed rate {} from SpecialRateValue {}", currentRateInfo.get("valor_minuto"), rate.getId());
             }
             // Special rates are typically per-minute
             currentRateInfo.put("ensegundos", false);
             applyFinalPricing(currentRateInfo, duration, callBuilder);
         } else {
             log.debug("No applicable special rate found, applying current rate.");
             // Ensure initial price is zero if no special rate applied
             currentRateInfo.put("valor_inicial", BigDecimal.ZERO);
             currentRateInfo.put("valor_inicial_iva", false);
             currentRateInfo.put("ensegundos", false); // Assume per-minute if no special rate
             applyFinalPricing(currentRateInfo, duration, callBuilder);
         }
    }

    private void applyFinalPricing(Map<String, Object> rateInfo, int duration, CallRecord.CallRecordBuilder callBuilder) {
         // Extract necessary values with defaults
         BigDecimal pricePerMinute = Optional.ofNullable((BigDecimal) rateInfo.get("valor_minuto")).orElse(BigDecimal.ZERO);
         boolean vatIncluded = Optional.ofNullable((Boolean) rateInfo.get("valor_minuto_iva")).orElse(false);
         BigDecimal vatPercentage = Optional.ofNullable((BigDecimal) rateInfo.get("iva")).orElse(BigDecimal.ZERO);
         boolean chargePerSecond = Optional.ofNullable((Boolean) rateInfo.get("ensegundos")).orElse(false);
         BigDecimal initialPrice = Optional.ofNullable((BigDecimal) rateInfo.get("valor_inicial")).orElse(BigDecimal.ZERO);
         boolean initialVatIncluded = Optional.ofNullable((Boolean) rateInfo.get("valor_inicial_iva")).orElse(false);

         // Calculate final billed amount using the helper
         BigDecimal calculatedBilledAmount = calculateBilledAmount(
                 duration, pricePerMinute, vatIncluded, vatPercentage, chargePerSecond, initialPrice, initialVatIncluded
         );

         // Set the final calculated values on the builder
         callBuilder.pricePerMinute(pricePerMinute.setScale(4, RoundingMode.HALF_UP));
         callBuilder.initialPrice(initialPrice.setScale(4, RoundingMode.HALF_UP));
         callBuilder.billedAmount(calculatedBilledAmount); // Already scaled in calculateBilledAmount
         log.trace("Final pricing applied: Rate={}, Initial={}, Billed={}", pricePerMinute, initialPrice, calculatedBilledAmount);
    }

    private Optional<Map<String, Object>> findRateInfo(Long prefixId, Long indicatorId, Long originIndicatorId, boolean bandOk) {
         Optional<Map<String, Object>> baseRateOpt = lookupService.findBaseRateForPrefix(prefixId);
         if (baseRateOpt.isEmpty()) {
             log.warn("Base rate info not found for prefixId: {}", prefixId);
             return Optional.empty();
         }
         // Start with base rate info
         Map<String, Object> rateInfo = new HashMap<>(baseRateOpt.get());
         // Initialize band info defaults
         rateInfo.put("band_id", 0L);
         rateInfo.put("band_name", "");
         // Ensure base values exist, default to zero/false if null from DB
         rateInfo.putIfAbsent("base_value", BigDecimal.ZERO);
         rateInfo.putIfAbsent("vat_included", false);
         rateInfo.putIfAbsent("vat_value", BigDecimal.ZERO);

         // Determine if band lookup should be performed
         boolean useBands = bandOk && indicatorId != null && indicatorId > 0;
         log.trace("findRateInfo: prefixId={}, indicatorId={}, originIndicatorId={}, bandOk={}, useBands={}",
                 prefixId, indicatorId, originIndicatorId, bandOk, useBands);

         if (useBands) {
             Optional<Map<String, Object>> bandOpt = lookupService.findBandByPrefixAndIndicator(prefixId, indicatorId, originIndicatorId);
             if (bandOpt.isPresent()) {
                 Map<String, Object> bandInfo = bandOpt.get();
                 // Override base values with band values
                 rateInfo.put("base_value", bandInfo.get("band_value")); // Use band value as the effective rate
                 rateInfo.put("vat_included", bandInfo.get("band_vat_included")); // Use band VAT inclusion flag
                 rateInfo.put("band_id", bandInfo.get("band_id"));
                 rateInfo.put("band_name", bandInfo.get("band_name"));
                 // Keep the VAT percentage from the prefix (vat_value), as bands don't define VAT %
                 log.trace("Using band rate for prefix {}, indicator {}: BandID={}, Value={}, VatIncluded={}",
                         prefixId, indicatorId, bandInfo.get("band_id"), bandInfo.get("band_value"), bandInfo.get("band_vat_included"));
             } else {
                  log.trace("Band lookup enabled for prefix {} but no matching band found for indicator {}", prefixId, indicatorId);
                  // If no band found, proceed using the base rate values already in rateInfo
             }
         } else {
              log.trace("Using base rate for prefix {} (Bands not applicable or indicator missing)", prefixId);
         }
         // Ensure final rate keys are set based on the effective base or band value
         rateInfo.put("valor_minuto", rateInfo.get("base_value"));
         rateInfo.put("valor_minuto_iva", rateInfo.get("vat_included"));
         rateInfo.put("iva", rateInfo.get("vat_value")); // IVA percentage comes from Prefix

         return Optional.of(rateInfo);
    }

    private String formatDestinationName(Map<String, Object> indicatorInfo) {
        String city = (String) indicatorInfo.get("city_name");
        String country = (String) indicatorInfo.get("department_country");
        if (StringUtils.hasText(city) && StringUtils.hasText(country)) return city + " (" + country + ")";
        return StringUtils.hasText(city) ? city : (StringUtils.hasText(country) ? country : "Unknown Destination");
    }

    private Optional<SpecialRateValue> findApplicableSpecialRate(List<SpecialRateValue> candidates, LocalDateTime callDateTime) {
        int callHour = callDateTime.getHour();
        return candidates.stream()
                         .filter(rate -> isHourApplicable(rate.getHoursSpecification(), callHour))
                         .findFirst(); // Find the first one that matches the hour criteria (list is already ordered by specificity)
    }

     private boolean isHourApplicable(String hoursSpecification, int callHour) {
        // If no specification, it applies to all hours
        if (!StringUtils.hasText(hoursSpecification)) return true;
        try {
            // Split by comma for multiple ranges/hours
            for (String part : hoursSpecification.split(",")) {
                String range = part.trim();
                if (range.contains("-")) {
                    // Handle ranges like "8-17"
                    String[] parts = range.split("-");
                    if (parts.length == 2) {
                        int start = Integer.parseInt(parts[0].trim());
                        int end = Integer.parseInt(parts[1].trim());
                        // Inclusive range check
                        if (callHour >= start && callHour <= end) return true;
                    } else {
                         log.warn("Invalid hour range format: {}", range);
                    }
                } else if (!range.isEmpty()) {
                    // Handle single hours like "20"
                    if (callHour == Integer.parseInt(range)) return true;
                }
            }
        } catch (Exception e) {
            // Catch NumberFormatException or others during parsing
            log.error("Error parsing hoursSpecification: '{}'. Assuming not applicable.", hoursSpecification, e);
            return false; // Treat parse errors as non-applicable
        }
        // If no range/hour matched
        return false;
    }

    /**
     * Preprocesses a phone number based on Colombian numbering plan rules,
     * mimicking PHP functions _esCelular_fijo and _esEntrante_60.
     * This version is specifically for preparing the number for LOOKUP.
     *
     * @param number The phone number to preprocess.
     * @param originCountryId The origin country ID (used to check if rules apply).
     * @return The potentially modified phone number for lookup.
     */
    private String preprocessNumberForLookup(String number, Long originCountryId) {
        if (number == null || originCountryId == null || !originCountryId.equals(COLOMBIA_ORIGIN_COUNTRY_ID)) {
            return number; // Only apply to Colombia
        }

        int len = number.length();
        String originalNumber = number; // Keep original for logging
        String processedNumber = number; // Start with original

        // --- Logic from _esCelular_fijo & _esEntrante_60 ---
        if (len == 10) {
            if (number.startsWith("3")) {
                // Check if it looks like a 10-digit mobile number (3xx xxx xxxx)
                if (number.matches("^3[0-4][0-9]\\d{7}$")) { // Matches 300-349 followed by 7 digits
                    processedNumber = "03" + number; // Prepend "03" for mobile lookup consistency
                    log.trace("Preprocessing (Mobile 10-digit): {} -> {}", originalNumber, processedNumber);
                }
            } else if (number.startsWith("60")) {
                // Fixed line with new 60 prefix. Check if it's local or national.
                // PHP logic (_esNacional) implies comparing Dpto/City. This is complex here.
                // For lookup, we keep the '60' + 8 digits. Prefix/Indicator lookup should resolve it.
                log.trace("Preprocessing (Fixed 10-digit 60): {} kept as is for prefix lookup", originalNumber);
                // No change needed here for lookup, prefix search handles it.
            }
        } else if (len == 11) {
             if (number.startsWith("03")) {
                 // Mobile number dialed with leading 03 (e.g., 0315...). Remove leading 0.
                 if (number.matches("^03[0-4][0-9]\\d{7}$")) {
                     processedNumber = number.substring(1); // Result: 315...
                     log.trace("Preprocessing (Mobile 11-digit 03): {} -> {}", originalNumber, processedNumber);
                     // Re-apply 10-digit logic
                     processedNumber = "03" + processedNumber;
                     log.trace("Preprocessing (Mobile 11-digit post-strip): {} -> {}", originalNumber, processedNumber);
                 }
             } else if (number.startsWith("604")) {
                 // Fixed line with 604 prefix (potentially Medellin? Needs confirmation) - Keep 8 digits
                 if (number.matches("^604\\d{8}$")) {
                     processedNumber = number.substring(3); // Keep 8 digits
                     log.trace("Preprocessing (Fixed 11-digit 604): {} -> {}", originalNumber, processedNumber);
                 }
             }
        } else if (len == 12) {
            if (number.startsWith("573") || number.startsWith("603")) {
                // Mobile number dialed with country code (57) or new prefix (60) + mobile indicator (3)
                if (number.matches("^(57|60)3[0-4][0-9]\\d{7}$")) {
                    processedNumber = number.substring(2); // Keep only the 10 digits starting with 3
                    log.trace("Preprocessing (Mobile 12-digit 573/603): {} -> {}", originalNumber, processedNumber);
                    // Now it matches the 10-digit mobile case, prepend "03"
                    processedNumber = "03" + processedNumber;
                    log.trace("Preprocessing (Mobile 12-digit post-strip): {} -> {}", originalNumber, processedNumber);
                }
            } else if (number.startsWith("6060") || number.startsWith("5760")) {
                // Fixed line dialed with country code (57) or new prefix (60) + new fixed prefix (60)
                if (number.matches("^(57|60)60\\d{8}$")) {
                    // Keep the 60 + 8 digits for lookup
                    processedNumber = number.substring(2);
                    log.trace("Preprocessing (Fixed 12-digit 5760/6060): {} -> {}", originalNumber, processedNumber);
                }
            }
        } else if (len == 9 && number.startsWith("60")) {
            // National call with 60 prefix and 7 digits (e.g., 601xxxxxxx) - Keep 7 digits
            if (number.matches("^60\\d{7}$")) {
                processedNumber = number.substring(2); // Keep 7 digits
                log.trace("Preprocessing (National 9-digit 60): {} -> {}", originalNumber, processedNumber);
                // PHP _esNacional logic might prepend '09', but rely on prefix lookup for now.
            }
        }

        // Add more rules from PHP (_esNacional, _esEntrante_60) if necessary for LOOKUP stage

        if (!originalNumber.equals(processedNumber)) {
            log.debug("Final preprocessed number for lookup: {}", processedNumber);
        }
        return processedNumber;
    }

}