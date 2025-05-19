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

    // Cache for extension limits per CommunicationLocation ID
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
        log.debug("Determining call type and direction for CDR: {}", cdrData.getRawCdrLine());
        ExtensionLimits limits = getExtensionLimits(commLocation);

        // PHP: if (es_llamada_interna($info_cdr, $link))
        // The isInternalCall flag might be pre-set by the parser (e.g. Cisco CM 6.0 parser)
        if (!cdrData.isInternalCall()) { // If not already determined by parser
            log.debug("CDR not pre-marked as internal. Checking if it's an internal call.");
            // PHP: if ($esinterna || ExtensionPosible($origen))
            if (employeeLookupService.isPossibleExtension(cdrData.getCallingPartyNumber(), limits)) {
                log.debug("Calling party '{}' is a possible extension. Checking destination for internal call.", cdrData.getCallingPartyNumber());
                String destinationForInternalCheck = CdrParserUtil.cleanPhoneNumber(cdrData.getFinalCalledPartyNumber(), null, false);
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
                if ((destinationForInternalCheck.length() == 1 && destinationForInternalCheck.matches("\\d")) ||
                    employeeLookupService.isPossibleExtension(destinationForInternalCheck, limits)) {
                    cdrData.setInternalCall(true);
                    log.debug("Marked as internal call based on destination '{}' format/possibility.", destinationForInternalCheck);
                }
                // PHP: if (!$esinterna && $no_inicia_cero && $es_numerico && $destino != '' ) { $retornar = Validar_RangoExt(...); $esinterna = $retornar['nuevo']; }
                else if (destinationForInternalCheck.matches("\\d+") &&
                         !destinationForInternalCheck.startsWith("0") && // PHP: ExtensionValida($destino, true)
                         !destinationForInternalCheck.isEmpty()) {
                    log.debug("Destination '{}' is numeric, not starting with 0. Checking extension ranges.", destinationForInternalCheck);
                    Optional<Employee> employeeFromRange = employeeLookupService.findEmployeeByExtensionRange(
                            destinationForInternalCheck, null, cdrData.getDateTimeOrigination() // Search globally for dest range
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
        // PHP: $info[$j] = $info_cdr; (cdrData is modified in place)
        // PHP: if (isset($info_cdr['interna-ext-esp'])) { $funext_datos['extension'][$ext_marcado] = $info_cdr['interna-ext-esp']; }
        // This is handled if pbxInternalTransformed was used.
        cdrData.setEffectiveDestinationNumber(cdrData.getFinalCalledPartyNumber()); // Initialize effective destination

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
        // PHP: if (info_interna($info)) { InvertirLlamada($info); procesaSaliente(...); return; }
        if (cdrData.isInternalCall()) {
            log.debug("Incoming call marked as internal. Inverting and processing as outgoing.");
            CdrParserUtil.swapPartyInfo(cdrData);
            CdrParserUtil.swapTrunks(cdrData); // PHP also swaps trunks in InvertirLlamada
            cdrData.setCallDirection(CallDirection.OUTGOING);
            processOutgoingLogic(cdrData, commLocation, limits, false); // Process as outgoing
            return;
        }

        String externalCallerId = cdrData.getCallingPartyNumber(); // This is the external number
        // For incoming, effective destination is our internal extension
        cdrData.setEffectiveDestinationNumber(cdrData.getFinalCalledPartyNumber());
        log.debug("Incoming call. External Caller ID: {}, Our Extension (FinalCalled): {}", externalCallerId, cdrData.getEffectiveDestinationNumber());


        // PHP: $num_destino = evaluarPBXEspecial($link, $telefono, $directorio, $cliente, 1); // Entrantes
        // For incoming, PBX special rule applies to the *external caller ID*
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
        cdrData.setIndicatorId(originInfo.getIndicatorId()); // This is the *source* indicator for incoming
        cdrData.setDestinationCityName(originInfo.getDestinationDescription()); // This is the *source* city for incoming
        // Effective destination for incoming is our internal extension, which was already set.
        // The `originInfo.getEffectiveNumber()` is the cleaned/identified external caller ID.
        // We might want to store this cleaned external caller ID if it's different.
        if (!Objects.equals(cdrData.getCallingPartyNumber(), originInfo.getEffectiveNumber())) {
            log.debug("Incoming call's external number (callingPartyNumber) updated after origin determination from '{}' to '{}'",
                    cdrData.getCallingPartyNumber(), originInfo.getEffectiveNumber());
            cdrData.setCallingPartyNumber(originInfo.getEffectiveNumber());
        }


        // PHP: $tipotele = _TIPOTELE_LOCAL; (default if not found)
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
        cdrData.setEffectiveDestinationNumber(cdrData.getFinalCalledPartyNumber()); // Update effective destination

        // PHP: if (!$pbx_especial) { $esinterna = info_interna($info_cdr); if (!$esinterna) { $infovalor = procesaServespecial(...); } }
        if (!pbxSpecialRuleAppliedRecursively && !cdrData.isInternalCall()) {
            // PHP: $telefono_orig = limpiar_numero($info['dial_number'], $_PREFIJO_SALIDA_PBX, true);
            String numToCheckSpecial = CdrParserUtil.cleanPhoneNumber(
                    cdrData.getEffectiveDestinationNumber(),
                    commLocation.getPbxPrefix() != null ? Arrays.asList(commLocation.getPbxPrefix().split(",")) : Collections.emptyList(),
                    true // modo_seguro = true
            );
            if (numToCheckSpecial != null && !numToCheckSpecial.isEmpty()) {
                log.debug("Checking for special service with number: {}", numToCheckSpecial);
                Optional<SpecialServiceInfo> specialServiceInfo =
                        specialServiceLookupService.findSpecialService(
                                numToCheckSpecial,
                                commLocation.getIndicatorId(), // Context for special service
                                commLocation.getIndicator().getOriginCountryId()
                        );
                if (specialServiceInfo.isPresent()) {
                    log.info("Call to special service '{}' identified.", specialServiceInfo.get().description);
                    cdrData.setTelephonyTypeId(TelephonyTypeEnum.SPECIAL_SERVICES.getValue());
                    cdrData.setTelephonyTypeName(specialServiceInfo.get().description);
                    cdrData.setOperatorId(specialServiceInfo.get().operatorId);
                    cdrData.setOperatorName(specialServiceInfo.get().operatorName);
                    cdrData.setIndicatorId(commLocation.getIndicatorId()); // Special services are typically local context
                    cdrData.setEffectiveDestinationNumber(numToCheckSpecial);
                    cdrData.setSpecialServiceTariff(specialServiceInfo.get()); // Store for tariffing
                    return; // Special service processing complete
                }
            }
        }

        if (cdrData.isInternalCall()) {
            log.debug("Processing as internal call.");
            processInternalCallLogic(cdrData, commLocation, limits, pbxSpecialRuleAppliedRecursively);
            return;
        }

        // PHP: if (!$pbx_especial) { $telefono_eval = evaluarPBXEspecial($link, $info_cdr['dial_number'], $directorio, $cliente, 2); ... procesaSaliente(..., true); return; }
        if (!pbxSpecialRuleAppliedRecursively) {
            Optional<String> pbxTransformedDest = pbxSpecialRuleLookupService.applyPbxSpecialRule(
                    cdrData.getEffectiveDestinationNumber(), commLocation.getDirectory(), 2 // 2 for outgoing
            );
            if (pbxTransformedDest.isPresent() && !Objects.equals(pbxTransformedDest.get(), cdrData.getEffectiveDestinationNumber())) {
                String originalDest = cdrData.getEffectiveDestinationNumber();
                log.info("Outgoing number '{}' transformed by PBX rule to '{}'. Reprocessing.", originalDest, pbxTransformedDest.get());
                cdrData.setOriginalDialNumberBeforePbxOutgoing(originalDest);
                cdrData.setFinalCalledPartyNumber(pbxTransformedDest.get());
                cdrData.setEffectiveDestinationNumber(pbxTransformedDest.get()); // Update effective destination
                cdrData.setPbxSpecialRuleAppliedInfo("PBX Outgoing Rule: " + originalDest + " -> " + pbxTransformedDest.get());
                processOutgoingLogic(cdrData, commLocation, limits, true); // Recursive call
                return;
            }
        }
        
        // PHP: $infovalor = procesaSaliente_Complementar(...); (This is mainly tariffing, done in TariffCalculationService)
        // At this point, if it's not special service, not internal, and not transformed by PBX rule (or already reprocessed),
        // the telephony type will be determined by prefix matching during tariffing.
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

        // PHP: if ($pbx_especial) { $_PREFIJO_SALIDA_PBX = ...; $telefono_dest = limpiar_numero($info['dial_number'], $_PREFIJO_SALIDA_PBX, true); }
        // PHP: else { $telefono_dest = limpiar_numero($info['dial_number']); }
        List<String> prefixesToClean = null;
        boolean stripOnlyIfPrefixMatches = true; // PHP's limpiar_numero default behavior without prefix list
        if (pbxSpecialRuleAppliedRecursively && commLocation.getPbxPrefix() != null) {
            prefixesToClean = Arrays.asList(commLocation.getPbxPrefix().split(","));
            // For internal calls after PBX rule, PHP's limpiar_numero uses modo_seguro=true
            // which means it will strip prefix if found, otherwise use original.
            // Our CdrParserUtil.cleanPhoneNumber with stripOnlyIfPrefixMatches=true behaves similarly.
        } else if (!pbxSpecialRuleAppliedRecursively) {
            // If not a recursive call due to PBX rule, PHP just calls limpiar_numero without prefixes.
            // This means it only cleans non-numeric chars after the first.
            stripOnlyIfPrefixMatches = false; // Don't try to strip PBX prefixes, just clean non-numerics
        }

        String cleanedDestination = CdrParserUtil.cleanPhoneNumber(
            cdrData.getEffectiveDestinationNumber(), // Use effective, which might have been set by PBX rule
            prefixesToClean,
            stripOnlyIfPrefixMatches
        );
        cdrData.setEffectiveDestinationNumber(cleanedDestination);
        log.debug("Cleaned internal destination: {}", cleanedDestination);

        // PHP: if (trim($info['ext']) != '' && trim($info['ext']) === trim($telefono_dest)) { $infovalor = IgnorarLlamada(...'IGUALDESTINO'); }
        if (cdrData.getCallingPartyNumber() != null && !cdrData.getCallingPartyNumber().trim().isEmpty() &&
            Objects.equals(cdrData.getCallingPartyNumber().trim(), cleanedDestination.trim())) {
            log.warn("Internal call to self (Origin: {}, Destination: {}). Marking for quarantine.", cdrData.getCallingPartyNumber(), cleanedDestination);
            cdrData.setTelephonyTypeId(TelephonyTypeEnum.ERRORS.getValue());
            cdrData.setTelephonyTypeName("Internal Self-Call (Ignored)");
            cdrData.setMarkedForQuarantine(true);
            cdrData.setQuarantineReason("Internal call to self (PHP: IGUALDESTINO)");
            cdrData.setQuarantineStep("processInternalCallLogic_SelfCall");
            return;
        }

        // PHP: $arreglo_info = tipo_llamada_interna($info['ext'], $telefono_dest, $info['date'], $funext, $resultado_directorio, $link);
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
        cdrData.setIndicatorId(internalTypeInfo.getDestinationIndicatorId()); // For internal, this is dest indicator

        // PHP: if (!ExtensionEncontrada($info['funcionario_funid']) && ExtensionEncontrada($info['funcionario_fundes']) && $arreglo_info['incoming'] <= 0 && $info['funcionario_fundes']['comid'] == $resultado_directorio['COMUBICACION_ID']) { InvertirLlamada($info); ... }
        if (internalTypeInfo.isEffectivelyIncoming() && cdrData.getCallDirection() == CallDirection.OUTGOING) {
            log.debug("Internal call determined to be effectively incoming. Inverting parties.");
            CdrParserUtil.swapPartyInfo(cdrData); // This also swaps partitions if they were set
            // Note: PHP's InvertirLlamada also swaps trunks. For internal calls, trunks might not be relevant or might need specific logic.
            // CdrParserUtil.swapTrunks(cdrData); // Assuming trunks might be relevant for some internal IP scenarios
            cdrData.setCallDirection(CallDirection.INCOMING);
            // Update employee and indicator based on the new direction
            cdrData.setEmployee(internalTypeInfo.getDestinationEmployee()); // Now the "calling" party is the original destination
            cdrData.setEmployeeId(internalTypeInfo.getDestinationEmployee() != null ? internalTypeInfo.getDestinationEmployee().getId() : null);
            cdrData.setDestinationEmployee(internalTypeInfo.getOriginEmployee()); // Now the "destination" is the original caller
            cdrData.setDestinationEmployeeId(internalTypeInfo.getOriginEmployee() != null ? internalTypeInfo.getOriginEmployee().getId() : null);
            cdrData.setIndicatorId(internalTypeInfo.getOriginIndicatorId()); // Indicator of the (new) calling party
        } else {
            cdrData.setEmployee(internalTypeInfo.getOriginEmployee());
            cdrData.setEmployeeId(internalTypeInfo.getOriginEmployee() != null ? internalTypeInfo.getOriginEmployee().getId() : null);
            cdrData.setDestinationEmployee(internalTypeInfo.getDestinationEmployee());
            cdrData.setDestinationEmployeeId(internalTypeInfo.getDestinationEmployee() != null ? internalTypeInfo.getDestinationEmployee().getId() : null);
        }

        // PHP: $infovalor['operador'] = operador_interno($link, $tipotele_id, $resultado_directorio);
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
        // PHP: $tipoDeLlamada = -1; (default to error/unknown before specific assignment)
        // PHP: $indicativoOrigen = $resultado_directorio['INDICATIVO_ID'];
        // PHP: $ComubicacionOrigen = $COMUBICACION_ID;
        result.setTelephonyTypeId(appConfigService.getDefaultInternalCallTypeId()); // Initial default
        result.setTelephonyTypeName(telephonyTypeLookupService.getTelephonyTypeName(result.getTelephonyTypeId()));
        result.setDestinationIndicatorId(currentCommLocation.getIndicatorId());
        result.setOriginIndicatorId(currentCommLocation.getIndicatorId());

        // PHP: $arreglo_ori = ObtenerFuncionario_Arreglo($link, $extOrigen, '', 0, $fecha, $funext, $COMUBICACION_ID, 0);
        // PHP: $arreglo_fun = ObtenerFuncionario_Arreglo($link, $extDestino, '', 0, $fecha, $funext, $COMUBICACION_ID, 1);
        // Type 0 for origin (local or global), Type 1 for destination (global)
        Optional<Employee> originEmpOpt = employeeLookupService.findEmployeeByExtensionOrAuthCode(
                cdrData.getCallingPartyNumber(), null, currentCommLocation.getId(), cdrData.getDateTimeOrigination());
        Optional<Employee> destEmpOpt = employeeLookupService.findEmployeeByExtensionOrAuthCode(
                cdrData.getEffectiveDestinationNumber(), null, null, cdrData.getDateTimeOrigination()); // Search globally for dest

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
        CommunicationLocation destCommLoc = destEmpOpt.map(Employee::getCommunicationLocation).orElse(null); // Can be null if dest not found or global

        // PHP: if ($ComubicacionOrigen > 0 && $ComubicacionDestino <= 0) { $ComubicacionDestino = $COMUBICACION_ID; ... }
        if (originCommLoc != null && destCommLoc == null && originEmpOpt.isPresent()) { // If origin is known but dest is not, assume dest is in current plant
            destCommLoc = currentCommLocation;
            log.debug("Destination employee not found for internal call; assuming destination is within current commLocation: {}", currentCommLocation.getDirectory());
        }
        
        // PHP: $ignorar = ValidarOrigenDestino($link, $resultado_directorio, $ComubicacionOrigen, $ComubicacionDestino, true, $arreglo);
        boolean extGlobales = appConfigService.areExtensionsGlobal(currentCommLocation.getPlantTypeId());
        if (extGlobales && originCommLoc != null && destCommLoc != null &&
            (!Objects.equals(currentCommLocation.getId(), originCommLoc.getId()) || !Objects.equals(currentCommLocation.getId(), destCommLoc.getId()))) {
            // PHP: if ($comid_actual != $ComubicacionOrigen && $comid_actual == $ComubicacionDestino) { $ignorar = $ignorar_globales; }
            if (!Objects.equals(currentCommLocation.getId(), originCommLoc.getId()) && Objects.equals(currentCommLocation.getId(), destCommLoc.getId())) {
                result.setIgnoreCall(true);
                result.setAdditionalInfo("Global Extension - Incoming internal from another plant");
                log.warn("Ignoring internal call: Incoming from another plant (Origin: {}, Current: {})", originCommLoc.getDirectory(), currentCommLocation.getDirectory());
                return result;
            }
            // PHP: elseif ($comid_actual != $ComubicacionOrigen && $comid_actual != $ComubicacionDestino) { $ignorar = true; }
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
            // PHP: if ($tipoDeLlamada <= 0 && $interna_defecto > 0) { $tipoDeLlamada = $interna_defecto; if ($subdireccionDestino == '') { $infoadd = _ASUMIDO; } }
            if (!prefixMatched) {
                result.setTelephonyTypeId(appConfigService.getDefaultInternalCallTypeId());
                result.setTelephonyTypeName(telephonyTypeLookupService.getTelephonyTypeName(result.getTelephonyTypeId()));
                result.setAdditionalInfo(appConfigService.getAssumedText());
                log.debug("No internal prefix matched. Defaulting to type {} with 'ASUMIDO'.", result.getTelephonyTypeId());
            }
        } else if (originCommLoc != null && destCommLoc != null && originCommLoc.getIndicator() != null && destCommLoc.getIndicator() != null) {
            // Both origin and destination employees/locations are known
            Indicator originIndicator = originCommLoc.getIndicator();
            Indicator destIndicator = destCommLoc.getIndicator();
            result.setOriginIndicatorId(originIndicator.getId());
            result.setDestinationIndicatorId(destIndicator.getId());

            Subdivision originSubdivision = originEmpOpt.map(Employee::getSubdivision).orElse(null);
            // PHP: $oficinaBuscadaOrigen = $arrSubDirsCliente[$subdireccionOrigen]['oficina'];
            // Assuming Subdivision ID directly represents the office for simplicity here.
            // A more complex model might have Office entity linked to Subdivision.
            Long originOfficeId = originSubdivision != null ? originSubdivision.getId() : null;

            Subdivision destSubdivision = destEmpOpt.map(Employee::getSubdivision).orElse(null);
            Long destOfficeId = destSubdivision != null ? destSubdivision.getId() : null;

            // PHP: if ($mpaisOrigen != $mpaisDestino) { $tipoDeLlamada = _TIPOTELE_INTERNAL_IP; }
            if (!Objects.equals(originIndicator.getOriginCountryId(), destIndicator.getOriginCountryId())) {
                result.setTelephonyTypeId(TelephonyTypeEnum.INTERNAL_INTERNATIONAL_IP.getValue()); // PHP: _TIPOTELE_INTERNAL_IP
            }
            // PHP: elseif ($indicativoOrigen != $indicativoDestino) { $tipoDeLlamada = _TIPOTELE_NACIONAL_IP; }
            else if (!Objects.equals(originIndicator.getId(), destIndicator.getId())) {
                result.setTelephonyTypeId(TelephonyTypeEnum.NATIONAL_IP.getValue());
            }
            // PHP: elseif ($subdireccionOrigen != '' && $oficinaBuscadaOrigen != $oficinaBuscadaDestino) { $tipoDeLlamada = _TIPOTELE_LOCAL_IP; }
            else if (originOfficeId != null && destOfficeId != null && !Objects.equals(originOfficeId, destOfficeId)) {
                result.setTelephonyTypeId(TelephonyTypeEnum.LOCAL_IP.getValue());
            }
            // PHP: else { $tipoDeLlamada = _TIPOTELE_INTERNA_IP; }
            else {
                result.setTelephonyTypeId(TelephonyTypeEnum.INTERNAL_SIMPLE.getValue()); // PHP: _TIPOTELE_INTERNA_IP
            }
            result.setTelephonyTypeName(telephonyTypeLookupService.getTelephonyTypeName(result.getTelephonyTypeId()));
            log.debug("Internal call type set to {} based on origin/destination locations.", result.getTelephonyTypeId());

            // PHP: if ($subdireccionOrigen == '') { $infoadd = _ASUMIDO.'/'._ORIGEN; }
            if (originEmpOpt.isEmpty()) { // If origin employee was not found (e.g., only range matched)
                result.setAdditionalInfo(appConfigService.getAssumedText() + "/" + appConfigService.getOriginText());
            }
        } else { // Fallback if location info is missing for one of the parties
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
            // The indicator for the "new" calling party (original destination) is destIndicator.
            // The indicator for the "new" destination party (original caller, now unknown) would be originIndicator.
            if (originCommLoc != null && originCommLoc.getIndicator() != null) {
                 result.setDestinationIndicatorId(originCommLoc.getIndicator().getId()); // This becomes the "destination" for the inverted call
            }
        }
        log.debug("Final specific internal call type info: {}", result);
        return result;
    }
}