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
    private final EntityLookupService entityLookupService;

    private final CdrPricingService cdrPricingService;
    private final CdrNumberProcessingService cdrNumberProcessingService;
    private final CdrEnrichmentHelper cdrEnrichmentHelper;

    private final CdrProcessingConfig configService;

    private static final String ASSUMED_SUFFIX = " (Assumed)";

    private static class IncomingOriginResult {
        Long telephonyTypeId;
        Long operatorId;
        Long indicatorId; // Originating indicator
        String destinationName; // Name of the origin (e.g., "CELULARES (COMCEL)")
        String effectiveNumberForDialField; // The number representation to store in CallRecord.dial
    }


    public Optional<CallRecord> enrichCdr(StandardizedCallEventDto standardDto, CommunicationLocation commLocation) {
        log.info("Enriching Standardized CDR: {}, Hash: {}", standardDto.getGlobalCallId(), standardDto.getCdrHash());

        CallRecord.CallRecordBuilder callBuilder = CallRecord.builder();
        callBuilder.commLocationId(commLocation.getId());
        callBuilder.commLocation(commLocation);
        callBuilder.serviceDate(standardDto.getCallStartTime());
        callBuilder.employeeExtension(standardDto.getCallingPartyNumber());
        callBuilder.employeeAuthCode(standardDto.getAuthCode());
        // destinationPhone will be set more definitively after PBX rules
        callBuilder.destinationPhone(standardDto.getCalledPartyNumber()); // Initial, may change
        callBuilder.dial(standardDto.getCalledPartyNumber()); // Initial, will be refined
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
            log.info("CDR {} identified as conference leg.", standardDto.getGlobalCallId());
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
            callBuilder.assignmentCause(CallAssignmentCause.CONFERENCE.getValue());
            callBuilder.telephonyTypeId(CdrProcessingConfig.TIPOTELE_INTERNA_IP);
            configService.getOperatorInternal(CdrProcessingConfig.TIPOTELE_INTERNA_IP, cdrEnrichmentHelper.getOriginCountryId(commLocation))
                    .ifPresent(op -> callBuilder.operatorId(op.getId()));
            callBuilder.indicatorId(null);
            linkAssociatedEntities(callBuilder);
            CallRecord finalRecord = callBuilder.build();
            log.info("Processed conference CDR {}: Type={}, Billed=0", standardDto.getGlobalCallId(), finalRecord.getTelephonyTypeId());
            return Optional.of(finalRecord);
        }

        Optional<Employee> initialEmployeeOpt = employeeLookupService.findEmployeeByExtensionOrAuthCode(
                standardDto.getCallingPartyNumber(), standardDto.getAuthCode(), commLocation.getId()
        );

        CallAssignmentCause initialAssignmentCause = CallAssignmentCause.UNKNOWN;
        if (initialEmployeeOpt.isPresent()) {
            callBuilder.employeeId(initialEmployeeOpt.get().getId());
            callBuilder.employee(initialEmployeeOpt.get());
            if (StringUtils.hasText(standardDto.getAuthCode()) && !configService.getIgnoredAuthCodes().contains(standardDto.getAuthCode())) {
                initialAssignmentCause = CallAssignmentCause.AUTH_CODE;
            } else if (StringUtils.hasText(standardDto.getAuthCode())) {
                initialAssignmentCause = CallAssignmentCause.IGNORED_AUTH_CODE;
                Optional<Employee> extOnlyEmployeeOpt = employeeLookupService.findEmployeeByExtensionOrAuthCode(standardDto.getCallingPartyNumber(), null, commLocation.getId());
                if (extOnlyEmployeeOpt.isPresent()) {
                    callBuilder.employeeId(extOnlyEmployeeOpt.get().getId());
                    callBuilder.employee(extOnlyEmployeeOpt.get());
                    initialAssignmentCause = CallAssignmentCause.EXTENSION;
                } else {
                    callBuilder.employeeId(null);
                    callBuilder.employee(null);
                    initialAssignmentCause = CallAssignmentCause.UNKNOWN;
                }
            } else {
                initialAssignmentCause = CallAssignmentCause.EXTENSION;
            }
        } else {
            CdrProcessingConfig.ExtensionLengthConfig extConfig = configService.getExtensionLengthConfig(commLocation.getId());
            if (cdrEnrichmentHelper.isLikelyExtension(standardDto.getCallingPartyNumber(), extConfig)) {
                Optional<Map<String, Object>> rangeAssignment = employeeLookupService.findRangeAssignment(standardDto.getCallingPartyNumber(), commLocation.getId(), standardDto.getCallStartTime());
                if (rangeAssignment.isPresent()) {
                    initialAssignmentCause = CallAssignmentCause.RANGES;
                }
            }
        }
        callBuilder.assignmentCause(initialAssignmentCause.getValue());


        try {
            if (standardDto.isIncoming()) {
                processIncomingCall(standardDto, commLocation, callBuilder, standardDto.getCalledPartyNumber());
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

        CallRecord tempRecordForAssignment = callBuilder.build();
        if (tempRecordForAssignment.isIncoming() && initialAssignmentCause != CallAssignmentCause.CONFERENCE) {
            Optional<Employee> finalInternalPartyOpt = Optional.ofNullable(tempRecordForAssignment.getEmployeeId())
                .flatMap(employeeLookupService::findEmployeeById);

            if (finalInternalPartyOpt.isPresent()) {
                callBuilder.assignmentCause(CallAssignmentCause.EXTENSION.getValue());
            } else {
                CdrProcessingConfig.ExtensionLengthConfig extConfig = configService.getExtensionLengthConfig(commLocation.getId());
                if (cdrEnrichmentHelper.isLikelyExtension(tempRecordForAssignment.getEmployeeExtension(), extConfig)) {
                    Optional<Map<String, Object>> rangeAssignment = employeeLookupService.findRangeAssignment(
                        tempRecordForAssignment.getEmployeeExtension(), commLocation.getId(), standardDto.getCallStartTime()
                    );
                    if (rangeAssignment.isPresent()) {
                        callBuilder.assignmentCause(CallAssignmentCause.RANGES.getValue());
                    } else {
                        callBuilder.assignmentCause(CallAssignmentCause.UNKNOWN.getValue());
                    }
                } else {
                     callBuilder.assignmentCause(CallAssignmentCause.UNKNOWN.getValue());
                }
            }
        } else if (!tempRecordForAssignment.isIncoming() && tempRecordForAssignment.getEmployeeId() == null && initialAssignmentCause != CallAssignmentCause.CONFERENCE) {
            CdrProcessingConfig.ExtensionLengthConfig extConfig = configService.getExtensionLengthConfig(commLocation.getId());
            if (cdrEnrichmentHelper.isLikelyExtension(standardDto.getCallingPartyNumber(), extConfig)) {
                Optional<Map<String, Object>> rangeAssignment = employeeLookupService.findRangeAssignment(
                    standardDto.getCallingPartyNumber(), commLocation.getId(), standardDto.getCallStartTime()
                );
                if (rangeAssignment.isPresent()) {
                    callBuilder.assignmentCause(CallAssignmentCause.RANGES.getValue());
                } else {
                     callBuilder.assignmentCause(CallAssignmentCause.UNKNOWN.getValue());
                }
            } else {
                 callBuilder.assignmentCause(CallAssignmentCause.UNKNOWN.getValue());
            }
        } else if (initialAssignmentCause == CallAssignmentCause.IGNORED_AUTH_CODE && tempRecordForAssignment.getEmployeeId() == null) {
            callBuilder.assignmentCause(CallAssignmentCause.UNKNOWN.getValue());
        }

        // Apply SINCONSUMO logic (PHP: if ($tiempo <= 0 && $tipotele_id > 0 && $tipotele_id != _TIPOTELE_ERRORES))
        CallRecord recordBeforeSinConsumo = callBuilder.build();
        if (recordBeforeSinConsumo.getDuration() != null &&
            recordBeforeSinConsumo.getDuration() <= 0 && // PHP logic: tiempo <= 0
            recordBeforeSinConsumo.getTelephonyTypeId() != null &&
            recordBeforeSinConsumo.getTelephonyTypeId() > 0 && // Ensure it's not already unclassified
            !recordBeforeSinConsumo.getTelephonyTypeId().equals(CdrProcessingConfig.TIPOTELE_ERRORES)) {
            log.info("Call duration {} is not billable (<=0), setting TelephonyType to SINCONSUMO ({})",
                    recordBeforeSinConsumo.getDuration(), CdrProcessingConfig.TIPOTELE_SINCONSUMO);
            callBuilder.telephonyTypeId(CdrProcessingConfig.TIPOTELE_SINCONSUMO);
            callBuilder.billedAmount(BigDecimal.ZERO);
            callBuilder.pricePerMinute(BigDecimal.ZERO);
            callBuilder.initialPrice(BigDecimal.ZERO);
        }

        linkAssociatedEntities(callBuilder);
        CallRecord finalRecord = callBuilder.build();

        log.info("Successfully enriched CDR {}: Type={}, Op={}, Ind={}, Dial={}, DestPhone={}, Billed={}, IsIncoming={}, EmpExt={}, AssignCause={}",
                standardDto.getGlobalCallId(), finalRecord.getTelephonyTypeId(), finalRecord.getOperatorId(),
                finalRecord.getIndicatorId(), finalRecord.getDial(), finalRecord.getDestinationPhone(),
                finalRecord.getBilledAmount(), finalRecord.isIncoming(), finalRecord.getEmployeeExtension(),
                finalRecord.getAssignmentCause());
        return Optional.of(finalRecord);
    }

    private void processOutgoingCall(StandardizedCallEventDto standardDto, CommunicationLocation commLocation, CallRecord.CallRecordBuilder callBuilder, String effectiveCallingNumber, String originalDialedNumber) {
        log.info("Processing outgoing call for CDR {} (caller: {}, original dialed: {})", standardDto.getGlobalCallId(), effectiveCallingNumber, originalDialedNumber);

        List<String> pbxPrefixes = configService.getPbxPrefixes(commLocation.getId());
        Long originCountryId = cdrEnrichmentHelper.getOriginCountryId(commLocation);
        Long indicatorIdForSpecial = commLocation.getIndicatorId();
        CdrProcessingConfig.ExtensionLengthConfig extConfig = configService.getExtensionLengthConfig(commLocation.getId());

        String numberAfterPbxRule = originalDialedNumber;
        Optional<PbxSpecialRule> pbxRuleOpt = specialRuleLookupService.findPbxSpecialRule(
                originalDialedNumber, commLocation.getId(), CallDirection.OUTGOING.getValue()
        );
        if (pbxRuleOpt.isPresent()) {
            PbxSpecialRule rule = pbxRuleOpt.get();
            String replacement = rule.getReplacement() != null ? rule.getReplacement() : "";
            String searchPattern = rule.getSearchPattern();
            if (StringUtils.hasText(searchPattern) && originalDialedNumber.startsWith(searchPattern)) {
                String numberAfterSearch = originalDialedNumber.substring(searchPattern.length());
                numberAfterPbxRule = replacement + numberAfterSearch;
                log.info("Applied OUTGOING PBX rule {}, number changed from {} to {}", rule.getId(), originalDialedNumber, numberAfterPbxRule);
            }
        }
        callBuilder.destinationPhone(numberAfterPbxRule); // Set destinationPhone after PBX rules

        String numberForSpecialCheck = cdrNumberProcessingService.cleanNumber(numberAfterPbxRule, pbxPrefixes, true, extConfig);
        if (indicatorIdForSpecial != null && originCountryId != null) {
            Optional<SpecialService> specialServiceOpt = specialRuleLookupService.findSpecialService(numberForSpecialCheck, indicatorIdForSpecial, originCountryId);
            if (specialServiceOpt.isPresent()) {
                SpecialService specialService = specialServiceOpt.get();
                log.info("Call matches Special Service: {}", specialService.getId());
                cdrPricingService.applySpecialServicePricing(specialService, callBuilder, standardDto.getDurationSeconds());
                callBuilder.dial(numberForSpecialCheck);
                return;
            }
        }

        String cleanedNumberForInternalCheck = cdrNumberProcessingService.cleanNumber(numberAfterPbxRule, Collections.emptyList(), false, extConfig);
        if (cdrEnrichmentHelper.isInternalCall(effectiveCallingNumber, cleanedNumberForInternalCheck, extConfig)) {
            log.info("Processing as internal call (caller: {}, effective dialed for internal: {})", effectiveCallingNumber, cleanedNumberForInternalCheck);
            processInternalCall(standardDto, commLocation, callBuilder, cleanedNumberForInternalCheck, extConfig);
            return;
        }

        CdrNumberProcessingService.FieldWrapper<Long> forcedTelephonyType = new CdrNumberProcessingService.FieldWrapper<>(null);
        String preprocessedNumber = cdrNumberProcessingService.preprocessNumberForLookup(numberAfterPbxRule, originCountryId, forcedTelephonyType, commLocation);
        if (!preprocessedNumber.equals(numberAfterPbxRule)) {
            log.info("Number preprocessed for lookup: {} -> {}", numberAfterPbxRule, preprocessedNumber);
        }

        log.info("Processing as external outgoing call (caller: {}, final number for lookup: {})", effectiveCallingNumber, preprocessedNumber);
        evaluateDestinationAndRate(standardDto, commLocation, callBuilder, preprocessedNumber, pbxPrefixes, forcedTelephonyType.getValue());
    }


    private void processIncomingCall(StandardizedCallEventDto standardDto, CommunicationLocation commLocation, CallRecord.CallRecordBuilder callBuilder, String externalOriginalNumber) {
        log.info("Processing incoming call for CDR {} (external original number: {})", standardDto.getGlobalCallId(), externalOriginalNumber);

        Long originCountryId = cdrEnrichmentHelper.getOriginCountryId(commLocation);
        Long commIndicatorId = commLocation.getIndicatorId();

        String numberAfterPbxRule = externalOriginalNumber;
        Optional<PbxSpecialRule> pbxRuleOpt = specialRuleLookupService.findPbxSpecialRule(
                externalOriginalNumber, commLocation.getId(), CallDirection.INCOMING.getValue()
        );
        if (pbxRuleOpt.isPresent()) {
            PbxSpecialRule rule = pbxRuleOpt.get();
            String replacement = rule.getReplacement() != null ? rule.getReplacement() : "";
            String searchPattern = rule.getSearchPattern();
            if (StringUtils.hasText(searchPattern) && externalOriginalNumber.startsWith(searchPattern)) {
                String numberAfterSearch = externalOriginalNumber.substring(searchPattern.length());
                numberAfterPbxRule = replacement + numberAfterSearch;
                log.info("Applied INCOMING PBX rule {} to incoming number, result: {} -> {}", rule.getId(), externalOriginalNumber, numberAfterPbxRule);
            }
        }
        callBuilder.destinationPhone(numberAfterPbxRule); // Set destinationPhone after PBX rules

        CdrNumberProcessingService.FieldWrapper<Long> forcedTelephonyType = new CdrNumberProcessingService.FieldWrapper<>(null);
        String preprocessedNumber = cdrNumberProcessingService.preprocessNumberForLookup(numberAfterPbxRule, originCountryId, forcedTelephonyType, commLocation);
        if (!preprocessedNumber.equals(numberAfterPbxRule)) {
            log.info("Incoming number preprocessed for lookup: {} -> {}", numberAfterPbxRule, preprocessedNumber);
        }
        
        if (originCountryId != null && commIndicatorId != null) {
            Optional<IncomingOriginResult> originResultOpt = classifyIncomingCallOrigin(
                    preprocessedNumber, originCountryId, commIndicatorId, forcedTelephonyType.getValue()
            );

            if (originResultOpt.isPresent()) {
                IncomingOriginResult originResult = originResultOpt.get();
                callBuilder.telephonyTypeId(originResult.telephonyTypeId);
                callBuilder.operatorId(originResult.operatorId);
                callBuilder.indicatorId(originResult.indicatorId);
                callBuilder.dial(originResult.effectiveNumberForDialField);
                log.info("Incoming call origin classified as Type ID: {}, Operator ID: {}. Originating Indicator ID: {}, Dial: {}",
                        originResult.telephonyTypeId, originResult.operatorId, originResult.indicatorId, callBuilder.build().getDial());
            } else {
                log.error("CRITICAL: classifyIncomingCallOrigin returned empty for {}, which should not happen.", preprocessedNumber);
                callBuilder.telephonyTypeId(CdrProcessingConfig.TIPOTELE_ERRORES);
                callBuilder.indicatorId(null);
                callBuilder.dial(preprocessedNumber);
            }
        } else {
            log.warn("Missing Origin Country or Indicator for CommunicationLocation {}, cannot classify incoming call origin.", commLocation.getId());
            callBuilder.telephonyTypeId(CdrProcessingConfig.TIPOTELE_ERRORES);
            callBuilder.indicatorId(null);
            callBuilder.dial(preprocessedNumber);
        }

        callBuilder.billedAmount(BigDecimal.ZERO);
        callBuilder.pricePerMinute(BigDecimal.ZERO);
        callBuilder.initialPrice(BigDecimal.ZERO);
    }

    private Optional<IncomingOriginResult> classifyIncomingCallOrigin(
            String processedNumber,
            Long commLocationOriginCountryId,
            Long commLocationIndicatorId,
            Long forcedTelephonyTypeFromPreprocessing
    ) {
        log.info("Classifying incoming call origin for number: {}, commLocCountry: {}, commLocInd: {}, forcedType: {}",
                processedNumber, commLocationOriginCountryId, commLocationIndicatorId, forcedTelephonyTypeFromPreprocessing);

        List<Long> typeOrderToTry;
        if (forcedTelephonyTypeFromPreprocessing != null) {
            typeOrderToTry = Collections.singletonList(forcedTelephonyTypeFromPreprocessing);
            log.info("Using forced telephony type {} for incoming classification.", forcedTelephonyTypeFromPreprocessing);
        } else {
            typeOrderToTry = configService.getIncomingTelephonyTypeClassificationOrder();
        }

        for (Long currentTelephonyTypeId : typeOrderToTry) {
            String numberToLookup = processedNumber;
            Long typeForLookup = currentTelephonyTypeId;
            String finalDialField = processedNumber;

            if (currentTelephonyTypeId.equals(CdrProcessingConfig.TIPOTELE_LOCAL)) {
                Optional<Integer> localNdcOpt = prefixInfoLookupService.findLocalNdcForIndicator(commLocationIndicatorId);
                if (localNdcOpt.isPresent()) {
                    numberToLookup = localNdcOpt.get() + processedNumber;
                    typeForLookup = CdrProcessingConfig.TIPOTELE_NACIONAL;
                    log.info("For incoming LOCAL check, prepended commLocation NDC: {} -> {}. Lookup type: {}. Dial field will be: {}",
                            processedNumber, numberToLookup, typeForLookup, finalDialField);
                } else {
                     log.warn("Could not find local NDC for commLocationIndicatorId {} to prepend for LOCAL type check of incoming number {}",
                            commLocationIndicatorId, processedNumber);
                }
            } else if (currentTelephonyTypeId.equals(CdrProcessingConfig.TIPOTELE_CELULAR) &&
                       commLocationOriginCountryId.equals(CdrProcessingConfig.COLOMBIA_ORIGIN_COUNTRY_ID) &&
                       numberToLookup.startsWith(CdrProcessingConfig.COLOMBIAN_MOBILE_INTERNAL_PREFIX)) {
                finalDialField = numberToLookup.substring(CdrProcessingConfig.COLOMBIAN_MOBILE_INTERNAL_PREFIX.length());
                numberToLookup = finalDialField;
                log.info("For incoming CELULAR check, stripped '{}' prefix: {} -> {}. Lookup number: {}. Dial field will be: {}",
                        CdrProcessingConfig.COLOMBIAN_MOBILE_INTERNAL_PREFIX, processedNumber, numberToLookup, numberToLookup, finalDialField);
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
                result.effectiveNumberForDialField = finalDialField;

                if (currentTelephonyTypeId.equals(CdrProcessingConfig.TIPOTELE_LOCAL)) {
                    Integer ndcOfFoundIndicator = indicatorInfo.get("series_ndc") != null ? Integer.parseInt(String.valueOf(indicatorInfo.get("series_ndc"))) : null;
                    if (prefixInfoLookupService.isLocalExtended(ndcOfFoundIndicator, commLocationIndicatorId)) {
                        result.telephonyTypeId = CdrProcessingConfig.TIPOTELE_LOCAL_EXT;
                        configService.getTelephonyTypeById(result.telephonyTypeId).ifPresent(tt ->
                            result.destinationName = result.destinationName.replace("(Local)", "(Local Extended)")
                        );
                    }
                }
                log.info("Incoming call origin classified: Number '{}' (lookup as '{}') -> Type={}, Op={}, OriginInd={}, Name='{}', DialField='{}'",
                        processedNumber, numberToLookup, result.telephonyTypeId, result.operatorId, result.indicatorId, result.destinationName, result.effectiveNumberForDialField);
                return Optional.of(result);
            }
        }

        log.warn("Could not classify incoming call origin for '{}' using specified/ordered types. Defaulting to LOCAL type, using commLocation's indicator as origin.", processedNumber);
        IncomingOriginResult defaultResult = new IncomingOriginResult();
        defaultResult.telephonyTypeId = CdrProcessingConfig.TIPOTELE_LOCAL;
        defaultResult.indicatorId = commLocationIndicatorId;
        configService.getOperatorInternal(CdrProcessingConfig.TIPOTELE_LOCAL, commLocationOriginCountryId)
                .ifPresent(op -> defaultResult.operatorId = op.getId());
        entityLookupService.findIndicatorById(commLocationIndicatorId).ifPresent(ind ->
                defaultResult.destinationName = cdrEnrichmentHelper.formatDestinationName(Map.of("city_name", ind.getCityName(), "department_country", ind.getDepartmentCountry()))
        );
        defaultResult.effectiveNumberForDialField = processedNumber; 
        return Optional.of(defaultResult);
    }


    private void processInternalCall(StandardizedCallEventDto standardDto, CommunicationLocation commLocation, CallRecord.CallRecordBuilder callBuilder, String destinationExtensionFromParam, CdrProcessingConfig.ExtensionLengthConfig extConfig) {
        String sourceExtension = standardDto.getCallingPartyNumber();
        log.info("Processing internal call from {} to {}", sourceExtension, destinationExtensionFromParam);

        Optional<Employee> currentSourceEmployeeOpt = Optional.ofNullable(callBuilder.build().getEmployee());
        Optional<Employee> currentDestEmployeeOpt = employeeLookupService.findEmployeeByExtensionOrAuthCode(
                destinationExtensionFromParam, null, commLocation.getId()
        );

        boolean callerIsSyntacticExt = cdrEnrichmentHelper.isLikelyExtension(sourceExtension, extConfig);

        boolean swapForIncoming =
                !callerIsSyntacticExt &&
                !currentSourceEmployeeOpt.isPresent() &&
                currentDestEmployeeOpt.isPresent() &&
                !standardDto.isIncoming() &&
                currentDestEmployeeOpt.get().getCommunicationLocationId() != null &&
                currentDestEmployeeOpt.get().getCommunicationLocationId().equals(commLocation.getId());


        Optional<Employee> effectiveSourceEmployeeOpt;
        Optional<Employee> effectiveDestEmployeeOpt;
        String effectiveSourceExtension;
        String effectiveDestinationExtension;


        if (swapForIncoming) {
            log.info("Internal call inversion conditions met for {} -> {}. Inverting to INCOMING.", sourceExtension, destinationExtensionFromParam);
            callBuilder.isIncoming(true);
            effectiveSourceExtension = destinationExtensionFromParam;
            effectiveDestinationExtension = sourceExtension;
            callBuilder.employeeExtension(effectiveSourceExtension);
            callBuilder.destinationPhone(effectiveDestinationExtension);
            callBuilder.dial(effectiveDestinationExtension);

            Long originalSourceEmpIdFromBuilder = callBuilder.build().getEmployeeId();
            Long originalDestEmpIdFromLookup = currentDestEmployeeOpt.get().getId();

            callBuilder.employeeId(originalDestEmpIdFromLookup);
            callBuilder.employee(currentDestEmployeeOpt.get());
            callBuilder.destinationEmployeeId(originalSourceEmpIdFromBuilder);
            callBuilder.destinationEmployee(originalSourceEmpIdFromBuilder != null ? currentSourceEmployeeOpt.orElse(null) : null);

            String tempTrunk = callBuilder.build().getTrunk();
            callBuilder.trunk(callBuilder.build().getInitialTrunk());
            callBuilder.initialTrunk(tempTrunk);

            effectiveSourceEmployeeOpt = currentDestEmployeeOpt;
            effectiveDestEmployeeOpt = currentSourceEmployeeOpt;
        } else {
            log.info("Internal call {} -> {} processed as OUTGOING (swapForIncoming=false, callerIsSyntacticExt={}, currentSourceEmployeeOpt.isPresent()={}, standardDto.isIncoming()={})",
                sourceExtension, destinationExtensionFromParam, callerIsSyntacticExt, currentSourceEmployeeOpt.isPresent(), standardDto.isIncoming());
            effectiveSourceEmployeeOpt = currentSourceEmployeeOpt;
            effectiveDestEmployeeOpt = currentDestEmployeeOpt;
            effectiveSourceExtension = sourceExtension;
            effectiveDestinationExtension = destinationExtensionFromParam;
            callBuilder.dial(destinationExtensionFromParam);
        }


        CdrEnrichmentHelper.LocationInfo sourceLoc = cdrEnrichmentHelper.getLocationInfo(effectiveSourceEmployeeOpt.orElse(null), commLocation);
        CdrEnrichmentHelper.LocationInfo destLoc = cdrEnrichmentHelper.getLocationInfo(effectiveDestEmployeeOpt.orElse(null), commLocation);

        Long internalCallTypeId = null;
        Long operatorId = null;
        Long indicatorId = null; 

        if (effectiveDestEmployeeOpt.isPresent() || swapForIncoming) { 
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
            log.info("Internal call type determined by location comparison: {}", internalCallTypeId);

        } else {
            log.warn("Internal call destination extension {} not found as employee (and no inversion).", effectiveDestinationExtension);
            Optional<Map<String, Object>> internalPrefixOpt = prefixInfoLookupService.findInternalPrefixMatch(effectiveDestinationExtension, sourceLoc.originCountryId());
            if (internalPrefixOpt.isPresent()) {
                internalCallTypeId = (Long) internalPrefixOpt.get().get("telephony_type_id");
                operatorId = (Long) internalPrefixOpt.get().get("operator_id");
                indicatorId = sourceLoc.indicatorId();
                log.info("Destination {} matched internal prefix for type {}, Op {}, using source Ind {}", effectiveDestinationExtension, internalCallTypeId, operatorId, indicatorId);
            } else {
                internalCallTypeId = CdrProcessingConfig.getDefaultInternalCallTypeId();
                indicatorId = sourceLoc.indicatorId();
                log.info("Destination {} not found and no internal prefix matched, defaulting to type {}, using source Ind {}", effectiveDestinationExtension, internalCallTypeId, indicatorId);
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
        CdrProcessingConfig.ExtensionLengthConfig extConfig = configService.getExtensionLengthConfig(commLocation.getId());

        String numberForAttempt;
        if (usesTrunk && trunkOpt.get().getNoPbxPrefix() != null && trunkOpt.get().getNoPbxPrefix()) {
            boolean removePbx = trunkOpt.get().getNoPbxPrefix(); 
            numberForAttempt = cdrNumberProcessingService.cleanNumber(numberAfterPbxAndPreprocessing, pbxPrefixes, removePbx, extConfig);
            log.info("Trunk call. Trunk noPbxPrefix={}, so removePbxForClean={}. Number for lookup: {}", trunkOpt.get().getNoPbxPrefix(), removePbx, numberForAttempt);
        } else {
            numberForAttempt = cdrNumberProcessingService.cleanNumber(numberAfterPbxAndPreprocessing, pbxPrefixes, true, extConfig);
            log.info("Non-trunk call or trunk implies PBX prefix might be used. Cleaned number for lookup: {}", numberForAttempt);
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
            log.info("Initial rate lookup for trunk call {} resulted in assumed/error rate or no rate. Attempting fallback as non-trunk.", standardDto.getGlobalCallId());
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

            if (fallbackRateOpt.isPresent() && !cdrEnrichmentHelper.isRateAssumedOrError(fallbackRateOpt.get(), (Long)fallbackRateOpt.get().get("telephony_type_id"))) {
                boolean originalIsErrorOrAssumed = rateLookupResultOpt.map(r -> cdrEnrichmentHelper.isRateAssumedOrError(r, (Long)r.get("telephony_type_id"))).orElse(true);
                
                boolean typeChanged = !originalIsErrorOrAssumed &&
                                      !rateLookupResultOpt.get().get("telephony_type_id").equals(fallbackRateOpt.get().get("telephony_type_id"));
                boolean indicatorChanged = !originalIsErrorOrAssumed &&
                                           !Optional.ofNullable(rateLookupResultOpt.get().get("indicator_id"))
                                                .equals(Optional.ofNullable(fallbackRateOpt.get().get("indicator_id")));

                if (originalIsErrorOrAssumed || typeChanged || indicatorChanged) {
                    rateLookupResultOpt = fallbackRateOpt;
                    log.info("Fallback (non-trunk) lookup provided a better or different valid rate for {}.", standardDto.getGlobalCallId());
                } else {
                     log.info("Fallback (non-trunk) lookup did not provide a better or sufficiently different rate for {}. Original trunk rate (or assumed/error) retained.", standardDto.getGlobalCallId());
                }
            } else {
                log.info("Fallback (non-trunk) lookup did not provide a valid rate for {}.", standardDto.getGlobalCallId());
            }
        }

        if (rateLookupResultOpt.isPresent()) {
            Map<String, Object> currentRateInfo = new HashMap<>(rateLookupResultOpt.get());
            callBuilder.telephonyTypeId((Long) currentRateInfo.get("telephony_type_id"));
            callBuilder.operatorId((Long) currentRateInfo.get("operator_id"));
            callBuilder.indicatorId((Long) currentRateInfo.get("indicator_id"));
            callBuilder.dial((String) currentRateInfo.getOrDefault("number_for_dial_field", numberForAttempt));


            cdrPricingService.applySpecialPricing(currentRateInfo, standardDto.getCallStartTime(), standardDto.getDurationSeconds(), originIndicatorId, callBuilder);

            if (usesTrunk && !(Boolean)currentRateInfo.getOrDefault("applied_trunk_pricing_by_rule", false)) {
                 cdrPricingService.applyTrunkRuleOverrides(trunkOpt.get(), currentRateInfo, standardDto.getDurationSeconds(), originIndicatorId, callBuilder);
            }

            cdrPricingService.applyFinalPricing(currentRateInfo, standardDto.getDurationSeconds(), callBuilder);

        } else {
            log.warn("Could not determine rate for number: {} (effective for attempt: {}) after all fallbacks.", standardDto.getCalledPartyNumber(), numberForAttempt);
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
                    log.debug("Operator prefix '{}' removed from '{}', result: '{}'", currentPrefixCode, numberForLookup, numberWithoutOperatorPrefix);
                } else {
                    log.debug("Operator prefix '{}' matched for '{}', but not removed (removeOperatorPrefixThisIteration={}, isLocal={})",
                             currentPrefixCode, numberForLookup, removeOperatorPrefixThisIteration, currentTelephonyTypeId == CdrProcessingConfig.TIPOTELE_LOCAL);
                }
            }

            if (numberWithoutOperatorPrefix.isEmpty() && StringUtils.hasText(numberForLookup) && operatorPrefixRemoved) {
                log.debug("Number became empty after stripping prefix '{}' from '{}', skipping this prefix.", currentPrefixCode, numberForLookup);
                continue;
            }

            int operatorPrefixLength = (StringUtils.hasText(currentPrefixCode) && operatorPrefixRemoved ? currentPrefixCode.length() : 0);
            int effectiveMinLength = Math.max(0, typeMinLength - operatorPrefixLength);
            int effectiveMaxLength = Math.max(0, typeMaxLength - operatorPrefixLength);


            if (numberWithoutOperatorPrefix.length() < effectiveMinLength) {
                 log.debug("Subscriber part '{}' (from num '{}') too short for prefix '{}' (effectiveMinLength {})", numberWithoutOperatorPrefix, numberForLookup, currentPrefixCode, effectiveMinLength);
                continue;
            }
            String finalNumberForIndicatorLookup = numberWithoutOperatorPrefix;
            if (effectiveMaxLength > 0 && numberWithoutOperatorPrefix.length() > effectiveMaxLength) {
                finalNumberForIndicatorLookup = numberWithoutOperatorPrefix.substring(0, effectiveMaxLength);
                log.debug("Subscriber part '{}' truncated to '{}' (effectiveMaxLength {}) for indicator lookup.", numberWithoutOperatorPrefix, finalNumberForIndicatorLookup, effectiveMaxLength);
            }

            Optional<Map<String, Object>> indicatorInfoOpt = prefixInfoLookupService.findIndicatorByNumber(
                    finalNumberForIndicatorLookup, currentTelephonyTypeId, originCountryId,
                    currentPrefixBandOk, currentPrefixId, originCommLocationIndicatorId
            );

            boolean destinationFound = indicatorInfoOpt.isPresent() && !(Boolean)indicatorInfoOpt.get().getOrDefault("is_approximate", false);
            boolean approximateDestinationFound = indicatorInfoOpt.isPresent() && (Boolean)indicatorInfoOpt.get().getOrDefault("is_approximate", false);

            boolean lengthMatchOnly = (cdrEnrichmentHelper.maxNdcLength(currentTelephonyTypeId, originCountryId) == 0 &&
                    numberWithoutOperatorPrefix.length() == typeMaxLength &&
                    !destinationFound && !approximateDestinationFound &&
                    prefixes.size() == 1);
            boolean considerMatch = destinationFound || lengthMatchOnly;


            if (considerMatch || (approximateDestinationFound && assumedRateInfo == null) ) { 
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
                    rateInfo.put("destination_name", indicatorInfoOpt.map(cdrEnrichmentHelper::formatDestinationName).orElse(lengthMatchOnly ? "Unknown (Length Match)" : (approximateDestinationFound ? "Unknown (Approx. Match)" : "Unknown Destination")));
                    rateInfo.put("effective_number", numberForLookup);
                    rateInfo.put("number_for_dial_field", numberWithoutOperatorPrefix);
                    rateInfo.put("applied_trunk_pricing", false);

                    if (usesTrunk && trunk != null) {
                        Long operatorForTrunkRate = cdrEnrichmentHelper.findEffectiveTrunkOperator(trunk, finalTelephonyTypeId, currentPrefixCode, currentOperatorId, originCountryId);
                        if (operatorForTrunkRate != null && operatorForTrunkRate >=0) {
                            Optional<TrunkRate> trOpt = trunkLookupService.findTrunkRate(trunk.getId(), operatorForTrunkRate, finalTelephonyTypeId);
                            if (trOpt.isPresent()) {
                                TrunkRate tr = trOpt.get();
                                rateInfo.put("valor_inicial", rateInfo.get("valor_minuto"));
                                rateInfo.put("valor_inicial_iva", rateInfo.get("valor_minuto_iva"));
                                rateInfo.put("valor_minuto", Optional.ofNullable(tr.getRateValue()).orElse(BigDecimal.ZERO));
                                rateInfo.put("valor_minuto_iva", Optional.ofNullable(tr.getIncludesVat()).orElse(false));
                                rateInfo.put("ensegundos", tr.getSeconds() != null && tr.getSeconds() > 0);
                                prefixInfoLookupService.findPrefixByTypeOperatorOrigin(finalTelephonyTypeId, operatorForTrunkRate, originCountryId)
                                        .ifPresent(p -> rateInfo.put("iva", p.getVatValue()));
                                rateInfo.put("applied_trunk_pricing", true);
                            }
                        }
                    }
                    if (approximateDestinationFound && assumedRateInfo == null) {
                        assumedRateInfo = new HashMap<>(rateInfo); 
                        log.debug("Stored approximate rate for {}: {}", numberForLookup, assumedRateInfo);
                        if (destinationFound || lengthMatchOnly) { 
                             log.info("Best rate found (exact/length) for {}: {}", numberForLookup, rateInfo);
                             return Optional.of(rateInfo);
                        }
                    } else if (destinationFound || lengthMatchOnly) {
                        log.info("Best rate found (exact/length) for {}: {}", numberForLookup, rateInfo);
                        return Optional.of(rateInfo);
                    }
                }
            }
        }

        if (assumedRateInfo != null) {
            log.info("Using best available (approximate) rate for {}: {}", numberForLookup, assumedRateInfo);
            return Optional.of(assumedRateInfo);
        }

        boolean allPrefixesPointToSameTypeAndOperator = prefixes.size() > 0 &&
                prefixes.stream().map(p -> p.get("telephony_type_id")).distinct().count() <= 1 &&
                prefixes.stream().map(p -> p.get("operator_id")).distinct().count() <= 1;

        if (allPrefixesPointToSameTypeAndOperator && !prefixes.isEmpty()) {
            Map<String, Object> firstPrefix = prefixes.get(0);
            Long assumedTelephonyTypeId = (Long) firstPrefix.get("telephony_type_id");
            Long assumedOperatorId = (Long) firstPrefix.get("operator_id");
            Long assumedPrefixId = (Long) firstPrefix.get("prefix_id");
            boolean assumedBandOk = (Boolean) firstPrefix.get("prefix_band_ok");

            Optional<Map<String, Object>> baseRateOpt = cdrPricingService.findRateInfo(assumedPrefixId, null, originCommLocationIndicatorId, assumedBandOk);
            if (baseRateOpt.isPresent()) {
                Map<String, Object> rateMap = new HashMap<>(baseRateOpt.get());
                rateMap.put("telephony_type_id", assumedTelephonyTypeId);
                rateMap.put("operator_id", assumedOperatorId);
                rateMap.put("indicator_id", null);
                rateMap.put("telephony_type_name", (String) firstPrefix.get("telephony_type_name") + ASSUMED_SUFFIX);
                rateMap.put("operator_name", (String) firstPrefix.get("operator_name"));
                rateMap.put("destination_name", "Assumed Destination");
                rateMap.put("effective_number", numberForLookup);
                rateMap.put("number_for_dial_field", numberForLookup);
                rateMap.put("applied_trunk_pricing", false);
                rateMap.putIfAbsent("valor_minuto", rateMap.get("base_value"));
                rateMap.putIfAbsent("valor_minuto_iva", rateMap.get("vat_included"));
                rateMap.putIfAbsent("iva", rateMap.get("vat_value"));
                rateMap.putIfAbsent("valor_inicial", rateMap.get("valor_minuto"));
                rateMap.putIfAbsent("valor_inicial_iva", rateMap.get("valor_minuto_iva"));
                rateMap.putIfAbsent("ensegundos", false);

                log.info("Using assumed rate (all prefixes same type/op) for {}: {}", numberForLookup, rateMap);
                return Optional.of(rateMap);
            }
        }

        log.warn("No definitive or assumed rate found for numberForLookup: {}", numberForLookup);
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
        } else if (record.getIndicatorId() == null && record.getIndicator() != null) {
            callBuilder.indicator(null);
        }

        if (record.getEmployeeId() != null && record.getEmployee() == null) {
             employeeLookupService.findEmployeeById(record.getEmployeeId())
                .ifPresentOrElse(callBuilder::employee, () -> log.warn("Could not link main employee entity for ID: {}", record.getEmployeeId()));
        } else if (record.getEmployeeId() == null && record.getEmployee() != null) {
            callBuilder.employee(null);
        }


        if (record.getDestinationEmployeeId() != null && record.getDestinationEmployee() == null) {
            employeeLookupService.findEmployeeById(record.getDestinationEmployeeId())
                    .ifPresentOrElse(callBuilder::destinationEmployee, () -> log.warn("Could not link destination employee entity for ID: {}", record.getDestinationEmployeeId()));
        } else if (record.getDestinationEmployeeId() == null && record.getDestinationEmployee() != null) {
            callBuilder.destinationEmployee(null);
        }
    }
}