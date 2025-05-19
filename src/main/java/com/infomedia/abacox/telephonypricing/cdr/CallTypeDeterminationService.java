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
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;


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

    // Cache for extension limits per CommunicationLocation to avoid repeated DB calls
    // Key: CommunicationLocation ID
    private final Map<Long, ExtensionLimits> extensionLimitsCache = new ConcurrentHashMap<>();

    public void resetExtensionLimitsCache(CommunicationLocation commLocation) {
        if (commLocation != null && commLocation.getId() != null &&
            commLocation.getIndicator() != null && commLocation.getIndicator().getOriginCountryId() != null) {
            this.extensionLimitsCache.put(commLocation.getId(), employeeLookupService.getExtensionLimits(
                    commLocation.getIndicator().getOriginCountryId(),
                    commLocation.getId(),
                    commLocation.getPlantTypeId()
            ));
            log.debug("Cached extension limits for CommLocation ID: {}", commLocation.getId());
        } else {
            log.warn("Cannot cache extension limits: CommunicationLocation, Indicator, or OriginCountryId is null for {}.",
                     commLocation != null ? commLocation.getDirectory() : "UNKNOWN_COMMLOC");
        }
    }

    public ExtensionLimits getExtensionLimits(CommunicationLocation commLocation) {
        if (commLocation == null || commLocation.getId() == null) {
            log.warn("ExtensionLimits accessed with null CommunicationLocation. Returning default.");
            return new ExtensionLimits();
        }
        return this.extensionLimitsCache.computeIfAbsent(commLocation.getId(), id -> {
            log.warn("ExtensionLimits accessed for CommLocation ID {} before initialization or cache miss. Fetching now.", id);
            if (commLocation.getIndicator() != null && commLocation.getIndicator().getOriginCountryId() != null) {
                return employeeLookupService.getExtensionLimits(
                        commLocation.getIndicator().getOriginCountryId(),
                        id,
                        commLocation.getPlantTypeId()
                );
            }
            return new ExtensionLimits();
        });
    }


    /**
     * PHP equivalent: Orchestrates logic from CargarCDR's main loop after `evaluar_Formato`,
     * leading into `procesaEntrante` or `procesaSaliente`.
     * Also incorporates logic from `es_llamada_interna`.
     */
    public void determineCallTypeAndDirection(CdrData cdrData, CommunicationLocation commLocation) {
        ExtensionLimits limits = getExtensionLimits(commLocation);

        // PHP: $esinterna = es_llamada_interna($info_cdr, $link);
        if (!cdrData.isInternalCall()) { // If not already marked by parser (e.g. Cisco CM 6.0 parser)
            // PHP: if ($esinterna || ExtensionPosible($origen))
            if (employeeLookupService.isPossibleExtension(cdrData.getCallingPartyNumber(), limits)) {
                String destinationForInternalCheck = CdrParserUtil.cleanPhoneNumber(cdrData.getFinalCalledPartyNumber(), null, false);

                // PHP: $telefono_eval = evaluarPBXEspecial($link, $destino, $directorio, $cliente, 3); // internas
                Optional<String> pbxInternalTransformed = pbxSpecialRuleLookupService.applyPbxSpecialRule(
                        destinationForInternalCheck, commLocation.getDirectory(), 3 // 3 for internal
                );
                if (pbxInternalTransformed.isPresent()) {
                    destinationForInternalCheck = pbxInternalTransformed.get();
                    cdrData.setInternalCheckPbxTransformedDest(destinationForInternalCheck); // Store for reference
                }

                // PHP: $esinterna = ($len_destino == 1 || ExtensionPosible($destino));
                if ((destinationForInternalCheck.length() == 1 && destinationForInternalCheck.matches("\\d")) || // Single digit is internal
                    employeeLookupService.isPossibleExtension(destinationForInternalCheck, limits)) {
                    cdrData.setInternalCall(true);
                }
                // PHP: if (!$esinterna && $no_inicia_cero && $es_numerico && $destino != '' ) { $retornar = Validar_RangoExt(...); $esinterna = $retornar['nuevo']; }
                else if (destinationForInternalCheck.matches("\\d+") &&
                         !destinationForInternalCheck.startsWith("0") && // PHP: ExtensionValida($destino, true)
                         !destinationForInternalCheck.isEmpty()) {
                    Optional<Employee> employeeFromRange = employeeLookupService.findEmployeeByExtensionRange(
                            destinationForInternalCheck, commLocation.getId(), cdrData.getDateTimeOrigination()
                    );
                    if (employeeFromRange.isPresent()) {
                        cdrData.setInternalCall(true);
                    }
                }
            }
        }
        // PHP: $info['destino'] = $tel_dial; (effectiveDestinationNumber is initialized to finalCalledPartyNumber)
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
            CdrParserUtil.swapPartyInfo(cdrData); // PHP: InvertirLlamada
            CdrParserUtil.swapTrunks(cdrData);    // PHP: InvertirLlamada (trunks)
            cdrData.setCallDirection(CallDirection.OUTGOING);
            processOutgoingLogic(cdrData, commLocation, limits, false); // PHP: procesaSaliente
            return;
        }

        // PHP: $telefono = trim($info['dial_number']); (this is the external caller ID before inversion)
        String externalCallerId = cdrData.getCallingPartyNumber();
        // PHP: $info['destino'] = $telefono; (effectiveDestinationNumber is our extension after inversion)
        cdrData.setEffectiveDestinationNumber(cdrData.getFinalCalledPartyNumber());

        // PHP: $num_destino = evaluarPBXEspecial($link, $telefono, $directorio, $cliente, 1); // Entrantes
        Optional<String> pbxTransformedCaller = pbxSpecialRuleLookupService.applyPbxSpecialRule(
                externalCallerId, commLocation.getDirectory(), 1 // 1 for incoming
        );
        if (pbxTransformedCaller.isPresent()) {
            cdrData.setOriginalCallerIdBeforePbxIncoming(externalCallerId); // Store for reference
            externalCallerId = pbxTransformedCaller.get();
            cdrData.setPbxSpecialRuleAppliedInfo("PBX Incoming Rule: " + cdrData.getCallingPartyNumber() + " -> " + externalCallerId);
        }

        // PHP: $telefono_eval = _esEntrante_60($telefono_eval, $resultado_directorio);
        TransformationResult transformedIncoming = phoneNumberTransformationService.transformIncomingNumberCME(
                externalCallerId, commLocation.getIndicator().getOriginCountryId()
        );
        if (transformedIncoming.isTransformed()) {
            cdrData.setOriginalCallerIdBeforeCMETransform(externalCallerId); // Store for reference
            externalCallerId = transformedIncoming.getTransformedNumber();
            if (transformedIncoming.getNewTelephonyTypeId() != null) {
                cdrData.setHintedTelephonyTypeIdFromTransform(transformedIncoming.getNewTelephonyTypeId());
            }
        }

        // PHP: $info_origen = buscarOrigen($link, $telefono_eval, $tipotele, $indicativo_destino, $operador);
        IncomingCallOriginInfo originInfo = callOriginDeterminationService.determineIncomingCallOrigin(
            externalCallerId, commLocation
        );

        cdrData.setTelephonyTypeId(originInfo.getTelephonyTypeId());
        cdrData.setTelephonyTypeName(originInfo.getTelephonyTypeName());
        cdrData.setOperatorId(originInfo.getOperatorId());
        cdrData.setOperatorName(originInfo.getOperatorName());
        cdrData.setIndicatorId(originInfo.getIndicatorId()); // This is the source indicator
        cdrData.setDestinationCityName(originInfo.getDestinationDescription()); // Description of the source
        // PHP: if ($telefono_eval !== $telefono) { $info['destino'] = $telefono_eval; }
        // This means the effective number for display/record might be the transformed one.
        // For tariffing, buscarOrigen's transformed number is used.
        cdrData.setEffectiveDestinationNumber(originInfo.getEffectiveNumber()); // This is the number used for tariffing

        // PHP: if ($tipotele <= 0) $tipotele = _TIPOTELE_LOCAL;
        if (cdrData.getTelephonyTypeId() == null || cdrData.getTelephonyTypeId() == TelephonyTypeEnum.UNKNOWN.getValue()) {
             cdrData.setTelephonyTypeId(TelephonyTypeEnum.LOCAL.getValue());
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
            cdrData.setOriginalDialNumberBeforeCMETransform(cdrData.getFinalCalledPartyNumber()); // Store for reference
            cdrData.setFinalCalledPartyNumber(transformedOutgoing.getTransformedNumber());
        }
        // PHP: $info['destino'] = $tel_dial; (effectiveDestinationNumber is finalCalledPartyNumber initially)
        cdrData.setEffectiveDestinationNumber(cdrData.getFinalCalledPartyNumber());

        // PHP: if (!$pbx_especial) { $esinterna = info_interna($info_cdr); if (!$esinterna) { $infovalor = procesaServespecial(...); } }
        if (!pbxSpecialRuleAppliedRecursively && !cdrData.isInternalCall()) {
            // PHP: $telefono_orig = limpiar_numero($info['dial_number'], $_PREFIJO_SALIDA_PBX, true);
            String numToCheckSpecial = CdrParserUtil.cleanPhoneNumber(
                    cdrData.getEffectiveDestinationNumber(),
                    commLocation.getPbxPrefix() != null ? Arrays.asList(commLocation.getPbxPrefix().split(",")) : Collections.emptyList(),
                    true // true for modo_seguro (PHP: $modo_seguro = true)
            );
            if (numToCheckSpecial != null && !numToCheckSpecial.isEmpty()) {
                // PHP: $infovalor = buscar_NumeroEspecial(...)
                Optional<SpecialServiceInfo> specialServiceInfo =
                        specialServiceLookupService.findSpecialService(
                                numToCheckSpecial,
                                commLocation.getIndicatorId(), // indicator_id for context
                                commLocation.getIndicator().getOriginCountryId()
                        );
                if (specialServiceInfo.isPresent()) {
                    cdrData.setTelephonyTypeId(TelephonyTypeEnum.SPECIAL_SERVICES.getValue());
                    cdrData.setTelephonyTypeName(specialServiceInfo.get().description); // PHP: $infovalor['tipotele_nombre'] = _SERVESPECIAL;
                    cdrData.setOperatorId(specialServiceInfo.get().operatorId);
                    cdrData.setOperatorName(specialServiceInfo.get().operatorName);
                    cdrData.setIndicatorId(commLocation.getIndicatorId()); // For special services, indicator is usually local plant's
                    cdrData.setEffectiveDestinationNumber(numToCheckSpecial); // PHP: $info['destino'] = $telefono_orig;
                    cdrData.setSpecialServiceTariff(specialServiceInfo.get()); // Store for tariff calc
                    log.debug("Call to special service: {}", numToCheckSpecial);
                    return; // Tariffing for special services is handled differently by TariffCalculationService
                }
            }
        }

        // PHP: if ($esinterna) { $infovalor = procesaInterna(...); }
        if (cdrData.isInternalCall()) {
            processInternalCallLogic(cdrData, commLocation, limits, pbxSpecialRuleAppliedRecursively);
            return;
        }

        // PHP: if (!$pbx_especial) { $telefono_eval = evaluarPBXEspecial($link, $info_cdr['dial_number'], $directorio, $cliente, 2); // Salientes }
        if (!pbxSpecialRuleAppliedRecursively) {
            Optional<String> pbxTransformedDest = pbxSpecialRuleLookupService.applyPbxSpecialRule(
                    cdrData.getEffectiveDestinationNumber(), commLocation.getDirectory(), 2 // 2 for outgoing
            );
            // PHP: if ($telefono_eval !== '' && $telefono_eval !== $info_cdr['dial_number'])
            if (pbxTransformedDest.isPresent() && !Objects.equals(pbxTransformedDest.get(), cdrData.getEffectiveDestinationNumber())) {
                String originalDest = cdrData.getEffectiveDestinationNumber();
                cdrData.setOriginalDialNumberBeforePbxOutgoing(originalDest); // Store for reference
                cdrData.setFinalCalledPartyNumber(pbxTransformedDest.get());
                cdrData.setEffectiveDestinationNumber(pbxTransformedDest.get());
                cdrData.setPbxSpecialRuleAppliedInfo("PBX Outgoing Rule: " + originalDest + " -> " + pbxTransformedDest.get());
                log.debug("Re-processing outgoing logic after PBX rule transformed {} to {}", originalDest, cdrData.getEffectiveDestinationNumber());
                // PHP: procesaSaliente($link, $info_cdr, $resultado_directorio, $funext, true);
                processOutgoingLogic(cdrData, commLocation, limits, true); // Recursive call with flag
                return;
            }
        }
        
        // PHP: $infovalor = procesaSaliente_Complementar(...); (This is essentially the tariffing step)
        // If not internal, not special, and no further PBX transformation, then it's a standard outgoing call
        // for tariffing. The telephony type, operator, indicator etc. will be determined by TariffCalculationService.
        if (cdrData.getTelephonyTypeId() == null || cdrData.getTelephonyTypeId() == TelephonyTypeEnum.UNKNOWN.getValue()) {
             log.debug("Outgoing call, telephony type to be determined by prefix/tariffing: {}", cdrData.getEffectiveDestinationNumber());
        }
    }

    /**
     * PHP equivalent: procesaInterna
     */
    private void processInternalCallLogic(CdrData cdrData, CommunicationLocation commLocation, ExtensionLimits limits, boolean pbxSpecialRuleAppliedRecursively) {
        // PHP: if ($pbx_especial) { $_PREFIJO_SALIDA_PBX = ...; $telefono_dest = limpiar_numero(..., true); } else { $telefono_dest = limpiar_numero(...); }
        List<String> prefixesToClean = null;
        boolean stripOnlyIfPrefixMatches = true; // PHP's modo_seguro for limpiar_numero
        if (pbxSpecialRuleAppliedRecursively && commLocation.getPbxPrefix() != null) {
            prefixesToClean = Arrays.asList(commLocation.getPbxPrefix().split(","));
        } else if (!pbxSpecialRuleAppliedRecursively) {
            // If not a recursive call due to PBX rule, then no PBX prefix is assumed for internal numbers
            stripOnlyIfPrefixMatches = false; // PHP's default for non-PBX special internal
        }

        String cleanedDestination = CdrParserUtil.cleanPhoneNumber(
            cdrData.getEffectiveDestinationNumber(),
            prefixesToClean,
            stripOnlyIfPrefixMatches
        );
        cdrData.setEffectiveDestinationNumber(cleanedDestination);

        // PHP: if (trim($info['ext']) != '' && trim($info['ext']) === trim($telefono_dest)) { $infovalor = IgnorarLlamada(...'IGUALDESTINO'); }
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

        // PHP: $arreglo_info = tipo_llamada_interna(...)
        InternalCallTypeInfo internalTypeInfo = determineSpecificInternalCallType(cdrData, commLocation, limits);

        // PHP: if ($arreglo_info['ignorar']) { $infovalor = IgnorarLlamada(...); }
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
        cdrData.setIndicatorId(internalTypeInfo.getDestinationIndicatorId()); // For internal, "destination" is the callee's indicator

        // PHP: if (!ExtensionEncontrada($info['funcionario_funid']) && ExtensionEncontrada($info['funcionario_fundes']) && $arreglo_info['incoming'] <= 0 && $info['funcionario_fundes']['comid'] == $resultado_directorio['COMUBICACION_ID']) { InvertirLlamada($info); $indicativo_destino = $arreglo_info['indicaorigen']; }
        if (internalTypeInfo.isEffectivelyIncoming() && cdrData.getCallDirection() == CallDirection.OUTGOING) {
            log.debug("Internal call determined to be effectively INCOMING. Inverting. Original Caller: {}, Original Callee: {}", cdrData.getCallingPartyNumber(), cdrData.getEffectiveDestinationNumber());
            CdrParserUtil.swapPartyInfo(cdrData); // PHP: InvertirLlamada
            // Note: PHP's InvertirLlamada also swaps trunks if not told otherwise.
            // For internal calls, trunk swapping might not be relevant or might be handled by specific logic.
            // The PHP code for internal calls doesn't show explicit trunk swapping at this point.
            cdrData.setCallDirection(CallDirection.INCOMING);
            cdrData.setEmployee(internalTypeInfo.getDestinationEmployee()); // After swap, "employee" is the new callingParty
            cdrData.setEmployeeId(internalTypeInfo.getDestinationEmployee() != null ? internalTypeInfo.getDestinationEmployee().getId() : null);
            cdrData.setDestinationEmployee(internalTypeInfo.getOriginEmployee());
            cdrData.setDestinationEmployeeId(internalTypeInfo.getOriginEmployee() != null ? internalTypeInfo.getOriginEmployee().getId() : null);
            cdrData.setIndicatorId(internalTypeInfo.getOriginIndicatorId()); // For incoming, "indicatorId" refers to the source
        } else {
            cdrData.setEmployee(internalTypeInfo.getOriginEmployee());
            cdrData.setEmployeeId(internalTypeInfo.getOriginEmployee() != null ? internalTypeInfo.getOriginEmployee().getId() : null);
            cdrData.setDestinationEmployee(internalTypeInfo.getDestinationEmployee());
            cdrData.setDestinationEmployeeId(internalTypeInfo.getDestinationEmployee() != null ? internalTypeInfo.getDestinationEmployee().getId() : null);
        }

        // PHP: $infovalor['operador'] = operador_interno(...);
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
        // PHP: $tipoDeLlamada = -1; (default to error/unknown)
        result.setTelephonyTypeId(appConfigService.getDefaultInternalCallTypeId()); // Default
        result.setTelephonyTypeName(telephonyTypeLookupService.getTelephonyTypeName(result.getTelephonyTypeId()));
        result.setDestinationIndicatorId(currentCommLocation.getIndicatorId());
        result.setOriginIndicatorId(currentCommLocation.getIndicatorId());

        // PHP: $arreglo_ori = ObtenerFuncionario_Arreglo($link, $extOrigen, '', 0, $fecha, $funext, $COMUBICACION_ID, 0);
        Optional<Employee> originEmpOpt = employeeLookupService.findEmployeeByExtensionOrAuthCode(
                cdrData.getCallingPartyNumber(), null, currentCommLocation.getId(), cdrData.getDateTimeOrigination());
        // PHP: $arreglo_fun = ObtenerFuncionario_Arreglo($link, $extDestino, '', 0, $fecha, $funext, $COMUBICACION_ID, 1);
        Optional<Employee> destEmpOpt = employeeLookupService.findEmployeeByExtensionOrAuthCode(
                cdrData.getEffectiveDestinationNumber(), null, null, cdrData.getDateTimeOrigination()); // Search globally for dest

        // PHP: if ($retornar['id'] <= 0 && ExtensionValida($ext, true)) { $retornar = Validar_RangoExt(...); }
        if (originEmpOpt.isEmpty() && employeeLookupService.isPossibleExtension(cdrData.getCallingPartyNumber(), limits)) {
            originEmpOpt = employeeLookupService.findEmployeeByExtensionRange(cdrData.getCallingPartyNumber(), currentCommLocation.getId(), cdrData.getDateTimeOrigination());
        }
        if (destEmpOpt.isEmpty() && employeeLookupService.isPossibleExtension(cdrData.getEffectiveDestinationNumber(), limits)) {
            // For destination, PHP's ObtenerFuncionario_Arreglo with tipo=1 implies global search for employee,
            // then range lookup if not found.
            destEmpOpt = employeeLookupService.findEmployeeByExtensionRange(cdrData.getEffectiveDestinationNumber(), null, cdrData.getDateTimeOrigination());
        }
        
        result.setOriginEmployee(originEmpOpt.orElse(null));
        result.setDestinationEmployee(destEmpOpt.orElse(null));

        // PHP: asignar_ubicacion
        CommunicationLocation originCommLoc = originEmpOpt.map(Employee::getCommunicationLocation).orElse(currentCommLocation);
        CommunicationLocation destCommLoc = destEmpOpt.map(Employee::getCommunicationLocation).orElse(null); // Initially null if destEmp not found

        // PHP: if ($ComubicacionOrigen > 0 && $ComubicacionDestino <= 0) { $ComubicacionDestino = $COMUBICACION_ID; ... }
        if (originCommLoc != null && destCommLoc == null && originEmpOpt.isPresent()) { // Origin known, dest unknown
            destCommLoc = currentCommLocation; // Assume destination is the current plant
            log.debug("Internal call, origin employee known ({}), destination unknown. Assuming destination is current plant: {}",
                      originEmpOpt.get().getExtension(), currentCommLocation.getDirectory());
        }
        
        // PHP: $ignorar = ValidarOrigenDestino($link, $resultado_directorio, $ComubicacionOrigen, $ComubicacionDestino, true, $arreglo);
        boolean extGlobales = appConfigService.areExtensionsGlobal(currentCommLocation.getPlantTypeId());
        if (extGlobales && originCommLoc != null && destCommLoc != null &&
            (!Objects.equals(currentCommLocation.getId(), originCommLoc.getId()) || !Objects.equals(currentCommLocation.getId(), destCommLoc.getId()))) {
            // PHP: if ($comid_actual != $ComubicacionOrigen && $comid_actual == $ComubicacionDestino) { $ignorar = $ignorar_globales; }
            if (!Objects.equals(currentCommLocation.getId(), originCommLoc.getId()) && Objects.equals(currentCommLocation.getId(), destCommLoc.getId())) {
                result.setIgnoreCall(true);
                result.setAdditionalInfo("Global Extension - Incoming from another plant");
                return result;
            }
            // PHP: elseif ($comid_actual != $ComubicacionOrigen && $comid_actual != $ComubicacionDestino) { $ignorar = true; }
            else if (!Objects.equals(currentCommLocation.getId(), originCommLoc.getId()) && !Objects.equals(currentCommLocation.getId(), destCommLoc.getId())) {
                result.setIgnoreCall(true);
                result.setAdditionalInfo("Global Extension - Call between two other plants");
                return result;
            }
        }

        // PHP: if ($subdireccionDestino == '')
        if (destEmpOpt.isEmpty()) {
            // PHP: foreach ($_lista_Prefijos['ttin'] as $prefijo_txt => $tipotele_id) ...
            // This part in PHP checks if the destination number starts with a pre-defined internal prefix.
            // This is complex to replicate without the exact $_lista_Prefijos['ttin'] structure.
            // For now, if destination employee is not found, we use the default internal type.
            // PHP: if ($tipoDeLlamada <= 0 && $interna_defecto > 0) { $tipoDeLlamada = $interna_defecto; ... $infoadd = _ASUMIDO; }
            result.setTelephonyTypeId(appConfigService.getDefaultInternalCallTypeId());
            result.setTelephonyTypeName(telephonyTypeLookupService.getTelephonyTypeName(result.getTelephonyTypeId()));
            result.setAdditionalInfo(appConfigService.getAssumedText());
        } else if (originCommLoc != null && destCommLoc != null && originCommLoc.getIndicator() != null && destCommLoc.getIndicator() != null) {
            // PHP: else // Existe destino pero podría no haber certeza sobre el origen
            Indicator originIndicator = originCommLoc.getIndicator();
            Indicator destIndicator = destCommLoc.getIndicator();
            result.setOriginIndicatorId(originIndicator.getId());
            result.setDestinationIndicatorId(destIndicator.getId());

            Subdivision originSubdivision = originEmpOpt.map(Employee::getSubdivision).orElse(null);
            Subdivision destSubdivision = destEmpOpt.map(Employee::getSubdivision).orElse(null);

            // PHP: if ($mpaisOrigen != $mpaisDestino) { $tipoDeLlamada = _TIPOTELE_INTERNAL_IP; }
            if (!Objects.equals(originIndicator.getOriginCountryId(), destIndicator.getOriginCountryId())) {
                result.setTelephonyTypeId(TelephonyTypeEnum.INTERNAL_INTERNATIONAL_IP.getValue());
            }
            // PHP: elseif ($indicativoOrigen != $indicativoDestino) { $tipoDeLlamada = _TIPOTELE_NACIONAL_IP; }
            else if (!Objects.equals(originIndicator.getId(), destIndicator.getId())) {
                result.setTelephonyTypeId(TelephonyTypeEnum.NATIONAL_IP.getValue());
            }
            // PHP: elseif ($subdireccionOrigen != '' && $oficinaBuscadaOrigen != $oficinaBuscadaDestino) { $tipoDeLlamada = _TIPOTELE_LOCAL_IP; }
            // "Oficina" in PHP is derived from Subdivision. We'll compare Subdivision IDs.
            else if (originSubdivision != null && destSubdivision != null &&
                       !Objects.equals(originSubdivision.getId(), destSubdivision.getId())) {
                result.setTelephonyTypeId(TelephonyTypeEnum.LOCAL_IP.getValue());
            }
            // PHP: else { $tipoDeLlamada = _TIPOTELE_INTERNA_IP; }
            else {
                result.setTelephonyTypeId(TelephonyTypeEnum.INTERNAL_SIMPLE.getValue()); // PHP uses INTERNA_IP, maps to our INTERNAL_SIMPLE
            }
            result.setTelephonyTypeName(telephonyTypeLookupService.getTelephonyTypeName(result.getTelephonyTypeId()));
            // PHP: if ($subdireccionOrigen == '') { $infoadd = _ASUMIDO.'/'._ORIGEN; }
            if (originEmpOpt.isEmpty()) {
                result.setAdditionalInfo(appConfigService.getAssumedText() + "/" + appConfigService.getOriginText());
            }
        } else { // One of the commlocs or indicators is null, or origin employee not found
             result.setTelephonyTypeId(appConfigService.getDefaultInternalCallTypeId());
             result.setTelephonyTypeName(telephonyTypeLookupService.getTelephonyTypeName(result.getTelephonyTypeId()));
             result.setAdditionalInfo(appConfigService.getAssumedText());
             if (originCommLoc != null && originCommLoc.getIndicator() != null) result.setOriginIndicatorId(originCommLoc.getIndicator().getId());
             if (destCommLoc != null && destCommLoc.getIndicator() != null) result.setDestinationIndicatorId(destCommLoc.getIndicator().getId());
        }
        
        // PHP: if (!ExtensionEncontrada($info['funcionario_funid']) && ExtensionEncontrada($info['funcionario_fundes']) && $arreglo_info['incoming'] <= 0 && $info['funcionario_fundes']['comid'] == $resultado_directorio['COMUBICACION_ID'])
        if (originEmpOpt.isEmpty() && destEmpOpt.isPresent() &&
            cdrData.getCallDirection() == CallDirection.OUTGOING && // Call was not already incoming
            destCommLoc != null && Objects.equals(destCommLoc.getId(), currentCommLocation.getId())) {
            // PHP: InvertirLlamada($info); // La convierte en entrante
            // PHP: $indicativo_destino = $arreglo_info['indicaorigen'];
            result.setEffectivelyIncoming(true);
            if (originCommLoc != null && originCommLoc.getIndicator() != null) {
                 result.setDestinationIndicatorId(originCommLoc.getIndicator().getId()); // For incoming, "destination" for tariffing is origin's indicator
            }
        }
        return result;
    }
}