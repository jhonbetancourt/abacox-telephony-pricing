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
            return new ExtensionLimits(); // Return default empty limits
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
        log.debug("Determining call type and direction for CDR: {}", cdrData.getCtlHash());
        ExtensionLimits limits = getExtensionLimits(commLocation);

        // PHP: if (info_interna($info)) { $esinterna = true; }
        // PHP: if ($esinterna || ExtensionPosible($origen)) { ... }
        // The cdrData.isInternalCall() flag is set by the parser (CiscoCm60CdrProcessor)
        // based on partition presence and extension format.
        // If not pre-marked, we attempt to determine it here.
        if (!cdrData.isInternalCall()) {
            log.debug("CDR not pre-marked as internal. Checking if it's an internal call. CallingParty: '{}', FinalCalled: '{}'",
                    cdrData.getCallingPartyNumber(), cdrData.getFinalCalledPartyNumber());
            // PHP: $origen = $info['ext'];
            if (employeeLookupService.isPossibleExtension(cdrData.getCallingPartyNumber(), limits)) {
                log.debug("Calling party '{}' is a possible extension. Checking destination for internal call.", cdrData.getCallingPartyNumber());
                // PHP: $destino = limpiar_numero($info['dial_number']);
                String destinationForInternalCheck = CdrUtil.cleanPhoneNumber(cdrData.getFinalCalledPartyNumber(), null, false);
                log.debug("Cleaned destination for internal check: {}", destinationForInternalCheck);

                // PHP: $telefono_eval = evaluarPBXEspecial($link, $destino, $directorio, $cliente, 3); // internas
                Optional<String> pbxInternalTransformed = pbxSpecialRuleLookupService.applyPbxSpecialRule(
                        destinationForInternalCheck, commLocation.getDirectory(), 3 // 3 for internal
                );
                if (pbxInternalTransformed.isPresent()) {
                    destinationForInternalCheck = pbxInternalTransformed.get();
                    cdrData.setInternalCheckPbxTransformedDest(destinationForInternalCheck);
                    log.debug("Destination transformed by PBX internal rule: {}", destinationForInternalCheck);
                }

                // PHP: $esinterna = ($len_destino == 1 || ExtensionPosible($destino));
                if ((destinationForInternalCheck.length() == 1 && destinationForInternalCheck.matches("\\d")) || // Single digit (e.g. operator)
                    employeeLookupService.isPossibleExtension(destinationForInternalCheck, limits)) {
                    cdrData.setInternalCall(true);
                    log.debug("Marked as internal call based on destination '{}' format/possibility.", destinationForInternalCheck);
                }
                // PHP: if (!$esinterna && $no_inicia_cero && $es_numerico && $destino != '' ) { $retornar = Validar_RangoExt(...); $esinterna = $retornar['nuevo']; }
                else if (destinationForInternalCheck.matches("\\d+") && // is_numeric
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
        log.debug("Processing INCOMING logic for CDR: {}", cdrData.getCtlHash());
        // PHP: if (info_interna($info)) { InvertirLlamada($info); $control_saliente = true; }
        if (cdrData.isInternalCall()) {
            log.debug("Incoming call marked as internal. Inverting and processing as outgoing.");
            CdrUtil.swapPartyInfo(cdrData); // PHP: _invertir($info['ext'], $info['dial_number']);
            CdrUtil.swapTrunks(cdrData);    // PHP: _invertir($info['troncal'], $info['troncal-ini']);
            cdrData.setCallDirection(CallDirection.OUTGOING);
            // PHP: $info['interna'] = 0; // Asegura que no sea tratada como interna (after swap, it's outgoing)
            // The internalCall flag will be re-evaluated by processOutgoingLogic if it's truly internal after swap.
            processOutgoingLogic(cdrData, commLocation, limits, false);
            return;
        }

        // PHP: $telefono = trim($info['dial_number']);
        // For incoming, dial_number is the external caller, ext is our extension (after parser swap)
        String externalCallerId = cdrData.getCallingPartyNumber();
        String ourExtension = cdrData.getFinalCalledPartyNumber();
        cdrData.setEffectiveDestinationNumber(ourExtension); // Our extension is the effective destination for this leg
        log.debug("Incoming call. External Caller ID: {}, Our Extension (FinalCalled): {}", externalCallerId, ourExtension);


        // PHP: $num_destino = evaluarPBXEspecial($link, $telefono, $directorio, $cliente, 1);
        Optional<String> pbxTransformedCaller = pbxSpecialRuleLookupService.applyPbxSpecialRule(
                externalCallerId, commLocation.getDirectory(), 1 // 1 for incoming
        );
        if (pbxTransformedCaller.isPresent()) {
            log.debug("External Caller ID '{}' transformed by PBX incoming rule to '{}'", externalCallerId, pbxTransformedCaller.get());
            cdrData.setOriginalCallerIdBeforePbxIncoming(externalCallerId); // Store original before this specific transform
            externalCallerId = pbxTransformedCaller.get();
            cdrData.setPbxSpecialRuleAppliedInfo("PBX Incoming Rule: " + cdrData.getCallingPartyNumber() + " -> " + externalCallerId);
        }

        // PHP: $telefono_eval = _esEntrante_60($telefono_eval, $resultado_directorio);
        TransformationResult transformedIncoming = phoneNumberTransformationService.transformIncomingNumberCME(
                externalCallerId, commLocation.getIndicator().getOriginCountryId()
        );
        if (transformedIncoming.isTransformed()) {
            log.debug("External Caller ID '{}' transformed by CME rule to '{}'", externalCallerId, transformedIncoming.getTransformedNumber());
            cdrData.setOriginalCallerIdBeforeCMETransform(externalCallerId); // Store original before this specific transform
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
        log.debug("Processing OUTGOING logic for CDR: {}. Recursive PBX applied: {}", cdrData.getCtlHash(), pbxSpecialRuleAppliedRecursively);

        // PHP: $val_numero = _es_Saliente($info_cdr['dial_number']);
        TransformationResult transformedOutgoing = phoneNumberTransformationService.transformOutgoingNumberCME(
                cdrData.getFinalCalledPartyNumber(), commLocation.getIndicator().getOriginCountryId()
        );
        if (transformedOutgoing.isTransformed()) {
            log.debug("Outgoing number '{}' transformed by CME rule to '{}'", cdrData.getFinalCalledPartyNumber(), transformedOutgoing.getTransformedNumber());
            cdrData.setOriginalDialNumberBeforeCMETransform(cdrData.getFinalCalledPartyNumber()); // Store original before this specific transform
            cdrData.setFinalCalledPartyNumber(transformedOutgoing.getTransformedNumber());
        }
        cdrData.setEffectiveDestinationNumber(cdrData.getFinalCalledPartyNumber());

        // PHP: if (!$esinterna) { $infovalor = procesaServespecial(...); }
        if (!pbxSpecialRuleAppliedRecursively && !cdrData.isInternalCall()) {
            // PHP: $telefono_orig = limpiar_numero($info['dial_number'], $_PREFIJO_SALIDA_PBX, true);
            String numToCheckSpecial = CdrUtil.cleanPhoneNumber(
                    cdrData.getEffectiveDestinationNumber(),
                    commLocation.getPbxPrefix() != null ? Arrays.asList(commLocation.getPbxPrefix().split(",")) : Collections.emptyList(),
                    true // true for modo_seguro (PHP: $modo_seguro = true)
            );
            if (numToCheckSpecial != null && !numToCheckSpecial.isEmpty()) {
                log.debug("Checking for special service with number: {}", numToCheckSpecial);
                Optional<SpecialServiceInfo> specialServiceInfo =
                        specialServiceLookupService.findSpecialService(
                                numToCheckSpecial,
                                commLocation.getIndicatorId(),
                                commLocation.getIndicator().getOriginCountryId()
                        );
                if (specialServiceInfo.isPresent()) {
                    log.info("Call to special service '{}' identified.", specialServiceInfo.get().description);
                    cdrData.setTelephonyTypeId(TelephonyTypeEnum.SPECIAL_SERVICES.getValue());
                    cdrData.setTelephonyTypeName(specialServiceInfo.get().description);
                    cdrData.setOperatorId(specialServiceInfo.get().operatorId);
                    cdrData.setOperatorName(specialServiceInfo.get().operatorName);
                    cdrData.setIndicatorId(commLocation.getIndicatorId()); // Indicator of the plant
                    cdrData.setEffectiveDestinationNumber(numToCheckSpecial);
                    cdrData.setSpecialServiceTariff(specialServiceInfo.get());
                    return;
                }
            }
        }

        // PHP: if ($esinterna) { $infovalor = procesaInterna(...); }
        if (cdrData.isInternalCall()) {
            log.debug("Processing as internal call.");
            processInternalCallLogic(cdrData, commLocation, limits, pbxSpecialRuleAppliedRecursively);
            return;
        }

        // PHP: if (!$pbx_especial) { $telefono_eval = evaluarPBXEspecial(...); if ($telefono_eval !== '' && $telefono_eval !== $info_cdr['dial_number']) { procesaSaliente(..., true); return; } }
        if (!pbxSpecialRuleAppliedRecursively) {
            Optional<String> pbxTransformedDest = pbxSpecialRuleLookupService.applyPbxSpecialRule(
                    cdrData.getEffectiveDestinationNumber(), commLocation.getDirectory(), 2 // 2 for outgoing
            );
            if (pbxTransformedDest.isPresent() && !Objects.equals(pbxTransformedDest.get(), cdrData.getEffectiveDestinationNumber())) {
                String originalDest = cdrData.getEffectiveDestinationNumber();
                log.info("Outgoing number '{}' transformed by PBX rule to '{}'. Reprocessing.", originalDest, pbxTransformedDest.get());
                cdrData.setOriginalDialNumberBeforePbxOutgoing(originalDest); // Store original before this specific transform
                cdrData.setFinalCalledPartyNumber(pbxTransformedDest.get());
                cdrData.setEffectiveDestinationNumber(pbxTransformedDest.get());
                cdrData.setPbxSpecialRuleAppliedInfo("PBX Outgoing Rule: " + originalDest + " -> " + pbxTransformedDest.get());
                processOutgoingLogic(cdrData, commLocation, limits, true); // Recursive call
                return;
            }
        }
        
        // PHP: $infovalor = procesaSaliente_Complementar(...);
        // If not internal, not special, and not transformed by PBX (or already re-processed),
        // the telephony type will be determined by the tariffing logic.
        if (cdrData.getTelephonyTypeId() == null || cdrData.getTelephonyTypeId() == TelephonyTypeEnum.UNKNOWN.getValue()) {
             log.debug("Outgoing call, telephony type to be determined by prefix/tariffing for destination: {}", cdrData.getEffectiveDestinationNumber());
        }
        log.debug("Finished processing OUTGOING logic. CDR Data: {}", cdrData);
    }

    /**
     * PHP equivalent: procesaInterna
     */
    private void processInternalCallLogic(CdrData cdrData, CommunicationLocation commLocation, ExtensionLimits limits, boolean pbxSpecialRuleAppliedRecursively) {
        log.debug("Processing INTERNAL call logic for CDR: {}. Recursive PBX applied: {}", cdrData.getCtlHash(), pbxSpecialRuleAppliedRecursively);

        List<String> prefixesToClean = null;
        boolean stripOnlyIfPrefixMatchesAndFound = true; // PHP: $modo_seguro = true for internal calls if $pbx_especial is true
        // PHP: if ($pbx_especial) { $_PREFIJO_SALIDA_PBX = explode(...); $telefono_dest = limpiar_numero(..., true); }
        // PHP: else { $telefono_dest = limpiar_numero(...); }
        // If pbxSpecialRuleAppliedRecursively is true, it means a PBX rule (likely outgoing) was applied,
        // so we should use the PBX prefixes for cleaning. Otherwise, no PBX prefix cleaning for internal.
        if (pbxSpecialRuleAppliedRecursively && commLocation.getPbxPrefix() != null && !commLocation.getPbxPrefix().isEmpty()) {
            prefixesToClean = Arrays.asList(commLocation.getPbxPrefix().split(","));
        } else {
            prefixesToClean = Collections.emptyList(); // No PBX prefix cleaning if not pbx_especial
            stripOnlyIfPrefixMatchesAndFound = false; // Doesn't matter if prefixesToClean is empty
        }

        String cleanedDestination = CdrUtil.cleanPhoneNumber(
                cdrData.getEffectiveDestinationNumber(),
                prefixesToClean,
                stripOnlyIfPrefixMatchesAndFound
        );
        cdrData.setEffectiveDestinationNumber(cleanedDestination);
        log.debug("Cleaned internal destination: {}", cleanedDestination);

        // PHP: if (trim($info['ext']) != '' && trim($info['ext']) === trim($telefono_dest))
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

        // PHP: $arreglo_info = tipo_llamada_interna(...)
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
        cdrData.setIndicatorId(internalTypeInfo.getDestinationIndicatorId()); // Default to destination's indicator

        // PHP: if (!ExtensionEncontrada($info['funcionario_funid']) && ExtensionEncontrada($info['funcionario_fundes']) && $arreglo_info['incoming'] <= 0 && $info['funcionario_fundes']['comid'] == $resultado_directorio['COMUBICACION_ID'])
        if (internalTypeInfo.isEffectivelyIncoming() && cdrData.getCallDirection() == CallDirection.OUTGOING) {
            log.debug("Internal call determined to be effectively incoming. Inverting parties and trunks.");
            CdrUtil.swapPartyInfo(cdrData);
            CdrUtil.swapTrunks(cdrData);
            cdrData.setCallDirection(CallDirection.INCOMING);
            // Assign employees based on the new direction
            cdrData.setEmployee(internalTypeInfo.getDestinationEmployee()); // Now the caller
            cdrData.setEmployeeId(internalTypeInfo.getDestinationEmployee() != null ? internalTypeInfo.getDestinationEmployee().getId() : null);
            cdrData.setDestinationEmployee(internalTypeInfo.getOriginEmployee()); // Now the callee
            cdrData.setDestinationEmployeeId(internalTypeInfo.getOriginEmployee() != null ? internalTypeInfo.getOriginEmployee().getId() : null);
            cdrData.setIndicatorId(internalTypeInfo.getOriginIndicatorId()); // Indicator of the new caller
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
        result.setTelephonyTypeId(appConfigService.getDefaultInternalCallTypeId()); // Default
        result.setTelephonyTypeName(telephonyTypeLookupService.getTelephonyTypeName(result.getTelephonyTypeId()));
        result.setDestinationIndicatorId(currentCommLocation.getIndicatorId());
        result.setOriginIndicatorId(currentCommLocation.getIndicatorId());

        Optional<Employee> originEmpOpt = employeeLookupService.findEmployeeByExtensionOrAuthCode(
                cdrData.getCallingPartyNumber(), null,
                currentCommLocation.getId(),
                cdrData.getDateTimeOrigination());
        if (originEmpOpt.isEmpty() && employeeLookupService.isPossibleExtension(cdrData.getCallingPartyNumber(), limits)) {
            originEmpOpt = employeeLookupService.findEmployeeByExtensionRange(cdrData.getCallingPartyNumber(), currentCommLocation.getId(), cdrData.getDateTimeOrigination());
        }
        result.setOriginEmployee(originEmpOpt.orElse(null));

        Optional<Employee> destEmpOpt = employeeLookupService.findEmployeeByExtensionOrAuthCode(
                cdrData.getEffectiveDestinationNumber(), null,
                null, // Search globally for destination
                cdrData.getDateTimeOrigination());
        if (destEmpOpt.isEmpty() && employeeLookupService.isPossibleExtension(cdrData.getEffectiveDestinationNumber(), limits)) {
            destEmpOpt = employeeLookupService.findEmployeeByExtensionRange(cdrData.getEffectiveDestinationNumber(), null, cdrData.getDateTimeOrigination());
        }
        result.setDestinationEmployee(destEmpOpt.orElse(null));

        CommunicationLocation originCommLoc = originEmpOpt.map(Employee::getCommunicationLocation).orElse(currentCommLocation);
        CommunicationLocation destCommLoc = destEmpOpt.map(Employee::getCommunicationLocation).orElse(null);

        if (originCommLoc != null && destCommLoc == null && originEmpOpt.isPresent()) {
            destCommLoc = currentCommLocation;
            log.debug("Destination employee not found for internal call; assuming destination is within current commLocation: {}", currentCommLocation.getDirectory());
        }
        
        // PHP: $ignorar = ValidarOrigenDestino(...)
        boolean extGlobales = appConfigService.areExtensionsGlobal(currentCommLocation.getPlantTypeId());
        if (extGlobales && originCommLoc != null && destCommLoc != null &&
            (!Objects.equals(currentCommLocation.getId(), originCommLoc.getId()) || !Objects.equals(currentCommLocation.getId(), destCommLoc.getId()))) {
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

        if (destEmpOpt.isEmpty()) {
            log.debug("Destination employee not found. Checking internal prefixes for: {}", cdrData.getEffectiveDestinationNumber());
            Map<String, Long> internalPrefixes = prefixLookupService.getInternalTelephonyTypePrefixes(
                currentCommLocation.getIndicator().getOriginCountryId()
            );
            boolean prefixMatched = false;
            for (Map.Entry<String, Long> entry : internalPrefixes.entrySet()) {
                if (cdrData.getEffectiveDestinationNumber().startsWith(entry.getKey())) {
                    result.setTelephonyTypeId(entry.getValue());
                    result.setTelephonyTypeName(telephonyTypeLookupService.getTelephonyTypeName(entry.getValue()));
                    result.setAdditionalInfo(appConfigService.getPrefixText());
                    prefixMatched = true;
                    log.debug("Internal call destination matched prefix '{}', type set to {}", entry.getKey(), entry.getValue());
                    break;
                }
            }
            if (!prefixMatched) {
                Long defaultUnresolvedType = appConfigService.getDefaultTelephonyTypeForUnresolvedInternalCalls();
                List<Long> validInternalTypes = telephonyTypeLookupService.getInternalTypeIds();
                if (defaultUnresolvedType != null && defaultUnresolvedType > 0 && validInternalTypes.contains(defaultUnresolvedType)) {
                    result.setTelephonyTypeId(defaultUnresolvedType);
                    result.setTelephonyTypeName(telephonyTypeLookupService.getTelephonyTypeName(defaultUnresolvedType));
                } else {
                    result.setTelephonyTypeId(appConfigService.getDefaultInternalCallTypeId()); // Fallback to general internal
                    result.setTelephonyTypeName(telephonyTypeLookupService.getTelephonyTypeName(result.getTelephonyTypeId()));
                }
                result.setAdditionalInfo(appConfigService.getAssumedText());
                log.info("Destination employee not found and no internal prefix matched. Using type: {}, Info: '{}'",
                         result.getTelephonyTypeId(), result.getAdditionalInfo());
            }
        } else if (originCommLoc != null && destCommLoc != null && originCommLoc.getIndicator() != null && destCommLoc.getIndicator() != null) {
            Indicator originIndicator = originCommLoc.getIndicator();
            Indicator destIndicator = destCommLoc.getIndicator();
            result.setOriginIndicatorId(originIndicator.getId());
            result.setDestinationIndicatorId(destIndicator.getId());

            Subdivision originSubdivision = originEmpOpt.map(Employee::getSubdivision).orElse(null);
            Long originOfficeId = originSubdivision != null ? originSubdivision.getId() : null;
            Subdivision destSubdivision = destEmpOpt.map(Employee::getSubdivision).orElse(null);
            Long destOfficeId = destSubdivision != null ? destSubdivision.getId() : null;

            if (!Objects.equals(originIndicator.getOriginCountryId(), destIndicator.getOriginCountryId())) {
                result.setTelephonyTypeId(TelephonyTypeEnum.INTERNAL_INTERNATIONAL_IP.getValue());
            } else if (!Objects.equals(originIndicator.getId(), destIndicator.getId())) {
                result.setTelephonyTypeId(TelephonyTypeEnum.NATIONAL_IP.getValue());
            } else if (originOfficeId != null && destOfficeId != null && !Objects.equals(originOfficeId, destOfficeId)) {
                result.setTelephonyTypeId(TelephonyTypeEnum.LOCAL_IP.getValue());
            } else {
                result.setTelephonyTypeId(TelephonyTypeEnum.INTERNAL_SIMPLE.getValue());
            }
            result.setTelephonyTypeName(telephonyTypeLookupService.getTelephonyTypeName(result.getTelephonyTypeId()));
            log.debug("Internal call type set to {} based on origin/destination locations.", result.getTelephonyTypeId());

            if (originEmpOpt.isEmpty()) {
                result.setAdditionalInfo(appConfigService.getAssumedText() + "/" + appConfigService.getOriginText());
            }
        } else {
             result.setTelephonyTypeId(appConfigService.getDefaultInternalCallTypeId());
             result.setTelephonyTypeName(telephonyTypeLookupService.getTelephonyTypeName(result.getTelephonyTypeId()));
             result.setAdditionalInfo(appConfigService.getAssumedText());
             log.debug("Internal call, but location info missing for one party. Defaulting to type {} with 'ASUMIDO'.", result.getTelephonyTypeId());
             if (originCommLoc != null && originCommLoc.getIndicator() != null) result.setOriginIndicatorId(originCommLoc.getIndicator().getId());
             if (destCommLoc != null && destCommLoc.getIndicator() != null) result.setDestinationIndicatorId(destCommLoc.getIndicator().getId());
        }
        
        // PHP: if (!ExtensionEncontrada($info['funcionario_funid']) && ExtensionEncontrada($info['funcionario_fundes']) && $arreglo_info['incoming'] <= 0 && $info['funcionario_fundes']['comid'] == $resultado_directorio['COMUBICACION_ID'])
        if (originEmpOpt.isEmpty() && destEmpOpt.isPresent() &&
            cdrData.getCallDirection() == CallDirection.OUTGOING &&
            destCommLoc != null && Objects.equals(destCommLoc.getId(), currentCommLocation.getId())) {
            log.debug("Internal call: Origin not found, Destination found in current plant. Marking as effectively incoming.");
            result.setEffectivelyIncoming(true);
            if (originCommLoc != null && originCommLoc.getIndicator() != null) {
                 result.setDestinationIndicatorId(originCommLoc.getIndicator().getId()); // Destination of the *new* incoming call is the original origin's indicator
            }
        }
        log.debug("Final specific internal call type info: {}", result);
        return result;
    }
}