// FILE: com/infomedia/abacox/telephonypricing/cdr/CdrEnrichmentService.java
package com.infomedia.abacox.telephonypricing.cdr;

import com.infomedia.abacox.telephonypricing.entity.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.util.*;

@Service
@RequiredArgsConstructor
@Log4j2
public class CdrEnrichmentService {

    private final EmployeeLookupService employeeLookupService;
    private final ExtensionLookupService extensionLookupService;
    private final PrefixInfoLookupService prefixInfoLookupService;
    private final TrunkLookupService trunkLookupService;
    private final SpecialRuleLookupService specialRuleLookupService;
    private final ConfigurationLookupService configurationLookupService;
    private final EntityLookupService entityLookupService;

    private final CdrPricingService cdrPricingService;
    private final CdrNumberProcessingService cdrNumberProcessingService;
    private final CdrEnrichmentHelper cdrEnrichmentHelper;

    private final CdrProcessingConfig configService;

    private static final String ASSUMED_SUFFIX = " (Assumed)";


    public Optional<CallRecord> enrichCdr(StandardizedCallEventDto standardDto, CommunicationLocation commLocation) {
        log.info("Enriching Standardized CDR: {}, Hash: {}", standardDto.getGlobalCallId(), standardDto.getCdrHash());

        CallRecord.CallRecordBuilder callBuilder = CallRecord.builder();
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
        callBuilder.transferCause(Optional.ofNullable(standardDto.getRedirectReason()).orElse(CallTransferCause.NONE.getValue()));
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
            Optional<Employee> confEmployeeOpt = employeeLookupService.findEmployeeByExtensionOrAuthCode(
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
            configService.getOperatorInternal(CdrProcessingConfig.TIPOTELE_INTERNA_IP, cdrEnrichmentHelper.getOriginCountryId(commLocation))
                    .ifPresent(op -> callBuilder.operatorId(op.getId()));
            callBuilder.indicatorId(null); // Conferences typically don't have a destination indicator in the same way
            linkAssociatedEntities(callBuilder);
            CallRecord finalRecord = callBuilder.build();
            log.info("Processed conference CDR {}: Type={}, Billed=0", standardDto.getGlobalCallId(), finalRecord.getTelephonyTypeId());
            return Optional.of(finalRecord);
        }

        Optional<Employee> employeeOpt = employeeLookupService.findEmployeeByExtensionOrAuthCode(
                standardDto.getCallingPartyNumber(), standardDto.getAuthCode(), commLocation.getId()
        );
        employeeOpt.ifPresent(employee -> {
            callBuilder.employeeId(employee.getId());
            callBuilder.employee(employee);
            if (StringUtils.hasText(standardDto.getAuthCode()) && !configService.getIgnoredAuthCodes().contains(standardDto.getAuthCode())) {
                callBuilder.assignmentCause(CallAssignmentCause.AUTH_CODE.getValue());
            } else if (StringUtils.hasText(standardDto.getAuthCode())) { // Auth code is present but ignored
                callBuilder.assignmentCause(CallAssignmentCause.IGNORED_AUTH_CODE.getValue());
                // If auth code is ignored, try to find by extension only
                Optional<Employee> extOnlyEmployeeOpt = employeeLookupService.findEmployeeByExtensionOrAuthCode(standardDto.getCallingPartyNumber(), null, commLocation.getId());
                if (extOnlyEmployeeOpt.isPresent()) {
                    callBuilder.employeeId(extOnlyEmployeeOpt.get().getId());
                    callBuilder.employee(extOnlyEmployeeOpt.get());
                    callBuilder.assignmentCause(CallAssignmentCause.EXTENSION.getValue());
                } else {
                    // No employee found by extension alone after ignoring auth code
                    callBuilder.employeeId(null);
                    callBuilder.employee(null);
                    callBuilder.assignmentCause(CallAssignmentCause.UNKNOWN.getValue()); // Or keep IGNORED_AUTH_CODE if preferred
                }
            } else { // No auth code, assigned by extension
                callBuilder.assignmentCause(CallAssignmentCause.EXTENSION.getValue());
            }
        });

        // If employee still not found (or was reset due to ignored auth code)
        if (callBuilder.build().getEmployeeId() == null) {
            log.warn("Employee lookup failed or auth code ignored for CDR {} (Effective Caller: {}, Code: {})",
                    standardDto.getGlobalCallId(), standardDto.getCallingPartyNumber(), standardDto.getAuthCode());
            CdrProcessingConfig.ExtensionLengthConfig extConfig = configService.getExtensionLengthConfig(commLocation.getId());
            if (cdrEnrichmentHelper.isLikelyExtension(standardDto.getCallingPartyNumber(), extConfig)) {
                Optional<Map<String, Object>> rangeAssignment = employeeLookupService.findRangeAssignment(standardDto.getCallingPartyNumber(), commLocation.getId(), standardDto.getCallStartTime());
                if (rangeAssignment.isPresent()) {
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
        if (tempRecord.getDuration() != null && tempRecord.getDuration() <= configService.getMinCallDurationForBilling()
                && tempRecord.getTelephonyTypeId() != null
                && tempRecord.getTelephonyTypeId() != CdrProcessingConfig.TIPOTELE_ERRORES) {
            log.debug("Call duration {} is not billable (min required: {}), setting TelephonyType to SINCONSUMO ({})",
                    tempRecord.getDuration(), configService.getMinCallDurationForBilling(), CdrProcessingConfig.TIPOTELE_SINCONSUMO);
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
        Long originCountryId = cdrEnrichmentHelper.getOriginCountryId(commLocation);

        Optional<PbxSpecialRule> pbxRuleOpt = specialRuleLookupService.findPbxSpecialRule(
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
        callBuilder.dial(numberAfterPbxRule); // Set dial to number after PBX rule for now

        CdrProcessingConfig.ExtensionLengthConfig extConfig = configService.getExtensionLengthConfig(commLocation.getId());
        String cleanedNumberForInternalCheck = cdrNumberProcessingService.cleanNumber(numberAfterPbxRule, Collections.emptyList(), false, extConfig);
        if (cdrEnrichmentHelper.isInternalCall(effectiveCallingNumber, cleanedNumberForInternalCheck, extConfig)) {
            log.debug("Processing as internal call (caller: {}, effective dialed for internal: {})", effectiveCallingNumber, cleanedNumberForInternalCheck);
            processInternalCall(standardDto, commLocation, callBuilder, cleanedNumberForInternalCheck, extConfig);
            return;
        }

        Long indicatorIdForSpecial = commLocation.getIndicatorId();
        if (indicatorIdForSpecial != null && originCountryId != null) {
            String numberForSpecialCheck = cdrNumberProcessingService.cleanNumber(numberAfterPbxRule, pbxPrefixes, true, extConfig);
            Optional<SpecialService> specialServiceOpt = specialRuleLookupService.findSpecialService(numberForSpecialCheck, indicatorIdForSpecial, originCountryId);
            if (specialServiceOpt.isPresent()) {
                SpecialService specialService = specialServiceOpt.get();
                log.debug("Call matches Special Service: {}", specialService.getId());
                cdrPricingService.applySpecialServicePricing(specialService, callBuilder, standardDto.getDurationSeconds());
                callBuilder.dial(numberForSpecialCheck);
                return;
            }
        }

        CdrNumberProcessingService.FieldWrapper<Long> forcedTelephonyType = new CdrNumberProcessingService.FieldWrapper<>(null);
        String preprocessedNumber = cdrNumberProcessingService.preprocessNumberForLookup(numberAfterPbxRule, originCountryId, forcedTelephonyType, commLocation);
        if (!preprocessedNumber.equals(numberAfterPbxRule)) {
            log.debug("Number preprocessed for lookup: {} -> {}", numberAfterPbxRule, preprocessedNumber);
        }
        callBuilder.dial(preprocessedNumber);

        log.debug("Processing as external outgoing call (caller: {}, final number for lookup: {})", effectiveCallingNumber, preprocessedNumber);
        evaluateDestinationAndRate(standardDto, commLocation, callBuilder, preprocessedNumber, pbxPrefixes, forcedTelephonyType.getValue());
    }


    private void processIncomingCall(StandardizedCallEventDto standardDto, CommunicationLocation commLocation, CallRecord.CallRecordBuilder callBuilder, String originalIncomingNumber) {
        log.debug("Processing incoming call for CDR {} (original caller ID: {})", standardDto.getGlobalCallId(), originalIncomingNumber);

        Long originCountryId = cdrEnrichmentHelper.getOriginCountryId(commLocation);
        Long commIndicatorId = commLocation.getIndicatorId();
        CdrProcessingConfig.ExtensionLengthConfig extConfig = configService.getExtensionLengthConfig(commLocation.getId());

        Optional<PbxSpecialRule> pbxRuleOpt = specialRuleLookupService.findPbxSpecialRule(
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
        callBuilder.dial(cdrNumberProcessingService.cleanNumber(numberAfterPbxRule, Collections.emptyList(), false, extConfig));

        CdrNumberProcessingService.FieldWrapper<Long> forcedTelephonyType = new CdrNumberProcessingService.FieldWrapper<>(null);
        String preprocessedNumber = cdrNumberProcessingService.preprocessNumberForLookup(numberAfterPbxRule, originCountryId, forcedTelephonyType, commLocation);
        if (!preprocessedNumber.equals(numberAfterPbxRule)) {
            log.debug("Incoming number preprocessed for lookup: {} -> {}", numberAfterPbxRule, preprocessedNumber);
            callBuilder.dial(cdrNumberProcessingService.cleanNumber(preprocessedNumber, Collections.emptyList(), false, extConfig));
        }

        if (originCountryId != null && commIndicatorId != null) {
            // For incoming calls, isTrunkCall is false, allowedTelephonyTypeIdsForTrunk is null.
            // The forcedTelephonyType from preprocessing is passed.
            List<Map<String, Object>> prefixes = prefixInfoLookupService.findMatchingPrefixes(
                    preprocessedNumber, originCountryId, false, null, forcedTelephonyType.getValue(), commLocation
            );

            Optional<Map<String, Object>> bestPrefix = prefixes.stream().findFirst();

            if (bestPrefix.isPresent()) {
                Map<String, Object> prefixInfo = bestPrefix.get();
                Long telephonyTypeId = (Long) prefixInfo.get("telephony_type_id");
                Long operatorId = (Long) prefixInfo.get("operator_id");

                callBuilder.telephonyTypeId(telephonyTypeId);
                callBuilder.operatorId(operatorId);
                callBuilder.indicatorId(commIndicatorId);

                log.debug("Incoming call origin classified as Type ID: {}, Operator ID: {}. Destination Indicator (CommLocation): {}",
                        telephonyTypeId, operatorId, commIndicatorId);

            } else {
                log.warn("Could not classify incoming call origin for {}, assuming LOCAL", preprocessedNumber);
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
        Optional<Employee> destEmployeeOpt = employeeLookupService.findEmployeeByExtensionOrAuthCode(destinationExtension, null, commLocation.getId());

        CdrEnrichmentHelper.LocationInfo sourceLoc = cdrEnrichmentHelper.getLocationInfo(sourceEmployeeOpt.orElse(null), commLocation);
        CdrEnrichmentHelper.LocationInfo destLoc = cdrEnrichmentHelper.getLocationInfo(destEmployeeOpt.orElse(null), commLocation);

        Long internalCallTypeId = null;
        Long operatorId = null;
        Long indicatorId = null;

        if (destEmployeeOpt.isPresent()) {
            callBuilder.destinationEmployeeId(destEmployeeOpt.get().getId());
            callBuilder.destinationEmployee(destEmployeeOpt.get());
            indicatorId = destLoc.indicatorId();

            if (sourceLoc.originCountryId() != null && destLoc.originCountryId() != null && !sourceLoc.originCountryId().equals(destLoc.originCountryId())) {
                internalCallTypeId = CdrProcessingConfig.TIPOTELE_INTERNACIONAL_IP;
            } else if (sourceLoc.indicatorId() != null && destLoc.indicatorId() != null && !sourceLoc.indicatorId().equals(destLoc.indicatorId())) {
                internalCallTypeId = CdrProcessingConfig.TIPOTELE_NACIONAL_IP;
            } else if (sourceLoc.officeId() != null && destLoc.officeId() != null && !sourceLoc.officeId().equals(destLoc.officeId())) {
                internalCallTypeId = CdrProcessingConfig.TIPOTELE_LOCAL_IP;
            } else {
                internalCallTypeId = CdrProcessingConfig.TIPOTELE_INTERNA_IP;
            }
            log.debug("Internal call type determined by location comparison: {}", internalCallTypeId);

        } else {
            log.warn("Internal call destination extension {} not found as employee.", destinationExtension);
            Optional<Map<String, Object>> internalPrefixOpt = prefixInfoLookupService.findInternalPrefixMatch(destinationExtension, sourceLoc.originCountryId());
            if (internalPrefixOpt.isPresent()) {
                internalCallTypeId = (Long) internalPrefixOpt.get().get("telephony_type_id");
                operatorId = (Long) internalPrefixOpt.get().get("operator_id");
                indicatorId = sourceLoc.indicatorId();
                log.debug("Destination {} matched internal prefix for type {}, Op {}, using source Ind {}", destinationExtension, internalCallTypeId, operatorId, indicatorId);
            } else {
                internalCallTypeId = CdrProcessingConfig.getDefaultInternalCallTypeId();
                indicatorId = sourceLoc.indicatorId();
                log.debug("Destination {} not found and no internal prefix matched, defaulting to type {}, using source Ind {}", destinationExtension, internalCallTypeId, indicatorId);
            }
        }

        callBuilder.telephonyTypeId(internalCallTypeId);
        callBuilder.indicatorId(indicatorId != null && indicatorId > 0 ? indicatorId : null);

        if (operatorId == null && internalCallTypeId != null) {
            configService.getOperatorInternal(internalCallTypeId, sourceLoc.originCountryId())
                    .ifPresent(op -> callBuilder.operatorId(op.getId()));
        } else if (operatorId != null) {
            callBuilder.operatorId(operatorId);
        }

        cdrPricingService.applyInternalPricing(internalCallTypeId, callBuilder, standardDto.getDurationSeconds());
    }

    private void evaluateDestinationAndRate(StandardizedCallEventDto standardDto, CommunicationLocation commLocation, CallRecord.CallRecordBuilder callBuilder, String numberAfterPbxAndPreprocessing, List<String> pbxPrefixes, Long forcedTelephonyTypeId) {
        Optional<Trunk> trunkOpt = trunkLookupService.findTrunkByCode(standardDto.getDestinationTrunkIdentifier(), commLocation.getId());
        boolean usesTrunk = trunkOpt.isPresent();
        Long originIndicatorId = commLocation.getIndicatorId();
        Long originCountryId = cdrEnrichmentHelper.getOriginCountryId(commLocation);

        String numberForAttempt;
        CdrProcessingConfig.ExtensionLengthConfig extConfig = configService.getExtensionLengthConfig(commLocation.getId());

        if (usesTrunk && trunkOpt.get().getNoPbxPrefix() != null && !trunkOpt.get().getNoPbxPrefix()) {
            numberForAttempt = numberAfterPbxAndPreprocessing;
            log.trace("Trunk call and trunk does NOT specify 'no PBX prefix'. Using number for lookup: {}", numberForAttempt);
        } else {
            numberForAttempt = cdrNumberProcessingService.cleanNumber(numberAfterPbxAndPreprocessing, pbxPrefixes, true, extConfig);
            log.trace("Non-trunk call or trunk specifies 'no PBX prefix'. Cleaned number for lookup: {}", numberForAttempt);
        }

        Set<Long> allowedTypesForTrunk = null;
        if (usesTrunk) {
            allowedTypesForTrunk = trunkLookupService.getAllowedTelephonyTypesForTrunk(trunkOpt.get().getId());
        }

        List<Map<String, Object>> prefixes = prefixInfoLookupService.findMatchingPrefixes(
                numberForAttempt,
                originCountryId,
                usesTrunk,
                allowedTypesForTrunk,
                forcedTelephonyTypeId,
                commLocation
        );

        Optional<Map<String, Object>> rateLookupResultOpt = findBestRateFromPrefixes(
                standardDto, commLocation, callBuilder, numberForAttempt, prefixes, trunkOpt, originIndicatorId
        );


        if (usesTrunk && rateLookupResultOpt.map(r -> cdrEnrichmentHelper.isRateAssumedOrError(r, (Long)r.get("telephony_type_id"))).orElse(true)) {
            log.warn("Initial rate lookup for trunk call {} resulted in assumed/error rate or no rate. Attempting fallback as non-trunk.", standardDto.getGlobalCallId());
            String nonTrunkNumberForAttempt = cdrNumberProcessingService.cleanNumber(numberAfterPbxAndPreprocessing, pbxPrefixes, true, extConfig);

            List<Map<String, Object>> nonTrunkPrefixes = prefixInfoLookupService.findMatchingPrefixes(
                    nonTrunkNumberForAttempt,
                    originCountryId,
                    false, // Not a trunk call for this attempt
                    null,  // No allowed types
                    forcedTelephonyTypeId, // Keep forced type if any
                    commLocation
            );
            Optional<Map<String, Object>> fallbackRateOpt = findBestRateFromPrefixes(
                    standardDto, commLocation, callBuilder, nonTrunkNumberForAttempt, nonTrunkPrefixes, Optional.empty(), originIndicatorId
            );


            if (fallbackRateOpt.isPresent() && !cdrEnrichmentHelper.isRateAssumedOrError(fallbackRateOpt.get(), (Long)fallbackRateOpt.get().get("telephony_type_id"))) {
                rateLookupResultOpt = fallbackRateOpt;
                log.debug("Fallback (non-trunk) lookup provided a better rate for {}.", standardDto.getGlobalCallId());
            } else {
                log.debug("Fallback (non-trunk) lookup did not provide a better rate for {}.", standardDto.getGlobalCallId());
            }
        }


        if (rateLookupResultOpt.isPresent()) {
            Map<String, Object> currentRateInfo = new HashMap<>(rateLookupResultOpt.get());
            callBuilder.telephonyTypeId((Long) currentRateInfo.get("telephony_type_id"));
            callBuilder.operatorId((Long) currentRateInfo.get("operator_id"));
            callBuilder.indicatorId((Long) currentRateInfo.get("indicator_id"));
            callBuilder.dial((String) currentRateInfo.getOrDefault("effective_number", numberForAttempt));

            cdrPricingService.applySpecialPricing(currentRateInfo, standardDto.getCallStartTime(), standardDto.getDurationSeconds(), originIndicatorId, callBuilder);

            if (usesTrunk && !(Boolean)currentRateInfo.getOrDefault("applied_trunk_pricing", false)) {
                cdrPricingService.applyTrunkRuleOverrides(trunkOpt.get(), currentRateInfo, standardDto.getDurationSeconds(), originIndicatorId, callBuilder);
            }

            cdrPricingService.applyFinalPricing(currentRateInfo, standardDto.getDurationSeconds(), callBuilder);

        } else {
            log.error("Could not determine rate for number: {} (effective for attempt: {}) after all fallbacks.", standardDto.getCalledPartyNumber(), numberForAttempt);
            callBuilder.telephonyTypeId(CdrProcessingConfig.TIPOTELE_ERRORES);
            callBuilder.dial(numberForAttempt);
            callBuilder.indicatorId(null);
            callBuilder.billedAmount(BigDecimal.ZERO);
            callBuilder.pricePerMinute(BigDecimal.ZERO);
            callBuilder.initialPrice(BigDecimal.ZERO);
        }
    }

    private Optional<Map<String, Object>> findBestRateFromPrefixes(
            StandardizedCallEventDto standardDto, CommunicationLocation commLocation,
            CallRecord.CallRecordBuilder callBuilder, String numberForLookup,
            List<Map<String, Object>> prefixes, Optional<Trunk> trunkOpt,
            Long originCommLocationIndicatorId) {

        Long originCountryId = cdrEnrichmentHelper.getOriginCountryId(commLocation);
        boolean usesTrunk = trunkOpt.isPresent();
        Trunk trunk = trunkOpt.orElse(null);
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

            String numberWithoutOperatorPrefix = numberForLookup;
            boolean operatorPrefixRemoved = false;
            boolean removeOperatorPrefixThisIteration = true;

            if (usesTrunk && trunk != null) {
                Long operatorForTrunkRate = cdrEnrichmentHelper.findEffectiveTrunkOperator(trunk, currentTelephonyTypeId, currentPrefixCode, currentOperatorId, originCountryId);
                if (operatorForTrunkRate != null && operatorForTrunkRate >= 0) {
                    Optional<TrunkRate> trOpt = trunkLookupService.findTrunkRate(trunk.getId(), operatorForTrunkRate, currentTelephonyTypeId);
                    if (trOpt.isPresent() && trOpt.get().getNoPrefix() != null) {
                        removeOperatorPrefixThisIteration = !trOpt.get().getNoPrefix();
                    }
                }
            }

            if (StringUtils.hasText(currentPrefixCode) && numberForLookup.startsWith(currentPrefixCode)) {
                if (removeOperatorPrefixThisIteration && currentTelephonyTypeId != CdrProcessingConfig.TIPOTELE_LOCAL) {
                    numberWithoutOperatorPrefix = numberForLookup.substring(currentPrefixCode.length());
                    operatorPrefixRemoved = true;
                }
            }
            if (numberWithoutOperatorPrefix.isEmpty() && StringUtils.hasText(numberForLookup) && operatorPrefixRemoved) {
                continue;
            }

            int operatorPrefixLength = (currentPrefixCode != null ? currentPrefixCode.length() : 0);
            int effectiveMinLength = operatorPrefixRemoved ? Math.max(0, typeMinLength - operatorPrefixLength) : typeMinLength;
            int effectiveMaxLength = operatorPrefixRemoved ? Math.max(0, typeMaxLength - operatorPrefixLength) : typeMaxLength;


            if (numberWithoutOperatorPrefix.length() < effectiveMinLength) continue;
            if (effectiveMaxLength > 0 && numberWithoutOperatorPrefix.length() > effectiveMaxLength) {
                numberWithoutOperatorPrefix = numberWithoutOperatorPrefix.substring(0, effectiveMaxLength);
            }

            Optional<Map<String, Object>> indicatorInfoOpt = prefixInfoLookupService.findIndicatorByNumber(
                    numberWithoutOperatorPrefix, currentTelephonyTypeId, originCountryId,
                    currentPrefixBandOk, currentPrefixId, originCommLocationIndicatorId
            );

            boolean destinationFound = indicatorInfoOpt.isPresent();
            boolean lengthMatchOnly = (cdrEnrichmentHelper.maxNdcLength(currentTelephonyTypeId, originCountryId) == 0 &&
                    numberForLookup.length() == typeMaxLength &&
                    !destinationFound &&
                    prefixes.size() == 1);
            boolean considerMatch = destinationFound || lengthMatchOnly;

            if (considerMatch) {
                Long destinationIndicatorId = indicatorInfoOpt.map(ind -> (Long) ind.get("indicator_id")).filter(id -> id != null && id > 0).orElse(null);
                Integer destinationNdc = indicatorInfoOpt.map(ind -> (ind.get("series_ndc") instanceof Number ? ((Number)ind.get("series_ndc")).intValue() : (ind.get("series_ndc") != null ? Integer.parseInt(String.valueOf(ind.get("series_ndc"))) : null))).orElse(null);

                Long finalTelephonyTypeId = currentTelephonyTypeId;
                Long finalPrefixId = currentPrefixId;
                boolean finalBandOk = currentPrefixBandOk;
                String finalTelephonyTypeName = (String) prefixInfo.get("telephony_type_name");

                if (currentTelephonyTypeId == CdrProcessingConfig.TIPOTELE_LOCAL && destinationNdc != null) {
                    if (prefixInfoLookupService.isLocalExtended(destinationNdc, originCommLocationIndicatorId)) {
                        finalTelephonyTypeId = CdrProcessingConfig.TIPOTELE_LOCAL_EXT;
                        finalTelephonyTypeName = configService.getTelephonyTypeById(finalTelephonyTypeId).map(TelephonyType::getName).orElse("Local Extended");
                        Optional<Operator> localExtOpOpt = configService.getOperatorInternal(finalTelephonyTypeId, originCountryId);
                        if (localExtOpOpt.isPresent()) {
                            Optional<Prefix> localExtPrefixOpt = prefixInfoLookupService.findPrefixByTypeOperatorOrigin(finalTelephonyTypeId, localExtOpOpt.get().getId(), originCountryId);
                            if (localExtPrefixOpt.isPresent()) {
                                finalPrefixId = localExtPrefixOpt.get().getId();
                                finalBandOk = localExtPrefixOpt.get().isBandOk();
                            }
                        }
                    }
                }

                Optional<Map<String, Object>> rateInfoOpt = cdrPricingService.findRateInfo(finalPrefixId, destinationIndicatorId, originCommLocationIndicatorId, finalBandOk);

                if (rateInfoOpt.isPresent()) {
                    Map<String, Object> rateInfo = rateInfoOpt.get();
                    rateInfo.put("telephony_type_id", finalTelephonyTypeId);
                    rateInfo.put("operator_id", currentOperatorId);
                    rateInfo.put("indicator_id", destinationIndicatorId);
                    rateInfo.put("telephony_type_name", finalTelephonyTypeName);
                    rateInfo.put("operator_name", (String) prefixInfo.get("operator_name"));
                    rateInfo.put("destination_name", indicatorInfoOpt.map(cdrEnrichmentHelper::formatDestinationName).orElse(lengthMatchOnly ? "Unknown (Length Match)" : "Unknown Destination"));
                    rateInfo.put("effective_number", numberForLookup);
                    rateInfo.put("applied_trunk_pricing", false);

                    if (usesTrunk && trunk != null) {
                        Long operatorForTrunkRate = cdrEnrichmentHelper.findEffectiveTrunkOperator(trunk, finalTelephonyTypeId, currentPrefixCode, currentOperatorId, originCountryId);
                        if (operatorForTrunkRate != null && operatorForTrunkRate >=0) {
                            Optional<TrunkRate> trOpt = trunkLookupService.findTrunkRate(trunk.getId(), operatorForTrunkRate, finalTelephonyTypeId);
                            if (trOpt.isPresent()) {
                                TrunkRate tr = trOpt.get();
                                rateInfo.put("valor_minuto", Optional.ofNullable(tr.getRateValue()).orElse(BigDecimal.ZERO));
                                rateInfo.put("valor_minuto_iva", Optional.ofNullable(tr.getIncludesVat()).orElse(false));
                                rateInfo.put("ensegundos", tr.getSeconds() != null && tr.getSeconds() > 0);
                                rateInfo.put("valor_inicial", BigDecimal.ZERO);
                                rateInfo.put("valor_inicial_iva", false);
                                prefixInfoLookupService.findPrefixByTypeOperatorOrigin(finalTelephonyTypeId, operatorForTrunkRate, originCountryId)
                                        .ifPresent(p -> rateInfo.put("iva", p.getVatValue()));
                                rateInfo.put("applied_trunk_pricing", true);
                            }
                        }
                    }
                    return Optional.of(rateInfo);
                } else {
                    if (assumedRateInfo == null && allPrefixesConsistent) {
                        assumedRateInfo = new HashMap<>();
                        assumedRateInfo.put("telephony_type_id", finalTelephonyTypeId);
                        assumedRateInfo.put("operator_id", currentOperatorId);
                        assumedRateInfo.put("indicator_id", null);
                        assumedRateInfo.put("telephony_type_name", finalTelephonyTypeName + ASSUMED_SUFFIX);
                        assumedRateInfo.put("operator_name", (String) prefixInfo.get("operator_name"));
                        assumedRateInfo.put("destination_name", "Assumed Destination");
                        assumedRateInfo.put("effective_number", numberForLookup);
                        assumedRateInfo.put("applied_trunk_pricing", false);
                        Optional<Map<String, Object>> baseRateOpt = prefixInfoLookupService.findBaseRateForPrefix(finalPrefixId);
                        baseRateOpt.ifPresent(assumedRateInfo::putAll);
                        assumedRateInfo.putIfAbsent("valor_minuto", assumedRateInfo.get("base_value"));
                        assumedRateInfo.putIfAbsent("valor_minuto_iva", assumedRateInfo.get("vat_included"));
                        assumedRateInfo.putIfAbsent("iva", assumedRateInfo.get("vat_value"));
                    }
                }
            }
        }

        if (assumedRateInfo != null && allPrefixesConsistent) {
            return Optional.of(assumedRateInfo);
        }
        return Optional.empty();
    }

    private void linkAssociatedEntities(CallRecord.CallRecordBuilder callBuilder) {
        CallRecord record = callBuilder.build();

        if (record.getTelephonyTypeId() != null && record.getTelephonyType() == null) {
            configService.getTelephonyTypeById(record.getTelephonyTypeId())
                    .ifPresentOrElse(callBuilder::telephonyType, () -> log.warn("Could not link TelephonyType entity for ID: {}", record.getTelephonyTypeId()));
        }
        if (record.getOperatorId() != null && record.getOperator() == null) {
            configService.getOperatorById(record.getOperatorId())
                    .ifPresentOrElse(callBuilder::operator, () -> log.warn("Could not link Operator entity for ID: {}", record.getOperatorId()));
        }
        if (record.getIndicatorId() != null && record.getIndicator() == null) {
            entityLookupService.findIndicatorById(record.getIndicatorId())
                    .ifPresentOrElse(callBuilder::indicator, () -> log.warn("Could not link Indicator entity for ID: {}", record.getIndicatorId()));
        } else if (record.getIndicatorId() == null) {
            log.trace("Indicator ID is null, skipping entity linking.");
        }
        if (record.getDestinationEmployeeId() != null && record.getDestinationEmployee() == null) {
            employeeLookupService.findEmployeeById(record.getDestinationEmployeeId())
                    .ifPresentOrElse(callBuilder::destinationEmployee, () -> log.warn("Could not link destination employee entity for ID: {}", record.getDestinationEmployeeId()));
        }
    }
}