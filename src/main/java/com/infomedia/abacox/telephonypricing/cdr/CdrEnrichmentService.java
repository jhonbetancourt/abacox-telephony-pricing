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

            if (cdrData.isMarkedForQuarantine() && QuarantineErrorType.INTERNAL_SELF_CALL.name().equals(cdrData.getQuarantineStep())) {
                return cdrData;
            }

            String searchExtForEmployee;
            String searchAuthCodeForEmployee;
            String partyToIdentifyAsEmployee = cdrData.getCallingPartyNumber(); // Default for outgoing

            if (cdrData.getCallDirection() == CallDirection.INCOMING) {
                // For incoming, after parser swaps, callingPartyNumber is OUR extension, finalCalledPartyNumber is external.
                // PHP's ObtenerFuncionario_Arreglo uses $ext (our extension) and $clave (auth code, but ignored for incoming).
                partyToIdentifyAsEmployee = cdrData.getCallingPartyNumber(); // This is our extension
                searchAuthCodeForEmployee = null; // Auth code not relevant for identifying our extension on an incoming call
            } else { // OUTGOING or internal processed as outgoing
                partyToIdentifyAsEmployee = cdrData.getCallingPartyNumber();
                searchAuthCodeForEmployee = cdrData.getAuthCodeDescription();
            }
            searchExtForEmployee = partyToIdentifyAsEmployee;


            Employee foundEmployee = employeeLookupService.findEmployeeByExtensionOrAuthCode(
                            searchExtForEmployee,
                            searchAuthCodeForEmployee,
                            commLocation.getId(),
                            cdrData.getDateTimeOrigination())
                    .orElse(null);

            if (foundEmployee != null) {
                cdrData.setEmployeeId(foundEmployee.getId());
                cdrData.setEmployee(foundEmployee);
                log.info("Found employee: ID={}, Ext={}", foundEmployee.getId(), foundEmployee.getExtension());

                boolean authCodeProvided = searchAuthCodeForEmployee != null && !searchAuthCodeForEmployee.isEmpty();
                boolean authCodeMatchedAndValid = authCodeProvided &&
                                          foundEmployee.getAuthCode() != null &&
                                          foundEmployee.getAuthCode().equalsIgnoreCase(searchAuthCodeForEmployee) &&
                                          !appConfigService.getIgnoredAuthCodeDescriptions().stream()
                                            .anyMatch(desc -> desc.equalsIgnoreCase(searchAuthCodeForEmployee));

                if (authCodeMatchedAndValid) {
                    cdrData.setAssignmentCause(AssignmentCause.AUTH_CODE);
                } else if (authCodeProvided) { // Auth code provided but didn't match or was invalid type
                    cdrData.setAssignmentCause(AssignmentCause.IGNORED_AUTH_CODE);
                } else { // No auth code provided, or it was ignored type, so assignment is by extension
                    cdrData.setAssignmentCause(AssignmentCause.EXTENSION);
                }

                if (foundEmployee.getId() == null && cdrData.getAssignmentCause() == AssignmentCause.EXTENSION) {
                    cdrData.setAssignmentCause(AssignmentCause.RANGES);
                }
                log.debug("Employee assignment cause: {}", cdrData.getAssignmentCause());

            } else {
                 cdrData.setAssignmentCause(AssignmentCause.NOT_ASSIGNED);
                 log.warn("Employee not found for Ext: {}, AuthCode: {}", searchExtForEmployee, searchAuthCodeForEmployee);
            }

            if (cdrData.isInternalCall() && cdrData.getEffectiveDestinationNumber() != null && cdrData.getDestinationEmployeeId() == null) {
                 employeeLookupService.findEmployeeByExtensionOrAuthCode(
                                 cdrData.getEffectiveDestinationNumber(), null,
                                 null,
                                 cdrData.getDateTimeOrigination())
                    .ifPresent(destEmployee -> {
                        cdrData.setDestinationEmployeeId(destEmployee.getId());
                        cdrData.setDestinationEmployee(destEmployee);
                        log.debug("Internal call destination employee found: ID={}, Ext={}", destEmployee.getId(), destEmployee.getExtension());
                    });
            }

            // PHP: if (!ExtensionEncontrada($arreglo_fun) && !$es_interna && isset($info['funcionario_redir']) && $info['funcionario_redir']['comid'] == $COMUBICACION_ID)
            // This logic assigns the call to the redirecting employee if the primary employee wasn't found and it's a transfer.
            if (cdrData.getEmployeeId() == null && !cdrData.isInternalCall() &&
                cdrData.getLastRedirectDn() != null && !cdrData.getLastRedirectDn().isEmpty() &&
                cdrData.getTransferCause() != TransferCause.NONE && cdrData.getTransferCause() != TransferCause.CONFERENCE_END) { // Don't reassign for conference end
                Employee redirEmployee = employeeLookupService.findEmployeeByExtensionOrAuthCode(
                                cdrData.getLastRedirectDn(), null,
                                commLocation.getId(),
                                cdrData.getDateTimeOrigination())
                        .orElse(null);
                if (redirEmployee != null &&
                    redirEmployee.getCommunicationLocation() != null &&
                    Objects.equals(redirEmployee.getCommunicationLocation().getId(), commLocation.getId())) {
                    cdrData.setEmployeeId(redirEmployee.getId());
                    cdrData.setEmployee(redirEmployee);
                    cdrData.setAssignmentCause(AssignmentCause.TRANSFER); // This is the employee who *received* the transfer
                    log.info("Assigned call to redirecting employee (due to transfer): ID={}, Ext={}", redirEmployee.getId(), redirEmployee.getExtension());
                }
            }


            if (cdrData.getDurationSeconds() != null && cdrData.getDurationSeconds() <= appConfigService.getMinCallDurationForTariffing()) {
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

            if (cdrData.getTransferCause() != TransferCause.NONE &&
                cdrData.getLastRedirectDn() != null && !cdrData.getLastRedirectDn().isEmpty()) {
                cdrData.setEmployeeTransferExtension(cdrData.getLastRedirectDn());

                boolean transferToSelfOrOtherParty = false;
                String currentPartyExtension = null;
                String otherPartyExtension = null;

                if (cdrData.getCallDirection() == CallDirection.INCOMING) {
                    currentPartyExtension = cdrData.getCallingPartyNumber(); // Our extension
                    otherPartyExtension = cdrData.getFinalCalledPartyNumber(); // External number
                } else {
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