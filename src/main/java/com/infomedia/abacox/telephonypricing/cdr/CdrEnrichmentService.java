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
        // --- Populate Builder from Standardized DTO (as before) ---
        callBuilder.commLocationId(commLocation.getId());
        callBuilder.commLocation(commLocation);
        callBuilder.serviceDate(standardDto.getCallStartTime());
        callBuilder.employeeExtension(standardDto.getCallingPartyNumber());
        callBuilder.employeeAuthCode(standardDto.getAuthCode());
        callBuilder.destinationPhone(standardDto.getCalledPartyNumber());
        callBuilder.dial(standardDto.getCalledPartyNumber());
        callBuilder.duration(standardDto.getDurationSeconds());
        callBuilder.ringCount(standardDto.getRingDurationSeconds());
        callBuilder.isIncoming(standardDto.isIncoming());
        callBuilder.trunk(standardDto.getDestinationTrunkIdentifier());
        callBuilder.initialTrunk(standardDto.getSourceTrunkIdentifier());
        callBuilder.employeeTransfer(standardDto.getRedirectingPartyNumber());
        callBuilder.transferCause(CallTransferCause.fromValue(Optional.ofNullable(standardDto.getRedirectReason()).orElse(0)).getValue());
        callBuilder.assignmentCause(CallAssignmentCause.UNKNOWN.getValue());
        callBuilder.fileInfoId(standardDto.getFileInfoId());
        callBuilder.originIp(null);
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

        // --- Final Adjustments ---
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

        // Link associated entities
        linkAssociatedEntities(callBuilder);

        // Build final record
        CallRecord finalRecord = callBuilder.build();

        log.info("Successfully enriched CDR {}: Type={}, Billed={}",
                standardDto.getGlobalCallId(), finalRecord.getTelephonyTypeId(), finalRecord.getBilledAmount());
        return Optional.of(finalRecord);
    }

    // ========================================================================
    // Processing Logic Methods (processOutgoingCall, processIncomingCall, etc.)
    // Now use specific lookup services and RateCalculationService
    // ========================================================================

    private void processOutgoingCall(StandardizedCallEventDto standardDto, CommunicationLocation commLocation, CallRecord.CallRecordBuilder callBuilder, String effectiveCallingNumber, String effectiveDialedNumber) {
        log.debug("Processing outgoing call for CDR {} (effective: {} -> {})", standardDto.getGlobalCallId(), effectiveCallingNumber, effectiveDialedNumber);

        List<String> pbxPrefixes = configService.getPbxPrefixes(commLocation.getId());
        boolean shouldRemovePrefixInitially = getPrefixLength(effectiveDialedNumber, pbxPrefixes) > 0;
        String cleanedNumberForDial = cleanNumber(effectiveDialedNumber, pbxPrefixes, shouldRemovePrefixInitially);
        callBuilder.dial(cleanedNumberForDial);
        log.trace("Cleaned number for DIAL field: {}", cleanedNumberForDial);

        CdrProcessingConfig.ExtensionLengthConfig extConfig = configService.getExtensionLengthConfig(commLocation.getId());
        if (isInternalCall(effectiveCallingNumber, cleanedNumberForDial, extConfig)) {
            log.debug("Processing as internal call (effective: {} -> {})", effectiveCallingNumber, cleanedNumberForDial);
            processInternalCall(standardDto, commLocation, callBuilder, cleanedNumberForDial, extConfig);
            return;
        }

        Long originCountryId = configLookupService.getOriginCountryIdFromCommLocation(commLocation);
        Optional<PbxSpecialRule> pbxRuleOpt = pbxRuleLookupService.findPbxSpecialRule(
                effectiveDialedNumber, commLocation.getId(), CallDirection.OUTGOING.getValue()
        );
        String numberAfterPbxRule = effectiveDialedNumber;
        if (pbxRuleOpt.isPresent()) {
            PbxSpecialRule rule = pbxRuleOpt.get();
            String replacement = rule.getReplacement() != null ? rule.getReplacement() : "";
            String searchPattern = rule.getSearchPattern();
            if (StringUtils.hasText(searchPattern) && effectiveDialedNumber.startsWith(searchPattern)) {
                String numberAfterSearch = effectiveDialedNumber.substring(searchPattern.length());
                numberAfterPbxRule = replacement + numberAfterSearch;
                log.debug("Applied OUTGOING PBX rule {}, number changed to {}", rule.getId(), numberAfterPbxRule);
            }
        }

        String preprocessedNumber = preprocessNumberForLookup(numberAfterPbxRule, originCountryId);
        FieldWrapper<Long> forcedTelephonyType = new FieldWrapper<>(null);
        if (!preprocessedNumber.equals(numberAfterPbxRule)) {
            log.debug("Number preprocessed for lookup: {} -> {}", numberAfterPbxRule, preprocessedNumber);
            if (preprocessedNumber.startsWith("03") && preprocessedNumber.length() == 12) {
                forcedTelephonyType.setValue(CdrProcessingConfig.TIPOTELE_CELULAR);
            } else if (preprocessedNumber.matches("^\\d{7,8}$")) {
                forcedTelephonyType.setValue(CdrProcessingConfig.TIPOTELE_LOCAL);
            }
        }

        Long indicatorIdForSpecial = commLocation.getIndicatorId();
        if (indicatorIdForSpecial != null && originCountryId != null) {
            String numberForSpecialCheck = cleanNumber(preprocessedNumber, Collections.emptyList(), false);
            Optional<SpecialService> specialServiceOpt = specialLookupService.findSpecialService(numberForSpecialCheck, indicatorIdForSpecial, originCountryId);
            if (specialServiceOpt.isPresent()) {
                SpecialService specialService = specialServiceOpt.get();
                log.debug("Call matches Special Service: {}", specialService.getId());
                // Use RateCalculationService
                rateCalculationService.applySpecialServicePricing(specialService, callBuilder);
                callBuilder.dial(numberForSpecialCheck);
                return;
            }
        }

        log.debug("Processing as external outgoing call (effective: {} -> preprocessed: {})", effectiveCallingNumber, preprocessedNumber);
        evaluateDestinationAndRate(standardDto, commLocation, callBuilder, preprocessedNumber, pbxPrefixes, forcedTelephonyType.getValue());
    }


    private void processIncomingCall(StandardizedCallEventDto standardDto, CommunicationLocation commLocation, CallRecord.CallRecordBuilder callBuilder, String effectiveCallingNumber) {
        log.debug("Processing incoming call for CDR {} (effective caller: {})", standardDto.getGlobalCallId(), effectiveCallingNumber);

        String originalIncomingNumber = effectiveCallingNumber;
        Long originCountryId = configLookupService.getOriginCountryIdFromCommLocation(commLocation);
        Long commIndicatorId = commLocation.getIndicatorId();
        List<String> pbxPrefixes = configService.getPbxPrefixes(commLocation.getId());

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

        String preprocessedNumber = preprocessNumberForLookup(numberAfterPbxRule, originCountryId);
        FieldWrapper<Long> forcedTelephonyType = new FieldWrapper<>(null);
        if (!preprocessedNumber.equals(numberAfterPbxRule)) {
            log.debug("Incoming number preprocessed for lookup: {} -> {}", numberAfterPbxRule, preprocessedNumber);
            if (preprocessedNumber.startsWith("03") && preprocessedNumber.length() == 12) {
                forcedTelephonyType.setValue(CdrProcessingConfig.TIPOTELE_CELULAR);
            } else if (preprocessedNumber.matches("^\\d{7,8}$")) {
                forcedTelephonyType.setValue(CdrProcessingConfig.TIPOTELE_LOCAL);
            }
        }

        String numberForLookup;
        int prefixLen = getPrefixLength(numberAfterPbxRule, pbxPrefixes);
        if (prefixLen > 0) {
            numberForLookup = numberAfterPbxRule.substring(prefixLen);
            numberForLookup = cleanNumber(numberForLookup, Collections.emptyList(), false);
            log.trace("Incoming call had PBX prefix, looking up origin for: {}", numberForLookup);
        } else {
            numberForLookup = preprocessedNumber;
            log.trace("Incoming call had no PBX prefix, looking up origin for: {}", numberForLookup);
        }

        callBuilder.dial(cleanNumber(preprocessedNumber, Collections.emptyList(), false));

        if (originCountryId != null && commIndicatorId != null) {
            List<Map<String, Object>> prefixes = prefixLookupService.findPrefixesByNumber(numberForLookup, originCountryId);

            if (forcedTelephonyType.getValue() != null) {
                Long forcedType = forcedTelephonyType.getValue();
                prefixes = prefixes.stream()
                        .filter(p -> forcedType.equals(p.get("telephony_type_id")))
                        .collect(Collectors.toList());
                 if (prefixes.isEmpty())
                    log.warn("Forced TelephonyType ID {} has no matching prefixes for incoming lookup number {}", forcedType, numberForLookup);
            }

            Optional<Map<String, Object>> bestPrefix = prefixes.stream().findFirst();

            if (bestPrefix.isPresent()) {
                Map<String, Object> prefixInfo = bestPrefix.get();
                Long telephonyTypeId = (Long) prefixInfo.get("telephony_type_id");
                Long operatorId = (Long) prefixInfo.get("operator_id");
                String prefixCode = (String) prefixInfo.get("prefix_code");

                callBuilder.telephonyTypeId(telephonyTypeId);
                callBuilder.operatorId(operatorId);

                String numberWithoutPrefix = numberForLookup;
                if (StringUtils.hasText(prefixCode) && numberForLookup.startsWith(prefixCode)) {
                    numberWithoutPrefix = numberForLookup.substring(prefixCode.length());
                }

                Optional<Map<String, Object>> indicatorInfoOpt = indicatorLookupService.findIndicatorByNumber(numberWithoutPrefix, telephonyTypeId, originCountryId);
                Long indicatorId = indicatorInfoOpt
                        .map(ind -> (Long) ind.get("indicator_id"))
                        .filter(id -> id != null && id > 0)
                        .orElse(null);
                callBuilder.indicatorId(indicatorId);

                log.debug("Incoming call classified as Type ID: {}, Operator ID: {}, Indicator ID: {}",
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

        // Incoming calls typically zero cost
        callBuilder.billedAmount(BigDecimal.ZERO);
        callBuilder.pricePerMinute(BigDecimal.ZERO);
        callBuilder.initialPrice(BigDecimal.ZERO);
    }

    private void processInternalCall(StandardizedCallEventDto standardDto, CommunicationLocation commLocation, CallRecord.CallRecordBuilder callBuilder, String destinationExtension, CdrProcessingConfig.ExtensionLengthConfig extConfig) {
        String sourceExtension = standardDto.getCallingPartyNumber();
        log.debug("Processing internal call from {} to {}", sourceExtension, destinationExtension);

        Optional<Employee> sourceEmployeeOpt = Optional.ofNullable(callBuilder.build().getEmployee());
        Optional<Employee> destEmployeeOpt = employeeLookupService.findEmployeeByExtensionOrAuthCode(destinationExtension, null, commLocation.getId());

        LocationInfo sourceLoc = getLocationInfo(sourceEmployeeOpt.orElse(null), commLocation);
        LocationInfo destLoc = getLocationInfo(destEmployeeOpt.orElse(null), commLocation);

        Long internalCallTypeId = null;
        Long operatorId = null;
        Long indicatorId = null;

        if (destEmployeeOpt.isPresent()) {
            callBuilder.destinationEmployeeId(destEmployeeOpt.get().getId());
            callBuilder.destinationEmployee(destEmployeeOpt.get());
            indicatorId = destLoc.indicatorId;

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
            Optional<Map<String, Object>> internalPrefixOpt = prefixLookupService.findInternalPrefixMatch(destinationExtension, sourceLoc.originCountryId);
            if (internalPrefixOpt.isPresent()) {
                internalCallTypeId = (Long) internalPrefixOpt.get().get("telephony_type_id");
                operatorId = (Long) internalPrefixOpt.get().get("operator_id");
                Optional<Map<String, Object>> indInfo = indicatorLookupService.findIndicatorByNumber(destinationExtension, internalCallTypeId, sourceLoc.originCountryId);
                indicatorId = indInfo.map(m -> (Long)m.get("indicator_id")).filter(id -> id != null && id > 0).orElse(null);
                log.debug("Destination {} matched internal prefix for type {}, Op {}, Ind {}", destinationExtension, internalCallTypeId, operatorId, indicatorId);
            } else {
                internalCallTypeId = CdrProcessingConfig.getDefaultInternalCallTypeId();
                indicatorId = sourceLoc.indicatorId;
                log.debug("Destination {} not found and no internal prefix matched, defaulting to type {}, Ind {}", destinationExtension, internalCallTypeId, indicatorId);
            }
        }

        callBuilder.telephonyTypeId(internalCallTypeId);
        callBuilder.indicatorId(indicatorId != null && indicatorId > 0 ? indicatorId : null);

        if (operatorId == null) {
            configService.getOperatorInternal(internalCallTypeId, sourceLoc.originCountryId)
                    .ifPresent(op -> callBuilder.operatorId(op.getId()));
        } else {
            callBuilder.operatorId(operatorId);
        }

        // Use RateCalculationService
        rateCalculationService.applyInternalPricing(internalCallTypeId, callBuilder, standardDto.getDurationSeconds());
    }

    private void evaluateDestinationAndRate(StandardizedCallEventDto standardDto, CommunicationLocation commLocation, CallRecord.CallRecordBuilder callBuilder, String initialNumberToLookup, List<String> pbxPrefixes, Long forcedTelephonyTypeId) {
        Optional<Trunk> trunkOpt = trunkLookupService.findTrunkByCode(standardDto.getDestinationTrunkIdentifier(), commLocation.getId());
        boolean usesTrunk = trunkOpt.isPresent();
        Long originIndicatorId = commLocation.getIndicatorId();
        Long originCountryId = configLookupService.getOriginCountryIdFromCommLocation(commLocation);

        Optional<Map<String, Object>> finalRateInfoOpt = attemptRateLookup(standardDto, commLocation, callBuilder, initialNumberToLookup, pbxPrefixes, trunkOpt, forcedTelephonyTypeId);

        if (finalRateInfoOpt.isEmpty() && usesTrunk) {
            log.warn("Initial rate lookup failed for trunk call {}, attempting fallback (no trunk info)", standardDto.getGlobalCallId());
            finalRateInfoOpt = attemptRateLookup(standardDto, commLocation, callBuilder, initialNumberToLookup, pbxPrefixes, Optional.empty(), forcedTelephonyTypeId);
        }

        if (finalRateInfoOpt.isPresent()) {
            Map<String, Object> finalRateInfo = finalRateInfoOpt.get();
            callBuilder.telephonyTypeId((Long) finalRateInfo.get("telephony_type_id"));
            callBuilder.operatorId((Long) finalRateInfo.get("operator_id"));
            callBuilder.indicatorId((Long) finalRateInfo.get("indicator_id")); // Can be null
            callBuilder.dial((String) finalRateInfo.getOrDefault("effective_number", initialNumberToLookup));

            boolean appliedTrunkPricing = finalRateInfo.containsKey("applied_trunk_pricing") && (Boolean) finalRateInfo.get("applied_trunk_pricing");
            if (appliedTrunkPricing && usesTrunk) {
                // Use RateCalculationService for trunk pricing
                rateCalculationService.applyTrunkPricing(trunkOpt.get(), finalRateInfo, standardDto.getDurationSeconds(), originIndicatorId, originCountryId, standardDto.getCallStartTime(), callBuilder);
            } else {
                // Use RateCalculationService for special pricing (base/band + special rates)
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

    private Optional<Map<String, Object>> attemptRateLookup(StandardizedCallEventDto standardDto, CommunicationLocation commLocation, CallRecord.CallRecordBuilder callBuilder, String initialNumberToLookup, List<String> pbxPrefixes, Optional<Trunk> trunkOpt, Long forcedTelephonyTypeId) {
        Long originCountryId = configLookupService.getOriginCountryIdFromCommLocation(commLocation);
        Long originIndicatorId = commLocation.getIndicatorId();
        boolean usesTrunk = trunkOpt.isPresent();
        Trunk trunk = trunkOpt.orElse(null);

        if (originCountryId == null || originIndicatorId == null) {
            log.error("Cannot attempt rate lookup: Missing Origin Country ({}) or Indicator ID ({}) for Location {}", originCountryId, originIndicatorId, commLocation.getId());
            return Optional.empty();
        }

        String effectiveNumber = initialNumberToLookup;
        if (usesTrunk && trunk.getNoPbxPrefix() != null && trunk.getNoPbxPrefix()) {
            effectiveNumber = cleanNumber(standardDto.getCalledPartyNumber(), Collections.emptyList(), false);
            log.trace("Attempting lookup (Trunk {} ignores PBX prefix): {}", trunk.getId(), effectiveNumber);
        } else {
            log.trace("Attempting lookup (Effective number): {}", effectiveNumber);
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

            if (firstOperatorId == null) {
                firstOperatorId = currentOperatorId;
                firstTelephonyTypeId = currentTelephonyTypeId;
            } else if (!firstOperatorId.equals(currentOperatorId) || !firstTelephonyTypeId.equals(currentTelephonyTypeId)) {
                allPrefixesConsistent = false;
            }

            String numberWithoutPrefix = effectiveNumber;
            boolean prefixRemoved = false;
            boolean removePrefixForLookup = true;

            if (StringUtils.hasText(currentPrefixCode) && effectiveNumber.startsWith(currentPrefixCode)) {
                if (usesTrunk) {
                    Long operatorTroncal = findEffectiveTrunkOperator(trunk, currentTelephonyTypeId, currentPrefixCode, currentOperatorId);
                    if (operatorTroncal != null && operatorTroncal >= 0) {
                        Optional<TrunkRate> trOpt = trunkLookupService.findTrunkRate(trunk.getId(), operatorTroncal, currentTelephonyTypeId);
                        if (trOpt.isPresent() && trOpt.get().getNoPrefix() != null && trOpt.get().getNoPrefix()) {
                            removePrefixForLookup = false;
                            log.trace("TrunkRate for prefix {} (effective op {}) prevents prefix removal during indicator lookup", currentPrefixCode, operatorTroncal);
                        }
                    } else {
                        log.trace("No matching trunk operator rule found for prefix {}, type {}, op {}. Defaulting prefix removal.", currentPrefixCode, currentTelephonyTypeId, currentOperatorId);
                    }
                }
                if (removePrefixForLookup && currentTelephonyTypeId != CdrProcessingConfig.TIPOTELE_LOCAL) {
                    numberWithoutPrefix = effectiveNumber.substring(currentPrefixCode.length());
                    prefixRemoved = true;
                    log.trace("Prefix {} removed for indicator lookup (Type: {}), remaining: {}", currentPrefixCode, currentTelephonyTypeId, numberWithoutPrefix);
                } else if (removePrefixForLookup) {
                    log.trace("Prefix {} not removed for indicator lookup (Type: {})", currentPrefixCode, currentTelephonyTypeId);
                    removePrefixForLookup = false; // Explicitly set false
                }
            } else {
                removePrefixForLookup = false; // No prefix matched
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

            Optional<Map<String, Object>> indicatorInfoOpt = indicatorLookupService.findIndicatorByNumber(
                    numberWithoutPrefix, currentTelephonyTypeId, originCountryId
            );

            boolean destinationFound = indicatorInfoOpt.isPresent();
            boolean lengthMatch = (effectiveMaxLength > 0 && numberWithoutPrefix.length() == effectiveMaxLength && !destinationFound);
            boolean considerMatch = destinationFound || lengthMatch;

            if (considerMatch) {
                Long destinationIndicatorId = indicatorInfoOpt
                        .map(ind -> (Long) ind.get("indicator_id"))
                        .filter(id -> id != null && id > 0)
                        .orElse(null);
                Integer destinationNdc = indicatorInfoOpt.map(ind -> (Integer) ind.get("series_ndc")).orElse(null);

                Long finalTelephonyTypeId = currentTelephonyTypeId;
                Long finalPrefixId = currentPrefixId;
                boolean finalBandOk = bandOk;
                if (currentTelephonyTypeId == CdrProcessingConfig.TIPOTELE_LOCAL && destinationNdc != null) {
                    boolean isExtended = indicatorLookupService.isLocalExtended(destinationNdc, originIndicatorId);
                    if (isExtended) {
                        finalTelephonyTypeId = CdrProcessingConfig.TIPOTELE_LOCAL_EXT;
                        log.debug("Reclassified call to {} as LOCAL_EXTENDED based on NDC {} and origin {}", effectiveNumber, destinationNdc, originIndicatorId);
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
                    rateInfo.put("operator_id", currentOperatorId);
                    rateInfo.put("indicator_id", destinationIndicatorId); // Pass potentially null ID
                    rateInfo.put("telephony_type_name", entityLookupService.findTelephonyTypeById(finalTelephonyTypeId).map(TelephonyType::getName).orElse("Unknown Type"));
                    rateInfo.put("operator_name", entityLookupService.findOperatorById(currentOperatorId).map(Operator::getName).orElse("Unknown Operator"));
                    rateInfo.put("destination_name", indicatorInfoOpt.map(this::formatDestinationName).orElse(lengthMatch ? "Unknown (Length Match)" : "Unknown Destination"));
                    rateInfo.put("band_name", rateInfo.get("band_name"));
                    rateInfo.put("effective_number", effectiveNumber);
                    rateInfo.put("applied_trunk_pricing", usesTrunk); // Mark if trunk was involved in this attempt

                    log.debug("Attempt successful: Found rate for prefix {}, indicator {}", currentPrefixCode, destinationIndicatorId);
                    return Optional.of(rateInfo);
                } else {
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
                        Optional<Map<String, Object>> baseRateOpt = prefixLookupService.findBaseRateForPrefix(finalPrefixId);
                        baseRateOpt.ifPresent(assumedRateInfo::putAll);
                        log.debug("Storing assumed rate info based on consistent prefix {} (Type: {}, Op: {})", currentPrefixCode, finalTelephonyTypeId, currentOperatorId);
                    }
                }
            } else {
                log.trace("No indicator found and length mismatch for prefix {}, number part {}", currentPrefixCode, numberWithoutPrefix);
            }
        } // End prefix loop

        // Fallbacks
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

        if (assumedRateInfo != null && allPrefixesConsistent) {
            log.warn("Using assumed rate info for number {} based on consistent prefix type/operator.", effectiveNumber);
            return Optional.of(assumedRateInfo);
        }

        log.warn("Attempt failed: No matching rate found for number: {}", effectiveNumber);
        return Optional.empty();
    }

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
        if (destinationNdc != null) {
            boolean isExtended = indicatorLookupService.isLocalExtended(destinationNdc, originIndicatorId);
            if (isExtended) {
                finalTelephonyTypeId = CdrProcessingConfig.TIPOTELE_LOCAL_EXT;
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
            rateInfo.put("telephony_type_name", entityLookupService.findTelephonyTypeById(finalTelephonyTypeId).map(TelephonyType::getName).orElse("Unknown Type"));
            rateInfo.put("operator_name", internalOpOpt.get().getName());
            rateInfo.put("destination_name", formatDestinationName(indicatorInfoOpt.get()));
            rateInfo.put("band_name", rateInfo.get("band_name"));
            return Optional.of(rateInfo);
        } else {
            log.warn("Rate info not found for LOCAL fallback (Type: {}, Prefix: {}, Indicator: {})", finalTelephonyTypeId, finalPrefixId, destinationIndicatorId);
        }

        return Optional.empty();
    }

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

        boolean useBands = bandOk && indicatorId != null;
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

    // --- Helper Methods (Keep relevant ones) ---

    @Getter @Setter
    private static class FieldWrapper<T> { T value; FieldWrapper(T v) { this.value = v; } }
    // swapFields remains the same

    private record LocationInfo(Long indicatorId, Long originCountryId, Long officeId) {}

    private LocationInfo getLocationInfo(Employee employee, CommunicationLocation defaultLocation) {
        Long defaultIndicatorId = defaultLocation.getIndicatorId();
        Long defaultOriginCountryId = configLookupService.getOriginCountryIdFromCommLocation(defaultLocation);
        Long defaultOfficeId = null;

        if (employee != null) {
            Long empOfficeId = employee.getSubdivisionId();
            Long empOriginCountryId = defaultOriginCountryId;
            Long empIndicatorId = defaultIndicatorId;

            if (employee.getCommunicationLocationId() != null) {
                Optional<CommunicationLocation> empLocOpt = entityLookupService.findCommunicationLocationById(employee.getCommunicationLocationId());
                if (empLocOpt.isPresent()) {
                    CommunicationLocation empLoc = empLocOpt.get();
                    empIndicatorId = empLoc.getIndicatorId() != null ? empLoc.getIndicatorId() : defaultIndicatorId;
                    Long empLocCountryId = configLookupService.getOriginCountryIdFromCommLocation(empLoc);
                    empOriginCountryId = empLocCountryId != null ? empLocCountryId : defaultOriginCountryId;
                    log.trace("Using location info from Employee's CommLocation {}: Indicator={}, Country={}", employee.getCommunicationLocationId(), empIndicatorId, empOriginCountryId);
                    return new LocationInfo(empIndicatorId, empOriginCountryId, empOfficeId);
                } else {
                    log.warn("Employee {} has CommLocationId {} assigned, but location not found.", employee.getId(), employee.getCommunicationLocationId());
                }
            }

            if (employee.getCostCenterId() != null) {
                Optional<CostCenter> ccOpt = entityLookupService.findCostCenterById(employee.getCostCenterId());
                if (ccOpt.isPresent() && ccOpt.get().getOriginCountryId() != null) {
                    empOriginCountryId = ccOpt.get().getOriginCountryId();
                    log.trace("Using OriginCountry {} from Employee's CostCenter {}", empOriginCountryId, employee.getCostCenterId());
                }
            }

            log.trace("Final location info for Employee {}: Indicator={}, Country={}, Office={}", employee.getId(), empIndicatorId, empOriginCountryId, empOfficeId);
            return new LocationInfo(empIndicatorId, empOriginCountryId, empOfficeId);
        }

        log.trace("Using default location info: Indicator={}, Country={}, Office={}", defaultIndicatorId, defaultOriginCountryId, defaultOfficeId);
        return new LocationInfo(defaultIndicatorId, defaultOriginCountryId, defaultOfficeId);
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
                    processedNumber = "03" + number;
                }
            } else if (number.startsWith("60")) {
                String nationalPrefix = determineNationalPrefix(number);
                if (nationalPrefix != null) {
                    processedNumber = nationalPrefix + number.substring(2);
                } else {
                    processedNumber = number.substring(2);
                }
            }
        }
        // --- Logic from _esEntrante_60 ---
        else if (len == 12) {
            if (number.startsWith("573") || number.startsWith("603")) {
                if (number.matches("^(57|60)3[0-4][0-9]\\d{7}$")) {
                    processedNumber = number.substring(2);
                    processedNumber = "03" + processedNumber;
                }
            } else if (number.startsWith("6060") || number.startsWith("5760")) {
                 if (number.matches("^(57|60)60\\d{8}$")) {
                    processedNumber = number.substring(4);
                }
            }
        } else if (len == 11) {
            if (number.startsWith("03")) {
                // Keep as is
            } else if (number.startsWith("604")) {
                 if (number.matches("^604\\d{8}$")) {
                    processedNumber = number.substring(3);
                }
            }
        } else if (len == 9 && number.startsWith("60")) {
             if (number.matches("^60\\d{7}$")) {
                processedNumber = number.substring(2);
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
        String ndcStr = number10Digit.substring(2, 3);
        String subscriberNumberStr = number10Digit.substring(3);

        if (!ndcStr.matches("\\d") || !subscriberNumberStr.matches("\\d+")) {
            return null;
        }
        int ndc = Integer.parseInt(ndcStr);
        long subscriberNumber = Long.parseLong(subscriberNumberStr);

        // Use IndicatorLookupService
        Optional<Map<String, Object>> seriesInfoOpt = indicatorLookupService.findSeriesInfoForNationalLookup(ndc, subscriberNumber);

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
        log.trace("isInternalCall check: Caller '{}' (isExt: {}) -> Dialed '{}' (isExt: {})", callingNumber, callingIsExt, dialedNumber, dialedIsExt);
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
                 if (actualOperatorId != null && !actualOperatorId.equals(defaultOperatorId)) {
                     log.trace("Global TrunkRate ignores prefix, but actual operator {} is not the default for type {}. Operator rule not applicable.", actualOperatorId, telephonyTypeId);
                     return null; // Indicate no applicable operator rule
                 }
             }
             log.trace("Found global TrunkRate for trunk {}, type {}. Using operator 0.", trunk.getId(), telephonyTypeId);
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
            // Use IndicatorLookupService
            indicatorLookupService.findIndicatorById(record.getIndicatorId())
                    .ifPresentOrElse(callBuilder::indicator, () -> log.warn("Could not link Indicator entity for ID: {}", record.getIndicatorId()));
        } else if (record.getIndicatorId() == null) {
             log.trace("Indicator ID is null, skipping entity linking.");
        }

        // Link Destination Employee
        if (record.getDestinationEmployeeId() != null && record.getDestinationEmployee() == null) {
            // Use EmployeeLookupService
            employeeLookupService.findDestinationEmployeeById(record.getDestinationEmployeeId())
                    .ifPresentOrElse(callBuilder::destinationEmployee, () -> log.warn("Could not link destination employee entity for ID: {}", record.getDestinationEmployeeId()));
        }
    }

}