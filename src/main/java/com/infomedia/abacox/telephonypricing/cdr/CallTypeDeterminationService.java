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
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
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
    private final PrefixLookupService prefixLookupService;

    private final Map<Long, ExtensionLimits> extensionLimitsCache = new ConcurrentHashMap<>();

    public void resetExtensionLimitsCache(CommunicationLocation commLocation) {
        if (commLocation != null && commLocation.getId() != null &&
            commLocation.getIndicator() != null && commLocation.getIndicator().getOriginCountryId() != null) {
            log.debug("Resetting extension limits cache for CommLocation ID: {}", commLocation.getId());
            this.extensionLimitsCache.put(commLocation.getId(), employeeLookupService.getExtensionLimits(
                    commLocation.getIndicator().getOriginCountryId(),
                    commLocation.getId(),
                    commLocation.getPlantTypeId()
            ));
        } else {
            log.warn("Cannot cache extension limits: CommunicationLocation, Indicator, or OriginCountryId is null for {}.",
                     commLocation != null ? commLocation.getDirectory() : "UNKNOWN_COMMLOC");
        }
    }

    public ExtensionLimits getExtensionLimits(CommunicationLocation commLocation) {
        if (commLocation == null || commLocation.getId() == null) {
            log.warn("getExtensionLimits called with null or invalid commLocation.");
            return new ExtensionLimits();
        }
        return this.extensionLimitsCache.computeIfAbsent(commLocation.getId(), id -> {
            log.debug("Extension limits not found in cache for CommLocation ID: {}. Fetching.", id);
            if (commLocation.getIndicator() != null && commLocation.getIndicator().getOriginCountryId() != null) {
                return employeeLookupService.getExtensionLimits(
                        commLocation.getIndicator().getOriginCountryId(),
                        id,
                        commLocation.getPlantTypeId()
                );
            }
            log.warn("Cannot fetch extension limits: Indicator or OriginCountryId is null for CommLocation ID: {}", id);
            return new ExtensionLimits();
        });
    }


    /**
     * PHP equivalent: Orchestrates logic from CargarCDR's main loop after `evaluar_Formato`,
     * leading into `procesaEntrante` or `procesaSaliente`.
     * Also incorporates logic from `es_llamada_interna`.
     */
    public void determineCallTypeAndDirection(CdrData cdrData, CommunicationLocation commLocation) {
        log.debug("Determining call type and direction for CDR: {}", cdrData.getRawCdrLine());
        ExtensionLimits limits = getExtensionLimits(commLocation);

        if (!cdrData.isInternalCall()) {
            log.debug("CDR not pre-marked as internal. Checking if it's an internal call. CallingParty: '{}', FinalCalled: '{}'",
                    cdrData.getCallingPartyNumber(), cdrData.getFinalCalledPartyNumber());
            if (employeeLookupService.isPossibleExtension(cdrData.getCallingPartyNumber(), limits)) {
                log.debug("Calling party '{}' is a possible extension. Checking destination for internal call.", cdrData.getCallingPartyNumber());
                String destinationForInternalCheck = CdrUtil.cleanPhoneNumber(cdrData.getFinalCalledPartyNumber(), null, false);
                log.debug("Cleaned destination for internal check: {}", destinationForInternalCheck);

                Optional<String> pbxInternalTransformed = pbxSpecialRuleLookupService.applyPbxSpecialRule(
                        destinationForInternalCheck, commLocation.getDirectory(), 3 // 3 for internal
                );
                if (pbxInternalTransformed.isPresent()) {
                    destinationForInternalCheck = pbxInternalTransformed.get();
                    cdrData.setInternalCheckPbxTransformedDest(destinationForInternalCheck); // Store for later use if needed
                    log.debug("Destination transformed by PBX internal rule: {}", destinationForInternalCheck);
                }

                if ((destinationForInternalCheck.length() == 1 && destinationForInternalCheck.matches("\\d")) ||
                    employeeLookupService.isPossibleExtension(destinationForInternalCheck, limits)) {
                    cdrData.setInternalCall(true);
                    log.debug("Marked as internal call based on destination '{}' format/possibility.", destinationForInternalCheck);
                }
                else if (destinationForInternalCheck.matches("\\d+") &&
                         !destinationForInternalCheck.startsWith("0") && // PHP: ExtensionValida($destino, true)
                         !destinationForInternalCheck.isEmpty()) {
                    log.debug("Destination '{}' is numeric, not starting with 0. Checking extension ranges.", destinationForInternalCheck);
                    Optional<Employee> employeeFromRange = employeeLookupService.findEmployeeByExtensionRange(
                            destinationForInternalCheck,
                            null, // Search globally for range match
                            cdrData.getDateTimeOrigination()
                    );
                    if (employeeFromRange.isPresent()) {
                        cdrData.setInternalCall(true);
                        log.debug("Marked as internal call based on destination '{}' matching an extension range.", destinationForInternalCheck);
                    } else {
                        log.debug("Destination '{}' did not match any extension range.", destinationForInternalCheck);
                    }
                } else {
                    log.debug("Destination '{}' not identified as internal by format or range.", destinationForInternalCheck);
                }
            } else {
                log.debug("Calling party '{}' is not a possible extension. Not an internal call.", cdrData.getCallingPartyNumber());
            }
        } else {
            log.debug("CDR was pre-marked as internal by the parser.");
        }
        // Set effective destination number before processing logic
        cdrData.setEffectiveDestinationNumber(cdrData.getFinalCalledPartyNumber());

        log.info("Initial call direction: {}, Is internal: {}", cdrData.getCallDirection(), cdrData.isInternalCall());

        if (cdrData.getCallDirection() == CallDirection.INCOMING) {
            processIncomingLogic(cdrData, commLocation, limits);
        } else { // OUTGOING
            processOutgoingLogic(cdrData, commLocation, limits, false);
        }
        log.info("Final determined call direction: {}, Is internal: {}, TelephonyType: {}",
                 cdrData.getCallDirection(), cdrData.isInternalCall(), cdrData.getTelephonyTypeId());
    }

    /**
     * PHP equivalent: procesaEntrante
     */
    private void processIncomingLogic(CdrData cdrData, CommunicationLocation commLocation, ExtensionLimits limits) {
        log.debug("Processing INCOMING logic for CDR: {}", cdrData.getRawCdrLine());
        // PHP: if (info_interna($info)) { InvertirLlamada($info); $control_saliente = true; }
        if (cdrData.isInternalCall()) {
            log.debug("Incoming call marked as internal. Inverting and processing as outgoing.");
            CdrUtil.swapPartyInfo(cdrData); // PHP: _invertir($info['ext'], $info['dial_number']);
            CdrUtil.swapTrunks(cdrData);    // PHP: _invertir($info['troncal'], $info['troncal-ini']);
            cdrData.setCallDirection(CallDirection.OUTGOING);
            // PHP: $info['interna'] = 0; // Asegura que no sea tratada como interna (after swap, it's outgoing)
            // cdrData.setInternalCall(false); // This would be set by processOutgoingLogic if it's not truly internal
            processOutgoingLogic(cdrData, commLocation, limits, false);
            return;
        }

        // PHP: $telefono = trim($info['dial_number']);
        String externalCallerId = cdrData.getCallingPartyNumber(); // This is the external number
        // PHP: $info['destino'] = $telefono; (already set as finalCalledPartyNumber)
        cdrData.setEffectiveDestinationNumber(cdrData.getFinalCalledPartyNumber()); // This is our extension
        log.debug("Incoming call. External Caller ID: {}, Our Extension (FinalCalled): {}", externalCallerId, cdrData.getEffectiveDestinationNumber());


        // PHP: $num_destino = evaluarPBXEspecial($link, $telefono, $directorio, $cliente, 1);
        Optional<String> pbxTransformedCaller = pbxSpecialRuleLookupService.applyPbxSpecialRule(
                externalCallerId, commLocation.getDirectory(), 1 // 1 for incoming
        );
        if (pbxTransformedCaller.isPresent()) {
            log.debug("External Caller ID '{}' transformed by PBX incoming rule to '{}'", externalCallerId, pbxTransformedCaller.get());
            cdrData.setOriginalCallerIdBeforePbxIncoming(externalCallerId);
            externalCallerId = pbxTransformedCaller.get();
            cdrData.setPbxSpecialRuleAppliedInfo("PBX Incoming Rule: " + cdrData.getCallingPartyNumber() + " -> " + externalCallerId);
        }

        // PHP: $telefono_eval = _esEntrante_60($telefono_eval, $resultado_directorio);
        TransformationResult transformedIncoming = phoneNumberTransformationService.transformIncomingNumberCME(
                externalCallerId, commLocation.getIndicator().getOriginCountryId()
        );
        if (transformedIncoming.isTransformed()) {
            log.debug("External Caller ID '{}' transformed by CME rule to '{}'", externalCallerId, transformedIncoming.getTransformedNumber());
            cdrData.setOriginalCallerIdBeforeCMETransform(externalCallerId);
            externalCallerId = transformedIncoming.getTransformedNumber();
            if (transformedIncoming.getNewTelephonyTypeId() != null) {
                cdrData.setHintedTelephonyTypeIdFromTransform(transformedIncoming.getNewTelephonyTypeId());
            }
        }

        // PHP: $info_origen = buscarOrigen($link, $telefono_eval, $tipotele, $indicativo_destino, $operador);
        IncomingCallOriginInfo originInfo = callOriginDeterminationService.determineIncomingCallOrigin(
            externalCallerId, commLocation
        );
        log.debug("Determined incoming call origin info: {}", originInfo);

        cdrData.setTelephonyTypeId(originInfo.getTelephonyTypeId());
        cdrData.setTelephonyTypeName(originInfo.getTelephonyTypeName());
        cdrData.setOperatorId(originInfo.getOperatorId());
        cdrData.setOperatorName(originInfo.getOperatorName());
        cdrData.setIndicatorId(originInfo.getIndicatorId()); // This is the source indicator
        cdrData.setDestinationCityName(originInfo.getDestinationDescription()); // This is the source description
        // PHP: if ($telefono_eval !== $telefono) { $info['destino'] = $telefono_eval; }
        // This means the callingPartyNumber (external number) might be updated by buscarOrigen
        if (!Objects.equals(cdrData.getCallingPartyNumber(), originInfo.getEffectiveNumber())) {
            log.debug("Incoming call's external number (callingPartyNumber) updated after origin determination from '{}' to '{}'",
                    cdrData.getCallingPartyNumber(), originInfo.getEffectiveNumber());
            cdrData.setCallingPartyNumber(originInfo.getEffectiveNumber());
        }

        if (cdrData.getTelephonyTypeId() == null || cdrData.getTelephonyTypeId() == TelephonyTypeEnum.UNKNOWN.getValue()) {
             log.warn("Incoming call origin determination resulted in UNKNOWN telephony type. Defaulting to LOCAL.");
             cdrData.setTelephonyTypeId(TelephonyTypeEnum.LOCAL.getValue());
             cdrData.setTelephonyTypeName(telephonyTypeLookupService.getTelephonyTypeName(TelephonyTypeEnum.LOCAL.getValue()) + " (Default Incoming)");
        }
        log.debug("Finished processing INCOMING logic. CDR Data: {}", cdrData);
    }

    /**
     * PHP equivalent: procesaSaliente
     */
    private void processOutgoingLogic(CdrData cdrData, CommunicationLocation commLocation, ExtensionLimits limits, boolean pbxSpecialRuleAppliedRecursively) {
        log.debug("Processing OUTGOING logic for CDR: {}. Recursive PBX applied: {}", cdrData.getRawCdrLine(), pbxSpecialRuleAppliedRecursively);

        // PHP: $val_numero = _es_Saliente($info_cdr['dial_number']);
        TransformationResult transformedOutgoing = phoneNumberTransformationService.transformOutgoingNumberCME(
                cdrData.getFinalCalledPartyNumber(), commLocation.getIndicator().getOriginCountryId()
        );
        if (transformedOutgoing.isTransformed()) {
            log.debug("Outgoing number '{}' transformed by CME rule to '{}'", cdrData.getFinalCalledPartyNumber(), transformedOutgoing.getTransformedNumber());
            cdrData.setOriginalDialNumberBeforeCMETransform(cdrData.getFinalCalledPartyNumber());
            cdrData.setFinalCalledPartyNumber(transformedOutgoing.getTransformedNumber());
        }
        // PHP: $info_cdr['dial_number'] = $val_numero;
        cdrData.setEffectiveDestinationNumber(cdrData.getFinalCalledPartyNumber()); // Update effective number

        // PHP: if (!$esinterna) { $infovalor = procesaServespecial(...); }
        if (!pbxSpecialRuleAppliedRecursively && !cdrData.isInternalCall()) {
            // PHP: $telefono_orig = limpiar_numero($info['dial_number'], $_PREFIJO_SALIDA_PBX, true);
            String numToCheckSpecial = CdrUtil.cleanPhoneNumber(
                    cdrData.getEffectiveDestinationNumber(),
                    commLocation.getPbxPrefix() != null ? Arrays.asList(commLocation.getPbxPrefix().split(",")) : Collections.emptyList(),
                    true // true for modo_seguro
            );
            if (numToCheckSpecial != null && !numToCheckSpecial.isEmpty()) {
                log.debug("Checking for special service with number: {}", numToCheckSpecial);
                Optional<SpecialServiceInfo> specialServiceInfo =
                        specialServiceLookupService.findSpecialService(
                                numToCheckSpecial,
                                commLocation.getIndicatorId(), // indicator_id for context
                                commLocation.getIndicator().getOriginCountryId()
                        );
                if (specialServiceInfo.isPresent()) {
                    log.info("Call to special service '{}' identified.", specialServiceInfo.get().description);
                    cdrData.setTelephonyTypeId(TelephonyTypeEnum.SPECIAL_SERVICES.getValue());
                    cdrData.setTelephonyTypeName(specialServiceInfo.get().description);
                    cdrData.setOperatorId(specialServiceInfo.get().operatorId);
                    cdrData.setOperatorName(specialServiceInfo.get().operatorName);
                    cdrData.setIndicatorId(commLocation.getIndicatorId()); // Indicator of the plant
                    cdrData.setEffectiveDestinationNumber(numToCheckSpecial); // Use the number matched
                    cdrData.setSpecialServiceTariff(specialServiceInfo.get()); // Store for tariffing
                    return; // Processing for special service is complete
                }
            }
        }

        // PHP: if ($esinterna) { $infovalor = procesaInterna(...); }
        if (cdrData.isInternalCall()) {
            log.debug("Processing as internal call.");
            processInternalCallLogic(cdrData, commLocation, limits, pbxSpecialRuleAppliedRecursively);
            return;
        }

        // PHP: if (!$pbx_especial) { $telefono_eval = evaluarPBXEspecial(...); if ($telefono_eval !== '') { procesaSaliente(..., true); return; } }
        if (!pbxSpecialRuleAppliedRecursively) {
            Optional<String> pbxTransformedDest = pbxSpecialRuleLookupService.applyPbxSpecialRule(
                    cdrData.getEffectiveDestinationNumber(), commLocation.getDirectory(), 2 // 2 for outgoing
            );
            if (pbxTransformedDest.isPresent() && !Objects.equals(pbxTransformedDest.get(), cdrData.getEffectiveDestinationNumber())) {
                String originalDest = cdrData.getEffectiveDestinationNumber();
                log.info("Outgoing number '{}' transformed by PBX rule to '{}'. Reprocessing.", originalDest, pbxTransformedDest.get());
                cdrData.setOriginalDialNumberBeforePbxOutgoing(originalDest);
                cdrData.setFinalCalledPartyNumber(pbxTransformedDest.get());
                cdrData.setEffectiveDestinationNumber(pbxTransformedDest.get()); // Update effective number
                cdrData.setPbxSpecialRuleAppliedInfo("PBX Outgoing Rule: " + originalDest + " -> " + pbxTransformedDest.get());
                // Recursive call to re-evaluate with the new number
                processOutgoingLogic(cdrData, commLocation, limits, true);
                return;
            }
        }
        
        // PHP: $infovalor = procesaSaliente_Complementar(...);
        // At this point, if it's not internal, not special, and not transformed by PBX rule (or already re-processed),
        // the telephony type will be determined by the tariffing logic based on the effective destination number.
        if (cdrData.getTelephonyTypeId() == null || cdrData.getTelephonyTypeId() == TelephonyTypeEnum.UNKNOWN.getValue()) {
             log.debug("Outgoing call, telephony type to be determined by prefix/tariffing for destination: {}", cdrData.getEffectiveDestinationNumber());
        }
        log.debug("Finished processing OUTGOING logic. CDR Data: {}", cdrData);
    }

    /**
     * PHP equivalent: procesaInterna
     */
    private void processInternalCallLogic(CdrData cdrData, CommunicationLocation commLocation, ExtensionLimits limits, boolean pbxSpecialRuleAppliedRecursively) {
        log.debug("Processing INTERNAL call logic for CDR: {}. Recursive PBX applied: {}", cdrData.getRawCdrLine(), pbxSpecialRuleAppliedRecursively);

        List<String> prefixesToClean = null;
        boolean stripOnlyIfPrefixMatches = true;
        if (pbxSpecialRuleAppliedRecursively && commLocation.getPbxPrefix() != null) {
            prefixesToClean = Arrays.asList(commLocation.getPbxPrefix().split(","));
        } else if (!pbxSpecialRuleAppliedRecursively) {
            stripOnlyIfPrefixMatches = false;
        }

        String cleanedDestination = CdrUtil.cleanPhoneNumber(
                cdrData.getEffectiveDestinationNumber(),
                prefixesToClean,
                stripOnlyIfPrefixMatches
        );
        cdrData.setEffectiveDestinationNumber(cleanedDestination);
        log.debug("Cleaned internal destination: {}", cleanedDestination);

        if (cdrData.getCallingPartyNumber() != null && !cdrData.getCallingPartyNumber().trim().isEmpty() &&
                Objects.equals(cdrData.getCallingPartyNumber().trim(), cleanedDestination.trim())) {
            log.warn("Internal call to self (Origin: {}, Destination: {}). Marking for quarantine.", cdrData.getCallingPartyNumber(), cleanedDestination);
            cdrData.setTelephonyTypeId(TelephonyTypeEnum.ERRORS.getValue());
            cdrData.setTelephonyTypeName("Internal Self-Call (Ignored)");
            cdrData.setMarkedForQuarantine(true);
            cdrData.setQuarantineReason("Internal call to self (PHP: IGUALDESTINO)");
            cdrData.setQuarantineStep(QuarantineErrorType.INTERNAL_SELF_CALL.name());
            return;
        }

        InternalCallTypeInfo internalTypeInfo = determineSpecificInternalCallType(cdrData, commLocation, limits);
        log.debug("Determined specific internal call type info: {}", internalTypeInfo);

        if (internalTypeInfo.isIgnoreCall()) {
            log.warn("Internal call marked to be ignored. Reason: {}", internalTypeInfo.getAdditionalInfo());
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
            log.debug("Internal call determined to be effectively incoming. Inverting parties and trunks.");
            CdrUtil.swapPartyInfo(cdrData);
            CdrUtil.swapTrunks(cdrData); // Added trunk swap
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
        log.debug("Finished processing INTERNAL call logic. CDR Data: {}", cdrData);
    }

    /**
     * PHP equivalent: tipo_llamada_interna
     */
    private InternalCallTypeInfo determineSpecificInternalCallType(CdrData cdrData, CommunicationLocation currentCommLocation, ExtensionLimits limits) {
        log.debug("Determining specific internal call type for Calling: {}, Destination: {}", cdrData.getCallingPartyNumber(), cdrData.getEffectiveDestinationNumber());
        InternalCallTypeInfo result = new InternalCallTypeInfo();
        // PHP: $tipoDeLlamada = -1; (initially)
        // PHP: $interna_defecto = defineParamCliente('CAPTURAS_INTERNADEF', $link);
        // Default to a general internal type if nothing more specific is found
        result.setTelephonyTypeId(appConfigService.getDefaultInternalCallTypeId());
        result.setTelephonyTypeName(telephonyTypeLookupService.getTelephonyTypeName(result.getTelephonyTypeId()));
        result.setDestinationIndicatorId(currentCommLocation.getIndicatorId()); // Default to current
        result.setOriginIndicatorId(currentCommLocation.getIndicatorId()); // Default to current

        // PHP: $arreglo_ori = ObtenerFuncionario_Arreglo($link, $extOrigen, '', 0, $fecha, $funext, $COMUBICACION_ID, 0);
        Optional<Employee> originEmpOpt = employeeLookupService.findEmployeeByExtensionOrAuthCode(
                cdrData.getCallingPartyNumber(), null, // No auth code for internal
                currentCommLocation.getId(), // Context for origin employee
                cdrData.getDateTimeOrigination());

        // PHP: $arreglo_fun = ObtenerFuncionario_Arreglo($link, $extDestino, '', 0, $fecha, $funext, $COMUBICACION_ID, 1);
        Optional<Employee> destEmpOpt = employeeLookupService.findEmployeeByExtensionOrAuthCode(
                cdrData.getEffectiveDestinationNumber(), null,
                null, // Search globally for destination employee
                cdrData.getDateTimeOrigination());

        // PHP: if ($retornar['id'] <= 0 && ExtensionValida($ext, true)) { $retornar = Validar_RangoExt(...); }
        if (originEmpOpt.isEmpty() && employeeLookupService.isPossibleExtension(cdrData.getCallingPartyNumber(), limits)) {
            originEmpOpt = employeeLookupService.findEmployeeByExtensionRange(cdrData.getCallingPartyNumber(), currentCommLocation.getId(), cdrData.getDateTimeOrigination());
        }
        if (destEmpOpt.isEmpty() && employeeLookupService.isPossibleExtension(cdrData.getEffectiveDestinationNumber(), limits)) {
            destEmpOpt = employeeLookupService.findEmployeeByExtensionRange(cdrData.getEffectiveDestinationNumber(), null, cdrData.getDateTimeOrigination());
        }
        
        result.setOriginEmployee(originEmpOpt.orElse(null));
        result.setDestinationEmployee(destEmpOpt.orElse(null));

        // PHP: asignar_ubicacion(...) logic
        CommunicationLocation originCommLoc = originEmpOpt.map(Employee::getCommunicationLocation).orElse(currentCommLocation);
        CommunicationLocation destCommLoc = destEmpOpt.map(Employee::getCommunicationLocation).orElse(null); // Can be null if destEmp not found

        // PHP: if ($ComubicacionOrigen > 0 && $ComubicacionDestino <= 0) { $ComubicacionDestino = $COMUBICACION_ID; ... }
        if (originCommLoc != null && destCommLoc == null && originEmpOpt.isPresent()) {
            // If origin employee is found (and thus originCommLoc is likely set),
            // but destination employee/commLoc is not, assume destination is within the current plant.
            destCommLoc = currentCommLocation;
            log.debug("Destination employee not found for internal call; assuming destination is within current commLocation: {}", currentCommLocation.getDirectory());
        }
        
        // PHP: $ignorar = ValidarOrigenDestino($link, $resultado_directorio, $ComubicacionOrigen, $ComubicacionDestino, true, $arreglo);
        boolean extGlobales = appConfigService.areExtensionsGlobal(currentCommLocation.getPlantTypeId());
        if (extGlobales && originCommLoc != null && destCommLoc != null &&
            (!Objects.equals(currentCommLocation.getId(), originCommLoc.getId()) || !Objects.equals(currentCommLocation.getId(), destCommLoc.getId()))) {
            // This logic is from PHP's ValidarOrigenDestino
            if (!Objects.equals(currentCommLocation.getId(), originCommLoc.getId()) && Objects.equals(currentCommLocation.getId(), destCommLoc.getId())) {
                result.setIgnoreCall(true);
                result.setAdditionalInfo("Global Extension - Incoming internal from another plant");
                log.warn("Ignoring internal call: Incoming from another plant (Origin: {}, Current: {})", originCommLoc.getDirectory(), currentCommLocation.getDirectory());
                return result;
            }
            else if (!Objects.equals(currentCommLocation.getId(), originCommLoc.getId()) && !Objects.equals(currentCommLocation.getId(), destCommLoc.getId())) {
                result.setIgnoreCall(true);
                result.setAdditionalInfo("Global Extension - Internal call between two other plants");
                log.warn("Ignoring internal call: Between two other plants (Origin: {}, Dest: {}, Current: {})",
                         originCommLoc.getDirectory(), destCommLoc.getDirectory(), currentCommLocation.getDirectory());
                return result;
            }
        }


        // PHP: if ($subdireccionDestino == '')
        if (destEmpOpt.isEmpty()) {
            log.debug("Destination employee not found. Checking internal prefixes for: {}", cdrData.getEffectiveDestinationNumber());
            // PHP: foreach ($_lista_Prefijos['ttin'] as $prefijo_txt => $tipotele_id)
            Map<String, Long> internalPrefixes = prefixLookupService.getInternalTelephonyTypePrefixes(
                currentCommLocation.getIndicator().getOriginCountryId()
            );
            boolean prefixMatched = false;
            for (Map.Entry<String, Long> entry : internalPrefixes.entrySet()) {
                String prefixTxt = entry.getKey();
                Long typeId = entry.getValue();
                if (cdrData.getEffectiveDestinationNumber().startsWith(prefixTxt)) {
                    result.setTelephonyTypeId(typeId);
                    result.setTelephonyTypeName(telephonyTypeLookupService.getTelephonyTypeName(typeId));
                    result.setAdditionalInfo(appConfigService.getPrefixText()); // PHP: $infoadd = 'x'._PREFIJO;
                    prefixMatched = true;
                    log.debug("Internal call destination matched prefix '{}', type set to {}", prefixTxt, typeId);
                    break;
                }
            }
            // PHP: if ($tipoDeLlamada <= 0 && $interna_defecto > 0)
            if (!prefixMatched) {
                // Use the configured default for unresolved internal calls
                Long defaultUnresolvedInternalType = appConfigService.getDefaultTelephonyTypeForUnresolvedInternalCalls();
                // PHP: if (!in_array($interna_defecto, $tt_internas)) { $interna_defecto = -1; }
                // We need to ensure this default is actually an internal type.
                List<Long> validInternalTypes = telephonyTypeLookupService.getInternalTypeIds();
                if (defaultUnresolvedInternalType != null && defaultUnresolvedInternalType > 0 &&
                    validInternalTypes.contains(defaultUnresolvedInternalType)) {
                    result.setTelephonyTypeId(defaultUnresolvedInternalType);
                    result.setTelephonyTypeName(telephonyTypeLookupService.getTelephonyTypeName(defaultUnresolvedInternalType));
                    result.setAdditionalInfo(appConfigService.getAssumedText());
                    log.info("Destination employee not found and no internal prefix matched. Using configured default for unresolved internal calls: TypeID={}, Name='{}', Info='{}'",
                             result.getTelephonyTypeId(), result.getTelephonyTypeName(), result.getAdditionalInfo());
                } else {
                    // Fallback to a general internal type if the configured default is not valid or not set
                    result.setTelephonyTypeId(appConfigService.getDefaultInternalCallTypeId());
                    result.setTelephonyTypeName(telephonyTypeLookupService.getTelephonyTypeName(result.getTelephonyTypeId()));
                    result.setAdditionalInfo(appConfigService.getAssumedText());
                    log.warn("Configured default for unresolved internal calls ({}) is invalid or not an internal type. Defaulting to general internal type: {}",
                             defaultUnresolvedInternalType, result.getTelephonyTypeId());
                }
            }
        } else if (originCommLoc != null && destCommLoc != null && originCommLoc.getIndicator() != null && destCommLoc.getIndicator() != null) {
            // PHP: else // Existe destino pero podría no haber certeza sobre el origen
            Indicator originIndicator = originCommLoc.getIndicator();
            Indicator destIndicator = destCommLoc.getIndicator();
            result.setOriginIndicatorId(originIndicator.getId());
            result.setDestinationIndicatorId(destIndicator.getId());

            // PHP: $oficinaBuscadaOrigen, $oficinaBuscadaDestino (derived from subdireccion)
            Subdivision originSubdivision = originEmpOpt.map(Employee::getSubdivision).orElse(null);
            Long originOfficeId = originSubdivision != null ? originSubdivision.getId() : null; // Using Subdivision ID as proxy for Office ID
            Subdivision destSubdivision = destEmpOpt.map(Employee::getSubdivision).orElse(null);
            Long destOfficeId = destSubdivision != null ? destSubdivision.getId() : null;

            if (!Objects.equals(originIndicator.getOriginCountryId(), destIndicator.getOriginCountryId())) {
                result.setTelephonyTypeId(TelephonyTypeEnum.INTERNAL_INTERNATIONAL_IP.getValue()); // PHP: _TIPOTELE_INTERNAL_IP
            }
            else if (!Objects.equals(originIndicator.getId(), destIndicator.getId())) {
                result.setTelephonyTypeId(TelephonyTypeEnum.NATIONAL_IP.getValue()); // PHP: _TIPOTELE_NACIONAL_IP
            }
            // PHP: elseif ($subdireccionOrigen != '' && $oficinaBuscadaOrigen != $oficinaBuscadaDestino)
            else if (originOfficeId != null && destOfficeId != null && !Objects.equals(originOfficeId, destOfficeId)) {
                result.setTelephonyTypeId(TelephonyTypeEnum.LOCAL_IP.getValue()); // PHP: _TIPOTELE_LOCAL_IP
            }
            else { // Same office, city, country
                result.setTelephonyTypeId(TelephonyTypeEnum.INTERNAL_SIMPLE.getValue()); // PHP: _TIPOTELE_INTERNA_IP
            }
            result.setTelephonyTypeName(telephonyTypeLookupService.getTelephonyTypeName(result.getTelephonyTypeId()));
            log.debug("Internal call type set to {} based on origin/destination locations.", result.getTelephonyTypeId());

            // PHP: if ($subdireccionOrigen == '') { $infoadd = _ASUMIDO.'/'._ORIGEN; }
            if (originEmpOpt.isEmpty()) { // If origin employee was not found (e.g., only range match)
                result.setAdditionalInfo(appConfigService.getAssumedText() + "/" + appConfigService.getOriginText());
            }
        } else { // Fallback if location info is incomplete for one of the parties
             // This case should ideally be covered by the destEmpOpt.isEmpty() block or the one above.
             // If it reaches here, it means destEmpOpt is present, but some location info is missing.
             result.setTelephonyTypeId(appConfigService.getDefaultInternalCallTypeId());
             result.setTelephonyTypeName(telephonyTypeLookupService.getTelephonyTypeName(result.getTelephonyTypeId()));
             result.setAdditionalInfo(appConfigService.getAssumedText());
             log.debug("Internal call, but location info missing for one party. Defaulting to type {} with 'ASUMIDO'.", result.getTelephonyTypeId());
             if (originCommLoc != null && originCommLoc.getIndicator() != null) result.setOriginIndicatorId(originCommLoc.getIndicator().getId());
             if (destCommLoc != null && destCommLoc.getIndicator() != null) result.setDestinationIndicatorId(destCommLoc.getIndicator().getId());
        }
        
        // PHP: if (!ExtensionEncontrada($info['funcionario_funid']) && ExtensionEncontrada($info['funcionario_fundes']) && $arreglo_info['incoming'] <= 0 && $info['funcionario_fundes']['comid'] == $resultado_directorio['COMUBICACION_ID'])
        if (originEmpOpt.isEmpty() && destEmpOpt.isPresent() &&
            cdrData.getCallDirection() == CallDirection.OUTGOING && // PHP: $arreglo_info['incoming'] <= 0
            destCommLoc != null && Objects.equals(destCommLoc.getId(), currentCommLocation.getId())) {
            log.debug("Internal call: Origin not found, Destination found in current plant. Marking as effectively incoming.");
            result.setEffectivelyIncoming(true);
            // The indicator for the (now) caller (which was dest) is destIndicator.
            // The indicator for the (now) callee (which was origin) is originIndicator.
            // So, we need to set result.destinationIndicatorId to what was origin's indicator.
            if (originCommLoc != null && originCommLoc.getIndicator() != null) {
                 result.setDestinationIndicatorId(originCommLoc.getIndicator().getId());
            }
        }
        log.debug("Final specific internal call type info: {}", result);
        return result;
    }
}