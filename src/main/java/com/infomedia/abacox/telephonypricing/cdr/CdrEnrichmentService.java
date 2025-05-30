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
            log.warn("CDR is null or already marked for quarantine. Skipping enrichment. CDR: {}", cdrData != null ? cdrData.getCtlHash() : "NULL");
            return cdrData;
        }
        log.info("Starting enrichment for CDR: {}", cdrData.getCtlHash());
        cdrData.setCommLocationId(commLocation.getId());

        try {
            callTypeDeterminationService.determineCallTypeAndDirection(cdrData, commLocation);
            log.debug("After call type determination: Direction={}, Internal={}, TelephonyType={}",
                    cdrData.getCallDirection(), cdrData.isInternalCall(), cdrData.getTelephonyTypeId());

            // PHP: if (trim($info['ext']) != '' && trim($info['ext']) === trim($telefono_dest)) ... IgnorarLlamada(... 'IGUALDESTINO')
            // This check is now inside CallTypeDeterminationService.processInternalCallLogic
            if (cdrData.isMarkedForQuarantine() && QuarantineErrorType.INTERNAL_SELF_CALL.name().equals(cdrData.getQuarantineStep())) {
                return cdrData;
            }


            String searchExtForEmployee;
            String searchAuthCodeForEmployee;

            // PHP: $ext = trim($info_cdr['ext']); $clave = strtoupper(trim($info_cdr['acc_code'])); $incoming = $info_cdr['incoming'];
            // PHP: $arreglo_fun = ObtenerFuncionario_Arreglo($link, $ext, $clave, $incoming, ...);
            // For incoming, PHP's ObtenerFuncionario_Arreglo effectively ignores authCode by not passing it to FunIDValido for 'clave' type.
            if (cdrData.getCallDirection() == CallDirection.INCOMING) {
                searchExtForEmployee = cdrData.getCallingPartyNumber(); // This is our extension after potential swap
                searchAuthCodeForEmployee = null; // Auth code not relevant for incoming party identification
            } else { // OUTGOING or internal processed as outgoing
                searchExtForEmployee = cdrData.getCallingPartyNumber();
                searchAuthCodeForEmployee = cdrData.getAuthCodeDescription();
            }

            Employee foundEmployee = employeeLookupService.findEmployeeByExtensionOrAuthCode(
                            searchExtForEmployee,
                            searchAuthCodeForEmployee,
                            commLocation.getId(),
                            cdrData.getDateTimeOrigination())
                    .orElse(null);

            if (foundEmployee != null) {
                cdrData.setEmployeeId(foundEmployee.getId());
                cdrData.setEmployee(foundEmployee); // Store the full object
                log.info("Found employee: ID={}, Ext={}", foundEmployee.getId(), foundEmployee.getExtension());

                boolean authCodeProvided = searchAuthCodeForEmployee != null && !searchAuthCodeForEmployee.isEmpty();
                boolean authCodeMatchedAndValid = authCodeProvided &&
                                          foundEmployee.getAuthCode() != null &&
                                          foundEmployee.getAuthCode().equalsIgnoreCase(searchAuthCodeForEmployee) &&
                                          !appConfigService.getIgnoredAuthCodeDescriptions().stream()
                                            .anyMatch(desc -> desc.equalsIgnoreCase(searchAuthCodeForEmployee));

                if (authCodeMatchedAndValid) {
                    cdrData.setAssignmentCause(AssignmentCause.AUTH_CODE);
                } else if (authCodeProvided && !authCodeMatchedAndValid) { // Auth code provided but didn't match or was invalid type
                    cdrData.setAssignmentCause(AssignmentCause.IGNORED_AUTH_CODE);
                } else { // No auth code provided, or it was ignored type, so assignment is by extension
                    cdrData.setAssignmentCause(AssignmentCause.EXTENSION);
                }

                // PHP: if ($funid <= 0 && $tiempo > 0) { $funid = ActualizarFuncionarios(...); }
                // PHP: if ($info_asigna == IMDEX_ASIGNA_EXT && ExtensionEncontrada($arreglo_fun)) { $info_asigna = IMDEX_ASIGNA_RANGOS; }
                // If employee was found via range (ID would be null for conceptual employee from range)
                if (foundEmployee.getId() == null && cdrData.getAssignmentCause() == AssignmentCause.EXTENSION) {
                    cdrData.setAssignmentCause(AssignmentCause.RANGES);
                    // Actual persistence of conceptual employee from range would happen in CallRecordPersistenceService if configured
                }
                log.debug("Employee assignment cause: {}", cdrData.getAssignmentCause());

            } else {
                 cdrData.setAssignmentCause(AssignmentCause.NOT_ASSIGNED);
                 log.warn("Employee not found for Ext: {}, AuthCode: {}", searchExtForEmployee, searchAuthCodeForEmployee);
            }

            // PHP: if (isset($info['funcionario_fundes'])) { $fundes = $info['funcionario_fundes']['id']; }
            // This is handled if cdrData.isInternalCall() is true and determineSpecificInternalCallType populates destinationEmployee
            if (cdrData.isInternalCall() && cdrData.getEffectiveDestinationNumber() != null && cdrData.getDestinationEmployeeId() == null) {
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
                    cdrData.setAssignmentCause(AssignmentCause.TRANSFER);
                    log.info("Assigned call to redirecting employee (due to transfer): ID={}, Ext={}", redirEmployee.getId(), redirEmployee.getExtension());
                }
            }

            // PHP: if ($tiempo <= 0 && $tipotele_id > 0 && $tipotele_id != _TIPOTELE_ERRORES) { $tipotele_id = _TIPOTELE_SINCONSUMO; }
            if (cdrData.getDurationSeconds() != null && cdrData.getDurationSeconds() <= appConfigService.getMinCallDurationForTariffing()) {
                if (cdrData.getTelephonyTypeId() != null &&
                    cdrData.getTelephonyTypeId() > 0 && // Not UNKNOWN
                    cdrData.getTelephonyTypeId() != TelephonyTypeEnum.ERRORS.getValue()) {
                    log.info("Call duration {}s <= min. Setting type to NO_CONSUMPTION.", cdrData.getDurationSeconds());
                    cdrData.setTelephonyTypeId(TelephonyTypeEnum.NO_CONSUMPTION.getValue());
                    cdrData.setTelephonyTypeName(telephonyTypeLookupService.getTelephonyTypeName(TelephonyTypeEnum.NO_CONSUMPTION.getValue()));
                    cdrData.setBilledAmount(BigDecimal.ZERO); // Ensure billed amount is zero
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
                cdrData.setEmployeeTransferExtension(cdrData.getLastRedirectDn()); // Store the redirect DN

                boolean transferToSelfOrOtherParty = false;
                String currentPartyExtension = null;
                String otherPartyExtension = null;

                if (cdrData.getCallDirection() == CallDirection.INCOMING) {
                    // After potential swaps, finalCalledPartyNumber is our extension, callingPartyNumber is external
                    currentPartyExtension = cdrData.getFinalCalledPartyNumber();
                    otherPartyExtension = cdrData.getCallingPartyNumber();
                } else { // OUTGOING
                    currentPartyExtension = cdrData.getCallingPartyNumber();
                    otherPartyExtension = cdrData.getFinalCalledPartyNumber();
                }

                if (Objects.equals(cdrData.getLastRedirectDn(), currentPartyExtension) ||
                    Objects.equals(cdrData.getLastRedirectDn(), otherPartyExtension)) {
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
            log.error("Error during CDR enrichment for line: {}", cdrData.getCtlHash(), e);
            cdrData.setMarkedForQuarantine(true);
            cdrData.setQuarantineReason("Enrichment failed: " + e.getMessage());
            cdrData.setQuarantineStep(QuarantineErrorType.ENRICHMENT_ERROR.name());
        }
        log.info("Finished enrichment for CDR: {}. Billed Amount: {}, Type: {}", cdrData.getCtlHash(), cdrData.getBilledAmount(), cdrData.getTelephonyTypeName());
        return cdrData;
    }
}