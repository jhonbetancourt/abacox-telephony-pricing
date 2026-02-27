// File: com/infomedia/abacox/telephonypricing/component/cdrprocessing/CdrEnrichmentService.java
package com.infomedia.abacox.telephonypricing.component.cdrprocessing;

import com.infomedia.abacox.telephonypricing.db.entity.CommunicationLocation;
import com.infomedia.abacox.telephonypricing.db.entity.Employee;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;

@Service
@Log4j2
@RequiredArgsConstructor
public class CdrEnrichmentService {

    private final CallTypeAndDirectionService callTypeAndDirectionService;
    private final EmployeeLookupService employeeLookupService;
    private final CdrConfigService appConfigService;
    private final TelephonyTypeLookupService telephonyTypeLookupService; // For default names

    public CdrData enrichCdr(CdrData cdrData, LineProcessingContext processingContext) {
        CommunicationLocation commLocation = processingContext.getCommLocation();
        if (cdrData == null || cdrData.isMarkedForQuarantine()) {
            log.debug("CDR is null or already marked for quarantine. Skipping enrichment. CDR: {}",
                    cdrData != null ? cdrData.getCtlHash() : "NULL");
            return cdrData;
        }
        log.debug("Starting enrichment for CDR: {}", cdrData.getCtlHash());
        cdrData.setCommLocationId(commLocation.getId());

        try {
            // This call now handles the main flow: es_llamada_interna -> procesaEntrante/procesaSaliente
            callTypeAndDirectionService.processCall(cdrData, processingContext);

            // Added support for GLOBAL_EXTENSION_IGNORE
            if (cdrData.isMarkedForQuarantine() &&
                    (QuarantineErrorType.INTERNAL_SELF_CALL.name().equals(cdrData.getQuarantineStep()) ||
                            QuarantineErrorType.INTERNAL_POLICY_IGNORE.name().equals(cdrData.getQuarantineStep()) ||
                            QuarantineErrorType.GLOBAL_EXTENSION_IGNORE.name().equals(cdrData.getQuarantineStep()) ||
                            (cdrData.getQuarantineStep() != null
                                    && cdrData.getQuarantineStep().contains("IgnorePolicy")))) {
                log.debug(
                        "CDR quarantined during call type processing (Self-call/Ignore Policy). Skipping further enrichment. Hash: {}",
                        cdrData.getCtlHash());
                return cdrData;
            }

            // Employee assignment logic
            assignEmployeeToCdr(cdrData, processingContext);

            // Final duration check
            if (cdrData.getDurationSeconds() != null
                    && cdrData.getDurationSeconds() <= appConfigService.getMinCallDurationForTariffing()) {
                if (cdrData.getTelephonyTypeId() != null &&
                        cdrData.getTelephonyTypeId() > 0 &&
                        cdrData.getTelephonyTypeId() != TelephonyTypeEnum.ERRORS.getValue()) {
                    log.debug("Call duration {}s <= min. Setting type to NO_CONSUMPTION after all processing.",
                            cdrData.getDurationSeconds());
                    cdrData.setTelephonyTypeId(TelephonyTypeEnum.NO_CONSUMPTION.getValue());
                    cdrData.setTelephonyTypeName(telephonyTypeLookupService
                            .getTelephonyTypeName(TelephonyTypeEnum.NO_CONSUMPTION.getValue()));
                    cdrData.setBilledAmount(BigDecimal.ZERO);
                    cdrData.setPricePerMinute(BigDecimal.ZERO);
                    cdrData.setInitialPricePerMinute(BigDecimal.ZERO);
                }
            }

            // Transfer cleanup
            finalizeTransferInformation(cdrData);

        } catch (Exception e) {
            log.debug("Unhandled exception during CDR enrichment for line: {}", cdrData.getCtlHash(), e);
            cdrData.setMarkedForQuarantine(true);
            cdrData.setQuarantineReason("Enrichment failed: " + e.getMessage());
            cdrData.setQuarantineStep(QuarantineErrorType.ENRICHMENT_ERROR.name());
        }
        log.debug("Finished enrichment for CDR: {}. Billed Amount: {}, Type: {}", cdrData.getCtlHash(),
                cdrData.getBilledAmount(), cdrData.getTelephonyTypeName());
        return cdrData;
    }

