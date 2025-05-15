package com.infomedia.abacox.telephonypricing.cdr;

import com.infomedia.abacox.telephonypricing.entity.*;
import lombok.RequiredArgsConstructor;
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
            callBuilder.indicatorId(null);
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
            } else if (StringUtils.hasText(standardDto.getAuthCode())) {
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
            } else {
                callBuilder.assignmentCause(CallAssignmentCause.EXTENSION.getValue());
            }
        });

        if (employeeOpt.isEmpty() || (StringUtils.hasText(standardDto.getAuthCode()) && configService.getIgnoredAuthCodes().contains(standardDto.getAuthCode()) && callBuilder.build().getEmployeeId() == null) ) {
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
        // For internal check, clean the number *after* PBX rule, but *without* PBX prefix removal yet, as internal numbers don't use PBX prefixes.
        String cleanedNumberForInternalCheck = cdrNumberProcessingService.cleanNumber(numberAfterPbxRule, Collections.emptyList(), false, extConfig);
        if (cdrEnrichmentHelper.isInternalCall(effectiveCallingNumber, cleanedNumberForInternalCheck, extConfig)) {
            log.debug("Processing as internal call (caller: {}, effective dialed for internal: {})", effectiveCallingNumber, cleanedNumberForInternalCheck);
            processInternalCall(standardDto, commLocation, callBuilder, cleanedNumberForInternalCheck, extConfig);
            return;
        }

        Long indicatorIdForSpecial = commLocation.getIndicatorId();
        if (indicatorIdForSpecial != null && originCountryId != null) {
            // For special service check, clean the number *after* PBX rule, and *with* PBX prefix removal,
            // as special numbers are typically dialed with an access code (PBX prefix).
            String numberForSpecialCheck = cdrNumberProcessingService.cleanNumber(numberAfterPbxRule, pbxPrefixes, true, extConfig);
            Optional<SpecialService> specialServiceOpt = specialRuleLookupService.findSpecialService(numberForSpecialCheck, indicatorIdForSpecial, originCountryId);
            if (specialServiceOpt.isPresent()) {
                SpecialService specialService = specialServiceOpt.get();
                log.debug("Call matches Special Service: {}", specialService.getId());
                cdrPricingService.applySpecialServicePricing(specialService, callBuilder, standardDto.getDurationSeconds());
                callBuilder.dial(numberForSpecialCheck); // Update dial to the number used for special service lookup
                return;
            }
        }

        CdrNumberProcessingService.FieldWrapper<Long> forcedTelephonyType = new CdrNumberProcessingService.FieldWrapper<>(null);
        String preprocessedNumber = cdrNumberProcessingService.preprocessNumberForLookup(numberAfterPbxRule, originCountryId, forcedTelephonyType, commLocation);
        if (!preprocessedNumber.equals(numberAfterPbxRule)) {
            log.debug("Number preprocessed for lookup: {} -> {}", numberAfterPbxRule, preprocessedNumber);
        }
        callBuilder.dial(preprocessedNumber); // Update dial to the number after preprocessing

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
        // For incoming, the 'dial' field should represent the cleaned originating number
        callBuilder.dial(cdrNumberProcessingService.cleanNumber(numberAfterPbxRule, Collections.emptyList(), false, extConfig));

        CdrNumberProcessingService.FieldWrapper<Long> forcedTelephonyType = new CdrNumberProcessingService.FieldWrapper<>(null);
        String preprocessedNumber = cdrNumberProcessingService.preprocessNumberForLookup(numberAfterPbxRule, originCountryId, forcedTelephonyType, commLocation);
        if (!preprocessedNumber.equals(numberAfterPbxRule)) {
            log.debug("Incoming number preprocessed for lookup: {} -> {}", numberAfterPbxRule, preprocessedNumber);
            callBuilder.dial(cdrNumberProcessingService.cleanNumber(preprocessedNumber, Collections.emptyList(), false, extConfig));
        }

        if (originCountryId != null && commIndicatorId != null) {
            List<Map<String, Object>> prefixes = prefixInfoLookupService.findPrefixesByNumber(preprocessedNumber, originCountryId);

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

                callBuilder.telephonyTypeId(telephonyTypeId);
                callBuilder.operatorId(operatorId);
                // For incoming calls, the indicator is the one of the communication location (destination)
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
                indicatorId = sourceLoc.indicatorId(); // Use source indicator if dest is unknown but matches internal prefix
                log.debug("Destination {} matched internal prefix for type {}, Op {}, using source Ind {}", destinationExtension, internalCallTypeId, operatorId, indicatorId);
            } else {
                internalCallTypeId = CdrProcessingConfig.getDefaultInternalCallTypeId();
                indicatorId = sourceLoc.indicatorId(); // Use source indicator if dest is unknown and no prefix match
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

        String numberForAttempt;
        CdrProcessingConfig.ExtensionLengthConfig extConfig = configService.getExtensionLengthConfig(commLocation.getId());

        // Determine the number to use for the first lookup attempt
        if (usesTrunk && trunkOpt.get().getNoPbxPrefix() != null && !trunkOpt.get().getNoPbxPrefix()) {
            // Trunk exists and explicitly states PBX prefix IS used (noPbxPrefix = false)
            // So, use the number as it is after PBX rule application and preprocessing
            numberForAttempt = numberAfterPbxAndPreprocessing;
            log.trace("Trunk call and trunk does NOT specify 'no PBX prefix'. Using number for lookup: {}", numberForAttempt);
        } else {
            // Either not a trunk call, or trunk specifies 'no PBX prefix' (noPbxPrefix = true or null)
            // Clean the number by removing PBX prefixes
            numberForAttempt = cdrNumberProcessingService.cleanNumber(numberAfterPbxAndPreprocessing, pbxPrefixes, true, extConfig);
            log.trace("Non-trunk call or trunk specifies 'no PBX prefix'. Cleaned number for lookup: {}", numberForAttempt);
        }

        Optional<Map<String, Object>> rateLookupResultOpt = attemptRateLookup(standardDto, commLocation, callBuilder, numberForAttempt, pbxPrefixes, trunkOpt, forcedTelephonyTypeId, originIndicatorId);

        // Fallback logic: if trunk call failed or resulted in assumed/error, try as non-trunk
        // PHP: if ($existe_troncal !== false && evaluarDestino_novalido($infovalor))
        if (usesTrunk && rateLookupResultOpt.map(r -> cdrEnrichmentHelper.isRateAssumedOrError(r, (Long)r.get("telephony_type_id"))).orElse(true)) {
            log.warn("Initial rate lookup for trunk call {} resulted in assumed/error rate or no rate. Attempting fallback as non-trunk.", standardDto.getGlobalCallId());
            // For non-trunk fallback, always clean PBX prefixes
            String nonTrunkNumberForAttempt = cdrNumberProcessingService.cleanNumber(numberAfterPbxAndPreprocessing, pbxPrefixes, true, extConfig);
            Optional<Map<String, Object>> fallbackRateOpt = attemptRateLookup(standardDto, commLocation, callBuilder, nonTrunkNumberForAttempt, pbxPrefixes, Optional.empty(), forcedTelephonyTypeId, originIndicatorId);

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

            // Apply special pricing first, as it might override base rates
            cdrPricingService.applySpecialPricing(currentRateInfo, standardDto.getCallStartTime(), standardDto.getDurationSeconds(), originIndicatorId, callBuilder);

            // If it was a trunk call and trunk pricing wasn't already applied by `attemptRateLookup`
            // or if a trunk rule needs to be evaluated on top of special pricing.
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

    private Optional<Map<String, Object>> attemptRateLookup(
            StandardizedCallEventDto standardDto, CommunicationLocation commLocation,
            CallRecord.CallRecordBuilder callBuilder, String numberForLookup,
            List<String> pbxPrefixes, Optional<Trunk> trunkOpt, Long forcedTelephonyTypeId,
            Long originCommLocationIndicatorId) {

        Long originCountryId = cdrEnrichmentHelper.getOriginCountryId(commLocation);
        boolean usesTrunk = trunkOpt.isPresent();
        Trunk trunk = trunkOpt.orElse(null);

        if (originCountryId == null || originCommLocationIndicatorId == null) {
            log.error("Cannot attempt rate lookup: Missing Origin Country ({}) or Comm Location Indicator ID ({}) for Location {}",
                    originCountryId, originCommLocationIndicatorId, commLocation.getId());
            return Optional.empty();
        }

        String effectiveNumberForPrefixMatching = numberForLookup; // Start with the number passed in
        CdrProcessingConfig.ExtensionLengthConfig extConfig = configService.getExtensionLengthConfig(commLocation.getId());

        // PHP: if ($existe_troncal === false) { $telefono = $info_destino_limpio; }
        // If not using a trunk, the number should already be cleaned of PBX prefixes for prefix matching.
        // If using a trunk, the `noPbxPrefix` flag determines if PBX prefix was removed *before* this method.
        // Here, we ensure that if `noPbxPrefix` is true for the trunk, we use a number cleaned of PBX prefixes.
        if (usesTrunk && trunk != null && trunk.getNoPbxPrefix() != null && trunk.getNoPbxPrefix()) {
            String cleanedForTrunkNoPbx = cdrNumberProcessingService.cleanNumber(numberForLookup, pbxPrefixes, true, extConfig);
            if (!cleanedForTrunkNoPbx.equals(effectiveNumberForPrefixMatching)) {
                log.trace("Trunk {} has noPbxPrefix=true. Using cleaned number for prefix search: {} -> {}", trunk.getId(), effectiveNumberForPrefixMatching, cleanedForTrunkNoPbx);
                effectiveNumberForPrefixMatching = cleanedForTrunkNoPbx;
            }
        } else if (!usesTrunk) { // If not a trunk call, ensure PBX prefixes are removed for prefix matching
             String cleanedForNonTrunk = cdrNumberProcessingService.cleanNumber(numberForLookup, pbxPrefixes, true, extConfig);
             if (!cleanedForNonTrunk.equals(effectiveNumberForPrefixMatching)) {
                log.trace("Non-trunk call. Using cleaned number for prefix search: {} -> {}", effectiveNumberForPrefixMatching, cleanedForNonTrunk);
                effectiveNumberForPrefixMatching = cleanedForNonTrunk;
            }
        }


        if (effectiveNumberForPrefixMatching.isEmpty() && StringUtils.hasText(numberForLookup)) {
            log.warn("Number {} became empty after cleaning for prefix matching. Aborting rate lookup for this path.", numberForLookup);
            return Optional.empty();
        }


        List<Map<String, Object>> prefixes = prefixInfoLookupService.findPrefixesByNumber(effectiveNumberForPrefixMatching, originCountryId);

        if (forcedTelephonyTypeId != null) {
            prefixes = prefixes.stream()
                    .filter(p -> forcedTelephonyTypeId.equals(p.get("telephony_type_id")))
                    .collect(Collectors.toList());
            if (!prefixes.isEmpty()) {
                log.debug("Lookup filtered to forced TelephonyType ID: {}", forcedTelephonyTypeId);
            } else {
                log.warn("Forced TelephonyType ID {} has no matching prefixes for number {}", forcedTelephonyTypeId, effectiveNumberForPrefixMatching);
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

            String numberWithoutOperatorPrefix = effectiveNumberForPrefixMatching;
            boolean operatorPrefixRemoved = false;
            boolean removeOperatorPrefixThisIteration = true; // Default to true

            // PHP: $reducir = false; if (isset($existe_troncal['noprefijo'])) { $reducir = $existe_troncal['noprefijo']; }
            // PHP: if (isset($existe_troncal['operador_destino_tt'][$tipotele_id])) { ... $reducir = $operador_destino['noprefijo']; }
            if (usesTrunk && trunk != null) {
                Long operatorForTrunkRate = cdrEnrichmentHelper.findEffectiveTrunkOperator(trunk, currentTelephonyTypeId, currentPrefixCode, currentOperatorId, originCountryId);
                if (operatorForTrunkRate != null && operatorForTrunkRate >= 0) {
                    Optional<TrunkRate> trOpt = trunkLookupService.findTrunkRate(trunk.getId(), operatorForTrunkRate, currentTelephonyTypeId);
                    if (trOpt.isPresent() && trOpt.get().getNoPrefix() != null) {
                        removeOperatorPrefixThisIteration = !trOpt.get().getNoPrefix(); // noPrefix=true means DON'T remove, so flag becomes false
                        log.trace("TrunkRate for prefix {} (effective op {}) has noPrefix={}. Operator prefix removal for indicator lookup: {}",
                                currentPrefixCode, operatorForTrunkRate, trOpt.get().getNoPrefix(), removeOperatorPrefixThisIteration);
                    }
                }
            }


            if (StringUtils.hasText(currentPrefixCode) && effectiveNumberForPrefixMatching.startsWith(currentPrefixCode)) {
                if (removeOperatorPrefixThisIteration && currentTelephonyTypeId != CdrProcessingConfig.TIPOTELE_LOCAL) {
                    numberWithoutOperatorPrefix = effectiveNumberForPrefixMatching.substring(currentPrefixCode.length());
                    operatorPrefixRemoved = true;
                    log.trace("Operator prefix {} removed for indicator lookup (Type: {}), remaining: {}", currentPrefixCode, currentTelephonyTypeId, numberWithoutOperatorPrefix);
                } else {
                    log.trace("Operator prefix {} not removed for indicator lookup (Type: {}, removeFlag: {})", currentPrefixCode, currentTelephonyTypeId, removeOperatorPrefixThisIteration);
                }
            }
            if (numberWithoutOperatorPrefix.isEmpty() && StringUtils.hasText(effectiveNumberForPrefixMatching) && operatorPrefixRemoved) {
                log.warn("Number {} became empty after operator prefix {} removal. Skipping this prefix.", effectiveNumberForPrefixMatching, currentPrefixCode);
                continue;
            }

            int operatorPrefixLength = (currentPrefixCode != null ? currentPrefixCode.length() : 0);
            // PHP: if (!$reducir) { $tipotele_min -= $len_prefijo; }
            // If operator prefix was NOT removed, then the min/max length for the subscriber part must account for the prefix length.
            // If it WAS removed, then min/max apply directly to numberWithoutOperatorPrefix.
            int effectiveMinLength = operatorPrefixRemoved ? Math.max(0, typeMinLength - operatorPrefixLength) : typeMinLength;
            int effectiveMaxLength = operatorPrefixRemoved ? Math.max(0, typeMaxLength - operatorPrefixLength) : typeMaxLength;


            if (numberWithoutOperatorPrefix.length() < effectiveMinLength) {
                log.trace("Skipping prefix {} - number part {} too short (min {})", currentPrefixCode, numberWithoutOperatorPrefix, effectiveMinLength);
                continue;
            }
            if (effectiveMaxLength > 0 && numberWithoutOperatorPrefix.length() > effectiveMaxLength) {
                log.trace("Trimming number part {} to max length {}", numberWithoutOperatorPrefix, effectiveMaxLength);
                numberWithoutOperatorPrefix = numberWithoutOperatorPrefix.substring(0, effectiveMaxLength);
            }

            Optional<Map<String, Object>> indicatorInfoOpt = prefixInfoLookupService.findIndicatorByNumber(
                    numberWithoutOperatorPrefix, currentTelephonyTypeId, originCountryId,
                    currentPrefixBandOk, currentPrefixId, originCommLocationIndicatorId
            );

            boolean destinationFound = indicatorInfoOpt.isPresent();
            // PHP: ($arr_destino_pre['INDICA_MAX'] <= 0 && strlen($info_destino) == $tipotelemax)
            // This means if no specific indicator series matched, but the number length perfectly matches the type's max length,
            // AND there are no NDC definitions for this type (maxNdcLength=0), consider it a match for rating purposes (assumed destination).
            // Also, this should only apply if it's the only prefix candidate.
            boolean lengthMatchOnly = (cdrEnrichmentHelper.maxNdcLength(currentTelephonyTypeId, originCountryId) == 0 &&
                    effectiveNumberForPrefixMatching.length() == typeMaxLength &&
                    !destinationFound &&
                    prefixes.size() == 1);

            boolean considerMatch = destinationFound || lengthMatchOnly;

            if (considerMatch) {
                Long destinationIndicatorId = indicatorInfoOpt
                        .map(ind -> (Long) ind.get("indicator_id"))
                        .filter(id -> id != null && id > 0)
                        .orElse(null);
                Integer destinationNdc = indicatorInfoOpt.map(ind -> (ind.get("series_ndc") instanceof Number ? ((Number)ind.get("series_ndc")).intValue() : (ind.get("series_ndc") != null ? Integer.parseInt(String.valueOf(ind.get("series_ndc"))) : null))).orElse(null);


                Long finalTelephonyTypeId = currentTelephonyTypeId;
                Long finalPrefixId = currentPrefixId;
                boolean finalBandOk = currentPrefixBandOk;
                String finalTelephonyTypeName = (String) prefixInfo.get("telephony_type_name");

                if (currentTelephonyTypeId == CdrProcessingConfig.TIPOTELE_LOCAL && destinationNdc != null) {
                    boolean isExtended = prefixInfoLookupService.isLocalExtended(destinationNdc, originCommLocationIndicatorId);
                    if (isExtended) {
                        finalTelephonyTypeId = CdrProcessingConfig.TIPOTELE_LOCAL_EXT;
                        finalTelephonyTypeName = configService.getTelephonyTypeById(finalTelephonyTypeId).map(TelephonyType::getName).orElse("Local Extended");
                        log.debug("Reclassified call to {} as LOCAL_EXTENDED based on NDC {} and origin {}", effectiveNumberForPrefixMatching, destinationNdc, originCommLocationIndicatorId);

                        Optional<Operator> localExtOpOpt = configService.getOperatorInternal(finalTelephonyTypeId, originCountryId);
                        if (localExtOpOpt.isPresent()) {
                            Optional<Prefix> localExtPrefixOpt = prefixInfoLookupService.findPrefixByTypeOperatorOrigin(finalTelephonyTypeId, localExtOpOpt.get().getId(), originCountryId);
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

                Optional<Map<String, Object>> rateInfoOpt = cdrPricingService.findRateInfo(finalPrefixId, destinationIndicatorId, originCommLocationIndicatorId, finalBandOk);

                if (rateInfoOpt.isPresent()) {
                    Map<String, Object> rateInfo = rateInfoOpt.get();
                    rateInfo.put("telephony_type_id", finalTelephonyTypeId);
                    rateInfo.put("operator_id", currentOperatorId);
                    rateInfo.put("indicator_id", destinationIndicatorId);
                    rateInfo.put("telephony_type_name", finalTelephonyTypeName);
                    rateInfo.put("operator_name", (String) prefixInfo.get("operator_name"));
                    rateInfo.put("destination_name", indicatorInfoOpt.map(cdrEnrichmentHelper::formatDestinationName).orElse(lengthMatchOnly ? "Unknown (Length Match)" : "Unknown Destination"));
                    rateInfo.put("effective_number", effectiveNumberForPrefixMatching);
                    rateInfo.put("applied_trunk_pricing", false);

                    if (usesTrunk && trunk != null) {
                        Long operatorForTrunkRate = cdrEnrichmentHelper.findEffectiveTrunkOperator(trunk, finalTelephonyTypeId, currentPrefixCode, currentOperatorId, originCountryId);
                        if (operatorForTrunkRate != null && operatorForTrunkRate >=0) {
                            Optional<TrunkRate> trOpt = trunkLookupService.findTrunkRate(trunk.getId(), operatorForTrunkRate, finalTelephonyTypeId);
                            if (trOpt.isPresent()) {
                                TrunkRate tr = trOpt.get();
                                log.debug("Overriding base rate with TrunkRate {} for trunk {}, type {}, op {}", tr.getId(), trunk.getId(), finalTelephonyTypeId, operatorForTrunkRate);
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
                    log.debug("Attempt successful: Found rate for prefix {}, indicator {}", currentPrefixCode, destinationIndicatorId);
                    return Optional.of(rateInfo);
                } else {
                    log.warn("Rate info not found for prefix {}, indicator {}", currentPrefixCode, destinationIndicatorId);
                    if (assumedRateInfo == null && allPrefixesConsistent) { // PHP: if ($arr_destino === false) { ... if ($todosok) { ... } }
                        assumedRateInfo = new HashMap<>();
                        assumedRateInfo.put("telephony_type_id", finalTelephonyTypeId);
                        assumedRateInfo.put("operator_id", currentOperatorId);
                        assumedRateInfo.put("indicator_id", null); // No specific indicator found
                        assumedRateInfo.put("telephony_type_name", finalTelephonyTypeName + ASSUMED_SUFFIX);
                        assumedRateInfo.put("operator_name", (String) prefixInfo.get("operator_name"));
                        assumedRateInfo.put("destination_name", "Assumed Destination");
                        assumedRateInfo.put("effective_number", effectiveNumberForPrefixMatching);
                        assumedRateInfo.put("applied_trunk_pricing", false);
                        // Populate with base prefix rates
                        Optional<Map<String, Object>> baseRateOpt = prefixInfoLookupService.findBaseRateForPrefix(finalPrefixId);
                        baseRateOpt.ifPresent(assumedRateInfo::putAll); // Adds base_value, vat_included, vat_value
                        // Ensure valor_minuto etc. are present from base_value
                        assumedRateInfo.putIfAbsent("valor_minuto", assumedRateInfo.get("base_value"));
                        assumedRateInfo.putIfAbsent("valor_minuto_iva", assumedRateInfo.get("vat_included"));
                        assumedRateInfo.putIfAbsent("iva", assumedRateInfo.get("vat_value"));
                        log.debug("Storing assumed rate info based on consistent prefix {} (Type: {}, Op: {})", currentPrefixCode, finalTelephonyTypeId, currentOperatorId);
                    }
                }
            } else {
                log.trace("No indicator found and length mismatch for prefix {}, number part {}", currentPrefixCode, numberWithoutOperatorPrefix);
            }
        }

        // Fallback to TIPOTELE_LOCAL if no prefixes found and not a trunk call
        // PHP: if ($arr_destino === false) { ... if (!$todosok) { ... $tipotele_id = 0; ... } }
        // PHP: ... then later ... if ($tipotele_id <= 0 && $existe_troncal === false) { ... $tipotele_id = _TIPOTELE_LOCAL; ... }
        if (!usesTrunk && prefixes.isEmpty()) {
            log.debug("No prefix found for non-trunk call, attempting lookup as LOCAL for {}", effectiveNumberForPrefixMatching);
            Optional<Map<String, Object>> localRateInfoOpt = cdrPricingService.findRateInfoForLocal(commLocation, effectiveNumberForPrefixMatching);
            if (localRateInfoOpt.isPresent()) {
                Map<String, Object> localRateInfo = localRateInfoOpt.get();
                localRateInfo.put("effective_number", effectiveNumberForPrefixMatching);
                localRateInfo.put("applied_trunk_pricing", false);
                return localRateInfoOpt;
            } else {
                log.warn("LOCAL fallback failed for number: {}", effectiveNumberForPrefixMatching);
            }
        }

        if (assumedRateInfo != null && allPrefixesConsistent) {
            log.warn("Using assumed rate info for number {} based on consistent prefix type/operator.", effectiveNumberForPrefixMatching);
            return Optional.of(assumedRateInfo);
        }

        log.warn("Attempt failed: No matching rate found for number: {}", effectiveNumberForPrefixMatching);
        return Optional.empty();
    }

    private void linkAssociatedEntities(CallRecord.CallRecordBuilder callBuilder) {
        CallRecord record = callBuilder.build(); // Build once to get current state

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
        // Employee linking is handled where employeeOpt is available
        if (record.getDestinationEmployeeId() != null && record.getDestinationEmployee() == null) {
            employeeLookupService.findEmployeeById(record.getDestinationEmployeeId())
                    .ifPresentOrElse(callBuilder::destinationEmployee, () -> log.warn("Could not link destination employee entity for ID: {}", record.getDestinationEmployeeId()));
        }
    }
}