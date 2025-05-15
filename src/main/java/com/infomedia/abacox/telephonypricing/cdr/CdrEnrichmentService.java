// FILE: com/infomedia/abacox/telephonypricing/cdr/CdrEnrichmentService.java
package com.infomedia.abacox.telephonypricing.cdr;

import com.infomedia.abacox.telephonypricing.entity.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

@Service
@RequiredArgsConstructor
@Log4j2
public class CdrEnrichmentService {

    private final EmployeeLookupService employeeLookupService;
    private final ExtensionLookupService extensionLookupService;
    private final PrefixInfoLookupService prefixInfoLookupService;
    private final TrunkLookupService trunkLookupService;
    private final SpecialRuleLookupService specialRuleLookupService;
    // ConfigurationLookupService is now part of CdrProcessingConfig
    private final EntityLookupService entityLookupService;

    private final CdrPricingService cdrPricingService;
    private final CdrNumberProcessingService cdrNumberProcessingService;
    private final CdrEnrichmentHelper cdrEnrichmentHelper;

    private final CdrProcessingConfig configService;

    private static final String ASSUMED_SUFFIX = " (Assumed)";

    private static class IncomingOriginResult {
        Long telephonyTypeId;
        Long operatorId;
        Long indicatorId;
        String destinationName; // Added to carry the name
    }


