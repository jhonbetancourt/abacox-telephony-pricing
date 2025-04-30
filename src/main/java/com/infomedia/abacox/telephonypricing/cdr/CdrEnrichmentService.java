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
    private static final String CONFERENCE_PREFIX = "b";
    private static final Long COLOMBIA_ORIGIN_COUNTRY_ID = 1L; // Assuming 1 is Colombia

    // --- Main Enrichment Method ---
    public Optional<CallRecord> enrichCdr(RawCdrDto rawCdr, CommunicationLocation commLocation) {
        log.debug("Enriching CDR: {}", rawCdr.getGlobalCallId());

        CallRecord.CallRecordBuilder callBuilder = CallRecord.builder();
        // Initial field setting from DTO
        callBuilder.commLocationId(commLocation.getId());
        callBuilder.commLocation(commLocation);
        callBuilder.serviceDate(rawCdr.getDateTimeOrigination());
        callBuilder.employeeExtension(rawCdr.getCallingPartyNumber());
        callBuilder.employeeAuthCode(rawCdr.getAuthCodeDescription());
        callBuilder.destinationPhone(rawCdr.getFinalCalledPartyNumber());
        callBuilder.dial(rawCdr.getFinalCalledPartyNumber());
        callBuilder.duration(rawCdr.getDuration());
        callBuilder.ringCount(rawCdr.getRingDuration());
        callBuilder.isIncoming(rawCdr.isIncoming());
        callBuilder.trunk(rawCdr.getDestDeviceName());
        callBuilder.initialTrunk(rawCdr.getOrigDeviceName());
        callBuilder.employeeTransfer(rawCdr.getLastRedirectDn());
        callBuilder.transferCause(rawCdr.getLastRedirectRedirectReason());
        callBuilder.assignmentCause(CallAssignmentCause.UNKNOWN.getValue());
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
             log.warn("Initial employee lookup failed for CDR {}", rawCdr.getGlobalCallId());
        }

        // --- Conference Handling & Field Adjustment ---
        boolean isConference = isConferenceCall(rawCdr);
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
            callBuilder.transferCause(CallTransferCause.CONFERENCE.getValue());

            effectiveCallingNumber.setValue(rawCdr.getLastRedirectDn());
            effectiveCallingPartition.setValue(rawCdr.getLastRedirectDnPartition());
            effectiveDialedNumber.setValue(rawCdr.getCallingPartyNumber());
            effectiveDialedPartition.setValue(rawCdr.getCallingPartyNumberPartition());
            effectiveIncoming.setValue(false); // Conferences are treated as outgoing from the initiator

            swapFields(effectiveTrunk, effectiveInitialTrunk);

            // Update builder fields with effective values after swap/conference logic
            callBuilder.employeeExtension(effectiveCallingNumber.getValue());
            callBuilder.destinationPhone(effectiveDialedNumber.getValue());
            callBuilder.dial(effectiveDialedNumber.getValue());
            callBuilder.isIncoming(effectiveIncoming.getValue());
            callBuilder.trunk(effectiveTrunk.getValue());
            callBuilder.initialTrunk(effectiveInitialTrunk.getValue());

             // Re-lookup employee based on the *effective* calling number for conferences
             Optional<Employee> confEmployeeOpt = lookupService.findEmployeeByExtensionOrAuthCode(
                effectiveCallingNumber.getValue(), null, commLocation.getId()
             );
             if (confEmployeeOpt.isPresent()) {
                 callBuilder.employeeId(confEmployeeOpt.get().getId());
                 callBuilder.employee(confEmployeeOpt.get());
                 callBuilder.assignmentCause(CallAssignmentCause.CONFERENCE.getValue());
             } else {
                 log.warn("Originating employee ({}) for conference call {} not found.", effectiveCallingNumber.getValue(), rawCdr.getGlobalCallId());
                 callBuilder.employeeId(null);
                 callBuilder.employee(null);
                 // Keep assignment cause as CONFERENCE if lookup fails? Or UNKNOWN? Let's keep CONFERENCE.
                 // callBuilder.assignmentCause(CallAssignmentCause.UNKNOWN.getValue());
             }
        }

        // --- Determine Call Type and Enrich ---
        try {
            if (effectiveIncoming.getValue()) {
                processIncomingCall(rawCdr, commLocation, callBuilder, effectiveCallingNumber.getValue());
            } else {
                processOutgoingCall(rawCdr, commLocation, callBuilder,
                                    effectiveCallingNumber.getValue(), effectiveDialedNumber.getValue());
            }
        } catch (Exception e) {
            log.error("Error during enrichment for CDR {}: {}", rawCdr.getGlobalCallId(), e.getMessage(), e);
            return Optional.empty();
        }

        // --- Final Adjustments ---
        // Set transfer cause enum value only if it wasn't overridden by conference logic
        if (callBuilder.build().getTransferCause() == rawCdr.getLastRedirectRedirectReason()) {
             callBuilder.transferCause(CallTransferCause.fromValue(rawCdr.getLastRedirectRedirectReason()).getValue());
        }
        linkAssociatedEntities(callBuilder);

        CallRecord finalRecord = callBuilder.build();
        log.debug("Successfully enriched CDR {}: Type={}, Billed={}",
                rawCdr.getGlobalCallId(), finalRecord.getTelephonyTypeId(), finalRecord.getBilledAmount());
        return Optional.of(finalRecord);
    }

    // ========================================================================
    // Processing Logic Methods
    // ========================================================================

    private void processOutgoingCall(RawCdrDto rawCdr, CommunicationLocation commLocation, CallRecord.CallRecordBuilder callBuilder, String effectiveCallingNumber, String effectiveDialedNumber) {
        log.debug("Processing outgoing call for CDR {} (effective: {} -> {})", rawCdr.getGlobalCallId(), effectiveCallingNumber, effectiveDialedNumber);
        String originalDialedNumber = rawCdr.getFinalCalledPartyNumber();
        List<String> pbxPrefixes = configService.getPbxPrefixes(commLocation.getId());
        Long originCountryId = getOriginCountryId(commLocation);

        // 1. Preprocess Number (Colombian rules)
        String preprocessedNumber = preprocessNumberForLookup(effectiveDialedNumber, originCountryId);
        FieldWrapper<Long> forcedTelephonyType = new FieldWrapper<>(null);
        if (!preprocessedNumber.equals(effectiveDialedNumber)) {
            log.debug("Number preprocessed for lookup: {} -> {}", effectiveDialedNumber, preprocessedNumber);
            if (preprocessedNumber.startsWith("03")) {
                forcedTelephonyType.setValue(ConfigurationService.TIPOTELE_CELULAR);
            }
        }

        // 2. Clean Dialed Number (using preprocessed number)
        boolean shouldRemovePrefix = getPrefixLength(originalDialedNumber, pbxPrefixes) > 0;
        String cleanedNumber = cleanNumber(preprocessedNumber, pbxPrefixes, shouldRemovePrefix);
        callBuilder.dial(cleanedNumber);

        // 3. Check PBX Special Rules (applied to original number)
        Optional<PbxSpecialRule> pbxRuleOpt = lookupService.findPbxSpecialRule(
                originalDialedNumber, commLocation.getId(), CallDirection.OUTGOING.getValue()
        );
        if (pbxRuleOpt.isPresent()) {
            PbxSpecialRule rule = pbxRuleOpt.get();
            String replacement = rule.getReplacement();
            String searchPattern = rule.getSearchPattern();
            if (originalDialedNumber.startsWith(searchPattern)) {
                 String numberAfterSearch = originalDialedNumber.substring(searchPattern.length());
                 cleanedNumber = replacement + numberAfterSearch;
                 preprocessedNumber = preprocessNumberForLookup(cleanedNumber, originCountryId);
                 if (!preprocessedNumber.equals(cleanedNumber) && preprocessedNumber.startsWith("03")) {
                     forcedTelephonyType.setValue(ConfigurationService.TIPOTELE_CELULAR);
                 }
                 cleanedNumber = cleanNumber(preprocessedNumber, pbxPrefixes, getPrefixLength(cleanedNumber, pbxPrefixes) > 0);
                 callBuilder.dial(cleanedNumber);
                 log.debug("Applied PBX rule {}, new effective number: {}", rule.getId(), cleanedNumber);
            }
        }

        // 4. Check Special Services
        Long indicatorIdForSpecial = commLocation.getIndicatorId();
        if (indicatorIdForSpecial != null && originCountryId != null) {
            Optional<SpecialService> specialServiceOpt = lookupService.findSpecialService(cleanedNumber, indicatorIdForSpecial, originCountryId);
            if (specialServiceOpt.isPresent()) {
                SpecialService specialService = specialServiceOpt.get();
                log.debug("Call matches Special Service: {}", specialService.getId());
                applySpecialServicePricing(specialService, callBuilder, rawCdr.getDuration());
                return;
            }
        }

        // 5. Check if Internal Call
        if (isInternalCall(effectiveCallingNumber, cleanedNumber, commLocation)) {
             log.debug("Processing as internal call (effective: {} -> {})", effectiveCallingNumber, cleanedNumber);
             processInternalCall(rawCdr, commLocation, callBuilder, cleanedNumber);
             return;
        }

        // 6. Process as External Outgoing Call
        log.debug("Processing as external outgoing call (effective: {} -> {})", effectiveCallingNumber, cleanedNumber);
        evaluateDestinationAndRate(rawCdr, commLocation, callBuilder, cleanedNumber, pbxPrefixes, forcedTelephonyType.getValue());
    }

    private void processIncomingCall(RawCdrDto rawCdr, CommunicationLocation commLocation, CallRecord.CallRecordBuilder callBuilder, String effectiveCallingNumber) {
        log.debug("Processing incoming call for CDR {} (effective caller: {})", rawCdr.getGlobalCallId(), effectiveCallingNumber);
        Long originCountryId = getOriginCountryId(commLocation);

        // 1. Preprocess Number (Colombian rules)
        String preprocessedNumber = preprocessNumberForLookup(effectiveCallingNumber, originCountryId);
        FieldWrapper<Long> forcedTelephonyType = new FieldWrapper<>(null);
        if (!preprocessedNumber.equals(effectiveCallingNumber)) {
            log.debug("Incoming number preprocessed for lookup: {} -> {}", effectiveCallingNumber, preprocessedNumber);
            if (preprocessedNumber.startsWith("03")) forcedTelephonyType.setValue(ConfigurationService.TIPOTELE_CELULAR);
        }

        // 2. Clean Calling Number (using preprocessed number)
        String cleanedCallingNumber = cleanNumber(preprocessedNumber, Collections.emptyList(), false);
        callBuilder.dial(cleanedCallingNumber);

        // 3. Check PBX Special Rules (applied to original number)
         Optional<PbxSpecialRule> pbxRuleOpt = lookupService.findPbxSpecialRule(
                effectiveCallingNumber, commLocation.getId(), CallDirection.INCOMING.getValue()
         );
         if (pbxRuleOpt.isPresent()) {
             log.debug("Incoming call matched PBX rule {}", pbxRuleOpt.get().getId());
             // cleanedCallingNumber = ...; callBuilder.dial(cleanedCallingNumber);
         }

        // 4. Determine Origin (buscarOrigen)
        Long commIndicatorId = commLocation.getIndicatorId();
        if (originCountryId != null && commIndicatorId != null) {
            String numberForLookup = cleanedCallingNumber;
            List<String> pbxPrefixes = configService.getPbxPrefixes(commLocation.getId());
            int prefixLen = getPrefixLength(effectiveCallingNumber, pbxPrefixes); // Check original for prefix
            if (prefixLen > 0) {
                numberForLookup = effectiveCallingNumber.substring(prefixLen);
                log.trace("Incoming call had PBX prefix, looking up origin for: {}", numberForLookup);
            }

            List<Map<String, Object>> prefixes = lookupService.findPrefixesByNumber(numberForLookup, originCountryId);
            // Filter if type was forced
            if(forcedTelephonyType.getValue() != null) {
                prefixes = prefixes.stream()
                                   .filter(p -> forcedTelephonyType.getValue().equals(p.get("telephony_type_id")))
                                   .collect(Collectors.toList());
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
                callBuilder.telephonyTypeId(ConfigurationService.TIPOTELE_LOCAL);
                callBuilder.indicatorId(commIndicatorId);
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
        Optional<Employee> destEmployeeOpt = lookupService.findEmployeeByExtensionOrAuthCode(destinationExtension, null, commLocation.getId());

        LocationInfo sourceLoc = getLocationInfo(sourceEmployeeOpt.orElse(null), commLocation);
        LocationInfo destLoc = getLocationInfo(destEmployeeOpt.orElse(null), commLocation);

        if (destEmployeeOpt.isPresent()) {
             callBuilder.destinationEmployeeId(destEmployeeOpt.get().getId());
             callBuilder.destinationEmployee(destEmployeeOpt.get());
        } else {
             log.warn("Internal call destination extension {} not found as employee.", destinationExtension);
             callBuilder.telephonyTypeId(ConfigurationService.TIPOTELE_INTERNA_IP);
             applyInternalPricing(callBuilder.build().getTelephonyTypeId(), callBuilder, rawCdr.getDuration());
             return;
        }

        Long internalCallTypeId;
        if (sourceLoc.originCountryId != null && destLoc.originCountryId != null && !sourceLoc.originCountryId.equals(destLoc.originCountryId)) {
            internalCallTypeId = ConfigurationService.TIPOTELE_INTERNACIONAL_IP;
        } else if (sourceLoc.indicatorId != null && destLoc.indicatorId != null && !sourceLoc.indicatorId.equals(destLoc.indicatorId)) {
            internalCallTypeId = ConfigurationService.TIPOTELE_NACIONAL_IP;
        } else if (sourceLoc.officeId != null && destLoc.officeId != null && !sourceLoc.officeId.equals(destLoc.officeId)) {
            internalCallTypeId = ConfigurationService.TIPOTELE_LOCAL_IP;
        } else {
            internalCallTypeId = ConfigurationService.TIPOTELE_INTERNA_IP;
        }

        callBuilder.telephonyTypeId(internalCallTypeId);
        callBuilder.indicatorId(destLoc.indicatorId);
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

        // Attempt 1
        Optional<Map<String, Object>> finalRateInfoOpt = attemptRateLookup(rawCdr, commLocation, callBuilder, cleanedNumber, pbxPrefixes, trunkOpt, forcedTelephonyTypeId);

        // Fallback (Normalizar)
        if (finalRateInfoOpt.isEmpty() && usesTrunk) {
            log.warn("Initial rate lookup failed for trunk call {}, attempting fallback (no trunk info)", rawCdr.getGlobalCallId());
            finalRateInfoOpt = attemptRateLookup(rawCdr, commLocation, callBuilder, cleanedNumber, pbxPrefixes, Optional.empty(), forcedTelephonyTypeId);
        }

        // Apply results
        if (finalRateInfoOpt.isPresent()) {
            Map<String, Object> finalRateInfo = finalRateInfoOpt.get();
            callBuilder.telephonyTypeId((Long) finalRateInfo.get("telephony_type_id"));
            callBuilder.operatorId((Long) finalRateInfo.get("operator_id"));
            callBuilder.indicatorId((Long) finalRateInfo.get("indicator_id"));
            callBuilder.dial((String) finalRateInfo.getOrDefault("effective_number", cleanedNumber));

            boolean appliedTrunkPricing = finalRateInfo.containsKey("applied_trunk_pricing") && (Boolean) finalRateInfo.get("applied_trunk_pricing");
            if (appliedTrunkPricing && usesTrunk) {
                 applyTrunkPricing(trunkOpt.get(), finalRateInfo, rawCdr.getDuration(), originIndicatorId, callBuilder);
            } else {
                 applySpecialPricing(finalRateInfo, rawCdr.getDateTimeOrigination(), rawCdr.getDuration(), originIndicatorId, callBuilder);
            }
        } else {
            log.error("Could not determine rate for number: {} (effective: {}) after fallback.", rawCdr.getFinalCalledPartyNumber(), cleanedNumber);
            callBuilder.telephonyTypeId(ConfigurationService.TIPOTELE_ERRORES);
            callBuilder.dial(cleanedNumber);
        }
    }

    // Added forcedTelephonyTypeId parameter
    private Optional<Map<String, Object>> attemptRateLookup(RawCdrDto rawCdr, CommunicationLocation commLocation, CallRecord.CallRecordBuilder callBuilder, String initialCleanedNumber, List<String> pbxPrefixes, Optional<Trunk> trunkOpt, Long forcedTelephonyTypeId) {
        Long originCountryId = getOriginCountryId(commLocation);
        Long originIndicatorId = commLocation.getIndicatorId();
        boolean usesTrunk = trunkOpt.isPresent();
        Trunk trunk = trunkOpt.orElse(null);

        if (originCountryId == null || originIndicatorId == null) {
            log.error("Cannot attempt rate lookup: Missing Origin Country or Indicator ID for Location {}", commLocation.getId());
            return Optional.empty();
        }

        String effectiveNumber = initialCleanedNumber;
        if (usesTrunk && trunk.getNoPbxPrefix() != null && trunk.getNoPbxPrefix()) {
            effectiveNumber = cleanNumber(rawCdr.getFinalCalledPartyNumber(), Collections.emptyList(), false);
            log.trace("Attempting lookup (Trunk ignores PBX prefix): {}", effectiveNumber);
        } else {
             log.trace("Attempting lookup (Effective number): {}", effectiveNumber);
        }

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
            }
        }


        // PHP LOCAL Fallback Logic
        if (prefixes.isEmpty() && !usesTrunk) {
            log.debug("No prefix found for non-trunk call, attempting lookup as LOCAL for {}", effectiveNumber);
            Optional<Map<String, Object>> localRateInfoOpt = findRateInfoForLocal(commLocation, effectiveNumber);
            if (localRateInfoOpt.isPresent()) {
                Map<String, Object> localRateInfo = localRateInfoOpt.get();
                localRateInfo.put("effective_number", effectiveNumber);
                localRateInfo.put("applied_trunk_pricing", false);
                return localRateInfoOpt;
            }
        } else if (prefixes.isEmpty()) {
             log.warn("Attempt failed: No prefix found for number: {}", effectiveNumber);
             return Optional.empty();
        }


        // --- Iterate through found prefixes ---
        for (Map<String, Object> prefixInfo : prefixes) {
            Long currentTelephonyTypeId = (Long) prefixInfo.get("telephony_type_id");
            Long currentOperatorId = (Long) prefixInfo.get("operator_id");
            String currentPrefixCode = (String) prefixInfo.get("prefix_code");
            Long currentPrefixId = (Long) prefixInfo.get("prefix_id");
            int typeMinLength = (Integer) prefixInfo.get("telephony_type_min");
            int typeMaxLength = (Integer) prefixInfo.get("telephony_type_max");
            boolean bandOk = (Boolean) prefixInfo.get("prefix_band_ok");

            String numberWithoutPrefix = effectiveNumber;
            boolean prefixRemoved = false;
            if (StringUtils.hasText(currentPrefixCode) && effectiveNumber.startsWith(currentPrefixCode)) {
                boolean removePrefixForLookup = true;
                if (usesTrunk) {
                    Optional<TrunkRate> trOpt = lookupService.findTrunkRate(trunk.getId(), currentOperatorId, currentTelephonyTypeId);
                    if (trOpt.isPresent() && trOpt.get().getNoPrefix() != null && trOpt.get().getNoPrefix()) {
                        removePrefixForLookup = false;
                         log.trace("TrunkRate for prefix {} prevents prefix removal", currentPrefixCode);
                    }
                }
                if(removePrefixForLookup){
                    numberWithoutPrefix = effectiveNumber.substring(currentPrefixCode.length());
                    prefixRemoved = true;
                }
            }

            int effectiveMinLength = prefixRemoved ? Math.max(0, typeMinLength - (currentPrefixCode != null ? currentPrefixCode.length() : 0)) : typeMinLength;
            int effectiveMaxLength = prefixRemoved ? Math.max(0, typeMaxLength - (currentPrefixCode != null ? currentPrefixCode.length() : 0)) : typeMaxLength;

            if (numberWithoutPrefix.length() < effectiveMinLength) {
                 log.trace("Skipping prefix {} - number {} too short (min {})", currentPrefixCode, numberWithoutPrefix, effectiveMinLength);
                continue;
            }
            if (effectiveMaxLength > 0 && numberWithoutPrefix.length() > effectiveMaxLength) {
                 log.trace("Trimming number {} to max length {}", numberWithoutPrefix, effectiveMaxLength);
                numberWithoutPrefix = numberWithoutPrefix.substring(0, effectiveMaxLength);
            }

            Optional<Map<String, Object>> indicatorInfoOpt = lookupService.findIndicatorByNumber(
                    numberWithoutPrefix, currentTelephonyTypeId, originCountryId
            );

            boolean destinationFound = indicatorInfoOpt.isPresent();
            boolean lengthMatch = (effectiveMaxLength > 0 && numberWithoutPrefix.length() == effectiveMaxLength);
            boolean considerMatch = destinationFound || lengthMatch;

            if (considerMatch) {
                Long destinationIndicatorId = indicatorInfoOpt.map(ind -> (Long) ind.get("indicator_id")).orElse(0L);
                Integer destinationNdc = indicatorInfoOpt.map(ind -> (Integer) ind.get("series_ndc")).orElse(null);

                // --- Local Extended Check ---
                Long finalTelephonyTypeId = currentTelephonyTypeId;
                Long finalPrefixId = currentPrefixId;
                boolean finalBandOk = bandOk;
                if (currentTelephonyTypeId == ConfigurationService.TIPOTELE_LOCAL && destinationNdc != null) {
                    boolean isExtended = lookupService.isLocalExtended(destinationNdc, originIndicatorId);
                    if (isExtended) {
                        finalTelephonyTypeId = ConfigurationService.TIPOTELE_LOCAL_EXT;
                        log.debug("Reclassified call to {} as LOCAL_EXTENDED", effectiveNumber);
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

                Optional<Map<String, Object>> rateInfoOpt = findRateInfo(finalPrefixId, destinationIndicatorId, originIndicatorId, finalBandOk);

                if (rateInfoOpt.isPresent()) {
                    Map<String, Object> rateInfo = rateInfoOpt.get();
                    rateInfo.put("telephony_type_id", finalTelephonyTypeId); // Use potentially updated type
                    rateInfo.put("operator_id", currentOperatorId);
                    rateInfo.put("indicator_id", destinationIndicatorId);
                    rateInfo.put("telephony_type_name", prefixInfo.get("telephony_type_name")); // Keep original name for now? Or lookup new name?
                    rateInfo.put("operator_name", prefixInfo.get("operator_name"));
                    rateInfo.put("destination_name", indicatorInfoOpt.map(this::formatDestinationName).orElse("Unknown"));
                    rateInfo.put("band_name", rateInfo.get("band_name"));
                    rateInfo.put("effective_number", effectiveNumber);
                    rateInfo.put("applied_trunk_pricing", usesTrunk);

                    log.debug("Attempt successful: Found rate for prefix {}, indicator {}", currentPrefixCode, destinationIndicatorId);
                    return Optional.of(rateInfo);
                } else {
                     log.warn("Rate info not found for prefix {}, indicator {}", currentPrefixCode, destinationIndicatorId);
                }
            } else {
                 log.trace("No indicator found and length mismatch for prefix {}, number {}", currentPrefixCode, numberWithoutPrefix);
            }
        } // End prefix loop

        log.warn("Attempt failed: No matching rate found for number: {}", effectiveNumber);
        return Optional.empty();
    }

    // Helper specifically for the LOCAL fallback in attemptRateLookup
    private Optional<Map<String, Object>> findRateInfoForLocal(CommunicationLocation commLocation, String effectiveNumber) {
        Long originCountryId = getOriginCountryId(commLocation);
        Long originIndicatorId = commLocation.getIndicatorId();
        Long localType = ConfigurationService.TIPOTELE_LOCAL;

        if (originCountryId == null || originIndicatorId == null) return Optional.empty();

        Optional<Operator> internalOpOpt = configService.getOperatorInternal(localType, originCountryId);
        if (internalOpOpt.isEmpty()) {
            log.warn("Cannot find internal operator for LOCAL type in country {}", originCountryId);
            return Optional.empty();
        }
        Long internalOperatorId = internalOpOpt.get().getId();

        Optional<Prefix> localPrefixOpt = lookupService.findPrefixByTypeOperatorOrigin(localType, internalOperatorId, originCountryId);
        if(localPrefixOpt.isEmpty()){
            log.warn("Cannot find Prefix entity for LOCAL type ({}) and Operator {} in Country {}", localType, internalOperatorId, originCountryId);
            return Optional.empty();
        }
        Prefix localPrefix = localPrefixOpt.get();

         Optional<Map<String, Object>> indicatorInfoOpt = lookupService.findIndicatorByNumber(
                    effectiveNumber, localType, originCountryId
            );
         if(indicatorInfoOpt.isEmpty()){
              log.warn("Could not find LOCAL indicator for number {}", effectiveNumber);
              return Optional.empty();
         }
         Long destinationIndicatorId = (Long) indicatorInfoOpt.get().get("indicator_id");
         Integer destinationNdc = indicatorInfoOpt.map(ind -> (Integer) ind.get("series_ndc")).orElse(null); // Get NDC for extended check

         // --- Local Extended Check ---
         Long finalTelephonyTypeId = localType;
         Long finalPrefixId = localPrefix.getId();
         boolean finalBandOk = localPrefix.isBandOk();
         if (destinationNdc != null) { // Check if destination NDC was found
             boolean isExtended = lookupService.isLocalExtended(destinationNdc, originIndicatorId);
             if (isExtended) {
                 finalTelephonyTypeId = ConfigurationService.TIPOTELE_LOCAL_EXT;
                 log.debug("Reclassified LOCAL fallback call to {} as LOCAL_EXTENDED", effectiveNumber);
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


         Optional<Map<String, Object>> rateInfoOpt = findRateInfo(finalPrefixId, destinationIndicatorId, originIndicatorId, finalBandOk);

         if (rateInfoOpt.isPresent()) {
             Map<String, Object> rateInfo = rateInfoOpt.get();
             rateInfo.put("telephony_type_id", finalTelephonyTypeId); // Use final type
             rateInfo.put("operator_id", internalOperatorId);
             rateInfo.put("indicator_id", destinationIndicatorId);
             rateInfo.put("telephony_type_name", finalTelephonyTypeId == ConfigurationService.TIPOTELE_LOCAL_EXT ? "Local Extended" : "Local"); // Adjust name
             rateInfo.put("operator_name", internalOpOpt.get().getName());
             rateInfo.put("destination_name", formatDestinationName(indicatorInfoOpt.get()));
             return Optional.of(rateInfo);
         }

        return Optional.empty();
    }


    // --- Helper Methods ---
    // [isInternalCall, isLikelyExtension, cleanNumber, getPrefixLength, isConferenceCall, isConferenceCallIndicator]
    // [swapFields, FieldWrapper, getLocationInfo, LocationInfo, linkAssociatedEntities]
    // [calculateBilledAmount, calculateValueWithoutVat, applySpecialServicePricing, applyInternalPricing]
    // [applyTrunkPricing, applySpecialPricing, findApplicableSpecialRate, isHourApplicable]
    // [findRateInfo, formatDestinationName]
    // [preprocessNumberForLookup]

    private String cleanNumber(String number, List<String> pbxPrefixes, boolean removePrefix) {
        if (!StringUtils.hasText(number)) return "";
        String cleaned = number.trim();
        int prefixLength = 0;

        if (removePrefix && pbxPrefixes != null && !pbxPrefixes.isEmpty()) {
            prefixLength = getPrefixLength(cleaned, pbxPrefixes);
            if (prefixLength > 0) {
                cleaned = cleaned.substring(prefixLength);
            } else {
                 log.trace("Prefix removal requested but no prefix found in {}", number);
            }
        }

        boolean hasPlus = cleaned.startsWith("+");
        cleaned = cleaned.replaceAll("[^0-9#*]", "");
        if (hasPlus && !cleaned.startsWith("+")) {
             cleaned = "+" + cleaned;
        }
        return cleaned;
    }

    private int getPrefixLength(String number, List<String> pbxPrefixes) {
         int prefixLength = 0;
         if (number != null && pbxPrefixes != null && !pbxPrefixes.isEmpty()) {
             for (String prefix : pbxPrefixes) {
                 if (prefix != null && !prefix.isEmpty() && number.startsWith(prefix) && prefix.length() > prefixLength) {
                     prefixLength = prefix.length();
                 }
             }
         }
         return prefixLength;
     }

    private boolean isInternalCall(String callingNumber, String dialedNumber, CommunicationLocation commLocation) {
        ConfigurationService.ExtensionLengthConfig extConfig = configService.getExtensionLengthConfig(commLocation.getId());
        boolean callingIsExt = isLikelyExtension(callingNumber, extConfig);
        boolean dialedIsExt = isLikelyExtension(dialedNumber, extConfig);
        return callingIsExt && dialedIsExt;
    }

    private boolean isLikelyExtension(String number, ConfigurationService.ExtensionLengthConfig extConfig) {
         if (!StringUtils.hasText(number)) return false;
         String effectiveNumber = number.startsWith("+") ? number.substring(1) : number;
         // Allow digits, hash, asterisk
         if (!effectiveNumber.matches("[\\d#*]+")) return false;

         int numLength = effectiveNumber.length();
         // Check length range
         if (numLength < extConfig.getMinLength() || numLength > extConfig.getMaxLength()) {
            return false;
         }

         // Check against max possible value if purely numeric
         try {
             if (effectiveNumber.matches("\\d+")) {
                 long numValue = Long.parseLong(effectiveNumber);
                 // Use <= for max value check as it represents the upper bound
                 if (numValue > extConfig.getMaxExtensionValue()) {
                     return false;
                 }
             }
         } catch (NumberFormatException e) { /* ignore if contains #/* */ }
         return true;
    }

    private boolean isConferenceCall(RawCdrDto rawCdr) {
        boolean finalIsConf = isConferenceCallIndicator(rawCdr.getFinalCalledPartyNumber());
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
    }

    private record LocationInfo(Long indicatorId, Long originCountryId, Long officeId) {}

    private LocationInfo getLocationInfo(Employee employee, CommunicationLocation defaultLocation) {
        Long defaultIndicatorId = defaultLocation.getIndicatorId();
        Long defaultOriginCountryId = getOriginCountryId(defaultLocation);
        Long defaultOfficeId = null;

        if (employee != null) {
            Long empOfficeId = employee.getSubdivisionId();
            Long empOriginCountryId = defaultOriginCountryId;
            Long empIndicatorId = defaultIndicatorId;

            if (employee.getCommunicationLocationId() != null) {
                 Optional<CommunicationLocation> empLocOpt = lookupService.findCommunicationLocationById(employee.getCommunicationLocationId());
                 if (empLocOpt.isPresent()) {
                     CommunicationLocation empLoc = empLocOpt.get();
                     empIndicatorId = empLoc.getIndicatorId() != null ? empLoc.getIndicatorId() : defaultIndicatorId;
                     empOriginCountryId = getOriginCountryId(empLoc) != null ? getOriginCountryId(empLoc) : defaultOriginCountryId;
                     return new LocationInfo(empIndicatorId, empOriginCountryId, empOfficeId);
                 }
            }
            if (employee.getCostCenterId() != null) {
                 Optional<CostCenter> ccOpt = lookupService.findCostCenterById(employee.getCostCenterId());
                 if (ccOpt.isPresent() && ccOpt.get().getOriginCountryId() != null) {
                     empOriginCountryId = ccOpt.get().getOriginCountryId();
                 }
            }
            return new LocationInfo(empIndicatorId, empOriginCountryId, empOfficeId);
        }
        return new LocationInfo(defaultIndicatorId, defaultOriginCountryId, defaultOfficeId);
    }

    private Long getOriginCountryId(CommunicationLocation commLocation) {
        if (commLocation == null) return null;
        Indicator indicator = commLocation.getIndicator();
        if (indicator != null) {
            return indicator.getOriginCountryId();
        }
        if (commLocation.getIndicatorId() != null) {
            return lookupService.findIndicatorById(commLocation.getIndicatorId())
                                .map(Indicator::getOriginCountryId)
                                .orElse(null);
        }
        return null;
    }


     private void linkAssociatedEntities(CallRecord.CallRecordBuilder callBuilder) {
        CallRecord record = callBuilder.build();
        if (record.getTelephonyTypeId() != null && record.getTelephonyType() == null) {
             lookupService.findTelephonyTypeById(record.getTelephonyTypeId()).ifPresent(callBuilder::telephonyType);
        }
        if (record.getOperatorId() != null && record.getOperator() == null) {
             lookupService.findOperatorById(record.getOperatorId()).ifPresent(callBuilder::operator);
        }
        if (record.getIndicatorId() != null && record.getIndicator() == null) {
             lookupService.findIndicatorById(record.getIndicatorId()).ifPresent(callBuilder::indicator);
        }
         if (record.getDestinationEmployeeId() != null && record.getDestinationEmployee() == null) {
             log.warn("Cannot link destination employee entity without a findById method for ID: {}", record.getDestinationEmployeeId());
         }
    }

    private BigDecimal calculateBilledAmount(int durationSeconds, BigDecimal rateValue, boolean rateVatIncluded, BigDecimal vatPercentage, boolean chargePerSecond, BigDecimal initialRateValue, boolean initialRateVatIncluded) {
        if (durationSeconds <= 0) return BigDecimal.ZERO;
        if (rateValue == null) rateValue = BigDecimal.ZERO;

        BigDecimal rateToUse = rateValue;
        boolean vatIncluded = rateVatIncluded;

        BigDecimal durationUnits;
        if (chargePerSecond) {
            durationUnits = new BigDecimal(durationSeconds);
        } else {
            durationUnits = new BigDecimal(durationSeconds).divide(SIXTY, 0, RoundingMode.CEILING);
            if (durationUnits.compareTo(BigDecimal.ZERO) == 0 && durationSeconds > 0) {
                durationUnits = BigDecimal.ONE;
            }
        }

        BigDecimal totalCost = rateToUse.multiply(durationUnits);

        if (!vatIncluded && vatPercentage != null && vatPercentage.compareTo(BigDecimal.ZERO) > 0) {
            BigDecimal vatMultiplier = BigDecimal.ONE.add(vatPercentage.divide(new BigDecimal("100"), 4, RoundingMode.HALF_UP));
            totalCost = totalCost.multiply(vatMultiplier);
        }

        return totalCost.setScale(4, RoundingMode.HALF_UP);
    }

     private BigDecimal calculateValueWithoutVat(BigDecimal value, BigDecimal vatPercentage, boolean vatIncluded) {
        if (value == null) return BigDecimal.ZERO;
        if (!vatIncluded || vatPercentage == null || vatPercentage.compareTo(BigDecimal.ZERO) <= 0) {
            return value;
        }
        BigDecimal vatDivisor = BigDecimal.ONE.add(vatPercentage.divide(new BigDecimal("100"), 10, RoundingMode.HALF_UP));
        if (vatDivisor.compareTo(BigDecimal.ZERO) == 0) return value;
        return value.divide(vatDivisor, 4, RoundingMode.HALF_UP);
    }

    private void applySpecialServicePricing(SpecialService specialService, CallRecord.CallRecordBuilder callBuilder, int duration) {
        callBuilder.telephonyTypeId(ConfigurationService.TIPOTELE_ESPECIALES);
        callBuilder.indicatorId(specialService.getIndicatorId());
        callBuilder.operatorId(null);

        BigDecimal price = specialService.getValue() != null ? specialService.getValue() : BigDecimal.ZERO;
        boolean vatIncluded = specialService.getVatIncluded() != null && specialService.getVatIncluded();
        BigDecimal vatAmount = specialService.getVatAmount() != null ? specialService.getVatAmount() : BigDecimal.ZERO;

        BigDecimal billedAmount = price;
        if (!vatIncluded && vatAmount.compareTo(BigDecimal.ZERO) > 0) {
             BigDecimal vatMultiplier = BigDecimal.ONE.add(vatAmount.divide(new BigDecimal("100"), 4, RoundingMode.HALF_UP));
             billedAmount = billedAmount.multiply(vatMultiplier);
        }

        callBuilder.pricePerMinute(price);
        callBuilder.initialPrice(BigDecimal.ZERO);
        callBuilder.billedAmount(billedAmount.setScale(4, RoundingMode.HALF_UP));
    }

    private void applyInternalPricing(Long internalCallTypeId, CallRecord.CallRecordBuilder callBuilder, int duration) {
         Optional<Map<String, Object>> tariffOpt = lookupService.findInternalTariff(internalCallTypeId);
         if (tariffOpt.isPresent()) {
             Map<String, Object> tariff = tariffOpt.get();
             tariff.putIfAbsent("valor_inicial", BigDecimal.ZERO);
             tariff.putIfAbsent("valor_inicial_iva", false);
             tariff.putIfAbsent("ensegundos", false);
             tariff.putIfAbsent("iva", BigDecimal.ZERO);
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

        Optional<TrunkRate> trunkRateOpt = lookupService.findTrunkRate(trunk.getId(), operatorId, telephonyTypeId);

        if (trunkRateOpt.isPresent()) {
            TrunkRate trunkRate = trunkRateOpt.get();
            log.debug("Applying TrunkRate {} for trunk {}", trunkRate.getId(), trunk.getId());
            baseRateInfo.put("valor_minuto", trunkRate.getRateValue());
            baseRateInfo.put("valor_minuto_iva", trunkRate.getIncludesVat());
            baseRateInfo.put("ensegundos", trunkRate.getSeconds() != null && trunkRate.getSeconds() > 0);
            baseRateInfo.put("valor_inicial", BigDecimal.ZERO);
            baseRateInfo.put("valor_inicial_iva", false);
            applyFinalPricing(baseRateInfo, duration, callBuilder);
        } else {
            Optional<TrunkRule> trunkRuleOpt = lookupService.findTrunkRule(trunk.getName(), telephonyTypeId, indicatorId, originIndicatorId);
            if (trunkRuleOpt.isPresent()) {
                TrunkRule rule = trunkRuleOpt.get();
                log.debug("Applying TrunkRule {} for trunk {}", rule.getId(), trunk.getName());
                baseRateInfo.put("valor_minuto", rule.getRateValue());
                baseRateInfo.put("valor_minuto_iva", rule.getIncludesVat());
                baseRateInfo.put("ensegundos", rule.getSeconds() != null && rule.getSeconds() > 0);
                baseRateInfo.put("valor_inicial", BigDecimal.ZERO);
                baseRateInfo.put("valor_inicial_iva", false);

                if (rule.getNewOperatorId() != null && rule.getNewOperatorId() > 0) {
                    callBuilder.operatorId(rule.getNewOperatorId());
                    lookupService.findOperatorById(rule.getNewOperatorId()).ifPresent(op -> baseRateInfo.put("operator_name", op.getName()));
                }
                if (rule.getNewTelephonyTypeId() != null && rule.getNewTelephonyTypeId() > 0) {
                    callBuilder.telephonyTypeId(rule.getNewTelephonyTypeId());
                     lookupService.findTelephonyTypeById(rule.getNewTelephonyTypeId()).ifPresent(tt -> baseRateInfo.put("telephony_type_name", tt.getName()));
                }
                applyFinalPricing(baseRateInfo, duration, callBuilder);
            } else {
                log.debug("No specific TrunkRate or TrunkRule found for trunk {}, applying special pricing", trunk.getName());
                applySpecialPricing(baseRateInfo, callBuilder.build().getServiceDate(), duration, originIndicatorId, callBuilder);
            }
        }
    }

    private void applySpecialPricing(Map<String, Object> currentRateInfo, LocalDateTime callDateTime, int duration, Long originIndicatorId, CallRecord.CallRecordBuilder callBuilder) {
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

             currentRateInfo.put("valor_inicial", originalRate);
             currentRateInfo.put("valor_inicial_iva", originalVatIncluded);

             if (rate.getValueType() != null && rate.getValueType() == 1) { // Percentage
                 BigDecimal discountPercentage = rate.getRateValue();
                 BigDecimal currentRateNoVat = calculateValueWithoutVat(originalRate, (BigDecimal) currentRateInfo.get("iva"), originalVatIncluded);
                 BigDecimal discountMultiplier = BigDecimal.ONE.subtract(discountPercentage.divide(new BigDecimal("100"), 4, RoundingMode.HALF_UP));
                 currentRateInfo.put("valor_minuto", currentRateNoVat.multiply(discountMultiplier));
                 currentRateInfo.put("valor_minuto_iva", originalVatIncluded);
                 currentRateInfo.put("descuento_p", discountPercentage);
             } else { // Fixed value
                 currentRateInfo.put("valor_minuto", rate.getRateValue());
                 currentRateInfo.put("valor_minuto_iva", rate.getIncludesVat());
             }
             currentRateInfo.put("ensegundos", false);
             applyFinalPricing(currentRateInfo, duration, callBuilder);
         } else {
             log.debug("No applicable special rate found, applying current rate.");
             currentRateInfo.put("valor_inicial", BigDecimal.ZERO);
             currentRateInfo.put("valor_inicial_iva", false);
             currentRateInfo.put("ensegundos", false);
             applyFinalPricing(currentRateInfo, duration, callBuilder);
         }
    }

    private void applyFinalPricing(Map<String, Object> rateInfo, int duration, CallRecord.CallRecordBuilder callBuilder) {
         BigDecimal pricePerMinute = (BigDecimal) rateInfo.get("valor_minuto");
         boolean vatIncluded = (Boolean) rateInfo.get("valor_minuto_iva");
         BigDecimal vatPercentage = (BigDecimal) rateInfo.get("iva");
         boolean chargePerSecond = (Boolean) rateInfo.get("ensegundos");
         BigDecimal initialPrice = (BigDecimal) rateInfo.get("valor_inicial");
         boolean initialVatIncluded = (Boolean) rateInfo.get("valor_inicial_iva");

         BigDecimal calculatedBilledAmount = calculateBilledAmount(
                 duration, pricePerMinute, vatIncluded, vatPercentage, chargePerSecond, initialPrice, initialVatIncluded
         );

         callBuilder.pricePerMinute(pricePerMinute);
         callBuilder.initialPrice(initialPrice);
         callBuilder.billedAmount(calculatedBilledAmount);
    }

    private Optional<Map<String, Object>> findRateInfo(Long prefixId, Long indicatorId, Long originIndicatorId, boolean bandOk) {
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

         if (useBands) {
             Optional<Map<String, Object>> bandOpt = lookupService.findBandByPrefixAndIndicator(prefixId, indicatorId, originIndicatorId);
             if (bandOpt.isPresent()) {
                 Map<String, Object> bandInfo = bandOpt.get();
                 rateInfo.put("base_value", bandInfo.get("band_value"));
                 rateInfo.put("vat_included", bandInfo.get("band_vat_included"));
                 rateInfo.put("band_id", bandInfo.get("band_id"));
                 rateInfo.put("band_name", bandInfo.get("band_name"));
                 log.trace("Using band rate for prefix {}, indicator {}", prefixId, indicatorId);
             } else {
                  log.trace("Band lookup enabled for prefix {} but no matching band found for indicator {}", prefixId, indicatorId);
             }
         } else {
              log.trace("Using base rate for prefix {}", prefixId);
         }
         // Ensure final rate keys are set based on base or band value
         rateInfo.put("valor_minuto", rateInfo.get("base_value"));
         rateInfo.put("valor_minuto_iva", rateInfo.get("vat_included"));
         rateInfo.put("iva", rateInfo.get("vat_value"));

         return Optional.of(rateInfo);
    }

    private String formatDestinationName(Map<String, Object> indicatorInfo) {
        String city = (String) indicatorInfo.get("city_name");
        String country = (String) indicatorInfo.get("department_country");
        if (StringUtils.hasText(city) && StringUtils.hasText(country)) return city + " (" + country + ")";
        return StringUtils.hasText(city) ? city : (StringUtils.hasText(country) ? country : "Unknown");
    }

    private Optional<SpecialRateValue> findApplicableSpecialRate(List<SpecialRateValue> candidates, LocalDateTime callDateTime) {
        int callHour = callDateTime.getHour();
        return candidates.stream()
                         .filter(rate -> isHourApplicable(rate.getHoursSpecification(), callHour))
                         .findFirst();
    }

     private boolean isHourApplicable(String hoursSpecification, int callHour) {
        if (!StringUtils.hasText(hoursSpecification)) return true;
        try {
            for (String range : hoursSpecification.split(",")) {
                range = range.trim();
                if (range.contains("-")) {
                    String[] parts = range.split("-");
                    int start = Integer.parseInt(parts[0].trim());
                    int end = Integer.parseInt(parts[1].trim());
                    if (callHour >= start && callHour <= end) return true;
                } else if (!range.isEmpty() && callHour == Integer.parseInt(range)) return true;
            }
        } catch (Exception e) { log.error("Error parsing hoursSpecification: {}", hoursSpecification, e); }
        return false;
    }

    /**
     * Preprocesses a phone number based on Colombian numbering plan rules,
     * mimicking PHP functions _esCelular_fijo and _esNacional.
     *
     * @param number The phone number to preprocess.
     * @param originCountryId The origin country ID (used to check if rules apply).
     * @return The potentially modified phone number.
     */
    private String preprocessNumberForLookup(String number, Long originCountryId) {
        if (number == null || originCountryId == null || !originCountryId.equals(COLOMBIA_ORIGIN_COUNTRY_ID)) {
            return number; // Only apply to Colombia
        }

        int len = number.length();
        String originalNumber = number;

        // --- Logic from _esCelular_fijo ---
        if (len == 10) {
            if (number.startsWith("3")) {
                 if (number.matches("^3[0-4][0-9]\\d{7}$")) {
                    number = "03" + number;
                    log.trace("Preprocessing (Celular): {} -> {}", originalNumber, number);
                 }
            } else if (number.startsWith("60")) {
                // Keep 60 prefix for now, let prefix/indicator lookup handle classification
                log.trace("Preprocessing (Fixed 60): {} kept as is for prefix lookup", originalNumber);
            }
        } else if (len == 11 && number.startsWith("03")) {
             if (number.matches("^03[0-4][0-9]\\d{7}$")) {
                 number = number.substring(1); // Remove leading 0
                 log.trace("Preprocessing (Mobile 03): {} -> {}", originalNumber, number);
             }
        }
        // Add other rules from _esEntrante_60, _es_Saliente if needed for the *lookup* stage

        return number;
    }
}