package com.infomedia.abacox.telephonypricing.cdr;

import com.infomedia.abacox.telephonypricing.entity.CommunicationLocation;
import com.infomedia.abacox.telephonypricing.entity.Employee;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.stereotype.Service;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

@Service
@Log4j2
@RequiredArgsConstructor
public class CallTypeDeterminationService {

    private final EmployeeLookupService employeeLookupService;
    private final PbxSpecialRuleLookupService pbxSpecialRuleLookupService;
    private final SpecialServiceLookupService specialServiceLookupService;
    private final TelephonyTypeLookupService telephonyTypeLookupService;
    private final PhoneNumberTransformationService phoneNumberTransformationService;
    private final CdrConfigService appConfigService;
    private final CommunicationLocationLookupService communicationLocationLookupService; // Added
    private ExtensionLimits extensionLimits; // Cache for current processing context

    // Called by CdrFileProcessorService at the start of a new file/stream
    public void resetExtensionLimitsCache(CommunicationLocation commLocation) {
        if (commLocation != null && commLocation.getIndicator() != null) {
            this.extensionLimits = employeeLookupService.getExtensionLimits(
                    commLocation.getIndicator().getOriginCountryId(),
                    commLocation.getId(),
                    commLocation.getPlantTypeId()
            );
        } else {
            this.extensionLimits = new ExtensionLimits(); // Default if commLocation is incomplete
            log.warn("CommunicationLocation or its Indicator is null, using default extension limits.");
        }
    }

    public ExtensionLimits getExtensionLimits() {
        if (this.extensionLimits == null) {
            log.warn("ExtensionLimits accessed before initialization. Returning default. Ensure resetExtensionLimitsCache is called.");
            return new ExtensionLimits(); // Should not happen if reset is called
        }
        return this.extensionLimits;
    }

    public void determineCallTypeAndDirection(CdrData cdrData, CommunicationLocation commLocation) {
        ExtensionLimits limits = getExtensionLimits(); // Use cached limits

        boolean isInitiallyInternalByParser = cdrData.isInternalCall();
        cdrData.setInternalCall(determineIfEffectivelyInternal(cdrData, commLocation, limits));

        if (cdrData.getCallDirection() == CallDirection.INCOMING) {
            processIncomingLogic(cdrData, commLocation, limits, isInitiallyInternalByParser);
        } else { // OUTGOING by default
            processOutgoingLogic(cdrData, commLocation, limits, false, isInitiallyInternalByParser);
        }
    }

    private boolean determineIfEffectivelyInternal(CdrData cdrData, CommunicationLocation commLocation, ExtensionLimits limits) {
        if (cdrData.isInternalCall()) {
            return true;
        }
        if (cdrData.getCallingPartyNumber() == null || cdrData.getFinalCalledPartyNumber() == null) {
            return false;
        }

        if (!employeeLookupService.isPossibleExtension(cdrData.getCallingPartyNumber(), limits)) {
            return false;
        }

        String destinationForInternalCheck = CdrParserUtil.cleanPhoneNumber(cdrData.getFinalCalledPartyNumber(), null, true);

        Optional<String> pbxInternalTransformed = pbxSpecialRuleLookupService.applyPbxSpecialRule(
                destinationForInternalCheck, commLocation.getDirectory(), 3
        );
        if (pbxInternalTransformed.isPresent()) {
            destinationForInternalCheck = pbxInternalTransformed.get();
        }

        if (employeeLookupService.isPossibleExtension(destinationForInternalCheck, limits)) {
            return true;
        }

        Optional<Employee> employeeFromRange = employeeLookupService.findEmployeeByExtensionRange(
                destinationForInternalCheck, commLocation.getId(), cdrData.getDateTimeOrigination()
        );
        return employeeFromRange.isPresent();
    }