    public Optional<CallRecord> enrichCdr(StandardizedCallEventDto standardDto, CommunicationLocation commLocation) {
        log.info("Enriching Standardized CDR: {}, Hash: {}", standardDto.getGlobalCallId(), standardDto.getCdrHash());

        CallRecord.CallRecordBuilder callBuilder = CallRecord.builder();
        callBuilder.commLocationId(commLocation.getId());
        callBuilder.commLocation(commLocation);
        callBuilder.serviceDate(standardDto.getCallStartTime());
        callBuilder.employeeExtension(standardDto.getCallingPartyNumber());
        callBuilder.employeeAuthCode(standardDto.getAuthCode());
        callBuilder.destinationPhone(standardDto.getCalledPartyNumber()); // Original dialed number
        callBuilder.dial(standardDto.getCalledPartyNumber()); // Will be updated by processing logic
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
                Optional<Employee> extOnlyEmployeeOpt = employeeLookupService.findEmployeeByExtensionOrAuthCode(standardDto.getCallingPartyNumber(), null, commLocation.getId());
                if (extOnlyEmployeeOpt.isPresent()) {
                    callBuilder.employeeId(extOnlyEmployeeOpt.get().getId());
                    callBuilder.employee(extOnlyEmployeeOpt.get());
                    callBuilder.assignmentCause(CallAssignmentCause.EXTENSION.getValue());
                } else {
                    callBuilder.employeeId(null);
                    callBuilder.employee(null);
                    callBuilder.assignmentCause(CallAssignmentCause.UNKNOWN.getValue());
                }
            } else {
                callBuilder.assignmentCause(CallAssignmentCause.EXTENSION.getValue());
            }
        });

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
        Long indicatorIdForSpecial = commLocation.getIndicatorId();
        CdrProcessingConfig.ExtensionLengthConfig extConfig = configService.getExtensionLengthConfig(commLocation.getId());

        // 1. Apply PBX Special Rule (if any)
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

        // 2. Check for Special Service
        // PHP: $telefono_orig = limpiar_numero($info['dial_number'], $_PREFIJO_SALIDA_PBX, true);
        String numberForSpecialCheck = cdrNumberProcessingService.cleanNumber(numberAfterPbxRule, pbxPrefixes, true, extConfig);
        if (indicatorIdForSpecial != null && originCountryId != null) {
            Optional<SpecialService> specialServiceOpt = specialRuleLookupService.findSpecialService(numberForSpecialCheck, indicatorIdForSpecial, originCountryId);
            if (specialServiceOpt.isPresent()) {
                SpecialService specialService = specialServiceOpt.get();
                log.debug("Call matches Special Service: {}", specialService.getId());
                cdrPricingService.applySpecialServicePricing(specialService, callBuilder, standardDto.getDurationSeconds());
                callBuilder.dial(numberForSpecialCheck); // Update dial with the number matched for special service
                return; // Processing ends if it's a special service
            }
        }

        // 3. Check if Internal Call
        // PHP: $esinterna = info_interna($info_cdr); if (!$esinterna) { ... } if ($esinterna) { procesaInterna }
        String cleanedNumberForInternalCheck = cdrNumberProcessingService.cleanNumber(numberAfterPbxRule, Collections.emptyList(), false, extConfig);
        if (cdrEnrichmentHelper.isInternalCall(effectiveCallingNumber, cleanedNumberForInternalCheck, extConfig)) {
            log.debug("Processing as internal call (caller: {}, effective dialed for internal: {})", effectiveCallingNumber, cleanedNumberForInternalCheck);
            processInternalCall(standardDto, commLocation, callBuilder, cleanedNumberForInternalCheck, extConfig);
            return; // Processing ends if it's internal
        }

        // 4. Process as External Outgoing Call (if not special or internal)
        CdrNumberProcessingService.FieldWrapper<Long> forcedTelephonyType = new CdrNumberProcessingService.FieldWrapper<>(null);
        String preprocessedNumber = cdrNumberProcessingService.preprocessNumberForLookup(numberAfterPbxRule, originCountryId, forcedTelephonyType, commLocation);
        if (!preprocessedNumber.equals(numberAfterPbxRule)) {
            log.debug("Number preprocessed for lookup: {} -> {}", numberAfterPbxRule, preprocessedNumber);
        }
        callBuilder.dial(preprocessedNumber); // Update dial with the number after all preprocessing for lookup

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
        // For incoming, `dial` field usually stores the *source* number after processing
        callBuilder.dial(cdrNumberProcessingService.cleanNumber(numberAfterPbxRule, Collections.emptyList(), false, extConfig));

        CdrNumberProcessingService.FieldWrapper<Long> forcedTelephonyType = new CdrNumberProcessingService.FieldWrapper<>(null);
        String preprocessedNumber = cdrNumberProcessingService.preprocessNumberForLookup(numberAfterPbxRule, originCountryId, forcedTelephonyType, commLocation);
        if (!preprocessedNumber.equals(numberAfterPbxRule)) {
            log.debug("Incoming number preprocessed for lookup: {} -> {}", numberAfterPbxRule, preprocessedNumber);
            callBuilder.dial(cdrNumberProcessingService.cleanNumber(preprocessedNumber, Collections.emptyList(), false, extConfig));
        }

        if (originCountryId != null && commIndicatorId != null) {
            Optional<IncomingOriginResult> originResultOpt = classifyIncomingCallOrigin(
                    preprocessedNumber, originCountryId, commIndicatorId
            );

            if (originResultOpt.isPresent()) {
                IncomingOriginResult originResult = originResultOpt.get();
                callBuilder.telephonyTypeId(originResult.telephonyTypeId);
                callBuilder.operatorId(originResult.operatorId);
                callBuilder.indicatorId(originResult.indicatorId); // This is the *originating* indicator
                // Update dial field again if classifyIncomingCallOrigin modified the number representation (e.g. for display)
                // callBuilder.dial(originResult.effectiveNumberForDialField); // If classifyIncomingCallOrigin returns it

                log.debug("Incoming call origin classified as Type ID: {}, Operator ID: {}. Originating Indicator ID: {}",
                        originResult.telephonyTypeId, originResult.operatorId, originResult.indicatorId);
            } else {
                log.error("CRITICAL: classifyIncomingCallOrigin returned empty for {}, which should not happen.", preprocessedNumber);
                callBuilder.telephonyTypeId(CdrProcessingConfig.TIPOTELE_ERRORES);
                callBuilder.indicatorId(null);
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

    private Optional<IncomingOriginResult> classifyIncomingCallOrigin(
            String processedNumber,
            Long commLocationOriginCountryId,
            Long commLocationIndicatorId
    ) {
        log.debug("Classifying incoming call origin for number: {}, commLocCountry: {}, commLocInd: {}",
                processedNumber, commLocationOriginCountryId, commLocationIndicatorId);

        List<Long> incomingTypeOrder = configService.getIncomingTelephonyTypeClassificationOrder();

        for (Long currentTelephonyTypeId : incomingTypeOrder) {
            String numberToLookup = processedNumber;
            Long typeForLookup = currentTelephonyTypeId;
            String destinationNameForDisplay = "Unknown Origin"; // Default

            if (currentTelephonyTypeId.equals(CdrProcessingConfig.TIPOTELE_LOCAL)) {
                Optional<Integer> localNdcOpt = prefixInfoLookupService.findLocalNdcForIndicator(commLocationIndicatorId);
                if (localNdcOpt.isPresent()) {
                    numberToLookup = localNdcOpt.get() + processedNumber;
                    typeForLookup = CdrProcessingConfig.TIPOTELE_NACIONAL;
                    log.trace("For incoming LOCAL check, prepended commLocation NDC: {} -> {}. Lookup type: {}",
                            processedNumber, numberToLookup, typeForLookup);
                } else {
                    log.warn("Could not find local NDC for commLocationIndicatorId {} to prepend for LOCAL type check of incoming number {}",
                            commLocationIndicatorId, processedNumber);
                }
            }

            Optional<Map<String, Object>> indicatorInfoOpt = prefixInfoLookupService.findIndicatorByNumber(
                    numberToLookup,
                    typeForLookup,
                    commLocationOriginCountryId,
                    false,
                    null,
                    commLocationIndicatorId
            );

            if (indicatorInfoOpt.isPresent()) {
                Map<String, Object> indicatorInfo = indicatorInfoOpt.get();
                IncomingOriginResult result = new IncomingOriginResult();
                result.indicatorId = (Long) indicatorInfo.get("indicator_id");
                result.operatorId = indicatorInfo.get("indicator_operator_id") != null ? ((Number)indicatorInfo.get("indicator_operator_id")).longValue() : 0L;
                result.telephonyTypeId = currentTelephonyTypeId;
                result.destinationName = cdrEnrichmentHelper.formatDestinationName(indicatorInfo);


                if (currentTelephonyTypeId.equals(CdrProcessingConfig.TIPOTELE_LOCAL)) {
                    Integer ndcOfFoundIndicator = indicatorInfo.get("series_ndc") != null ? Integer.parseInt(String.valueOf(indicatorInfo.get("series_ndc"))) : null;
                    if (prefixInfoLookupService.isLocalExtended(ndcOfFoundIndicator, commLocationIndicatorId)) {
                        result.telephonyTypeId = CdrProcessingConfig.TIPOTELE_LOCAL_EXT;
                        configService.getTelephonyTypeById(result.telephonyTypeId).ifPresent(tt ->
                            result.destinationName = result.destinationName.replace("(Local)", "(Local Extended)")
                        );
                    }
                }
                log.info("Incoming call origin classified: Number '{}' (lookup as '{}') -> Type={}, Op={}, OriginInd={}, Name='{}'",
                        processedNumber, numberToLookup, result.telephonyTypeId, result.operatorId, result.indicatorId, result.destinationName);
                return Optional.of(result);
            }
        }

        log.warn("Could not classify incoming call origin for '{}'. Defaulting to LOCAL type, using commLocation's indicator as origin.", processedNumber);
        IncomingOriginResult defaultResult = new IncomingOriginResult();
        defaultResult.telephonyTypeId = CdrProcessingConfig.TIPOTELE_LOCAL;
        defaultResult.indicatorId = commLocationIndicatorId;
        configService.getOperatorInternal(CdrProcessingConfig.TIPOTELE_LOCAL, commLocationOriginCountryId)
                .ifPresent(op -> defaultResult.operatorId = op.getId());
        entityLookupService.findIndicatorById(commLocationIndicatorId).ifPresent(ind ->
                defaultResult.destinationName = cdrEnrichmentHelper.formatDestinationName(Map.of("city_name", ind.getCityName(), "department_country", ind.getDepartmentCountry()))
        );
        return Optional.of(defaultResult);
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
            indicatorId = destLoc.indicatorId(); // Destination's indicator

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
                indicatorId = sourceLoc.indicatorId(); // Use source's indicator if dest unknown but matches prefix
                log.debug("Destination {} matched internal prefix for type {}, Op {}, using source Ind {}", destinationExtension, internalCallTypeId, operatorId, indicatorId);
            } else {
                internalCallTypeId = CdrProcessingConfig.getDefaultInternalCallTypeId();
                indicatorId = sourceLoc.indicatorId(); // Fallback to source's indicator
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
        Long originIndicatorId = commLocation.getIndicatorId(); // This is the indicator of the PBX/system making the call
        Long originCountryId = cdrEnrichmentHelper.getOriginCountryId(commLocation);
        CdrProcessingConfig.ExtensionLengthConfig extConfig = configService.getExtensionLengthConfig(commLocation.getId());

        String numberForAttempt;
        if (usesTrunk && trunkOpt.get().getNoPbxPrefix() != null && trunkOpt.get().getNoPbxPrefix()) {
            // PHP: if ($existe_troncal !== false && $existe_troncal['noprefijopbx']) { $prefijo_salida_pbx = ''; }
            // If trunk says "no pbx prefix", then the number for lookup should NOT have PBX prefix stripped by cleanNumber
            // It means the number as received by the trunk is what should be used.
            // However, `numberAfterPbxAndPreprocessing` might have already had PBX prefix logic applied if PBX rule ran.
            // The PHP `limpiar_numero` is complex here. If `noprefijopbx` is true, it means the number on the trunk
            // is "clean" and doesn't need PBX prefix removal.
            // If `noprefijopbx` is false, it means the number on the trunk *might* have a PBX prefix that needs removal.
            // The `numberAfterPbxAndPreprocessing` has already gone through PBX rule stage.
            // Let's assume `numberAfterPbxAndPreprocessing` is the starting point.
            // `cleanNumber`'s `removePbxPrefixIfNeeded` should be true if the trunk *expects* prefixes to be stripped.
            // So, if `noprefijopbx` is FALSE, then `removePbxPrefixIfNeeded` should be TRUE.
            boolean removePbx = !(trunkOpt.get().getNoPbxPrefix());
            numberForAttempt = cdrNumberProcessingService.cleanNumber(numberAfterPbxAndPreprocessing, pbxPrefixes, removePbx, extConfig);
            log.trace("Trunk call. Trunk noPbxPrefix={}, so removePbxForClean={}. Number for lookup: {}", trunkOpt.get().getNoPbxPrefix(), removePbx, numberForAttempt);
        } else {
            // Non-trunk call, or trunk implies PBX prefix is already handled/not present.
            // Always try to clean PBX prefix if defined for non-trunk.
            numberForAttempt = cdrNumberProcessingService.cleanNumber(numberAfterPbxAndPreprocessing, pbxPrefixes, true, extConfig);
            log.trace("Non-trunk call or trunk implies no PBX prefix. Cleaned number for lookup: {}", numberForAttempt);
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


        // PHP: if ($existe_troncal !== false && evaluarDestino_novalido($infovalor))
        if (usesTrunk && rateLookupResultOpt.map(r -> cdrEnrichmentHelper.isRateAssumedOrError(r, (Long)r.get("telephony_type_id"))).orElse(true)) {
            log.warn("Initial rate lookup for trunk call {} resulted in assumed/error rate or no rate. Attempting fallback as non-trunk.", standardDto.getGlobalCallId());
            // PHP: $prefijo_salida_pbx = ''; // Retira prefijo si existe pues debe ignorarse (for non-trunk fallback)
            // For non-trunk fallback, always try to remove PBX prefixes if they are defined.
            String nonTrunkNumberForAttempt = cdrNumberProcessingService.cleanNumber(numberAfterPbxAndPreprocessing, pbxPrefixes, true, extConfig);

            List<Map<String, Object>> nonTrunkPrefixes = prefixInfoLookupService.findMatchingPrefixes(
                    nonTrunkNumberForAttempt,
                    originCountryId,
                    false,
                    null,
                    forcedTelephonyTypeId,
                    commLocation
            );
            Optional<Map<String, Object>> fallbackRateOpt = findBestRateFromPrefixes(
                    standardDto, commLocation, callBuilder, nonTrunkNumberForAttempt, nonTrunkPrefixes, Optional.empty(), originIndicatorId
            );

            // PHP: if (($infovalor['tipotele'] != $infovalor_pos['tipotele'] || $infovalor['indicativo'] != $infovalor_pos['indicativo']) ... )
            if (fallbackRateOpt.isPresent() && !cdrEnrichmentHelper.isRateAssumedOrError(fallbackRateOpt.get(), (Long)fallbackRateOpt.get().get("telephony_type_id"))) {
                // Check if fallback is genuinely "better" (not assumed/error) and different
                boolean originalIsErrorOrAssumed = rateLookupResultOpt.map(r -> cdrEnrichmentHelper.isRateAssumedOrError(r, (Long)r.get("telephony_type_id"))).orElse(true);
                boolean typeChanged = !originalIsErrorOrAssumed && !rateLookupResultOpt.get().get("telephony_type_id").equals(fallbackRateOpt.get().get("telephony_type_id"));
                boolean indicatorChanged = !originalIsErrorOrAssumed && !Optional.ofNullable(rateLookupResultOpt.get().get("indicator_id")).equals(Optional.ofNullable(fallbackRateOpt.get().get("indicator_id")));

                if (originalIsErrorOrAssumed || typeChanged || indicatorChanged) {
                    rateLookupResultOpt = fallbackRateOpt;
                    log.debug("Fallback (non-trunk) lookup provided a better or different valid rate for {}.", standardDto.getGlobalCallId());
                } else {
                     log.debug("Fallback (non-trunk) lookup did not provide a better or sufficiently different rate for {}. Original trunk rate (or assumed/error) retained.", standardDto.getGlobalCallId());
                }
            } else {
                log.debug("Fallback (non-trunk) lookup did not provide a valid rate for {}.", standardDto.getGlobalCallId());
            }
        }


        if (rateLookupResultOpt.isPresent()) {
            Map<String, Object> currentRateInfo = new HashMap<>(rateLookupResultOpt.get());
            callBuilder.telephonyTypeId((Long) currentRateInfo.get("telephony_type_id"));
            callBuilder.operatorId((Long) currentRateInfo.get("operator_id"));
            callBuilder.indicatorId((Long) currentRateInfo.get("indicator_id"));
            callBuilder.dial((String) currentRateInfo.getOrDefault("effective_number", numberForAttempt));

            cdrPricingService.applySpecialPricing(currentRateInfo, standardDto.getCallStartTime(), standardDto.getDurationSeconds(), originIndicatorId, callBuilder);

            // Apply trunk rule overrides only if trunk was involved and special pricing didn't already set a trunk-specific rate
            // The 'applied_trunk_pricing' flag in currentRateInfo helps track if trunk-specific pricing (from TrunkRate) was already applied.
            // TrunkRule might override this or apply if TrunkRate wasn't specific enough.
            if (usesTrunk && !(Boolean)currentRateInfo.getOrDefault("applied_trunk_pricing_by_rule", false)) {
                 cdrPricingService.applyTrunkRuleOverrides(trunkOpt.get(), currentRateInfo, standardDto.getDurationSeconds(), originIndicatorId, callBuilder);
            }

            cdrPricingService.applyFinalPricing(currentRateInfo, standardDto.getDurationSeconds(), callBuilder);

        } else {
            log.error("Could not determine rate for number: {} (effective for attempt: {}) after all fallbacks.", standardDto.getCalledPartyNumber(), numberForAttempt);
            callBuilder.telephonyTypeId(CdrProcessingConfig.TIPOTELE_ERRORES);
            callBuilder.dial(numberForAttempt); // Store the number that was attempted for lookup
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

        // PHP: if ($arr_destino === false) { ... $todosok = true; ... if ($todosok) { $arr_destino = array(); ... } }
        // This handles the case where no specific prefix matched but all candidates pointed to the same type/operator.
        // The Java `findMatchingPrefixes` already filters to the longest prefix or specific trunk types.
        // If `prefixes` is empty, we might fall into an "assumed" scenario if there was a single type implied.
        // However, the PHP logic for "assumed" is more about when `buscarDestino` fails to find an indicator.

        for (Map<String, Object> prefixInfo : prefixes) {
            Long currentTelephonyTypeId = (Long) prefixInfo.get("telephony_type_id");
            Long currentOperatorId = (Long) prefixInfo.get("operator_id");
            String currentPrefixCode = (String) prefixInfo.get("prefix_code");
            Long currentPrefixId = (Long) prefixInfo.get("prefix_id");
            int typeMinLength = (Integer) prefixInfo.get("telephony_type_min");
            int typeMaxLength = (Integer) prefixInfo.get("telephony_type_max");
            boolean currentPrefixBandOk = (Boolean) prefixInfo.get("prefix_band_ok");

            String numberWithoutOperatorPrefix = numberForLookup;
            boolean operatorPrefixRemoved = false;
            boolean removeOperatorPrefixThisIteration = true; // Default to true

            if (usesTrunk && trunk != null) {
                Long operatorForTrunkRate = cdrEnrichmentHelper.findEffectiveTrunkOperator(trunk, currentTelephonyTypeId, currentPrefixCode, currentOperatorId, originCountryId);
                if (operatorForTrunkRate != null && operatorForTrunkRate >= 0) { // operatorForTrunkRate can be 0 for global trunk rate
                    Optional<TrunkRate> trOpt = trunkLookupService.findTrunkRate(trunk.getId(), operatorForTrunkRate, currentTelephonyTypeId);
                    if (trOpt.isPresent() && trOpt.get().getNoPrefix() != null) {
                        removeOperatorPrefixThisIteration = !trOpt.get().getNoPrefix(); // if noPrefix is true, then don't remove
                    }
                }
            }

            if (StringUtils.hasText(currentPrefixCode) && numberForLookup.startsWith(currentPrefixCode)) {
                if (removeOperatorPrefixThisIteration && currentTelephonyTypeId != CdrProcessingConfig.TIPOTELE_LOCAL) {
                    numberWithoutOperatorPrefix = numberForLookup.substring(currentPrefixCode.length());
                    operatorPrefixRemoved = true;
                    log.trace("Operator prefix '{}' removed from '{}', result: '{}'", currentPrefixCode, numberForLookup, numberWithoutOperatorPrefix);
                }
            }
            // If number becomes empty after stripping prefix, it's not a valid match for series lookup
            if (numberWithoutOperatorPrefix.isEmpty() && StringUtils.hasText(numberForLookup) && operatorPrefixRemoved) {
                log.trace("Number became empty after stripping prefix '{}' from '{}', skipping this prefix.", currentPrefixCode, numberForLookup);
                continue;
            }

            int operatorPrefixLength = (StringUtils.hasText(currentPrefixCode) ? currentPrefixCode.length() : 0);
            // Effective min/max length for the subscriber part of the number
            int effectiveMinLength = operatorPrefixRemoved ? Math.max(0, typeMinLength - operatorPrefixLength) : typeMinLength;
            int effectiveMaxLength = operatorPrefixRemoved ? Math.max(0, typeMaxLength - operatorPrefixLength) : typeMaxLength;


            if (numberWithoutOperatorPrefix.length() < effectiveMinLength) {
                 log.trace("Subscriber part '{}' (from num '{}') too short for prefix '{}' (effectiveMinLength {})", numberWithoutOperatorPrefix, numberForLookup, currentPrefixCode, effectiveMinLength);
                continue;
            }
            String finalNumberForIndicatorLookup = numberWithoutOperatorPrefix;
            if (effectiveMaxLength > 0 && numberWithoutOperatorPrefix.length() > effectiveMaxLength) {
                finalNumberForIndicatorLookup = numberWithoutOperatorPrefix.substring(0, effectiveMaxLength);
                log.trace("Subscriber part '{}' truncated to '{}' (effectiveMaxLength {}) for indicator lookup.", numberWithoutOperatorPrefix, finalNumberForIndicatorLookup, effectiveMaxLength);
            }

            Optional<Map<String, Object>> indicatorInfoOpt = prefixInfoLookupService.findIndicatorByNumber(
                    finalNumberForIndicatorLookup, currentTelephonyTypeId, originCountryId,
                    currentPrefixBandOk, currentPrefixId, originCommLocationIndicatorId
            );

            boolean destinationFound = indicatorInfoOpt.isPresent();
            // PHP: ($arr_destino_pre['INDICA_MAX'] <= 0 && strlen($info_destino) == $tipotelemax)
            // This means: no specific NDC length defined for the type (INDICA_MAX <=0 implies this),
            // and the original number (before potential truncation for indicator lookup) matches the max length for the type.
            // This is a weaker match, used if no specific series/indicator is found.
            boolean lengthMatchOnly = (cdrEnrichmentHelper.maxNdcLength(currentTelephonyTypeId, originCountryId) == 0 &&
                    numberForLookup.length() == typeMaxLength && // Compare original number for lookup length
                    !destinationFound &&
                    prefixes.size() == 1); // Only if this was the sole prefix candidate
            boolean considerMatch = destinationFound || lengthMatchOnly;

            if (considerMatch) {
                Long destinationIndicatorId = indicatorInfoOpt.map(ind -> (Long) ind.get("indicator_id")).filter(id -> id != null && id > 0).orElse(null);
                Integer destinationNdc = indicatorInfoOpt.map(ind -> (ind.get("series_ndc") instanceof Number ? ((Number)ind.get("series_ndc")).intValue() : (ind.get("series_ndc") != null ? Integer.parseInt(String.valueOf(ind.get("series_ndc"))) : null))).orElse(null);

                Long finalTelephonyTypeId = currentTelephonyTypeId;
                Long finalPrefixId = currentPrefixId;
                boolean finalBandOk = currentPrefixBandOk;
                String finalTelephonyTypeName = (String) prefixInfo.get("telephony_type_name");

                if (currentTelephonyTypeId.equals(CdrProcessingConfig.TIPOTELE_LOCAL) && destinationNdc != null && originCommLocationIndicatorId != null) {
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
                    rateInfo.put("effective_number", numberForLookup); // The number that led to this prefix match
                    rateInfo.put("applied_trunk_pricing", false); // Default, may be overridden by trunk logic

                    if (usesTrunk && trunk != null) {
                        Long operatorForTrunkRate = cdrEnrichmentHelper.findEffectiveTrunkOperator(trunk, finalTelephonyTypeId, currentPrefixCode, currentOperatorId, originCountryId);
                        if (operatorForTrunkRate != null && operatorForTrunkRate >=0) {
                            Optional<TrunkRate> trOpt = trunkLookupService.findTrunkRate(trunk.getId(), operatorForTrunkRate, finalTelephonyTypeId);
                            if (trOpt.isPresent()) {
                                TrunkRate tr = trOpt.get();
                                // PHP: Guardar_ValorInicial($infovalor, $infovalor_pre);
                                rateInfo.put("valor_inicial", rateInfo.get("valor_minuto")); // Save current per-minute as initial
                                rateInfo.put("valor_inicial_iva", rateInfo.get("valor_minuto_iva"));

                                rateInfo.put("valor_minuto", Optional.ofNullable(tr.getRateValue()).orElse(BigDecimal.ZERO));
                                rateInfo.put("valor_minuto_iva", Optional.ofNullable(tr.getIncludesVat()).orElse(false));
                                rateInfo.put("ensegundos", tr.getSeconds() != null && tr.getSeconds() > 0);
                                // IVA for trunk rate comes from the (potentially new) operator and type
                                prefixInfoLookupService.findPrefixByTypeOperatorOrigin(finalTelephonyTypeId, operatorForTrunkRate, originCountryId)
                                        .ifPresent(p -> rateInfo.put("iva", p.getVatValue()));
                                rateInfo.put("applied_trunk_pricing", true);
                            }
                        }
                    }
                    log.debug("Best rate found for {}: {}", numberForLookup, rateInfo);
                    return Optional.of(rateInfo);
                }
            }
        } // End of loop over prefixes

        // If loop finishes and no exact match, check if an "assumed" rate can be formed
        // PHP: if ($arr_destino === false) { ... if ($todosok) { ... $arr_destino['DESTINO'] = $tipotele_nombre . " ("._ASUMIDO.")"; ... } }
        // "todosok" in PHP implies all prefix candidates led to the same operator_id and telephony_type_id,
        // even if no specific indicator was found.
        boolean allPrefixesPointToSameTypeAndOperator = prefixes.size() > 0 &&
                prefixes.stream().map(p -> p.get("telephony_type_id")).distinct().count() <= 1 &&
                prefixes.stream().map(p -> p.get("operator_id")).distinct().count() <= 1;

        if (allPrefixesPointToSameTypeAndOperator && !prefixes.isEmpty()) {
            Map<String, Object> firstPrefix = prefixes.get(0); // Use the first one as representative
            Long assumedTelephonyTypeId = (Long) firstPrefix.get("telephony_type_id");
            Long assumedOperatorId = (Long) firstPrefix.get("operator_id");
            Long assumedPrefixId = (Long) firstPrefix.get("prefix_id");
            boolean assumedBandOk = (Boolean) firstPrefix.get("prefix_band_ok");

            Optional<Map<String, Object>> baseRateOpt = cdrPricingService.findRateInfo(assumedPrefixId, null, originCommLocationIndicatorId, assumedBandOk);
            if (baseRateOpt.isPresent()) {
                assumedRateInfo = new HashMap<>(baseRateOpt.get());
                assumedRateInfo.put("telephony_type_id", assumedTelephonyTypeId);
                assumedRateInfo.put("operator_id", assumedOperatorId);
                assumedRateInfo.put("indicator_id", null); // No specific indicator
                assumedRateInfo.put("telephony_type_name", (String) firstPrefix.get("telephony_type_name") + ASSUMED_SUFFIX);
                assumedRateInfo.put("operator_name", (String) firstPrefix.get("operator_name"));
                assumedRateInfo.put("destination_name", "Assumed Destination");
                assumedRateInfo.put("effective_number", numberForLookup);
                assumedRateInfo.put("applied_trunk_pricing", false); // Not from a specific TrunkRate match
                // Ensure valor_minuto etc. are set from base_value if not already
                assumedRateInfo.putIfAbsent("valor_minuto", assumedRateInfo.get("base_value"));
                assumedRateInfo.putIfAbsent("valor_minuto_iva", assumedRateInfo.get("vat_included"));
                assumedRateInfo.putIfAbsent("iva", assumedRateInfo.get("vat_value"));
                assumedRateInfo.putIfAbsent("valor_inicial", assumedRateInfo.get("valor_minuto"));
                assumedRateInfo.putIfAbsent("valor_inicial_iva", assumedRateInfo.get("valor_minuto_iva"));
                assumedRateInfo.putIfAbsent("ensegundos", false);

                log.debug("Using assumed rate for {}: {}", numberForLookup, assumedRateInfo);
                return Optional.of(assumedRateInfo);
            }
        }

        log.warn("No definitive or assumed rate found for numberForLookup: {}", numberForLookup);
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
        } else if (record.getIndicatorId() == null && record.getIndicator() != null) {
            // This case should not happen if builder logic is correct, but as a safeguard:
            callBuilder.indicator(null); // Ensure consistency if ID is null
        }

        if (record.getDestinationEmployeeId() != null && record.getDestinationEmployee() == null) {
            employeeLookupService.findEmployeeById(record.getDestinationEmployeeId())
                    .ifPresentOrElse(callBuilder::destinationEmployee, () -> log.warn("Could not link destination employee entity for ID: {}", record.getDestinationEmployeeId()));
        }
    }
}