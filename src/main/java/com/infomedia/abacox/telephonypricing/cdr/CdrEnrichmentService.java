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
            callBuilder.telephonyTypeId(CdrProcessingConfig.TIPOTELE_INTERNA_IP);
            callBuilder.operatorId(null);
            callBuilder.indicatorId(null);
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
            callBuilder.assignmentCause(CallAssignmentCause.UNKNOWN.getValue());
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

        Long originCountryId = getOriginCountryId(commLocation);
        Optional<PbxSpecialRule> pbxRuleOpt = lookupService.findPbxSpecialRule(
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

        FieldWrapper<Long> forcedTelephonyType = new FieldWrapper<>(null);
        String preprocessedNumber = preprocessNumberForLookup(numberAfterPbxRule, originCountryId, forcedTelephonyType, commLocation);

        if (!preprocessedNumber.equals(numberAfterPbxRule)) {
            log.debug("Number preprocessed for lookup: {} -> {}", numberAfterPbxRule, preprocessedNumber);
        }

        Long indicatorIdForSpecial = commLocation.getIndicatorId();
        if (indicatorIdForSpecial != null && originCountryId != null) {
            String numberForSpecialCheck = cleanNumber(preprocessedNumber, Collections.emptyList(), false);
            Optional<SpecialService> specialServiceOpt = lookupService.findSpecialService(numberForSpecialCheck, indicatorIdForSpecial, originCountryId);
            if (specialServiceOpt.isPresent()) {
                SpecialService specialService = specialServiceOpt.get();
                log.debug("Call matches Special Service: {}", specialService.getId());
                applySpecialServicePricing(specialService, callBuilder, standardDto.getDurationSeconds());
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
        Long originCountryId = getOriginCountryId(commLocation);
        Long commIndicatorId = commLocation.getIndicatorId();
        List<String> pbxPrefixes = configService.getPbxPrefixes(commLocation.getId());

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
                log.debug("Applied INCOMING PBX rule {} to incoming number, result: {}", rule.getId(), numberAfterPbxRule);
            }
        }
        FieldWrapper<Long> forcedTelephonyType = new FieldWrapper<>(null);
        String preprocessedNumber = preprocessNumberForLookup(numberAfterPbxRule, originCountryId, forcedTelephonyType, commLocation);

        if (!preprocessedNumber.equals(numberAfterPbxRule)) {
            log.debug("Incoming number preprocessed for lookup: {} -> {}", numberAfterPbxRule, preprocessedNumber);
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
            List<Map<String, Object>> prefixes = lookupService.findPrefixesByNumber(numberForLookup, originCountryId);

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
                Long currentPrefixId = (Long) prefixInfo.get("prefix_id");
                boolean isBandOk = (Boolean) prefixInfo.get("prefix_band_ok");

                callBuilder.telephonyTypeId(telephonyTypeId);
                callBuilder.operatorId(operatorId);

                String numberWithoutPrefix = numberForLookup;
                if (StringUtils.hasText(prefixCode) && numberForLookup.startsWith(prefixCode)) {
                    numberWithoutPrefix = numberForLookup.substring(prefixCode.length());
                }

                Optional<Map<String, Object>> indicatorInfoOpt = lookupService.findIndicatorByNumber(
                        numberWithoutPrefix, telephonyTypeId, originCountryId, isBandOk, currentPrefixId, commIndicatorId
                );
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

        callBuilder.billedAmount(BigDecimal.ZERO);
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
            Optional<Map<String, Object>> internalPrefixOpt = lookupService.findInternalPrefixMatch(destinationExtension, sourceLoc.originCountryId);
            if (internalPrefixOpt.isPresent()) {
                internalCallTypeId = (Long) internalPrefixOpt.get().get("telephony_type_id");
                operatorId = (Long) internalPrefixOpt.get().get("operator_id");

                Optional<Prefix> prefixEntityOpt = lookupService.findPrefixByTypeOperatorOrigin(internalCallTypeId, operatorId, sourceLoc.originCountryId);
                boolean isBandOkForPrefix = prefixEntityOpt.map(Prefix::isBandOk).orElse(false);
                Long prefixIdForLookup = prefixEntityOpt.map(Prefix::getId).orElse(null);

                Optional<Map<String, Object>> indInfo = lookupService.findIndicatorByNumber(
                        destinationExtension, internalCallTypeId, sourceLoc.originCountryId,
                        isBandOkForPrefix, prefixIdForLookup, sourceLoc.indicatorId
                );
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

        applyInternalPricing(internalCallTypeId, callBuilder, standardDto.getDurationSeconds());
    }

    private void evaluateDestinationAndRate(StandardizedCallEventDto standardDto, CommunicationLocation commLocation, CallRecord.CallRecordBuilder callBuilder, String initialNumberToLookup, List<String> pbxPrefixes, Long forcedTelephonyTypeId) {
        Optional<Trunk> trunkOpt = lookupService.findTrunkByCode(standardDto.getDestinationTrunkIdentifier(), commLocation.getId());
        boolean usesTrunk = trunkOpt.isPresent();
        Long originIndicatorId = commLocation.getIndicatorId();

        Optional<Map<String, Object>> finalRateInfoOpt = attemptRateLookup(standardDto, commLocation, callBuilder, initialNumberToLookup, pbxPrefixes, trunkOpt, forcedTelephonyTypeId, originIndicatorId);

        if (finalRateInfoOpt.isEmpty() && usesTrunk) {
            log.warn("Initial rate lookup failed for trunk call {}, attempting fallback (no trunk info)", standardDto.getGlobalCallId());
            finalRateInfoOpt = attemptRateLookup(standardDto, commLocation, callBuilder, initialNumberToLookup, pbxPrefixes, Optional.empty(), forcedTelephonyTypeId, originIndicatorId);
        }

        if (finalRateInfoOpt.isPresent()) {
            Map<String, Object> finalRateInfo = finalRateInfoOpt.get();
            callBuilder.telephonyTypeId((Long) finalRateInfo.get("telephony_type_id"));
            callBuilder.operatorId((Long) finalRateInfo.get("operator_id"));
            callBuilder.indicatorId((Long) finalRateInfo.get("indicator_id"));
            callBuilder.dial((String) finalRateInfo.getOrDefault("effective_number", initialNumberToLookup));

            boolean appliedTrunkPricing = finalRateInfo.containsKey("applied_trunk_pricing") && (Boolean) finalRateInfo.get("applied_trunk_pricing");
            if (appliedTrunkPricing && usesTrunk) {
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
        if (usesTrunk && trunk.getNoPbxPrefix() != null && trunk.getNoPbxPrefix()) {
            effectiveNumber = cleanNumber(standardDto.getCalledPartyNumber(), Collections.emptyList(), false);
            log.trace("Attempting lookup (Trunk {} ignores PBX prefix): {}", trunk.getId(), effectiveNumber);
        } else {
            log.trace("Attempting lookup (Effective number): {}", effectiveNumber);
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
            boolean removePrefixForLookup = true;

            if (StringUtils.hasText(currentPrefixCode) && effectiveNumber.startsWith(currentPrefixCode)) {
                if (usesTrunk) {
                    Long operatorTroncal = findEffectiveTrunkOperator(trunk, currentTelephonyTypeId, currentPrefixCode, currentOperatorId, originCountryId);
                    if (operatorTroncal != null && operatorTroncal >= 0) {
                        Optional<TrunkRate> trOpt = lookupService.findTrunkRate(trunk.getId(), operatorTroncal, currentTelephonyTypeId);
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
                boolean finalBandOk = currentPrefixBandOk;
                if (currentTelephonyTypeId == CdrProcessingConfig.TIPOTELE_LOCAL && destinationNdc != null) {
                    boolean isExtended = lookupService.isLocalExtended(destinationNdc, originCommLocationIndicatorId);
                    if (isExtended) {
                        finalTelephonyTypeId = CdrProcessingConfig.TIPOTELE_LOCAL_EXT;
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
                    rateInfo.put("telephony_type_name", lookupService.findTelephonyTypeById(finalTelephonyTypeId).map(TelephonyType::getName).orElse("Unknown Type"));
                    rateInfo.put("operator_name", lookupService.findOperatorById(currentOperatorId).map(Operator::getName).orElse("Unknown Operator"));
                    rateInfo.put("destination_name", indicatorInfoOpt.map(this::formatDestinationName).orElse(lengthMatch ? "Unknown (Length Match)" : "Unknown Destination"));
                    rateInfo.put("band_name", rateInfo.get("band_name"));
                    rateInfo.put("effective_number", effectiveNumber);
                    rateInfo.put("applied_trunk_pricing", usesTrunk);

                    log.debug("Attempt successful: Found rate for prefix {}, indicator {}", currentPrefixCode, destinationIndicatorId);
                    return Optional.of(rateInfo);
                } else {
                    log.warn("Rate info not found for prefix {}, indicator {}", currentPrefixCode, destinationIndicatorId);
                    if (assumedRateInfo == null && allPrefixesConsistent) {
                        assumedRateInfo = new HashMap<>();
                        assumedRateInfo.put("telephony_type_id", finalTelephonyTypeId);
                        assumedRateInfo.put("operator_id", currentOperatorId);
                        assumedRateInfo.put("indicator_id", null);
                        assumedRateInfo.put("telephony_type_name", lookupService.findTelephonyTypeById(finalTelephonyTypeId).map(TelephonyType::getName).orElse("Unknown Type") + ASSUMED_SUFFIX);
                        assumedRateInfo.put("operator_name", lookupService.findOperatorById(currentOperatorId).map(Operator::getName).orElse("Unknown Operator"));
                        assumedRateInfo.put("destination_name", "Assumed Destination");
                        assumedRateInfo.put("effective_number", effectiveNumber);
                        assumedRateInfo.put("applied_trunk_pricing", usesTrunk);
                        Optional<Map<String, Object>> baseRateOpt = lookupService.findBaseRateForPrefix(finalPrefixId);
                        baseRateOpt.ifPresent(assumedRateInfo::putAll);
                        log.debug("Storing assumed rate info based on consistent prefix {} (Type: {}, Op: {})", currentPrefixCode, finalTelephonyTypeId, currentOperatorId);
                    }
                }
            } else {
                log.trace("No indicator found and length mismatch for prefix {}, number part {}", currentPrefixCode, numberWithoutPrefix);
            }
        }

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
            boolean isExtended = lookupService.isLocalExtended(destinationNdc, originIndicatorId);
            if (isExtended) {
                finalTelephonyTypeId = CdrProcessingConfig.TIPOTELE_LOCAL_EXT;
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
            rateInfo.put("telephony_type_name", lookupService.findTelephonyTypeById(finalTelephonyTypeId).map(TelephonyType::getName).orElse("Unknown Type"));
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

        boolean isLocalTypeForBandCheck = (rateInfo.get("telephony_type_id") != null &&
                rateInfo.get("telephony_type_id").equals(CdrProcessingConfig.TIPOTELE_LOCAL));

        boolean useEffectiveBandLookup = bandOk && ( (indicatorId != null && indicatorId > 0) || isLocalTypeForBandCheck );

        log.trace("findRateInfo: prefixId={}, indicatorId={}, originIndicatorId={}, bandOk={}, useEffectiveBandLookup={}",
                prefixId, indicatorId, originIndicatorId, bandOk, useEffectiveBandLookup);

        if (useEffectiveBandLookup) {
            Long indicatorForBandLookup = (indicatorId != null && indicatorId > 0) ? indicatorId : null;
            Optional<Map<String, Object>> bandOpt = lookupService.findBandByPrefixAndIndicator(prefixId, indicatorForBandLookup, originIndicatorId);
            if (bandOpt.isPresent()) {
                 Map<String, Object> bandInfo = bandOpt.get();
                rateInfo.put("base_value", bandInfo.get("band_value"));
                rateInfo.put("vat_included", bandInfo.get("band_vat_included"));
                rateInfo.put("band_id", bandInfo.get("band_id"));
                rateInfo.put("band_name", bandInfo.get("band_name"));
                log.trace("Using band rate for prefix {}, indicator {}: BandID={}, Value={}, VatIncluded={}",
                        prefixId, indicatorForBandLookup, bandInfo.get("band_id"), bandInfo.get("band_value"), bandInfo.get("band_vat_included"));
            } else {
                log.trace("Band lookup enabled for prefix {} but no matching band found for indicator {}", prefixId, indicatorForBandLookup);
            }
        } else {
            log.trace("Using base rate for prefix {} (Bands not applicable or indicator missing/null for non-local)", prefixId);
        }
        rateInfo.put("valor_minuto", rateInfo.get("base_value"));
        rateInfo.put("valor_minuto_iva", rateInfo.get("vat_included"));
        rateInfo.put("iva", rateInfo.get("vat_value"));

        return Optional.of(rateInfo);
    }

    private void linkAssociatedEntities(CallRecord.CallRecordBuilder callBuilder) {
        CallRecord record = callBuilder.build();

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
                log.trace("Prefix removal requested, PBX prefixes defined, but no matching prefix found in {}. Returning empty.", number);
                return "";
            }
        }
        boolean hasPlus = cleaned.startsWith("+");
        String tempCleaned = cleaned.replaceAll("[^0-9#*]", "");
        if (hasPlus && !tempCleaned.startsWith("+") && cleaned.startsWith("+")) {
            cleaned = "+" + tempCleaned;
        } else {
            cleaned = tempCleaned;
        }
        return cleaned;
    }

    private int getPrefixLength(String number, List<String> pbxPrefixes) {
        int longestMatchLength = 0;
        if (number == null || pbxPrefixes == null || pbxPrefixes.isEmpty()) {
            return -1;
        }
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

    private String preprocessNumberForLookup(String number, Long originCountryId, FieldWrapper<Long> forcedTelephonyType, CommunicationLocation commLocation) {
        if (number == null || originCountryId == null || !originCountryId.equals(COLOMBIA_ORIGIN_COUNTRY_ID)) {
            return number;
        }
        int len = number.length();
        String originalNumber = number;
        String processedNumber = number;

        if (len == 10) {
            if (number.startsWith("3") && number.matches("^3[0-4][0-9]\\d{7}$")) { // Colombian Mobile 3xx xxx xxxx
                processedNumber = "03" + number; // Standardize to 033xx...
                forcedTelephonyType.setValue(CdrProcessingConfig.TIPOTELE_CELULAR);
            } else if (number.startsWith("60")) { // Colombian Fixed Line 60X xxx xxxx
                // Determine if it's local or national based on the "X" and series lookup
                String nationalPrefixBasedOnCompany = determineNationalPrefix(number, originCountryId);
                if (nationalPrefixBasedOnCompany != null) { // It's national based on series company
                    processedNumber = nationalPrefixBasedOnCompany + number.substring(2); // Remove "60", add national operator prefix (e.g., "09")
                    forcedTelephonyType.setValue(CdrProcessingConfig.TIPOTELE_NACIONAL);
                } else { // Assumed Local or could be national if not found in series for specific companies
                    // Check if the 60X matches the commLocation's indicator's NDC
                    Optional<Integer> localNdcOpt = Optional.ofNullable(commLocation.getIndicator())
                                                            .map(Indicator::getTelephonyType) // Assuming Indicator has TelephonyType
                                                            .flatMap(tt -> lookupService.findLocalNdcForIndicator(commLocation.getIndicatorId()));

                    String ndcFromNumber = number.substring(2, 3); // The 'X' in '60X'
                    if (localNdcOpt.isPresent() && String.valueOf(localNdcOpt.get()).equals(ndcFromNumber)) {
                        // It's a local call
                        if (number.length() >= 3) {
                            processedNumber = number.substring(3); // Remove "60X"
                            forcedTelephonyType.setValue(CdrProcessingConfig.TIPOTELE_LOCAL);
                        } else {
                             log.warn("Fixed line number {} starts with 60 but is too short to remove 60X for local.", number);
                        }
                    } else {
                        // Not local by commLocation's NDC, treat as potentially national (default to 09 if no company match)
                        // or keep as 60X... for further prefix matching
                        // PHP logic: ` $numero = substr($numero, 2); $g_numero = $numero = '09'.$numero;` if not found in series
                        // This implies a default to national with '09' if not specifically local or other national.
                        // For now, let's assume if determineNationalPrefix was null, and it's not local by commLocation's NDC,
                        // it might be a national call to an operator not in the specific company list.
                        // The prefix matching later should handle "60X..." if it's a valid national prefix.
                        // If we want to force it to a generic national:
                        // processedNumber = "09" + number.substring(2); // Example default national prefix
                        // forcedTelephonyType.setValue(CdrProcessingConfig.TIPOTELE_NACIONAL);
                        // However, it's safer to let prefix matching handle "60X..."
                        log.trace("Number {} (60X...) not local by comm_location NDC and no specific company national prefix found. Relying on prefix matching for 60X...", number);
                        // No change to processedNumber here, let prefix matching handle "60X..."
                    }
                }
            }
        } else if (len == 12) {
            if ((number.startsWith("573") || number.startsWith("603")) && number.matches("^(57|60)3[0-4][0-9]\\d{7}$")) {
                processedNumber = "03" + number.substring(2);
                forcedTelephonyType.setValue(CdrProcessingConfig.TIPOTELE_CELULAR);
            } else if ((number.startsWith("5760") || number.startsWith("6060")) && number.matches("^(57|60)60\\d{8}$")) {
                if (number.length() >= 5) {
                     processedNumber = number.substring(4); // Leaves X + 7 digits
                     // This could be local or national. Further checks needed or rely on prefix matching for "X..."
                     // For now, we assume it becomes a local-like number for prefix matching.
                     // If we need to force a type:
                     // forcedTelephonyType.setValue(CdrProcessingConfig.TIPOTELE_LOCAL); // Or national depending on X
                } else {
                     log.warn("Fixed line number {} starts with (57|60)60 but is too short.", number);
                }
            }
        } else if (len == 11) {
            if (number.startsWith("03") && number.matches("^03[0-4][0-9]\\d{7}$")) {
                // Already in 033xx... format
                forcedTelephonyType.setValue(CdrProcessingConfig.TIPOTELE_CELULAR);
            } else if (number.startsWith("604") && number.matches("^604\\d{8}$")) {
                processedNumber = number.substring(3);
                // Potentially local or national, depends on "4"
            }
        } else if (len == 9 && number.startsWith("60") && number.matches("^60\\d{7}$")) {
            processedNumber = number.substring(2); // Leaves X + 6 digits
            // Potentially local or national
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
        String ndcStr = number10DigitStartingWith60.substring(2, 3);
        String subscriberNumberStr = number10DigitStartingWith60.substring(3);

        if (!ndcStr.matches("\\d") || !subscriberNumberStr.matches("\\d+")) {
            log.warn("Invalid NDC or subscriber number format in determineNationalPrefix: NDC={}, Sub={}", ndcStr, subscriberNumberStr);
            return null;
        }
        try {
            int ndc = Integer.parseInt(ndcStr);
            long subscriberNumber = Long.parseLong(subscriberNumberStr);

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
        return null;
    }

    private LocationInfo getLocationInfo(Employee employee, CommunicationLocation defaultLocation) {
        Long defaultIndicatorId = defaultLocation.getIndicatorId();
        Long defaultOriginCountryId = getOriginCountryId(defaultLocation);
        Long defaultOfficeId = null; // Subdivision ID can represent an office

        if (employee != null) {
            Long empOfficeId = employee.getSubdivisionId(); // This is the office/department
            Long empOriginCountryId = defaultOriginCountryId;
            Long empIndicatorId = defaultIndicatorId;

            if (employee.getCommunicationLocationId() != null) {
                Optional<CommunicationLocation> empLocOpt = lookupService.findCommunicationLocationById(employee.getCommunicationLocationId());
                if (empLocOpt.isPresent()) {
                    CommunicationLocation empLoc = empLocOpt.get();
                    empIndicatorId = empLoc.getIndicatorId() != null ? empLoc.getIndicatorId() : defaultIndicatorId;
                    Long empLocCountryId = getOriginCountryId(empLoc);
                    empOriginCountryId = empLocCountryId != null ? empLocCountryId : defaultOriginCountryId;
                    log.trace("Using location info from Employee's CommLocation {}: Indicator={}, Country={}", employee.getCommunicationLocationId(), empIndicatorId, empOriginCountryId);
                    return new LocationInfo(empIndicatorId, empOriginCountryId, empOfficeId);
                } else {
                    log.warn("Employee {} has CommLocationId {} assigned, but location not found.", employee.getId(), employee.getCommunicationLocationId());
                }
            }

            // If employee doesn't have a specific comm_location, or it's not found,
            // their subdivision's country (if applicable) or cost center's country might be relevant.
            // For simplicity, we'll prioritize the comm_location's country if employee's comm_location is not set.
            // If subdivision implies a different country (via its own hierarchy or linked indicator), that's more complex.
            // For now, let's assume subdivision doesn't directly give a country, but cost center might.

            if (employee.getCostCenterId() != null) {
                Optional<CostCenter> ccOpt = lookupService.findCostCenterById(employee.getCostCenterId());
                if (ccOpt.isPresent() && ccOpt.get().getOriginCountryId() != null) {
                    // Only override if not already set by a more specific employee comm_location
                    if (employee.getCommunicationLocationId() == null) {
                         empOriginCountryId = ccOpt.get().getOriginCountryId();
                         log.trace("Using OriginCountry {} from Employee's CostCenter {}", empOriginCountryId, employee.getCostCenterId());
                    }
                }
            }
             // The indicator ID for an employee is primarily determined by their assigned CommunicationLocation.
             // If no specific comm_location for employee, use the default (call's comm_location).
             // Office ID is the employee's subdivision.

            log.trace("Final location info for Employee {}: Indicator={}, Country={}, Office={}", employee.getId(), empIndicatorId, empOriginCountryId, empOfficeId);
            return new LocationInfo(empIndicatorId, empOriginCountryId, empOfficeId);
        }

        log.trace("Using default location info: Indicator={}, Country={}, Office={}", defaultIndicatorId, defaultOriginCountryId, defaultOfficeId);
        return new LocationInfo(defaultIndicatorId, defaultOriginCountryId, defaultOfficeId);
    }

    private Long getOriginCountryId(CommunicationLocation commLocation) {
        if (commLocation == null) return null;
        Indicator indicator = commLocation.getIndicator(); // Assumes eager/fetched
        if (indicator != null && indicator.getOriginCountryId() != null) {
            return indicator.getOriginCountryId();
        }
        // Fallback to lookup if not pre-fetched
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
        // Fallback to a default or system-wide country ID if necessary
        return COLOMBIA_ORIGIN_COUNTRY_ID; // Example default
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
        callBuilder.operatorId(null);
        BigDecimal price = Optional.ofNullable(specialService.getValue()).orElse(BigDecimal.ZERO);
        boolean vatIncluded = Optional.ofNullable(specialService.getVatIncluded()).orElse(false);
        BigDecimal vatPercentage = Optional.ofNullable(specialService.getVatAmount()).orElse(BigDecimal.ZERO);
        BigDecimal billedAmount = price;
        if (!vatIncluded && vatPercentage.compareTo(BigDecimal.ZERO) > 0) {
            BigDecimal vatMultiplier = BigDecimal.ONE.add(vatPercentage.divide(new BigDecimal("100"), 4, RoundingMode.HALF_UP));
            billedAmount = billedAmount.multiply(vatMultiplier);
        }
        callBuilder.pricePerMinute(price.setScale(4, RoundingMode.HALF_UP));
        callBuilder.initialPrice(BigDecimal.ZERO);
        callBuilder.billedAmount(billedAmount.setScale(4, RoundingMode.HALF_UP));
        log.debug("Applied Special Service pricing: Rate={}, Billed={}", price, billedAmount);
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
            baseRateInfo.put("valor_inicial", BigDecimal.ZERO);
            baseRateInfo.put("valor_inicial_iva", false);
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
                    callBuilder.operatorId(finalOperatorId);
                    lookupService.findOperatorById(finalOperatorId).ifPresent(op -> baseRateInfo.put("operator_name", op.getName()));
                }
                if (rule.getNewTelephonyTypeId() != null && rule.getNewTelephonyTypeId() > 0) {
                    finalTelephonyTypeId = rule.getNewTelephonyTypeId();
                    callBuilder.telephonyTypeId(finalTelephonyTypeId);
                    lookupService.findTelephonyTypeById(finalTelephonyTypeId).ifPresent(tt -> baseRateInfo.put("telephony_type_name", tt.getName()));
                }

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
        Long telephonyTypeId = (Long) currentRateInfo.get("telephony_type_id");
        Long operatorId = (Long) currentRateInfo.get("operator_id");
        Long bandId = (Long) currentRateInfo.get("band_id"); // This comes from findRateInfo

        List<Map<String, Object>> specialRatesMaps = lookupService.findSpecialRateValues(
                telephonyTypeId, operatorId, bandId, originIndicatorId, callDateTime
        );

        Optional<Map<String, Object>> applicableRateMapOpt = specialRatesMaps.stream()
                .filter(rateMap -> isHourApplicable((String) rateMap.get("hours_specification"), callDateTime.getHour()))
                .findFirst();

        if (applicableRateMapOpt.isPresent()) {
            Map<String, Object> rateMap = applicableRateMapOpt.get();
            log.debug("Applying SpecialRateValue (ID: {})", rateMap.get("srv_id"));
            BigDecimal originalRate = (BigDecimal) currentRateInfo.get("valor_minuto");
            boolean originalVatIncluded = (Boolean) currentRateInfo.get("valor_minuto_iva");

            currentRateInfo.put("valor_inicial", originalRate);
            currentRateInfo.put("valor_inicial_iva", originalVatIncluded);

            Integer valueType = (Integer) rateMap.get("srv_value_type");
            BigDecimal rateValue = (BigDecimal) rateMap.get("srv_rate_value");
            Boolean includesVat = (Boolean) rateMap.get("srv_includes_vat");
            BigDecimal prefixVatValue = (BigDecimal) rateMap.get("prefix_vat_value"); // VAT from prefix

            if (valueType != null && valueType == 1) { // Percentage discount
                BigDecimal discountPercentage = Optional.ofNullable(rateValue).orElse(BigDecimal.ZERO);
                BigDecimal currentRateNoVat = calculateValueWithoutVat(originalRate, (BigDecimal) currentRateInfo.get("iva"), originalVatIncluded);
                BigDecimal discountMultiplier = BigDecimal.ONE.subtract(discountPercentage.divide(new BigDecimal("100"), 4, RoundingMode.HALF_UP));
                currentRateInfo.put("valor_minuto", currentRateNoVat.multiply(discountMultiplier));
                currentRateInfo.put("valor_minuto_iva", originalVatIncluded); // VAT handling remains same as original rate
                currentRateInfo.put("descuento_p", discountPercentage);
                log.trace("Applied percentage discount {}% from SpecialRateValue {}", discountPercentage, rateMap.get("srv_id"));
            } else { // Fixed value override
                currentRateInfo.put("valor_minuto", Optional.ofNullable(rateValue).orElse(BigDecimal.ZERO));
                currentRateInfo.put("valor_minuto_iva", Optional.ofNullable(includesVat).orElse(false));
                log.trace("Applied fixed rate {} from SpecialRateValue {}", currentRateInfo.get("valor_minuto"), rateMap.get("srv_id"));
            }
            currentRateInfo.put("iva", prefixVatValue); // Use VAT from the prefix associated with the special rate's type/op
            currentRateInfo.put("ensegundos", false); // Special rates are typically not per-second unless specified
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

    private Long findEffectiveTrunkOperator(Trunk trunk, Long telephonyTypeId, String prefixCode, Long actualOperatorId, Long originCountryId) {
        if (trunk == null || telephonyTypeId == null || actualOperatorId == null || originCountryId == null) {
            return null; // Not enough info
        }

        // Check if there's a specific TrunkRate for the actualOperatorId and telephonyTypeId
        Optional<TrunkRate> specificRateOpt = lookupService.findTrunkRate(trunk.getId(), actualOperatorId, telephonyTypeId);
        if (specificRateOpt.isPresent()) {
            log.trace("Found specific TrunkRate for trunk {}, op {}, type {}. Using operator {}.",
                    trunk.getId(), actualOperatorId, telephonyTypeId, actualOperatorId);
            return actualOperatorId;
        }

        // No specific rate, check for a global rate (operatorId = 0) for this trunk and telephonyTypeId
        Optional<TrunkRate> globalRateOpt = lookupService.findTrunkRate(trunk.getId(), 0L, telephonyTypeId);
        if (globalRateOpt.isPresent()) {
            TrunkRate globalRate = globalRateOpt.get();
            if (globalRate.getNoPrefix() != null && globalRate.getNoPrefix()) {
                // Global rate says "no prefix". This means the call is routed based on the trunk's default operator for this telephony type.
                // We need to check if the actualOperatorId (from the dialed prefix) matches this default.
                Optional<Operator> defaultTrunkOperatorOpt = configService.getOperatorInternal(telephonyTypeId, originCountryId);
                Long defaultTrunkOperatorId = defaultTrunkOperatorOpt.map(Operator::getId).orElse(null);

                // And if the original prefix code is uniquely tied to a single operator
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
            return 0L; // Use global operator rule
        }

        log.trace("No specific or global TrunkRate found for trunk {}, op {}, type {}. No effective trunk operator rule.",
                trunk.getId(), actualOperatorId, telephonyTypeId);
        return null; // No applicable trunk operator rule found
    }
}