    private void processIncomingLogic(CdrData cdrData, CommunicationLocation commLocation, ExtensionLimits limits, boolean wasInitiallyInternalByParser) {
        if (cdrData.isInternalCall()) {
            log.debug("Internal call initially marked as INCOMING. Inverting and processing as OUTGOING. Original Caller: {}, Original Callee: {}", cdrData.getFinalCalledPartyNumber(), cdrData.getCallingPartyNumber());
            String tempExt = cdrData.getCallingPartyNumber();
            cdrData.setCallingPartyNumber(cdrData.getFinalCalledPartyNumber());
            cdrData.setFinalCalledPartyNumber(tempExt);
            String tempPart = cdrData.getCallingPartyNumberPartition();
            cdrData.setCallingPartyNumberPartition(cdrData.getFinalCalledPartyNumberPartition());
            cdrData.setFinalCalledPartyNumberPartition(tempPart);
            String tempTrunk = cdrData.getOrigDeviceName();
            cdrData.setOrigDeviceName(cdrData.getDestDeviceName());
            cdrData.setDestDeviceName(tempTrunk);
            cdrData.setCallDirection(CallDirection.OUTGOING);
            processOutgoingLogic(cdrData, commLocation, limits, false, true);
            return;
        }

        String originalCallerId = cdrData.getCallingPartyNumber();
        Optional<String> pbxTransformedCaller = pbxSpecialRuleLookupService.applyPbxSpecialRule(
                originalCallerId, commLocation.getDirectory(), 1
        );
        if (pbxTransformedCaller.isPresent()) {
            cdrData.getAdditionalData().put("originalCallerIdBeforePbxIncoming", originalCallerId);
            cdrData.setCallingPartyNumber(pbxTransformedCaller.get());
            cdrData.setPbxSpecialRuleAppliedInfo("PBX Incoming Rule: " + originalCallerId + " -> " + pbxTransformedCaller.get());
        }

        TransformationResult transformedIncoming = phoneNumberTransformationService.transformIncomingNumber(
                cdrData.getCallingPartyNumber(), commLocation.getIndicator().getOriginCountryId()
        );
        if (transformedIncoming.isTransformed()) {
            cdrData.getAdditionalData().put("originalCallerIdBeforeNatTransform", cdrData.getCallingPartyNumber());
            cdrData.setCallingPartyNumber(transformedIncoming.getTransformedNumber());
            if (transformedIncoming.getNewTelephonyTypeId() != null) {
                cdrData.setTelephonyTypeId(transformedIncoming.getNewTelephonyTypeId());
            }
        }
        
        if (cdrData.getTelephonyTypeId() == null || cdrData.getTelephonyTypeId() == 0L) {
             cdrData.setTelephonyTypeId(TelephonyTypeEnum.LOCAL.getValue());
             cdrData.setTelephonyTypeName("Incoming Local (Default)");
        }
        cdrData.setEffectiveDestinationNumber(cdrData.getCallingPartyNumber());
    }

