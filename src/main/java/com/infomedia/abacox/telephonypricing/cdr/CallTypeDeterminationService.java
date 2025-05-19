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
        // This logic is complex and involves several checks.
        // 1. If cdrData.isInternalCall() is already true (set by parser like Cisco CM), it's internal.
        if (!cdrData.isInternalCall()) {
            // 2. Else if ExtensionPosible(origin):
            if (employeeLookupService.isPossibleExtension(cdrData.getCallingPartyNumber(), limits)) {
                String destinationForInternalCheck = CdrParserUtil.cleanPhoneNumber(cdrData.getFinalCalledPartyNumber(), null, true);

                // 2b. PBX special rule for internal calls on destination.
                // PHP: $telefono_eval = evaluarPBXEspecial($link, $destino, $directorio, $cliente, 3); // internas
                Optional<String> pbxInternalTransformed = pbxSpecialRuleLookupService.applyPbxSpecialRule(
                        destinationForInternalCheck, commLocation.getDirectory(), 3 // 3 for internal
                );
                if (pbxInternalTransformed.isPresent()) {
                    destinationForInternalCheck = pbxInternalTransformed.get();
                    cdrData.getAdditionalData().put("internalCheckPbxTransformedDest", destinationForInternalCheck);
                    // PHP: $info['dial-number-esp'] = $info['dial_number']; $info['dial_number'] = $telefono_eval;
                    // This implies the main dial_number should be updated if PBX rule applies for internal check.
                    // For now, we use destinationForInternalCheck locally. If it makes it internal,
                    // effectiveDestinationNumber will be set to this.
                }

                // 2c. Check if (cleaned/transformed) destination is single digit OR ExtensionPosible(destination).
                // PHP: $esinterna = ($len_destino == 1 || ExtensionPosible($destino));
                if ((destinationForInternalCheck.length() == 1 && destinationForInternalCheck.matches("\\d")) ||
                    employeeLookupService.isPossibleExtension(destinationForInternalCheck, limits)) {
                    cdrData.setInternalCall(true);
                }
                // 2d. If still not internal, check destination against ranges.
                // PHP: if (!$esinterna && $no_inicia_cero && $es_numerico && $destino != '' ) { $retornar = Validar_RangoExt(...); $esinterna = $retornar['nuevo']; }
                else if (destinationForInternalCheck.matches("\\d+") && !destinationForInternalCheck.startsWith("0")) { // is_numeric and not starting with 0
                    Optional<Employee> employeeFromRange = employeeLookupService.findEmployeeByExtensionRange(
                            destinationForInternalCheck, commLocation.getId(), cdrData.getDateTimeOrigination()
                    );
                    if (employeeFromRange.isPresent()) {
                        cdrData.setInternalCall(true);
                    }
                }
            }
        }
        // Store the original destination before it's potentially cleaned/modified further
        // This will be updated if PBX rules or internal call logic changes it.
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
        // PHP: if (info_interna($info)) { InvertirLlamada($info); procesaSaliente(...); return; }
        if (cdrData.isInternalCall()) {
            log.debug("Internal call initially marked as INCOMING. Inverting and processing as OUTGOING. Original Caller: {}, Original Callee: {}", cdrData.getFinalCalledPartyNumber(), cdrData.getCallingPartyNumber());
            CdrParserUtil.swapPartyInfo(cdrData);
            CdrParserUtil.swapTrunks(cdrData); // PHP's InvertirLlamada also swaps trunks by default
            cdrData.setCallDirection(CallDirection.OUTGOING);
            processOutgoingLogic(cdrData, commLocation, limits, false); // Process as outgoing
            return;
        }

        // For incoming, callingPartyNumber is the external number, finalCalledPartyNumber is our extension.
        String externalCallerId = cdrData.getCallingPartyNumber();
        cdrData.setEffectiveDestinationNumber(cdrData.getFinalCalledPartyNumber()); // Our extension is the destination

        // PHP: $num_destino = evaluarPBXEspecial($link, $telefono, $directorio, $cliente, 1); // Entrantes
        Optional<String> pbxTransformedCaller = pbxSpecialRuleLookupService.applyPbxSpecialRule(
                externalCallerId, commLocation.getDirectory(), 1 // 1 for incoming
        );
        if (pbxTransformedCaller.isPresent()) {
            cdrData.getAdditionalData().put("originalCallerIdBeforePbxIncoming", externalCallerId);
            externalCallerId = pbxTransformedCaller.get(); // Use transformed for further processing
            cdrData.setPbxSpecialRuleAppliedInfo("PBX Incoming Rule: " + cdrData.getCallingPartyNumber() + " -> " + externalCallerId);
        }

        // PHP: $telefono_eval = _esEntrante_60($telefono_eval, $resultado_directorio);
        TransformationResult transformedIncoming = phoneNumberTransformationService.transformIncomingNumberCME(
                externalCallerId, commLocation.getIndicator().getOriginCountryId()
        );
        if (transformedIncoming.isTransformed()) {
            cdrData.getAdditionalData().put("originalCallerIdBeforeCMETransform", externalCallerId);
            externalCallerId = transformedIncoming.getTransformedNumber();
            if (transformedIncoming.getNewTelephonyTypeId() != null) {
                // This sets an initial type, buscarOrigen will refine/confirm it.
                cdrData.setTelephonyTypeId(transformedIncoming.getNewTelephonyTypeId());
            }
        }

        // PHP: $info_origen = buscarOrigen(...);
        IncomingCallOriginInfo originInfo = callOriginDeterminationService.determineIncomingCallOrigin(
            externalCallerId, commLocation
        );

        cdrData.setTelephonyTypeId(originInfo.getTelephonyTypeId());
        cdrData.setTelephonyTypeName(originInfo.getTelephonyTypeName());
        cdrData.setOperatorId(originInfo.getOperatorId());
        cdrData.setOperatorName(originInfo.getOperatorName());
        cdrData.setIndicatorId(originInfo.getIndicatorId()); // This is the *source* indicator for incoming
        cdrData.setDestinationCityName(originInfo.getDestinationDescription()); // Description of the source
        // Effective destination for tariffing is the incoming number itself after transformations
        cdrData.setEffectiveDestinationNumber(originInfo.getEffectiveNumber());

        if (cdrData.getTelephonyTypeId() == null || cdrData.getTelephonyTypeId() == TelephonyTypeEnum.UNKNOWN.getValue()) {
             cdrData.setTelephonyTypeId(TelephonyTypeEnum.LOCAL.getValue()); // Default if buscarOrigen fails
             cdrData.setTelephonyTypeName(telephonyTypeLookupService.getTelephonyTypeName(TelephonyTypeEnum.LOCAL.getValue()) + " (Default Incoming)");
        }
    }

    /**
     * PHP equivalent: procesaSaliente
     */
    private void processOutgoingLogic(CdrData cdrData, CommunicationLocation commLocation, ExtensionLimits limits, boolean pbxSpecialRuleAppliedRecursively) {
        // PHP: $val_numero = _es_Saliente($info_cdr['dial_number']);
        TransformationResult transformedOutgoing = phoneNumberTransformationService.transformOutgoingNumberCME(
                cdrData.getFinalCalledPartyNumber(), commLocation.getIndicator().getOriginCountryId()
        );
        if (transformedOutgoing.isTransformed()) {
            cdrData.getAdditionalData().put("originalDialNumberBeforeCMETransform", cdrData.getFinalCalledPartyNumber());
            cdrData.setFinalCalledPartyNumber(transformedOutgoing.getTransformedNumber());
        }
        // Effective destination number is the one we'll use for lookups
        cdrData.setEffectiveDestinationNumber(cdrData.getFinalCalledPartyNumber());

        // PHP: if (!$pbx_especial && !$esinterna) { $infovalor = procesaServespecial(...); }
        if (!pbxSpecialRuleAppliedRecursively && !cdrData.isInternalCall()) {
            // PHP: $telefono_orig = limpiar_numero($info['dial_number'], $_PREFIJO_SALIDA_PBX, true);
            String numToCheckSpecial = CdrParserUtil.cleanPhoneNumber(
                    cdrData.getEffectiveDestinationNumber(),
                    commLocation.getPbxPrefix() != null ? Arrays.asList(commLocation.getPbxPrefix().split(",")) : Collections.emptyList(),
                    true // mode_seguro = true (only strip if prefix matches and found)
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
                    cdrData.setTelephonyTypeName(specialServiceInfo.get().description); // PHP: $infovalor['tipotele_nombre'] = _SERVESPECIAL;
                    cdrData.setOperatorId(specialServiceInfo.get().operatorId);
                    cdrData.setOperatorName(specialServiceInfo.get().operatorName);
                    cdrData.setIndicatorId(commLocation.getIndicatorId()); // Special services usually use local indicator context
                    cdrData.setEffectiveDestinationNumber(numToCheckSpecial); // Use the cleaned number for special service
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

        // PHP: if (!$pbx_especial) { $telefono_eval = evaluarPBXEspecial(..., 2); if ($telefono_eval !== '' && $telefono_eval !== $info_cdr['dial_number']) { processaSaliente(..., true); return; } }
        if (!pbxSpecialRuleAppliedRecursively) {
            Optional<String> pbxTransformedDest = pbxSpecialRuleLookupService.applyPbxSpecialRule(
                    cdrData.getEffectiveDestinationNumber(), commLocation.getDirectory(), 2 // 2 for outgoing
            );
            if (pbxTransformedDest.isPresent() && !Objects.equals(pbxTransformedDest.get(), cdrData.getEffectiveDestinationNumber())) {
                String originalDest = cdrData.getEffectiveDestinationNumber();
                // PHP: $info_cdr['dial-number-esp'] = $info_cdr['dial_number']; $info_cdr['dial_number'] = $telefono_eval;
                cdrData.getAdditionalData().put("originalDialNumberBeforePbxOutgoing", originalDest);
                cdrData.setFinalCalledPartyNumber(pbxTransformedDest.get());
                cdrData.setEffectiveDestinationNumber(pbxTransformedDest.get());
                cdrData.setPbxSpecialRuleAppliedInfo("PBX Outgoing Rule: " + originalDest + " -> " + pbxTransformedDest.get());
                log.debug("Re-processing outgoing logic after PBX rule transformed {} to {}", originalDest, cdrData.getEffectiveDestinationNumber());
                processOutgoingLogic(cdrData, commLocation, limits, true); // Recursive call
                return;
            }
        }
        
        // If not special, not internal, and no further PBX transformation, it's an external call.
        // TelephonyType, Operator, Indicator will be determined by TariffCalculationService via prefix lookups.
        if (cdrData.getTelephonyTypeId() == null || cdrData.getTelephonyTypeId() == TelephonyTypeEnum.UNKNOWN.getValue()) {
             log.debug("Outgoing call, telephony type to be determined by prefix/tariffing: {}", cdrData.getEffectiveDestinationNumber());
             // TariffCalculationService will set these.
        }
    }

    /**
     * PHP equivalent: procesaInterna
     */
    private void processInternalCallLogic(CdrData cdrData, CommunicationLocation commLocation, ExtensionLimits limits, boolean pbxSpecialRuleAppliedRecursively) {
        List<String> prefixesToClean = null;
        boolean stripOnlyIfPrefixMatches = true; // PHP: $modo_seguro = true for pbx_especial
        if (pbxSpecialRuleAppliedRecursively && commLocation.getPbxPrefix() != null) {
            prefixesToClean = Arrays.asList(commLocation.getPbxPrefix().split(","));
        } else if (!pbxSpecialRuleAppliedRecursively) {
            // PHP: $telefono_dest = limpiar_numero($info['dial_number']); (no PBX prefix)
            stripOnlyIfPrefixMatches = false; // PHP: $modo_seguro = false (default for limpiar_numero without prefix list)
        }

        String cleanedDestination = CdrParserUtil.cleanPhoneNumber(
            cdrData.getEffectiveDestinationNumber(), // Use effective, which might have been set by PBX rule
            prefixesToClean,
            stripOnlyIfPrefixMatches
        );
        // PHP: $info['destino'] = $telefono_dest; (updates the number used for tariffing)
        cdrData.setEffectiveDestinationNumber(cleanedDestination);

        // PHP: if (trim($info['ext']) != '' && trim($info['ext']) === trim($telefono_dest))
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

        // PHP: $arreglo_info = tipo_llamada_interna(...);
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
        cdrData.setIndicatorId(internalTypeInfo.getDestinationIndicatorId()); // Indicator of the destination extension

        // Handle inversion for internal incoming calls
        // PHP: if (!ExtensionEncontrada($info['funcionario_funid']) && ExtensionEncontrada($info['funcionario_fundes']) && $arreglo_info['incoming'] <= 0 && $info['funcionario_fundes']['comid'] == $resultado_directorio['COMUBICACION_ID'])
        if (internalTypeInfo.isEffectivelyIncoming() && cdrData.getCallDirection() == CallDirection.OUTGOING) {
            log.debug("Internal call determined to be effectively INCOMING. Inverting. Original Caller: {}, Original Callee: {}", cdrData.getCallingPartyNumber(), cdrData.getEffectiveDestinationNumber());
            CdrParserUtil.swapPartyInfo(cdrData);
            // Trunks are not typically inverted for internal calls in PHP's InvertirLlamada for this specific case
            cdrData.setCallDirection(CallDirection.INCOMING);
            // Update employee context
            cdrData.setEmployee(internalTypeInfo.getDestinationEmployee());
            cdrData.setEmployeeId(internalTypeInfo.getDestinationEmployee() != null ? internalTypeInfo.getDestinationEmployee().getId() : null);
            cdrData.setDestinationEmployee(internalTypeInfo.getOriginEmployee());
            cdrData.setDestinationEmployeeId(internalTypeInfo.getOriginEmployee() != null ? internalTypeInfo.getOriginEmployee().getId() : null);
            // PHP: $indicativo_destino = $arreglo_info['indicaorigen'];
            cdrData.setIndicatorId(internalTypeInfo.getOriginIndicatorId()); // For incoming, indicator is source
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

    /**
     * PHP equivalent: tipo_llamada_interna
     */
    private InternalCallTypeInfo determineSpecificInternalCallType(CdrData cdrData, CommunicationLocation currentCommLocation, ExtensionLimits limits) {
        InternalCallTypeInfo result = new InternalCallTypeInfo();
        result.setTelephonyTypeId(appConfigService.getDefaultInternalCallTypeId());
        result.setTelephonyTypeName(telephonyTypeLookupService.getTelephonyTypeName(result.getTelephonyTypeId()));
        result.setDestinationIndicatorId(currentCommLocation.getIndicatorId()); // Default to current
        result.setOriginIndicatorId(currentCommLocation.getIndicatorId()); // Default to current

        // PHP: $arreglo_ori = ObtenerFuncionario_Arreglo($link, $extOrigen, '', 0, $fecha, $funext, $COMUBICACION_ID, 0);
        // PHP: $arreglo_fun = ObtenerFuncionario_Arreglo($link, $extDestino, '', 0, $fecha, $funext, $COMUBICACION_ID, 1);
        // Type 0 for origin (any plant if global, current if not), Type 1 for destination (any plant if global)
        Optional<Employee> originEmpOpt = employeeLookupService.findEmployeeByExtensionOrAuthCode(cdrData.getCallingPartyNumber(), null, currentCommLocation.getId(), cdrData.getDateTimeOrigination());
        Optional<Employee> destEmpOpt = employeeLookupService.findEmployeeByExtensionOrAuthCode(cdrData.getEffectiveDestinationNumber(), null, null, cdrData.getDateTimeOrigination());

        // PHP: if ($retornar['id'] <= 0 && ExtensionValida($ext, true)) { $retornar = Validar_RangoExt(...); }
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

        // PHP: if ($ComubicacionOrigen > 0 && $ComubicacionDestino <= 0) { $ComubicacionDestino = $COMUBICACION_ID; ... }
        if (originCommLoc != null && destCommLoc == null && originEmpOpt.isPresent()) {
            destCommLoc = currentCommLocation;
            log.debug("Internal call, origin employee known, destination unknown. Assuming destination is current plant: {}", currentCommLocation.getDirectory());
        }
        
        // PHP: ValidarOrigenDestino logic for global extensions
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

        if (originCommLoc == null || destCommLoc == null || originCommLoc.getIndicator() == null || destCommLoc.getIndicator() == null) {
            result.setAdditionalInfo(appConfigService.getAssumedText());
            if (destEmpOpt.isEmpty()) { // PHP: if ($subdireccionDestino == '')
                // PHP: foreach ($_lista_Prefijos['ttin'] as $prefijo_txt => $tipotele_id) ...
                // This part checks if destination starts with an internal prefix.
                // For simplicity, if dest is unknown, use default internal type.
                // A more complete impl would require loading internal prefixes.
                result.setTelephonyTypeId(appConfigService.getDefaultInternalCallTypeId());
                result.setTelephonyTypeName(telephonyTypeLookupService.getTelephonyTypeName(result.getTelephonyTypeId()));
            }
            result.setDestinationIndicatorId(currentCommLocation.getIndicatorId());
            result.setOriginIndicatorId(originCommLoc != null && originCommLoc.getIndicator() != null ? originCommLoc.getIndicatorId() : currentCommLocation.getIndicatorId());
            return result;
        }
        
        result.setDestinationIndicatorId(destCommLoc.getIndicatorId());
        result.setOriginIndicatorId(originCommLoc.getIndicatorId());

        Indicator originIndicator = originCommLoc.getIndicator();
        Indicator destIndicator = destCommLoc.getIndicator();

        // PHP: if ($mpaisOrigen != $mpaisDestino)
        if (!Objects.equals(originIndicator.getOriginCountryId(), destIndicator.getOriginCountryId())) {
            result.setTelephonyTypeId(TelephonyTypeEnum.INTERNAL_INTERNATIONAL_IP.getValue());
        }
        // PHP: elseif ($indicativoOrigen != $indicativoDestino)
        else if (!Objects.equals(originIndicator.getId(), destIndicator.getId())) {
            result.setTelephonyTypeId(TelephonyTypeEnum.NATIONAL_IP.getValue());
        }
        // PHP: elseif ($subdireccionOrigen != '' && $oficinaBuscadaOrigen != $oficinaBuscadaDestino)
        // Assuming Subdivision ID represents "oficina" (office) for simplicity
        else if (originEmpOpt.isPresent() && destEmpOpt.isPresent() &&
                   originEmpOpt.get().getSubdivisionId() != null && destEmpOpt.get().getSubdivisionId() != null &&
                   !Objects.equals(originEmpOpt.get().getSubdivisionId(), destEmpOpt.get().getSubdivisionId())) {
            result.setTelephonyTypeId(TelephonyTypeEnum.LOCAL_IP.getValue());
        } else {
            result.setTelephonyTypeId(TelephonyTypeEnum.INTERNAL_SIMPLE.getValue());
        }
        result.setTelephonyTypeName(telephonyTypeLookupService.getTelephonyTypeName(result.getTelephonyTypeId()));

        if (originEmpOpt.isEmpty()) {
            result.setAdditionalInfo(appConfigService.getAssumedText() + "/" + appConfigService.getOriginText());
        }
        
        // PHP: if (!ExtensionEncontrada($info['funcionario_funid']) && ExtensionEncontrada($info['funcionario_fundes']) && $arreglo_info['incoming'] <= 0 && $info['funcionario_fundes']['comid'] == $resultado_directorio['COMUBICACION_ID'])
        if (originEmpOpt.isEmpty() && destEmpOpt.isPresent() &&
            cdrData.getCallDirection() == CallDirection.OUTGOING && // Call was not already incoming
            Objects.equals(destCommLoc.getId(), currentCommLocation.getId())) {
            result.setEffectivelyIncoming(true);
            // PHP: $indicativo_destino = $arreglo_info['indicaorigen'];
            // If it becomes incoming, the "destination" indicator (for tariffing) is the origin's indicator
            result.setDestinationIndicatorId(originIndicator.getId());
        }
        return result;
    }
}