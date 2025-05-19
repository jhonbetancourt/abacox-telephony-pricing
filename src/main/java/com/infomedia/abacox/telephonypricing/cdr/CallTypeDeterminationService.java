// File: com/infomedia/abacox/telephonypricing/cdr/CallTypeDeterminationService.java
package com.infomedia.abacox.telephonypricing.cdr;

import com.infomedia.abacox.telephonypricing.entity.CommunicationLocation;
import com.infomedia.abacox.telephonypricing.entity.Employee;
import com.infomedia.abacox.telephonypricing.entity.Indicator;
import com.infomedia.abacox.telephonypricing.entity.Subdivision;
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
    private final CallOriginDeterminationService callOriginDeterminationService;
    private final CdrConfigService appConfigService;
    private ExtensionLimits extensionLimits; // Cache per commLocation processing run

    public void resetExtensionLimitsCache(CommunicationLocation commLocation) {
        if (commLocation != null && commLocation.getIndicator() != null && commLocation.getIndicator().getOriginCountryId() != null) {
            this.extensionLimits = employeeLookupService.getExtensionLimits(
                    commLocation.getIndicator().getOriginCountryId(),
                    commLocation.getId(),
                    commLocation.getPlantTypeId()
            );
        } else {
            this.extensionLimits = new ExtensionLimits(); // Default empty limits
            log.warn("CommunicationLocation, Indicator, or OriginCountryId is null for {}. Using default (restrictive) extension limits.",
                     commLocation != null ? commLocation.getDirectory() : "UNKNOWN_COMMLOC");
        }
    }

    public ExtensionLimits getExtensionLimits() {
        if (this.extensionLimits == null) {
            log.warn("ExtensionLimits accessed before initialization. Returning default. Ensure resetExtensionLimitsCache is called.");
            return new ExtensionLimits();
        }
        return this.extensionLimits;
    }

    /**
     * PHP equivalent: Orchestrates logic from CargarCDR's main loop after `evaluar_Formato`,
     * leading into `procesaEntrante` or `procesaSaliente`.
     * Also incorporates logic from `es_llamada_interna`.
     */
    public void determineCallTypeAndDirection(CdrData cdrData, CommunicationLocation commLocation) {
        ExtensionLimits limits = getExtensionLimits();

        // PHP: $esinterna = es_llamada_interna($info_cdr, $link);
        if (!cdrData.isInternalCall()) { // If not already marked by parser
            if (employeeLookupService.isPossibleExtension(cdrData.getCallingPartyNumber(), limits)) {
                String destinationForInternalCheck = CdrParserUtil.cleanPhoneNumber(cdrData.getFinalCalledPartyNumber(), null, false); // PHP: $destino = limpiar_numero($info['dial_number']);

                Optional<String> pbxInternalTransformed = pbxSpecialRuleLookupService.applyPbxSpecialRule(
                        destinationForInternalCheck, commLocation.getDirectory(), 3 // 3 for internal
                );
                if (pbxInternalTransformed.isPresent()) {
                    destinationForInternalCheck = pbxInternalTransformed.get();
                    cdrData.getAdditionalData().put("internalCheckPbxTransformedDest", destinationForInternalCheck);
                }

                if ((destinationForInternalCheck.length() == 1 && destinationForInternalCheck.matches("\\d")) ||
                    employeeLookupService.isPossibleExtension(destinationForInternalCheck, limits)) {
                    cdrData.setInternalCall(true);
                }
                else if (destinationForInternalCheck.matches("\\d+") && !destinationForInternalCheck.startsWith("0")) {
                    Optional<Employee> employeeFromRange = employeeLookupService.findEmployeeByExtensionRange(
                            destinationForInternalCheck, commLocation.getId(), cdrData.getDateTimeOrigination()
                    );
                    if (employeeFromRange.isPresent()) {
                        cdrData.setInternalCall(true);
                    }
                }
            }
        }
        cdrData.setEffectiveDestinationNumber(cdrData.getFinalCalledPartyNumber());


        if (cdrData.getCallDirection() == CallDirection.INCOMING) {
            processIncomingLogic(cdrData, commLocation, limits);
        } else { // OUTGOING (default)
            processOutgoingLogic(cdrData, commLocation, limits, false);
        }
    }

    /**
     * PHP equivalent: procesaEntrante
     */
    private void processIncomingLogic(CdrData cdrData, CommunicationLocation commLocation, ExtensionLimits limits) {
        if (cdrData.isInternalCall()) {
            log.debug("Internal call initially marked as INCOMING. Inverting and processing as OUTGOING. Original Caller: {}, Original Callee: {}", cdrData.getFinalCalledPartyNumber(), cdrData.getCallingPartyNumber());
            CdrParserUtil.swapPartyInfo(cdrData);
            CdrParserUtil.swapTrunks(cdrData);
            cdrData.setCallDirection(CallDirection.OUTGOING);
            processOutgoingLogic(cdrData, commLocation, limits, false);
            return;
        }

        String externalCallerId = cdrData.getCallingPartyNumber();
        cdrData.setEffectiveDestinationNumber(cdrData.getFinalCalledPartyNumber());

        Optional<String> pbxTransformedCaller = pbxSpecialRuleLookupService.applyPbxSpecialRule(
                externalCallerId, commLocation.getDirectory(), 1
        );
        if (pbxTransformedCaller.isPresent()) {
            cdrData.getAdditionalData().put("originalCallerIdBeforePbxIncoming", externalCallerId);
            externalCallerId = pbxTransformedCaller.get();
            cdrData.setPbxSpecialRuleAppliedInfo("PBX Incoming Rule: " + cdrData.getCallingPartyNumber() + " -> " + externalCallerId);
        }

        TransformationResult transformedIncoming = phoneNumberTransformationService.transformIncomingNumberCME(
                externalCallerId, commLocation.getIndicator().getOriginCountryId()
        );
        if (transformedIncoming.isTransformed()) {
            cdrData.getAdditionalData().put("originalCallerIdBeforeCMETransform", externalCallerId);
            externalCallerId = transformedIncoming.getTransformedNumber();
            if (transformedIncoming.getNewTelephonyTypeId() != null) {
                cdrData.setTelephonyTypeId(transformedIncoming.getNewTelephonyTypeId());
            }
        }

        IncomingCallOriginInfo originInfo = callOriginDeterminationService.determineIncomingCallOrigin(
            externalCallerId, commLocation
        );

        cdrData.setTelephonyTypeId(originInfo.getTelephonyTypeId());
        cdrData.setTelephonyTypeName(originInfo.getTelephonyTypeName());
        cdrData.setOperatorId(originInfo.getOperatorId());
        cdrData.setOperatorName(originInfo.getOperatorName());
        cdrData.setIndicatorId(originInfo.getIndicatorId());
        cdrData.setDestinationCityName(originInfo.getDestinationDescription());
        cdrData.setEffectiveDestinationNumber(originInfo.getEffectiveNumber());

        if (cdrData.getTelephonyTypeId() == null || cdrData.getTelephonyTypeId() == TelephonyTypeEnum.UNKNOWN.getValue()) {
             cdrData.setTelephonyTypeId(TelephonyTypeEnum.LOCAL.getValue());
             cdrData.setTelephonyTypeName(telephonyTypeLookupService.getTelephonyTypeName(TelephonyTypeEnum.LOCAL.getValue()) + " (Default Incoming)");
        }
    }

    /**
     * PHP equivalent: procesaSaliente
     */
    private void processOutgoingLogic(CdrData cdrData, CommunicationLocation commLocation, ExtensionLimits limits, boolean pbxSpecialRuleAppliedRecursively) {
        TransformationResult transformedOutgoing = phoneNumberTransformationService.transformOutgoingNumberCME(
                cdrData.getFinalCalledPartyNumber(), commLocation.getIndicator().getOriginCountryId()
        );
        if (transformedOutgoing.isTransformed()) {
            cdrData.getAdditionalData().put("originalDialNumberBeforeCMETransform", cdrData.getFinalCalledPartyNumber());
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
                    cdrData.setOperatorName(specialServiceInfo.get().operatorName);
                    cdrData.setIndicatorId(commLocation.getIndicatorId());
                    cdrData.setEffectiveDestinationNumber(numToCheckSpecial);
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
            if (pbxTransformedDest.isPresent() && !Objects.equals(pbxTransformedDest.get(), cdrData.getEffectiveDestinationNumber())) {
                String originalDest = cdrData.getEffectiveDestinationNumber();
                cdrData.getAdditionalData().put("originalDialNumberBeforePbxOutgoing", originalDest);
                cdrData.setFinalCalledPartyNumber(pbxTransformedDest.get());
                cdrData.setEffectiveDestinationNumber(pbxTransformedDest.get());
                cdrData.setPbxSpecialRuleAppliedInfo("PBX Outgoing Rule: " + originalDest + " -> " + pbxTransformedDest.get());
                log.debug("Re-processing outgoing logic after PBX rule transformed {} to {}", originalDest, cdrData.getEffectiveDestinationNumber());
                processOutgoingLogic(cdrData, commLocation, limits, true);
                return;
            }
        }
        
        if (cdrData.getTelephonyTypeId() == null || cdrData.getTelephonyTypeId() == TelephonyTypeEnum.UNKNOWN.getValue()) {
             log.debug("Outgoing call, telephony type to be determined by prefix/tariffing: {}", cdrData.getEffectiveDestinationNumber());
        }
    }

    /**
     * PHP equivalent: procesaInterna
     */
    private void processInternalCallLogic(CdrData cdrData, CommunicationLocation commLocation, ExtensionLimits limits, boolean pbxSpecialRuleAppliedRecursively) {
        List<String> prefixesToClean = null;
        boolean stripOnlyIfPrefixMatches = true;
        if (pbxSpecialRuleAppliedRecursively && commLocation.getPbxPrefix() != null) {
            prefixesToClean = Arrays.asList(commLocation.getPbxPrefix().split(","));
        } else if (!pbxSpecialRuleAppliedRecursively) {
            stripOnlyIfPrefixMatches = false;
        }

        String cleanedDestination = CdrParserUtil.cleanPhoneNumber(
            cdrData.getEffectiveDestinationNumber(),
            prefixesToClean,
            stripOnlyIfPrefixMatches
        );
        cdrData.setEffectiveDestinationNumber(cleanedDestination);

        if (cdrData.getCallingPartyNumber() != null && !cdrData.getCallingPartyNumber().trim().isEmpty() &&
            Objects.equals(cdrData.getCallingPartyNumber().trim(), cleanedDestination.trim())) {
            log.warn("Internal call to self ignored: {}", cdrData.getCallingPartyNumber());
            cdrData.setTelephonyTypeId(TelephonyTypeEnum.ERRORS.getValue());
            cdrData.setTelephonyTypeName("Internal Self-Call (Ignored)");
            cdrData.setMarkedForQuarantine(true);
            cdrData.setQuarantineReason("Internal Self-Call (PHP: IGUALDESTINO)");
            cdrData.setQuarantineStep("processInternalCallLogic_SelfCall");
            return;
        }

        InternalCallTypeInfo internalTypeInfo = determineSpecificInternalCallType(cdrData, commLocation, limits);

        if (internalTypeInfo.isIgnoreCall()) {
            log.warn("Internal call marked for ignore by tipo_llamada_interna logic: {}", cdrData.getRawCdrLine());
            cdrData.setTelephonyTypeId(TelephonyTypeEnum.ERRORS.getValue());
            cdrData.setTelephonyTypeName("Internal Call Ignored (Policy: " + internalTypeInfo.getAdditionalInfo() + ")");
            cdrData.setMarkedForQuarantine(true);
            cdrData.setQuarantineReason(internalTypeInfo.getAdditionalInfo() != null ? internalTypeInfo.getAdditionalInfo() : "Internal call ignore policy");
            cdrData.setQuarantineStep("processInternalCallLogic_IgnorePolicy");
            return;
        }

        cdrData.setTelephonyTypeId(internalTypeInfo.getTelephonyTypeId());
        cdrData.setTelephonyTypeName(internalTypeInfo.getTelephonyTypeName());
        if (internalTypeInfo.getAdditionalInfo() != null && !internalTypeInfo.getAdditionalInfo().isEmpty()) {
            cdrData.setTelephonyTypeName(cdrData.getTelephonyTypeName() + " " + internalTypeInfo.getAdditionalInfo());
        }
        cdrData.setIndicatorId(internalTypeInfo.getDestinationIndicatorId());

        if (internalTypeInfo.isEffectivelyIncoming() && cdrData.getCallDirection() == CallDirection.OUTGOING) {
            log.debug("Internal call determined to be effectively INCOMING. Inverting. Original Caller: {}, Original Callee: {}", cdrData.getCallingPartyNumber(), cdrData.getEffectiveDestinationNumber());
            CdrParserUtil.swapPartyInfo(cdrData);
            cdrData.setCallDirection(CallDirection.INCOMING);
            cdrData.setEmployee(internalTypeInfo.getDestinationEmployee());
            cdrData.setEmployeeId(internalTypeInfo.getDestinationEmployee() != null ? internalTypeInfo.getDestinationEmployee().getId() : null);
            cdrData.setDestinationEmployee(internalTypeInfo.getOriginEmployee());
            cdrData.setDestinationEmployeeId(internalTypeInfo.getOriginEmployee() != null ? internalTypeInfo.getOriginEmployee().getId() : null);
            cdrData.setIndicatorId(internalTypeInfo.getOriginIndicatorId());
        } else {
            cdrData.setEmployee(internalTypeInfo.getOriginEmployee());
            cdrData.setEmployeeId(internalTypeInfo.getOriginEmployee() != null ? internalTypeInfo.getOriginEmployee().getId() : null);
            cdrData.setDestinationEmployee(internalTypeInfo.getDestinationEmployee());
            cdrData.setDestinationEmployeeId(internalTypeInfo.getDestinationEmployee() != null ? internalTypeInfo.getDestinationEmployee().getId() : null);
        }

        if (cdrData.getTelephonyTypeId() != null && commLocation.getIndicator() != null) {
             OperatorInfo internalOp = telephonyTypeLookupService.getInternalOperatorInfo(
                cdrData.getTelephonyTypeId(), commLocation.getIndicator().getOriginCountryId()
            );
            cdrData.setOperatorId(internalOp.getId());
            cdrData.setOperatorName(internalOp.getName());
        }
    }

    /**
     * PHP equivalent: tipo_llamada_interna
     */
    private InternalCallTypeInfo determineSpecificInternalCallType(CdrData cdrData, CommunicationLocation currentCommLocation, ExtensionLimits limits) {
        InternalCallTypeInfo result = new InternalCallTypeInfo();
        result.setTelephonyTypeId(appConfigService.getDefaultInternalCallTypeId()); // Default
        result.setTelephonyTypeName(telephonyTypeLookupService.getTelephonyTypeName(result.getTelephonyTypeId()));
        result.setDestinationIndicatorId(currentCommLocation.getIndicatorId());
        result.setOriginIndicatorId(currentCommLocation.getIndicatorId());

        Optional<Employee> originEmpOpt = employeeLookupService.findEmployeeByExtensionOrAuthCode(cdrData.getCallingPartyNumber(), null, currentCommLocation.getId(), cdrData.getDateTimeOrigination());
        Optional<Employee> destEmpOpt = employeeLookupService.findEmployeeByExtensionOrAuthCode(cdrData.getEffectiveDestinationNumber(), null, null, cdrData.getDateTimeOrigination()); // Search globally for dest

        if (originEmpOpt.isEmpty() && employeeLookupService.isPossibleExtension(cdrData.getCallingPartyNumber(), limits)) {
            originEmpOpt = employeeLookupService.findEmployeeByExtensionRange(cdrData.getCallingPartyNumber(), currentCommLocation.getId(), cdrData.getDateTimeOrigination());
        }
        if (destEmpOpt.isEmpty() && employeeLookupService.isPossibleExtension(cdrData.getEffectiveDestinationNumber(), limits)) {
            destEmpOpt = employeeLookupService.findEmployeeByExtensionRange(cdrData.getEffectiveDestinationNumber(), null, cdrData.getDateTimeOrigination());
        }
        
        result.setOriginEmployee(originEmpOpt.orElse(null));
        result.setDestinationEmployee(destEmpOpt.orElse(null));

        CommunicationLocation originCommLoc = originEmpOpt.map(Employee::getCommunicationLocation).orElse(currentCommLocation);
        CommunicationLocation destCommLoc = destEmpOpt.map(Employee::getCommunicationLocation).orElse(null);

        if (originCommLoc != null && destCommLoc == null && originEmpOpt.isPresent()) {
            destCommLoc = currentCommLocation;
            log.debug("Internal call, origin employee known, destination unknown. Assuming destination is current plant: {}", currentCommLocation.getDirectory());
        }
        
        boolean extGlobales = appConfigService.areExtensionsGlobal(currentCommLocation.getPlantTypeId());
        if (extGlobales && originCommLoc != null && destCommLoc != null &&
            (!Objects.equals(currentCommLocation.getId(), originCommLoc.getId()) || !Objects.equals(currentCommLocation.getId(), destCommLoc.getId()))) {
            if (!Objects.equals(currentCommLocation.getId(), originCommLoc.getId()) && Objects.equals(currentCommLocation.getId(), destCommLoc.getId())) {
                result.setIgnoreCall(true);
                result.setAdditionalInfo("Global Extension - Incoming from another plant");
                return result;
            } else if (!Objects.equals(currentCommLocation.getId(), originCommLoc.getId()) && !Objects.equals(currentCommLocation.getId(), destCommLoc.getId())) {
                result.setIgnoreCall(true);
                result.setAdditionalInfo("Global Extension - Call between two other plants");
                return result;
            }
        }

        if (destEmpOpt.isEmpty()) { // PHP: if ($subdireccionDestino == '')
            // PHP: foreach ($_lista_Prefijos['ttin'] as $prefijo_txt => $tipotele_id) ...
            // This part checks if destination starts with an internal prefix.
            // For simplicity, if dest is unknown, use default internal type.
            result.setTelephonyTypeId(appConfigService.getDefaultInternalCallTypeId());
            result.setTelephonyTypeName(telephonyTypeLookupService.getTelephonyTypeName(result.getTelephonyTypeId()));
            result.setAdditionalInfo(appConfigService.getAssumedText());
        } else if (originCommLoc != null && destCommLoc != null && originCommLoc.getIndicator() != null && destCommLoc.getIndicator() != null) {
            Indicator originIndicator = originCommLoc.getIndicator();
            Indicator destIndicator = destCommLoc.getIndicator();
            result.setOriginIndicatorId(originIndicator.getId());
            result.setDestinationIndicatorId(destIndicator.getId());

            Subdivision originSubdivision = originEmpOpt.map(Employee::getSubdivision).orElse(null);
            Subdivision destSubdivision = destEmpOpt.map(Employee::getSubdivision).orElse(null);

            if (!Objects.equals(originIndicator.getOriginCountryId(), destIndicator.getOriginCountryId())) {
                result.setTelephonyTypeId(TelephonyTypeEnum.INTERNAL_INTERNATIONAL_IP.getValue());
            } else if (!Objects.equals(originIndicator.getId(), destIndicator.getId())) {
                result.setTelephonyTypeId(TelephonyTypeEnum.NATIONAL_IP.getValue());
            } else if (originSubdivision != null && destSubdivision != null &&
                       !Objects.equals(originSubdivision.getId(), destSubdivision.getId())) { // Comparing Subdivision IDs as "office"
                result.setTelephonyTypeId(TelephonyTypeEnum.LOCAL_IP.getValue());
            } else {
                result.setTelephonyTypeId(TelephonyTypeEnum.INTERNAL_SIMPLE.getValue());
            }
            result.setTelephonyTypeName(telephonyTypeLookupService.getTelephonyTypeName(result.getTelephonyTypeId()));
            if (originEmpOpt.isEmpty()) {
                result.setAdditionalInfo(appConfigService.getAssumedText() + "/" + appConfigService.getOriginText());
            }
        } else { // One of the commlocs or indicators is null, treat as assumed
             result.setTelephonyTypeId(appConfigService.getDefaultInternalCallTypeId());
             result.setTelephonyTypeName(telephonyTypeLookupService.getTelephonyTypeName(result.getTelephonyTypeId()));
             result.setAdditionalInfo(appConfigService.getAssumedText());
             if (originCommLoc != null && originCommLoc.getIndicator() != null) result.setOriginIndicatorId(originCommLoc.getIndicatorId());
             if (destCommLoc != null && destCommLoc.getIndicator() != null) result.setDestinationIndicatorId(destCommLoc.getIndicatorId());
        }
        
        if (originEmpOpt.isEmpty() && destEmpOpt.isPresent() &&
            cdrData.getCallDirection() == CallDirection.OUTGOING && // Call was not already incoming
            destCommLoc != null && Objects.equals(destCommLoc.getId(), currentCommLocation.getId())) {
            result.setEffectivelyIncoming(true);
            if (originCommLoc != null && originCommLoc.getIndicator() != null) {
                 result.setDestinationIndicatorId(originCommLoc.getIndicatorId()); // For incoming, "destination" for tariffing is origin's indicator
            }
        }
        return result;
    }
}