    private void processOutgoingLogic(CdrData cdrData, CommunicationLocation commLocation, ExtensionLimits limits, boolean pbxSpecialRuleAppliedRecursively, boolean wasInitiallyInternal) {
        TransformationResult transformedOutgoing = phoneNumberTransformationService.transformOutgoingNumber(
                cdrData.getFinalCalledPartyNumber(), commLocation.getIndicator().getOriginCountryId()
        );
        if (transformedOutgoing.isTransformed()) {
            cdrData.getAdditionalData().put("originalDialNumberBeforeNatTransform", cdrData.getFinalCalledPartyNumber());
            cdrData.setFinalCalledPartyNumber(transformedOutgoing.getTransformedNumber());
        }
        cdrData.setEffectiveDestinationNumber(cdrData.getFinalCalledPartyNumber());


        if (!pbxSpecialRuleAppliedRecursively && !cdrData.isInternalCall()) {
            String numToCheckSpecial = CdrParserUtil.cleanPhoneNumber(
                    cdrData.getEffectiveDestinationNumber(),
                    commLocation.getPbxPrefix() != null ? Arrays.asList(commLocation.getPbxPrefix().split(",")) : Collections.emptyList(),
                    true
            );
            if (numToCheckSpecial != null && !numToCheckSpecial.isEmpty()) {
                Optional<SpecialServiceInfo> specialServiceInfo =
                        specialServiceLookupService.findSpecialService(
                                numToCheckSpecial,
                                commLocation.getIndicatorId(),
                                commLocation.getIndicator().getOriginCountryId()
                        );
                if (specialServiceInfo.isPresent()) {
                    cdrData.setTelephonyTypeId(TelephonyTypeEnum.SPECIAL_SERVICES.getValue());
                    cdrData.setTelephonyTypeName(specialServiceInfo.get().description);
                    cdrData.setOperatorId(specialServiceInfo.get().operatorId);
                    cdrData.getAdditionalData().put("specialServiceTariff", specialServiceInfo.get());
                    log.debug("Call to special service: {}", numToCheckSpecial);
                    return;
                }
            }
        }

        if (cdrData.isInternalCall()) {
            processInternalCallLogic(cdrData, commLocation, limits, pbxSpecialRuleAppliedRecursively);
            return;
        }

        if (!pbxSpecialRuleAppliedRecursively) {
            Optional<String> pbxTransformedDest = pbxSpecialRuleLookupService.applyPbxSpecialRule(
                    cdrData.getEffectiveDestinationNumber(), commLocation.getDirectory(), 2
            );
            if (pbxTransformedDest.isPresent()) {
                String originalDest = cdrData.getEffectiveDestinationNumber();
                cdrData.setEffectiveDestinationNumber(pbxTransformedDest.get());
                cdrData.setFinalCalledPartyNumber(pbxTransformedDest.get());
                cdrData.setPbxSpecialRuleAppliedInfo("PBX Outgoing Rule: " + originalDest + " -> " + pbxTransformedDest.get());
                log.debug("Re-processing outgoing logic after PBX rule transformed {} to {}", originalDest, cdrData.getEffectiveDestinationNumber());
                processOutgoingLogic(cdrData, commLocation, limits, true, wasInitiallyInternal);
                return;
            }
        }
        
        if (cdrData.getTelephonyTypeId() == null || cdrData.getTelephonyTypeId() == 0L) {
             log.debug("Outgoing call, telephony type to be determined by prefix: {}", cdrData.getEffectiveDestinationNumber());
        }
    }