    private void assignEmployeeToCdr(CdrData cdrData, LineProcessingContext processingContext) {
        String searchExtForEmployee;
        String searchAuthCodeForEmployee;
        Long commLocationId = processingContext.getCommLocation().getId();
        List<String> ignoredAuthCodes = processingContext.getCdrProcessor().getIgnoredAuthCodeDescriptions();

        // PHP: $arreglo_fun = ObtenerFuncionario_Arreglo($link, $ext, $clave,
        // $incoming, $info_cdr['date'], $funext, $COMUBICACION_ID, $tipo_fun);
        // $ext is callingPartyNumber, $clave is authCode, $incoming is callDirection
        // $tipo_fun (0=any_origin, 2=local_origin) depends on ext_globales.
        // For incoming, PHP's ObtenerFuncionario_Arreglo uses $ext (our extension) and
        // $clave (auth code, but ignored for incoming).
        // For outgoing, it uses $ext (our extension) and $clave (auth code).

        if (cdrData.getCallDirection() == CallDirection.INCOMING) {
            searchExtForEmployee = cdrData.getCallingPartyNumber(); // This is our extension after parser swap
            searchAuthCodeForEmployee = null; // Auth code not relevant for identifying our extension on an incoming
                                              // call
        } else { // OUTGOING or internal processed as outgoing
            searchExtForEmployee = cdrData.getCallingPartyNumber();
            searchAuthCodeForEmployee = cdrData.getAuthCodeDescription();
        }

        Employee foundEmployee = employeeLookupService.findEmployeeByExtensionOrAuthCode(
                searchExtForEmployee,
                searchAuthCodeForEmployee,
                commLocationId,
                ignoredAuthCodes,
                processingContext.getExtensionRanges(),
                cdrData.getDateTimeOrigination(),
                processingContext.getHistoricalData())
                .orElse(null);

        if (foundEmployee != null) {
            cdrData.setEmployeeId(foundEmployee.getId());
            cdrData.setEmployee(foundEmployee);
            log.debug("Found employee: ID={}, Ext={}", foundEmployee.getId(), foundEmployee.getExtension());

            boolean authCodeProvided = searchAuthCodeForEmployee != null && !searchAuthCodeForEmployee.isEmpty();
            boolean authCodeMatchedAndValid = authCodeProvided &&
                    foundEmployee.getAuthCode() != null &&
                    foundEmployee.getAuthCode()
                            .equalsIgnoreCase(searchAuthCodeForEmployee)
                    && processingContext.getIgnoredAuthCodes().stream()
                            .noneMatch(desc -> desc.equalsIgnoreCase(searchAuthCodeForEmployee));

            if (authCodeMatchedAndValid) {
                cdrData.setAssignmentCause(AssignmentCause.AUTH_CODE);
            } else if (authCodeProvided) {
                cdrData.setAssignmentCause(AssignmentCause.IGNORED_AUTH_CODE);
            } else {
                cdrData.setAssignmentCause(AssignmentCause.EXTENSION);
            }
            // If employee was found via range (ID is null for conceptual employee from
            // range)
            if (foundEmployee.getId() == null && cdrData.getAssignmentCause() == AssignmentCause.EXTENSION) {
                cdrData.setAssignmentCause(AssignmentCause.RANGES);
            }
            log.debug("Employee assignment cause: {}", cdrData.getAssignmentCause());

        } else {
            cdrData.setAssignmentCause(AssignmentCause.NOT_ASSIGNED);
            log.debug("Employee not found for Ext: {}, AuthCode: {}", searchExtForEmployee, searchAuthCodeForEmployee);
        }

        // PHP: if (!ExtensionEncontrada($arreglo_fun) && !$es_interna &&
        // isset($info['funcionario_redir']) && $info['funcionario_redir']['comid'] ==
        // $COMUBICACION_ID)
        // This logic assigns the call to the redirecting employee if the primary
        // employee wasn't found and it's a transfer.
        if (cdrData.getEmployeeId() == null && !cdrData.isInternalCall() &&
                cdrData.getLastRedirectDn() != null && !cdrData.getLastRedirectDn().isEmpty() &&
                cdrData.getTransferCause() != TransferCause.NONE
                && cdrData.getTransferCause() != TransferCause.CONFERENCE_END) {
            Employee redirEmployee = employeeLookupService.findEmployeeByExtensionOrAuthCode(
                    cdrData.getLastRedirectDn(), null, // No auth code for redirect lookup
                    commLocationId, ignoredAuthCodes, processingContext.getExtensionRanges(),
                    cdrData.getDateTimeOrigination(),
                    processingContext.getHistoricalData())
                    .orElse(null);
            if (redirEmployee != null &&
                    redirEmployee.getCommunicationLocation() != null &&
                    Objects.equals(redirEmployee.getCommunicationLocation().getId(), commLocationId)) {
                cdrData.setEmployeeId(redirEmployee.getId());
                cdrData.setEmployee(redirEmployee);
                cdrData.setAssignmentCause(AssignmentCause.TRANSFER);
                log.debug("Assigned call to redirecting employee (due to transfer): ID={}, Ext={}",
                        redirEmployee.getId(), redirEmployee.getExtension());
            }
        }
    }

    private void finalizeTransferInformation(CdrData cdrData) {
        if (cdrData.getTransferCause() != TransferCause.NONE &&
                cdrData.getLastRedirectDn() != null && !cdrData.getLastRedirectDn().isEmpty()) {
            cdrData.setEmployeeTransferExtension(cdrData.getLastRedirectDn());

            boolean transferToSelfOrOtherParty = false;
            String currentPartyExtension = null;
            String otherPartyExtension = null;

            if (cdrData.getCallDirection() == CallDirection.INCOMING) {
                currentPartyExtension = cdrData.getCallingPartyNumber(); // Our extension
                otherPartyExtension = cdrData.getFinalCalledPartyNumber(); // External number
            } else { // OUTGOING or internal
                currentPartyExtension = cdrData.getCallingPartyNumber(); // Our extension
                otherPartyExtension = cdrData.getFinalCalledPartyNumber(); // External/Internal number
            }

            if (Objects.equals(cdrData.getLastRedirectDn(), currentPartyExtension) ||
                    Objects.equals(cdrData.getLastRedirectDn(), otherPartyExtension)) {
                transferToSelfOrOtherParty = true;
            }

            if (transferToSelfOrOtherParty &&
                    cdrData.getTransferCause() != TransferCause.CONFERENCE &&
                    cdrData.getTransferCause() != TransferCause.CONFERENCE_NOW &&
                    cdrData.getTransferCause() != TransferCause.PRE_CONFERENCE_NOW) {
                log.debug(
                        "Clearing transfer info as it's a transfer to self/current party for non-conference. Original transfer ext: {}",
                        cdrData.getLastRedirectDn());
                cdrData.setEmployeeTransferExtension(null);
                cdrData.setTransferCause(TransferCause.NONE);
            } else {
                log.debug("Finalized transfer info. Transfer Ext: {}, Cause: {}",
                        cdrData.getEmployeeTransferExtension(), cdrData.getTransferCause());
            }
        }
    }
}