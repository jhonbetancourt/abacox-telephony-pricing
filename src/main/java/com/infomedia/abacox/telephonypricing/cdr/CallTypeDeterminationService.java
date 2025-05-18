package com.infomedia.abacox.telephonypricing.cdr;

import com.infomedia.abacox.telephonypricing.entity.CommunicationLocation;
import com.infomedia.abacox.telephonypricing.entity.Employee;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.stereotype.Service;

import java.util.Arrays;
import java.util.Collections;
import java.util.Optional;
import java.util.Objects; // Added for Objects.equals

@Service
@Log4j2
@RequiredArgsConstructor
public class CallTypeDeterminationService {

    private final EmployeeLookupService employeeLookupService;
    private final PbxSpecialRuleLookupService pbxSpecialRuleLookupService;
    private final SpecialServiceLookupService specialServiceLookupService;
    private final TelephonyTypeLookupService telephonyTypeLookupService;
    private final PhoneNumberTransformationService phoneNumberTransformationService;
    private final CdrConfigService appConfigService; // Added
    private ExtensionLimits extensionLimits;

    public void resetExtensionLimitsCache(CommunicationLocation commLocation) {
        if (this.extensionLimits == null) {
            this.extensionLimits = employeeLookupService.getExtensionLimits(
                    commLocation.getIndicator().getOriginCountryId(),
                    commLocation.getId(),
                    commLocation.getPlantTypeId()
            );
        }
    }

    public ExtensionLimits getExtensionLimits() {
        return this.extensionLimits;
    }

    public void determineCallTypeAndDirection(CdrData cdrData, CommunicationLocation commLocation) {
        ExtensionLimits limits = getExtensionLimits();

        boolean isEffectivelyInternal = determineIfEffectivelyInternal(cdrData, commLocation, limits);
        cdrData.setInternalCall(isEffectivelyInternal);

        if (cdrData.getCallDirection() == CallDirection.INCOMING) {
            processIncomingLogic(cdrData, commLocation, limits);
        } else {
            processOutgoingLogic(cdrData, commLocation, limits, false);
        }
    }

    private boolean determineIfEffectivelyInternal(CdrData cdrData, CommunicationLocation commLocation, ExtensionLimits limits) {
        if (cdrData.isInternalCall()) {
            return true;
        }

        if (!employeeLookupService.isPossibleExtension(cdrData.getCallingPartyNumber(), limits)) {
            return false;
        }

        String destinationForInternalCheck = cdrData.getFinalCalledPartyNumber();
        String cleanedDestination = CdrParserUtil.cleanPhoneNumber(destinationForInternalCheck, null, true);

        Optional<String> pbxInternalTransformed = pbxSpecialRuleLookupService.applyPbxSpecialRule(
                cleanedDestination, commLocation.getDirectory(), 3
        );
        if (pbxInternalTransformed.isPresent()) {
            cleanedDestination = pbxInternalTransformed.get();
        }

        if (employeeLookupService.isPossibleExtension(cleanedDestination, limits)) {
            return true;
        }

        Optional<Employee> employeeFromRange = employeeLookupService.findEmployeeByExtensionRange(
                cleanedDestination, commLocation.getId(), cdrData.getDateTimeOrigination()
        );
        return employeeFromRange.isPresent();
    }

    private void processIncomingLogic(CdrData cdrData, CommunicationLocation commLocation, ExtensionLimits limits) {
        if (cdrData.isInternalCall()) {
            log.debug("Internal call initially marked as incoming. Inverting and processing as outgoing. Original Caller: {}, Original Callee: {}", cdrData.getFinalCalledPartyNumber(), cdrData.getCallingPartyNumber());
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
            processOutgoingLogic(cdrData, commLocation, limits, false);
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

        PhoneNumberTransformationService.TransformationResult transformedIncoming = phoneNumberTransformationService.transformIncomingNumber(
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
    }

    private void processOutgoingLogic(CdrData cdrData, CommunicationLocation commLocation, ExtensionLimits limits, boolean pbxSpecialRuleAppliedRecursively) {
        PhoneNumberTransformationService.TransformationResult transformedOutgoing = phoneNumberTransformationService.transformOutgoingNumber(
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
            if (!numToCheckSpecial.isEmpty()) {
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
                 // Update finalCalledPartyNumber as well, as effectiveDestination is based on it
                cdrData.setFinalCalledPartyNumber(pbxTransformedDest.get());
                cdrData.setPbxSpecialRuleAppliedInfo("PBX Outgoing Rule: " + originalDest + " -> " + pbxTransformedDest.get());
                log.debug("Re-processing outgoing logic after PBX rule transformed {} to {}", originalDest, cdrData.getEffectiveDestinationNumber());
                processOutgoingLogic(cdrData, commLocation, limits, true);
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
            cdrData.setTelephonyTypeName("Internal Self-Call");
            return;
        }

        Optional<Employee> originEmployeeOpt = employeeLookupService.findEmployeeByExtensionOrAuthCode(cdrData.getCallingPartyNumber(), null, commLocation.getId(), cdrData.getDateTimeOrigination());
        Optional<Employee> destEmployeeOpt = employeeLookupService.findEmployeeByExtensionOrAuthCode(cdrData.getEffectiveDestinationNumber(), null, commLocation.getId(), cdrData.getDateTimeOrigination());

        if (originEmployeeOpt.isPresent() && destEmployeeOpt.isPresent()) {
            Employee originEmp = originEmployeeOpt.get();
            Employee destEmp = destEmployeeOpt.get();
            
            CommunicationLocation originCommLoc = originEmp.getCommunicationLocation();
            CommunicationLocation destCommLoc = destEmp.getCommunicationLocation();

            if (originCommLoc != null && destCommLoc != null && originCommLoc.getIndicator() != null && destCommLoc.getIndicator() != null) {
                if (!Objects.equals(originCommLoc.getIndicator().getOriginCountryId(), destCommLoc.getIndicator().getOriginCountryId())) {
                    cdrData.setTelephonyTypeId(TelephonyTypeEnum.INTERNAL_IP.getValue());
                    cdrData.setTelephonyTypeName("Internal International IP");
                } else if (!Objects.equals(originCommLoc.getIndicatorId(), destCommLoc.getIndicatorId())) {
                    cdrData.setTelephonyTypeId(TelephonyTypeEnum.NATIONAL_IP.getValue());
                    cdrData.setTelephonyTypeName("Internal National IP");
                } else if (!Objects.equals(originEmp.getSubdivisionId(), destEmp.getSubdivisionId())) {
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
            if (!destEmployeeOpt.isPresent()) {
                cdrData.setTelephonyTypeName(cdrData.getTelephonyTypeName() + " " + appConfigService.getAssumedText());
            }
        }
        if (cdrData.getTelephonyTypeId() != null && commLocation.getIndicator() != null) {
            cdrData.setOperatorId(telephonyTypeLookupService.getInternalOperatorId(cdrData.getTelephonyTypeId(), commLocation.getIndicator().getOriginCountryId()));
        }
    }
}