    private void processInternalCallLogic(CdrData cdrData, CommunicationLocation commLocation, ExtensionLimits limits, boolean pbxSpecialRuleAppliedRecursively) {
        String cleanedDestination = CdrParserUtil.cleanPhoneNumber(
            cdrData.getEffectiveDestinationNumber(),
            null,
            true
        );
        cdrData.setEffectiveDestinationNumber(cleanedDestination);


        if (Objects.equals(cdrData.getCallingPartyNumber(), cdrData.getEffectiveDestinationNumber())) {
            log.warn("Internal call to self ignored: {}", cdrData.getCallingPartyNumber());
            cdrData.setTelephonyTypeId(TelephonyTypeEnum.ERRORS.getValue());
            cdrData.setTelephonyTypeName("Internal Self-Call (Ignored)");
            cdrData.setMarkedForQuarantine(true);
            cdrData.setQuarantineReason("Internal Self-Call");
            cdrData.setQuarantineStep("processInternalCallLogic_SelfCall");
            return;
        }

        Optional<Employee> originEmployeeOpt = employeeLookupService.findEmployeeByExtensionOrAuthCode(cdrData.getCallingPartyNumber(), null, null, cdrData.getDateTimeOrigination());
        Optional<Employee> destEmployeeOpt = employeeLookupService.findEmployeeByExtensionOrAuthCode(cdrData.getEffectiveDestinationNumber(), null, null, cdrData.getDateTimeOrigination());

        Long originCommLocId = originEmployeeOpt.map(Employee::getCommunicationLocationId).orElse(commLocation.getId());
        Long destCommLocId = destEmployeeOpt.map(Employee::getCommunicationLocationId).orElse(null);

        if (destEmployeeOpt.isEmpty() && employeeLookupService.isPossibleExtension(cdrData.getEffectiveDestinationNumber(), limits)) {
            Optional<Employee> destFromRangeOpt = employeeLookupService.findEmployeeByExtensionRange(cdrData.getEffectiveDestinationNumber(), null, cdrData.getDateTimeOrigination());
            if (destFromRangeOpt.isPresent()) {
                destEmployeeOpt = destFromRangeOpt;
                destCommLocId = destFromRangeOpt.get().getCommunicationLocationId();
            }
        }
        
        if (destCommLocId == null && originCommLocId != null && !originCommLocId.equals(commLocation.getId())) {
            destCommLocId = commLocation.getId();
            log.debug("Internal call, origin from another plant, assuming destination {} is local to current plant {}", cdrData.getEffectiveDestinationNumber(), commLocation.getDirectory());
        }


        if (originCommLocId != null && destCommLocId != null) {
            // Fetch full CommunicationLocation entities if we only have IDs
            CommunicationLocation originActualCommLoc = originEmployeeOpt.flatMap(e -> Optional.ofNullable(e.getCommunicationLocation()))
                .orElseGet(() -> communicationLocationLookupService.findById(originCommLocId).orElse(commLocation));

            // copy for avoid "Variable used in lambda expression should be final or effectively final"
            Long destCommLocIdFinal = destCommLocId;
            CommunicationLocation destActualCommLoc = destEmployeeOpt.flatMap(e -> Optional.ofNullable(e.getCommunicationLocation()))
                .orElseGet(() -> communicationLocationLookupService.findById(destCommLocIdFinal).orElse(null));

            if (originActualCommLoc.getIndicator() != null && destActualCommLoc != null && destActualCommLoc.getIndicator() != null) {
                if (!Objects.equals(originActualCommLoc.getIndicator().getOriginCountryId(), destActualCommLoc.getIndicator().getOriginCountryId())) {
                    cdrData.setTelephonyTypeId(TelephonyTypeEnum.INTERNAL_IP.getValue());
                    cdrData.setTelephonyTypeName("Internal International IP");
                } else if (!Objects.equals(originActualCommLoc.getIndicatorId(), destActualCommLoc.getIndicatorId())) {
                    cdrData.setTelephonyTypeId(TelephonyTypeEnum.NATIONAL_IP.getValue());
                    cdrData.setTelephonyTypeName("Internal National IP");
                } else if (originEmployeeOpt.isPresent() && destEmployeeOpt.isPresent() &&
                           !Objects.equals(originEmployeeOpt.get().getSubdivisionId(), destEmployeeOpt.get().getSubdivisionId())) {
                    cdrData.setTelephonyTypeId(TelephonyTypeEnum.LOCAL_IP.getValue());
                    cdrData.setTelephonyTypeName("Internal Local IP");
                } else {
                    cdrData.setTelephonyTypeId(TelephonyTypeEnum.INTERNAL_SIMPLE.getValue());
                    cdrData.setTelephonyTypeName("Internal Simple");
                }
            } else {
                 cdrData.setTelephonyTypeId(TelephonyTypeEnum.INTERNAL_SIMPLE.getValue());
                 cdrData.setTelephonyTypeName("Internal (Location Undetermined)");
            }
        } else {
            cdrData.setTelephonyTypeId(TelephonyTypeEnum.INTERNAL_SIMPLE.getValue());
            cdrData.setTelephonyTypeName("Internal (Assumed)");
            if (destEmployeeOpt.isEmpty() && employeeLookupService.isPossibleExtension(cdrData.getEffectiveDestinationNumber(), limits)) {
                cdrData.setTelephonyTypeName(cdrData.getTelephonyTypeName() + " " + appConfigService.getAssumedText());
            }
        }
        if (cdrData.getTelephonyTypeId() != null && commLocation.getIndicator() != null) {
             OperatorInfo internalOp = telephonyTypeLookupService.getInternalOperatorInfo(
                cdrData.getTelephonyTypeId(), commLocation.getIndicator().getOriginCountryId()
            );
            cdrData.setOperatorId(internalOp.getId());
            cdrData.setOperatorName(internalOp.getName());
        }
    }
}