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

    /**
     * PHP equivalent: Parts of CargarCDR (after evaluar_Formato and before acumtotal_Insertar)
     */
    public CdrData enrichCdr(CdrData cdrData, CommunicationLocation commLocation) {
        if (cdrData == null || cdrData.isMarkedForQuarantine()) {
            log.warn("CDR is null or already marked for quarantine. Skipping enrichment. CDR: {}", cdrData != null ? cdrData.getRawCdrLine() : "NULL");
            return cdrData;
        }
        log.info("Starting enrichment for CDR: {}", cdrData.getRawCdrLine());
        cdrData.setCommLocationId(commLocation.getId());

        try {
            // 1. Determine Call Type (Internal, Incoming, Outgoing) and apply initial transformations
            callTypeDeterminationService.determineCallTypeAndDirection(cdrData, commLocation);
            log.debug("After call type determination: Direction={}, Internal={}, TelephonyType={}",
                    cdrData.getCallDirection(), cdrData.isInternalCall(), cdrData.getTelephonyTypeId());

            // 2. Assign Employees (PHP: ObtenerFuncionario_Arreglo)
            String searchExtForEmployee;
            String searchAuthCodeForEmployee;

            if (cdrData.getCallDirection() == CallDirection.INCOMING) {
                searchExtForEmployee = cdrData.getFinalCalledPartyNumber(); // Our extension after potential inversion
                searchAuthCodeForEmployee = null;
                log.debug("Incoming call. Searching employee by extension: {}", searchExtForEmployee);
            } else { // OUTGOING or effectively OUTGOING
                searchExtForEmployee = cdrData.getCallingPartyNumber();
                searchAuthCodeForEmployee = cdrData.getAuthCodeDescription();
                log.debug("Outgoing call. Searching employee by extension: {}, authCode: {}", searchExtForEmployee, searchAuthCodeForEmployee);
            }

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
                boolean authCodeMatched = authCodeProvided &&
                                          foundEmployee.getAuthCode() != null &&
                                          foundEmployee.getAuthCode().equalsIgnoreCase(searchAuthCodeForEmployee);
                boolean isIgnoredAuthCodeType = authCodeProvided && appConfigService.getIgnoredAuthCodeDescriptions().stream()
                                                                    .anyMatch(desc -> desc.equalsIgnoreCase(searchAuthCodeForEmployee));

                if (authCodeMatched) {
                    cdrData.setAssignmentCause(AssignmentCause.AUTH_CODE);
                } else if (authCodeProvided && !isIgnoredAuthCodeType) {
                    cdrData.setAssignmentCause(AssignmentCause.IGNORED_AUTH_CODE);
                } else {
                    cdrData.setAssignmentCause(AssignmentCause.EXTENSION);
                }

                if (foundEmployee.getId() == null && cdrData.getDurationSeconds() > 0 && appConfigService.createEmployeesAutomaticallyFromRange()) {
                    cdrData.setAssignmentCause(AssignmentCause.RANGES);
                    log.debug("Employee is conceptual (from range). Assignment cause: RANGES");
                }
                log.debug("Employee assignment cause: {}", cdrData.getAssignmentCause());

            } else {
                 cdrData.setAssignmentCause(AssignmentCause.NOT_ASSIGNED);
                 log.warn("Employee not found for Ext: {}, AuthCode: {}", searchExtForEmployee, searchAuthCodeForEmployee);
            }

            if (cdrData.isInternalCall() && cdrData.getEffectiveDestinationNumber() != null) {
                 employeeLookupService.findEmployeeByExtensionOrAuthCode(
                                 cdrData.getEffectiveDestinationNumber(), null,
                                 null, // Search globally for destination internal extension
                                 cdrData.getDateTimeOrigination())
                    .ifPresent(destEmployee -> {
                        cdrData.setDestinationEmployeeId(destEmployee.getId());
                        cdrData.setDestinationEmployee(destEmployee);
                        log.debug("Internal call destination employee found: ID={}, Ext={}", destEmployee.getId(), destEmployee.getExtension());
                    });
            }

            if (cdrData.getEmployeeId() == null && !cdrData.isInternalCall() &&
                cdrData.getLastRedirectDn() != null && !cdrData.getLastRedirectDn().isEmpty()) {
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
                    cdrData.setAssignmentCause(AssignmentCause.TRANSFER);
                    log.info("Assigned call to redirecting employee: ID={}, Ext={}", redirEmployee.getId(), redirEmployee.getExtension());
                }
            }

            // 3. Calculate Tariffs
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
                 if (cdrData.getBilledAmount() == null) cdrData.setBilledAmount(BigDecimal.ZERO); // Ensure it's not null
            }

            // 4. Finalize Transfer Info
            if (cdrData.getTransferCause() != TransferCause.NONE &&
                cdrData.getLastRedirectDn() != null && !cdrData.getLastRedirectDn().isEmpty()) {
                cdrData.setEmployeeTransferExtension(cdrData.getLastRedirectDn());
                boolean transferToSelf = false;
                String extToCompareAgainst = (cdrData.getCallDirection() == CallDirection.INCOMING) ?
                                             cdrData.getFinalCalledPartyNumber() :
                                             cdrData.getCallingPartyNumber();
                if (Objects.equals(cdrData.getLastRedirectDn(), extToCompareAgainst)) {
                    transferToSelf = true;
                } else {
                    String otherParty = (cdrData.getCallDirection() == CallDirection.INCOMING) ?
                                        cdrData.getCallingPartyNumber() :
                                        cdrData.getFinalCalledPartyNumber();
                    if (Objects.equals(cdrData.getLastRedirectDn(), otherParty)) {
                        transferToSelf = true;
                    }
                }
                if (transferToSelf && cdrData.getTransferCause() != TransferCause.CONFERENCE) {
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
            cdrData.setQuarantineStep("enrichCdr_UnhandledException");
        }
        log.info("Finished enrichment for CDR: {}. Billed Amount: {}, Type: {}", cdrData.getRawCdrLine(), cdrData.getBilledAmount(), cdrData.getTelephonyTypeName());
        return cdrData;
    }
}