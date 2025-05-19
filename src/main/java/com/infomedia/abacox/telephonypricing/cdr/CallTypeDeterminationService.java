package com.infomedia.abacox.telephonypricing.cdr;

import com.infomedia.abacox.telephonypricing.entity.CommunicationLocation;
import com.infomedia.abacox.telephonypricing.entity.Employee;
import com.infomedia.abacox.telephonypricing.entity.Indicator;
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
    private ExtensionLimits extensionLimits;

    public void resetExtensionLimitsCache(CommunicationLocation commLocation) {
        if (commLocation != null && commLocation.getIndicator() != null && commLocation.getIndicator().getOriginCountryId() != null) {
            this.extensionLimits = employeeLookupService.getExtensionLimits(
                    commLocation.getIndicator().getOriginCountryId(),
                    commLocation.getId(),
                    commLocation.getPlantTypeId()
            );
        } else {
            this.extensionLimits = new ExtensionLimits();
            log.warn("CommunicationLocation, Indicator, or OriginCountryId is null for {}. Using default extension limits.",
                     commLocation != null ? commLocation.getDirectory() : "UNKNOWN");
        }
    }

    public ExtensionLimits getExtensionLimits() {
        if (this.extensionLimits == null) {
            log.warn("ExtensionLimits accessed before initialization. Returning default. Ensure resetExtensionLimitsCache is called.");
            return new ExtensionLimits();
        }
        return this.extensionLimits;
    }

    public void determineCallTypeAndDirection(CdrData cdrData, CommunicationLocation commLocation) {
        ExtensionLimits limits = getExtensionLimits();

        boolean isInitiallyInternalByParser = cdrData.isInternalCall();
        cdrData.setInternalCall(determineIfEffectivelyInternal(cdrData, commLocation, limits));

        if (cdrData.getCallDirection() == CallDirection.INCOMING) {
            processIncomingLogic(cdrData, commLocation, limits, isInitiallyInternalByParser);
        } else {
            processOutgoingLogic(cdrData, commLocation, limits, false, isInitiallyInternalByParser);
        }
    }

    private boolean determineIfEffectivelyInternal(CdrData cdrData, CommunicationLocation commLocation, ExtensionLimits limits) {
        if (cdrData.isInternalCall()) { // Already determined by parser (e.g. Cisco partition logic)
            return true;
        }
        if (cdrData.getCallingPartyNumber() == null || cdrData.getFinalCalledPartyNumber() == null) {
            return false;
        }

        if (!employeeLookupService.isPossibleExtension(cdrData.getCallingPartyNumber(), limits)) {
            return false;
        }

        String destinationForInternalCheck = CdrParserUtil.cleanPhoneNumber(cdrData.getFinalCalledPartyNumber(), null, true);

        // PHP: $telefono_eval = evaluarPBXEspecial($link, $destino, $directorio, $cliente, 3); // internas
        Optional<String> pbxInternalTransformed = pbxSpecialRuleLookupService.applyPbxSpecialRule(
                destinationForInternalCheck, commLocation.getDirectory(), 3 // 3 for internal
        );
        if (pbxInternalTransformed.isPresent()) {
            destinationForInternalCheck = pbxInternalTransformed.get();
            cdrData.getAdditionalData().put("internalCheckPbxTransformedDest", destinationForInternalCheck);
        }

        // PHP: $esinterna = ($len_destino == 1 || ExtensionPosible($destino));
        if (destinationForInternalCheck.length() == 1 && destinationForInternalCheck.matches("\\d")) { // Single digit (e.g. operator '0')
            return true;
        }
        if (employeeLookupService.isPossibleExtension(destinationForInternalCheck, limits)) {
            return true;
        }

        // PHP: if (!$esinterna && $no_inicia_cero && $es_numerico && $destino != '' ) { $retornar = Validar_RangoExt(...); $esinterna = $retornar['nuevo']; }
        if (destinationForInternalCheck.matches("\\d+") && !destinationForInternalCheck.startsWith("0")) {
            Optional<Employee> employeeFromRange = employeeLookupService.findEmployeeByExtensionRange(
                    destinationForInternalCheck, commLocation.getId(), cdrData.getDateTimeOrigination()
            );
            return employeeFromRange.isPresent();
        }
        return false;
    }


    private void processIncomingLogic(CdrData cdrData, CommunicationLocation commLocation, ExtensionLimits limits, boolean wasInitiallyInternalByParser) {
        // PHP: if (info_interna($info)) { InvertirLlamada($info); procesaSaliente(...); return; }
        if (cdrData.isInternalCall()) {
            log.debug("Internal call initially marked as INCOMING. Inverting and processing as OUTGOING. Original Caller: {}, Original Callee: {}", cdrData.getFinalCalledPartyNumber(), cdrData.getCallingPartyNumber());
            CdrParserUtil.swapPartyInfo(cdrData); // Swaps calling/called numbers and partitions
            CdrParserUtil.swapTrunks(cdrData);    // Swaps orig/dest device names
            cdrData.setCallDirection(CallDirection.OUTGOING);
            // The employee associated with the call will now be the new callingPartyNumber
            // Re-evaluate if it's still internal after swap, though PHP's procesaSaliente would do this.
            // For simplicity, we assume it remains internal for now, or processOutgoingLogic will re-evaluate.
            processOutgoingLogic(cdrData, commLocation, limits, false, true); // Mark as wasInitiallyInternal
            return;
        }

        String originalCallerId = cdrData.getCallingPartyNumber();
        // PHP: $num_destino = evaluarPBXEspecial($link, $telefono, $directorio, $cliente, 1); // Entrantes
        Optional<String> pbxTransformedCaller = pbxSpecialRuleLookupService.applyPbxSpecialRule(
                originalCallerId, commLocation.getDirectory(), 1 // 1 for incoming
        );
        if (pbxTransformedCaller.isPresent()) {
            cdrData.getAdditionalData().put("originalCallerIdBeforePbxIncoming", originalCallerId);
            cdrData.setCallingPartyNumber(pbxTransformedCaller.get());
            cdrData.setPbxSpecialRuleAppliedInfo("PBX Incoming Rule: " + originalCallerId + " -> " + pbxTransformedCaller.get());
        }

        // PHP: $telefono_eval = _esEntrante_60($telefono_eval, $resultado_directorio);
        // This is specific to CME, but the structure allows it. For CM60, this might not do much.
        TransformationResult transformedIncoming = phoneNumberTransformationService.transformIncomingNumberCME(
                cdrData.getCallingPartyNumber(), commLocation.getIndicator().getOriginCountryId()
        );
        if (transformedIncoming.isTransformed()) {
            cdrData.getAdditionalData().put("originalCallerIdBeforeCMETransform", cdrData.getCallingPartyNumber());
            cdrData.setCallingPartyNumber(transformedIncoming.getTransformedNumber());
            if (transformedIncoming.getNewTelephonyTypeId() != null) {
                cdrData.setTelephonyTypeId(transformedIncoming.getNewTelephonyTypeId());
            }
        }

        // PHP: $info_origen = buscarOrigen(...);
        // This determines the type of the incoming call (Local, National, Cellular etc.)
        IncomingCallOriginInfo originInfo = callOriginDeterminationService.determineIncomingCallOrigin(
            cdrData.getCallingPartyNumber(), commLocation
        );

        cdrData.setTelephonyTypeId(originInfo.getTelephonyTypeId());
        cdrData.setTelephonyTypeName(originInfo.getTelephonyTypeName());
        cdrData.setOperatorId(originInfo.getOperatorId());
        cdrData.setOperatorName(originInfo.getOperatorName());
        cdrData.setIndicatorId(originInfo.getIndicatorId()); // This is the *source* indicator for incoming
        cdrData.setDestinationCityName(originInfo.getDestinationDescription()); // Description of the source
        cdrData.setEffectiveDestinationNumber(originInfo.getEffectiveNumber()); // Number after transformations by buscarOrigen

        if (cdrData.getTelephonyTypeId() == null || cdrData.getTelephonyTypeId() == 0L) {
             cdrData.setTelephonyTypeId(TelephonyTypeEnum.LOCAL.getValue()); // Default if buscarOrigen fails
             cdrData.setTelephonyTypeName(telephonyTypeLookupService.getTelephonyTypeName(TelephonyTypeEnum.LOCAL.getValue()) + " (Default Incoming)");
        }
    }

    private void processOutgoingLogic(CdrData cdrData, CommunicationLocation commLocation, ExtensionLimits limits, boolean pbxSpecialRuleAppliedRecursively, boolean wasInitiallyInternal) {
        // PHP: $val_numero = _es_Saliente($info_cdr['dial_number']);
        TransformationResult transformedOutgoing = phoneNumberTransformationService.transformOutgoingNumberCME(
                cdrData.getFinalCalledPartyNumber(), commLocation.getIndicator().getOriginCountryId()
        );
        if (transformedOutgoing.isTransformed()) {
            cdrData.getAdditionalData().put("originalDialNumberBeforeCMETransform", cdrData.getFinalCalledPartyNumber());
            cdrData.setFinalCalledPartyNumber(transformedOutgoing.getTransformedNumber());
        }
        cdrData.setEffectiveDestinationNumber(cdrData.getFinalCalledPartyNumber());


        // PHP: if (!$pbx_especial && !$esinterna) { $infovalor = procesaServespecial(...); }
        if (!pbxSpecialRuleAppliedRecursively && !cdrData.isInternalCall()) {
            String numToCheckSpecial = CdrParserUtil.cleanPhoneNumber(
                    cdrData.getEffectiveDestinationNumber(),
                    commLocation.getPbxPrefix() != null ? Arrays.asList(commLocation.getPbxPrefix().split(",")) : Collections.emptyList(),
                    true // mode_seguro = true (only strip if prefix matches)
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
                    cdrData.setTelephonyTypeName(specialServiceInfo.get().description); // PHP uses _SERVESPECIAL
                    cdrData.setOperatorId(specialServiceInfo.get().operatorId);
                    cdrData.setOperatorName(specialServiceInfo.get().operatorName);
                    cdrData.getAdditionalData().put("specialServiceTariff", specialServiceInfo.get());
                    log.debug("Call to special service: {}", numToCheckSpecial);
                    return; // Processing for special service ends here for type determination
                }
            }
        }

        if (cdrData.isInternalCall()) {
            processInternalCallLogic(cdrData, commLocation, limits, pbxSpecialRuleAppliedRecursively);
            return;
        }

        // PHP: if (!$pbx_especial) { $telefono_eval = evaluarPBXEspecial(..., 2); if ($telefono_eval !== '') { processaSaliente(..., true); return; } }
        if (!pbxSpecialRuleAppliedRecursively) {
            Optional<String> pbxTransformedDest = pbxSpecialRuleLookupService.applyPbxSpecialRule(
                    cdrData.getEffectiveDestinationNumber(), commLocation.getDirectory(), 2 // 2 for outgoing
            );
            if (pbxTransformedDest.isPresent() && !Objects.equals(pbxTransformedDest.get(), cdrData.getEffectiveDestinationNumber())) {
                String originalDest = cdrData.getEffectiveDestinationNumber();
                cdrData.setEffectiveDestinationNumber(pbxTransformedDest.get());
                // PHP also updates $info_cdr['dial_number'] here.
                cdrData.setFinalCalledPartyNumber(pbxTransformedDest.get());
                cdrData.setPbxSpecialRuleAppliedInfo("PBX Outgoing Rule: " + originalDest + " -> " + pbxTransformedDest.get());
                log.debug("Re-processing outgoing logic after PBX rule transformed {} to {}", originalDest, cdrData.getEffectiveDestinationNumber());
                processOutgoingLogic(cdrData, commLocation, limits, true, wasInitiallyInternal); // Recursive call
                return;
            }
        }
        
        // If not special, not internal, and no further PBX transformation, it's an external call.
        // TelephonyType, Operator, Indicator will be determined by TariffCalculationService via prefix lookups.
        if (cdrData.getTelephonyTypeId() == null || cdrData.getTelephonyTypeId() == 0L) {
             log.debug("Outgoing call, telephony type to be determined by prefix/tariffing: {}", cdrData.getEffectiveDestinationNumber());
        }
    }

    private void processInternalCallLogic(CdrData cdrData, CommunicationLocation commLocation, ExtensionLimits limits, boolean pbxSpecialRuleAppliedRecursively) {
        // PHP: $telefono_dest = limpiar_numero($info['dial_number'], $_PREFIJO_SALIDA_PBX, true) if pbx_especial else limpiar_numero($info['dial_number'])
        // If pbxSpecialRuleAppliedRecursively is true, it means a PBX rule might have made this call internal.
        // In that case, PHP's procesaInterna uses PBX prefixes for cleaning.
        List<String> prefixesToClean = pbxSpecialRuleAppliedRecursively && commLocation.getPbxPrefix() != null ?
                                       Arrays.asList(commLocation.getPbxPrefix().split(",")) :
                                       null;
        String cleanedDestination = CdrParserUtil.cleanPhoneNumber(
            cdrData.getEffectiveDestinationNumber(),
            prefixesToClean,
            pbxSpecialRuleAppliedRecursively // mode_seguro = true if pbx_special
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

        // PHP: $arreglo_info = tipo_llamada_interna(...);
        InternalCallTypeInfo internalTypeInfo = determineSpecificInternalCallType(cdrData, commLocation, limits);

        if (internalTypeInfo.isIgnoreCall()) {
            log.warn("Internal call marked for ignore by tipo_llamada_interna logic: {}", cdrData.getRawCdrLine());
            cdrData.setTelephonyTypeId(TelephonyTypeEnum.ERRORS.getValue()); // Or a specific "IgnoredInternal" type
            cdrData.setTelephonyTypeName("Internal Call Ignored (Global/Policy)");
            cdrData.setMarkedForQuarantine(true);
            cdrData.setQuarantineReason(internalTypeInfo.getAdditionalInfo());
            cdrData.setQuarantineStep("processInternalCallLogic_IgnorePolicy");
            return;
        }

        cdrData.setTelephonyTypeId(internalTypeInfo.getTelephonyTypeId());
        cdrData.setTelephonyTypeName(internalTypeInfo.getTelephonyTypeName());
        if (internalTypeInfo.getAdditionalInfo() != null && !internalTypeInfo.getAdditionalInfo().isEmpty()) {
            cdrData.setTelephonyTypeName(cdrData.getTelephonyTypeName() + " " + internalTypeInfo.getAdditionalInfo());
        }
        cdrData.setIndicatorId(internalTypeInfo.getDestinationIndicatorId()); // Indicator of the destination extension

        // Handle inversion for internal incoming calls (PHP: InvertirLlamada if origin not found but dest is, and dest is local)
        if (internalTypeInfo.isEffectivelyIncoming() && cdrData.getCallDirection() == CallDirection.OUTGOING) {
            log.debug("Internal call determined to be effectively INCOMING. Inverting. Original Caller: {}, Original Callee: {}", cdrData.getCallingPartyNumber(), cdrData.getEffectiveDestinationNumber());
            CdrParserUtil.swapPartyInfo(cdrData);
            // Trunks are not typically inverted for internal calls in PHP's InvertirLlamada for this specific case
            cdrData.setCallDirection(CallDirection.INCOMING);
            // Update employee context if needed
            cdrData.setEmployee(internalTypeInfo.getDestinationEmployee());
            cdrData.setEmployeeId(internalTypeInfo.getDestinationEmployee() != null ? internalTypeInfo.getDestinationEmployee().getId() : null);
            cdrData.setDestinationEmployee(internalTypeInfo.getOriginEmployee());
            cdrData.setDestinationEmployeeId(internalTypeInfo.getOriginEmployee() != null ? internalTypeInfo.getOriginEmployee().getId() : null);
        } else {
            // Set employees as determined by tipo_llamada_interna
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

    private InternalCallTypeInfo determineSpecificInternalCallType(CdrData cdrData, CommunicationLocation currentCommLocation, ExtensionLimits limits) {
        // This mirrors PHP's tipo_llamada_interna
        InternalCallTypeInfo result = new InternalCallTypeInfo();
        result.setTelephonyTypeId(appConfigService.getDefaultInternalCallTypeId()); // Default
        result.setTelephonyTypeName(appConfigService.getDefaultInternalCallTypeName());

        Optional<Employee> originEmpOpt = employeeLookupService.findEmployeeByExtensionOrAuthCode(cdrData.getCallingPartyNumber(), null, null, cdrData.getDateTimeOrigination());
        Optional<Employee> destEmpOpt = employeeLookupService.findEmployeeByExtensionOrAuthCode(cdrData.getEffectiveDestinationNumber(), null, null, cdrData.getDateTimeOrigination());

        // PHP: if ($retornar['id'] <= 0 && ExtensionValida($ext, true)) { $retornar = Validar_RangoExt(...); }
        if (originEmpOpt.isEmpty() && employeeLookupService.isPossibleExtension(cdrData.getCallingPartyNumber(), limits)) {
            originEmpOpt = employeeLookupService.findEmployeeByExtensionRange(cdrData.getCallingPartyNumber(), null, cdrData.getDateTimeOrigination());
        }
        if (destEmpOpt.isEmpty() && employeeLookupService.isPossibleExtension(cdrData.getEffectiveDestinationNumber(), limits)) {
            destEmpOpt = employeeLookupService.findEmployeeByExtensionRange(cdrData.getEffectiveDestinationNumber(), null, cdrData.getDateTimeOrigination());
        }
        
        result.setOriginEmployee(originEmpOpt.orElse(null));
        result.setDestinationEmployee(destEmpOpt.orElse(null));

        CommunicationLocation originCommLoc = originEmpOpt.map(Employee::getCommunicationLocation).orElse(currentCommLocation);
        CommunicationLocation destCommLoc = destEmpOpt.map(Employee::getCommunicationLocation).orElse(null);

        // PHP: if ($ComubicacionOrigen > 0 && $ComubicacionDestino <= 0) { $ComubicacionDestino = $COMUBICACION_ID; ... }
        if (originCommLoc != null && destCommLoc == null && originEmpOpt.isPresent()) { // If origin is known (even if not current plant) and dest is unknown
            destCommLoc = currentCommLocation; // Assume destination is the current plant
            log.debug("Internal call, origin employee known, destination unknown. Assuming destination is current plant: {}", currentCommLocation.getDirectory());
        }
        
        // PHP: ValidarOrigenDestino logic for global extensions
        // if ($ext_globales && $ComubicacionOrigen > 0 && $ComubicacionDestino > 0 && ($comid_actual != $ComubicacionOrigen || $comid_actual != $ComubicacionDestino))
        boolean extGlobales = appConfigService.areExtensionsGlobal(currentCommLocation.getPlantTypeId()); // Assuming a way to check this
        if (extGlobales && originCommLoc != null && destCommLoc != null &&
            (!Objects.equals(currentCommLocation.getId(), originCommLoc.getId()) || !Objects.equals(currentCommLocation.getId(), destCommLoc.getId()))) {
            if (!Objects.equals(currentCommLocation.getId(), originCommLoc.getId()) && Objects.equals(currentCommLocation.getId(), destCommLoc.getId())) {
                // Origin in another plant, destination in current. PHP marks to ignore.
                result.setIgnoreCall(true);
                result.setAdditionalInfo("Global Extension - Incoming from another plant");
                log.debug("Internal call (global): Origin from another plant ({}), Dest in current ({}). Marking to ignore.", originCommLoc.getDirectory(), currentCommLocation.getDirectory());
                return result;
            } else if (!Objects.equals(currentCommLocation.getId(), originCommLoc.getId()) && !Objects.equals(currentCommLocation.getId(), destCommLoc.getId())) {
                // Both origin and destination are in other plants.
                result.setIgnoreCall(true);
                result.setAdditionalInfo("Global Extension - Call between two other plants");
                 log.debug("Internal call (global): Origin ({}) and Dest ({}) in other plants. Current ({}). Marking to ignore.", originCommLoc.getDirectory(), destCommLoc.getDirectory(), currentCommLocation.getDirectory());
                return result;
            }
        }


        if (originCommLoc == null || destCommLoc == null || originCommLoc.getIndicator() == null || destCommLoc.getIndicator() == null) {
            result.setAdditionalInfo(appConfigService.getAssumedText());
            if (destCommLoc == null) {
                result.setTelephonyTypeName(telephonyTypeLookupService.getTelephonyTypeName(appConfigService.getDefaultInternalCallTypeId()));
            }
            result.setDestinationIndicatorId(currentCommLocation.getIndicatorId()); // Default to current
            return result;
        }
        
        result.setDestinationIndicatorId(destCommLoc.getIndicatorId());

        Indicator originIndicator = originCommLoc.getIndicator();
        Indicator destIndicator = destCommLoc.getIndicator();

        if (!Objects.equals(originIndicator.getOriginCountryId(), destIndicator.getOriginCountryId())) {
            result.setTelephonyTypeId(TelephonyTypeEnum.INTERNAL_INTERNATIONAL_IP.getValue());
        } else if (!Objects.equals(originIndicator.getId(), destIndicator.getId())) {
            result.setTelephonyTypeId(TelephonyTypeEnum.NATIONAL_IP.getValue());
        } else if (originEmpOpt.isPresent() && destEmpOpt.isPresent() &&
                   !Objects.equals(originEmpOpt.get().getSubdivisionId(), destEmpOpt.get().getSubdivisionId())) {
            // PHP: $oficinaBuscadaOrigen != $oficinaBuscadaDestino (Subdivision is a proxy for office here)
            result.setTelephonyTypeId(TelephonyTypeEnum.LOCAL_IP.getValue());
        } else {
            result.setTelephonyTypeId(TelephonyTypeEnum.INTERNAL_SIMPLE.getValue());
        }
        result.setTelephonyTypeName(telephonyTypeLookupService.getTelephonyTypeName(result.getTelephonyTypeId()));

        if (originEmpOpt.isEmpty()) {
            result.setAdditionalInfo(appConfigService.getAssumedText() + "/" + appConfigService.getOriginText());
        }
        
        // PHP: if (!ExtensionEncontrada($info['funcionario_funid']) && ExtensionEncontrada($info['funcionario_fundes']) && $arreglo_info['incoming'] <= 0 && $info['funcionario_fundes']['comid'] == $resultado_directorio['COMUBICACION_ID'])
        // This means: if origin employee not found, but destination employee IS found,
        // AND the call was initially determined as outgoing (not already incoming from parser),
        // AND the destination employee belongs to the current plant,
        // THEN treat it as an incoming internal call (invert parties).
        if (originEmpOpt.isEmpty() && destEmpOpt.isPresent() &&
            cdrData.getCallDirection() == CallDirection.OUTGOING && // Call was not already incoming
            Objects.equals(destCommLoc.getId(), currentCommLocation.getId())) {
            result.setEffectivelyIncoming(true);
            result.setDestinationIndicatorId(originIndicator.getId()); // Destination for tariffing becomes origin's indicator
        }

        return result;
    }
}