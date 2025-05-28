// File: com/infomedia/abacox/telephonypricing/cdr/CdrEnrichmentService.java
package com.infomedia.abacox.telephonypricing.cdr;

import com.infomedia.abacox.telephonypricing.entity.CommunicationLocation;
import com.infomedia.abacox.telephonypricing.entity.Employee;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.Objects;

@Service
@Log4j2
@RequiredArgsConstructor
public class CdrEnrichmentService {

    private final CallTypeDeterminationService callTypeDeterminationService;
    private final EmployeeLookupService employeeLookupService;
    private final TariffCalculationService tariffCalculationService;
    private final CdrConfigService appConfigService;
    private final TelephonyTypeLookupService telephonyTypeLookupService;

    public CdrData enrichCdr(CdrData cdrData, CommunicationLocation commLocation) {
        if (cdrData == null || cdrData.isMarkedForQuarantine()) {
            log.warn("CDR is null or already marked for quarantine. Skipping enrichment. CDR: {}", cdrData != null ? cdrData.getRawCdrLine() : "NULL");
            return cdrData;
        }
        log.info("Starting enrichment for CDR: {}", cdrData.getRawCdrLine());
        cdrData.setCommLocationId(commLocation.getId());

        try {
            callTypeDeterminationService.determineCallTypeAndDirection(cdrData, commLocation);
            log.debug("After call type determination: Direction={}, Internal={}, TelephonyType={}",
                    cdrData.getCallDirection(), cdrData.isInternalCall(), cdrData.getTelephonyTypeId());

            if (cdrData.isMarkedForQuarantine()) { // Check if callTypeDetermination quarantined it
                log.warn("CDR marked for quarantine after call type determination. Reason: {}, Step: {}", cdrData.getQuarantineReason(), cdrData.getQuarantineStep());
                return cdrData;
            }

            // PHP: if (trim($info['ext']) != '' && trim($info['ext']) === trim($telefono_dest)) ... IgnorarLlamada(... 'IGUALDESTINO')
            // This check is now inside CallTypeDeterminationService.processInternalCallLogic

            String searchExtForEmployee;
            String searchAuthCodeForEmployee;

            // PHP: $arreglo_fun = ObtenerFuncionario_Arreglo($link, $ext, $clave, $incoming, $info_cdr['date'], $funext, $COMUBICACION_ID, $tipo_fun);
            // The $tipo_fun (0 for origin, 1 for dest, 2 for origin_local_only) is handled by commLocationIdContext in findEmployeeByExtensionOrAuthCode
            if (cdrData.getCallDirection() == CallDirection.INCOMING) {
                // For incoming, PHP's ObtenerFuncionario_Arreglo would be called with the *destination* extension (our internal one)
                // and no auth code. The external number is not used for employee lookup.
                searchExtForEmployee = cdrData.getFinalCalledPartyNumber(); // Our extension
                searchAuthCodeForEmployee = null;
            } else { // OUTGOING
                searchExtForEmployee = cdrData.getCallingPartyNumber(); // Our extension
                searchAuthCodeForEmployee = cdrData.getAuthCodeDescription();
            }

            Employee foundEmployee = employeeLookupService.findEmployeeByExtensionOrAuthCode(
                            searchExtForEmployee,
                            searchAuthCodeForEmployee,
                            commLocation.getId(), // Context for current CommLocation
                            cdrData.getDateTimeOrigination())
                    .orElse(null);

            if (foundEmployee != null) {
                cdrData.setEmployeeId(foundEmployee.getId());
                cdrData.setEmployee(foundEmployee);
                log.info("Found employee: ID={}, Ext={}", foundEmployee.getId(), foundEmployee.getExtension());

                boolean authCodeProvided = searchAuthCodeForEmployee != null && !searchAuthCodeForEmployee.isEmpty();
                boolean authCodeMatched = authCodeProvided &&
                                          foundEmployee.getAuthCode() != null &&
                                          foundEmployee.getAuthCode().equalsIgnoreCase(searchAuthCodeForEmployee);
                boolean isIgnoredAuthCodeType = authCodeProvided && appConfigService.getIgnoredAuthCodeDescriptions().stream()
                                                                    .anyMatch(desc -> desc.equalsIgnoreCase(searchAuthCodeForEmployee));

                if (authCodeMatched) {
                    cdrData.setAssignmentCause(AssignmentCause.AUTH_CODE);
                } else if (authCodeProvided && !isIgnoredAuthCodeType) { // PHP: $esexten && $hayclave (clave provided but not matched/ignored)
                    cdrData.setAssignmentCause(AssignmentCause.IGNORED_AUTH_CODE);
                } else { // No auth code, or ignored auth code, or auth code didn't match but extension did
                    cdrData.setAssignmentCause(AssignmentCause.EXTENSION);
                }
                // PHP: if ($funid <= 0 && $tiempo > 0) { $funid = ActualizarFuncionarios(...); }
                // PHP: if ($info_asigna == IMDEX_ASIGNA_EXT && ExtensionEncontrada($arreglo_fun)) { $info_asigna = IMDEX_ASIGNA_RANGOS; }
                if (foundEmployee.getId() == null && // Means it's a conceptual employee from range
                    cdrData.getDurationSeconds() > 0 &&
                    appConfigService.createEmployeesAutomaticallyFromRange()) {
                    cdrData.setAssignmentCause(AssignmentCause.RANGES);
                }
                log.debug("Employee assignment cause: {}", cdrData.getAssignmentCause());

            } else {
                 cdrData.setAssignmentCause(AssignmentCause.NOT_ASSIGNED);
                 log.warn("Employee not found for Ext: {}, AuthCode: {}", searchExtForEmployee, searchAuthCodeForEmployee);
            }

            // PHP: if (isset($info['funcionario_fundes'])) { $fundes = $info['funcionario_fundes']['id']; }
            if (cdrData.isInternalCall() && cdrData.getEffectiveDestinationNumber() != null) {
                 employeeLookupService.findEmployeeByExtensionOrAuthCode(
                                 cdrData.getEffectiveDestinationNumber(), null,
                                 null, // Search globally for destination employee in internal call
                                 cdrData.getDateTimeOrigination())
                    .ifPresent(destEmployee -> {
                        cdrData.setDestinationEmployeeId(destEmployee.getId());
                        cdrData.setDestinationEmployee(destEmployee);
                        log.debug("Internal call destination employee found: ID={}, Ext={}", destEmployee.getId(), destEmployee.getExtension());
                    });
            }

            // PHP: if (!ExtensionEncontrada($arreglo_fun) && !$es_interna && isset($info['funcionario_redir']) && $info['funcionario_redir']['comid'] == $COMUBICACION_ID)
            if (cdrData.getEmployeeId() == null && !cdrData.isInternalCall() &&
                cdrData.getLastRedirectDn() != null && !cdrData.getLastRedirectDn().isEmpty()) {
                Employee redirEmployee = employeeLookupService.findEmployeeByExtensionOrAuthCode(
                                cdrData.getLastRedirectDn(), null,
                                commLocation.getId(), // Must be from the same commLocation
                                cdrData.getDateTimeOrigination())
                        .orElse(null);
                if (redirEmployee != null &&
                    redirEmployee.getCommunicationLocation() != null &&
                    Objects.equals(redirEmployee.getCommunicationLocation().getId(), commLocation.getId())) {
                    cdrData.setEmployeeId(redirEmployee.getId());
                    cdrData.setEmployee(redirEmployee);
                    cdrData.setAssignmentCause(AssignmentCause.TRANSFER); // PHP: $arreglo_funredir['info_asigna'] = IMDEX_ASIGNA_TRANS;
                    log.info("Assigned call to redirecting employee (due to transfer): ID={}, Ext={}", redirEmployee.getId(), redirEmployee.getExtension());
                }
            }

            // PHP: if ($tiempo <= 0 && $tipotele_id > 0 && $tipotele_id != _TIPOTELE_ERRORES) { $tipotele_id = _TIPOTELE_SINCONSUMO; }
            if (cdrData.getDurationSeconds() <= appConfigService.getMinCallDurationForTariffing()) {
                if (cdrData.getTelephonyTypeId() != null &&
                    cdrData.getTelephonyTypeId() > 0 &&
                    cdrData.getTelephonyTypeId() != TelephonyTypeEnum.ERRORS.getValue()) {
                    log.info("Call duration {}s <= min. Setting type to NO_CONSUMPTION.", cdrData.getDurationSeconds());
                    cdrData.setTelephonyTypeId(TelephonyTypeEnum.NO_CONSUMPTION.getValue());
                    cdrData.setTelephonyTypeName(telephonyTypeLookupService.getTelephonyTypeName(TelephonyTypeEnum.NO_CONSUMPTION.getValue()));
                    cdrData.setBilledAmount(BigDecimal.ZERO);
                }
            } else if (cdrData.getTelephonyTypeId() != null &&
                       cdrData.getTelephonyTypeId() != TelephonyTypeEnum.ERRORS.getValue() &&
                       cdrData.getTelephonyTypeId() != TelephonyTypeEnum.NO_CONSUMPTION.getValue()) {
                log.debug("Proceeding to tariff calculation for type: {}", cdrData.getTelephonyTypeId());
                tariffCalculationService.calculateTariffs(cdrData, commLocation);
            } else {
                log.debug("Skipping tariff calculation. Duration: {}, Type: {}", cdrData.getDurationSeconds(), cdrData.getTelephonyTypeId());
                 if (cdrData.getBilledAmount() == null) cdrData.setBilledAmount(BigDecimal.ZERO);
            }

            // PHP: if ($ext_transfer !== '' && ($ext_transfer === $extension || $ext_transfer === $tel_destino) && $info_transfer != IMDEX_TRANSFER_CONFERENCIA)
            if (cdrData.getTransferCause() != TransferCause.NONE &&
                cdrData.getLastRedirectDn() != null && !cdrData.getLastRedirectDn().isEmpty()) {
                cdrData.setEmployeeTransferExtension(cdrData.getLastRedirectDn());
                boolean transferToSelfOrOtherParty = false;
                String currentPartyExtension = null;
                String otherPartyExtension = null;

                if (cdrData.getCallDirection() == CallDirection.INCOMING) {
                    currentPartyExtension = cdrData.getCallingPartyNumber(); // This is our extension after potential swap
                    otherPartyExtension = cdrData.getFinalCalledPartyNumber(); // This is the external number
                } else { // OUTGOING
                    currentPartyExtension = cdrData.getCallingPartyNumber(); // This is our extension
                    otherPartyExtension = cdrData.getFinalCalledPartyNumber(); // This is the external/internal number
                }

                if (Objects.equals(cdrData.getLastRedirectDn(), currentPartyExtension) ||
                    (otherPartyExtension != null && Objects.equals(cdrData.getLastRedirectDn(), otherPartyExtension))) {
                    transferToSelfOrOtherParty = true;
                }

                if (transferToSelfOrOtherParty &&
                    cdrData.getTransferCause() != TransferCause.CONFERENCE &&
                    cdrData.getTransferCause() != TransferCause.CONFERENCE_NOW &&
                    cdrData.getTransferCause() != TransferCause.PRE_CONFERENCE_NOW) {
                    log.debug("Clearing transfer info as it's a transfer to self/current party for non-conference. Original transfer ext: {}", cdrData.getLastRedirectDn());
                    cdrData.setEmployeeTransferExtension(null);
                    cdrData.setTransferCause(TransferCause.NONE);
                } else {
                    log.debug("Finalized transfer info. Transfer Ext: {}, Cause: {}", cdrData.getEmployeeTransferExtension(), cdrData.getTransferCause());
                }
            }

        } catch (Exception e) {
            log.error("Error during CDR enrichment for line: {}", cdrData.getRawCdrLine(), e);
            cdrData.setMarkedForQuarantine(true);
            cdrData.setQuarantineReason("Enrichment failed: " + e.getMessage());
            cdrData.setQuarantineStep(QuarantineErrorType.ENRICHMENT_ERROR.name());
        }
        log.info("Finished enrichment for CDR: {}. Billed Amount: {}, Type: {}", cdrData.getRawCdrLine(), cdrData.getBilledAmount(), cdrData.getTelephonyTypeName());
        return cdrData;
    }
}