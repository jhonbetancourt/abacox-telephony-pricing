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
            return cdrData;
        }
        cdrData.setCommLocationId(commLocation.getId());

        try {
            // 1. Determine Call Type (Internal, Incoming, Outgoing) and apply initial transformations
            // This also handles some initial inversions based on PHP's es_llamada_interna and procesaEntrante/procesaSaliente logic
            callTypeDeterminationService.determineCallTypeAndDirection(cdrData, commLocation);

            // 2. Assign Employees (PHP: ObtenerFuncionario_Arreglo)
            String searchExtForEmployee;
            String searchAuthCodeForEmployee;

            if (cdrData.getCallDirection() == CallDirection.INCOMING) {
                // For incoming, the "extension" is the destination of the call (our employee)
                // PHP: $ext = trim($info_cdr['ext']); (after potential inversion)
                searchExtForEmployee = cdrData.getFinalCalledPartyNumber(); // This is our extension after inversion
                searchAuthCodeForEmployee = null; // Auth codes typically not for incoming
            } else { // OUTGOING or effectively OUTGOING (after internal call inversion)
                searchExtForEmployee = cdrData.getCallingPartyNumber();
                searchAuthCodeForEmployee = cdrData.getAuthCodeDescription();
            }

            // PHP: $arreglo_fun = ObtenerFuncionario_Arreglo($link, $ext, $clave, $incoming, $info_cdr['date'], $funext, $COMUBICACION_ID, $tipo_fun);
            // The $tipo_fun (0=any, 2=local only) is handled by employeeLookupService based on global extension config
            Employee foundEmployee = employeeLookupService.findEmployeeByExtensionOrAuthCode(
                            searchExtForEmployee,
                            searchAuthCodeForEmployee,
                            commLocation.getId(), // Pass full commLocation for context
                            cdrData.getDateTimeOrigination())
                    .orElse(null);

            if (foundEmployee != null) {
                cdrData.setEmployeeId(foundEmployee.getId()); // Can be null if conceptual employee from range
                cdrData.setEmployee(foundEmployee);

                // Determine assignment cause (PHP: $retornar['info_asigna'])
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
                } else { // No auth code provided, or auth code was an "ignored type" (so we assign by extension)
                    cdrData.setAssignmentCause(AssignmentCause.EXTENSION);
                }

                // If employee was conceptual (from range, ID is null)
                if (foundEmployee.getId() == null && cdrData.getDurationSeconds() > 0 && appConfigService.createEmployeesAutomaticallyFromRange()) {
                    cdrData.setAssignmentCause(AssignmentCause.RANGES);
                }

            } else {
                 cdrData.setAssignmentCause(AssignmentCause.NOT_ASSIGNED);
            }


            // Handle destination employee for internal calls (PHP: $info_cdr['funcionario_fundes'])
            if (cdrData.isInternalCall() && cdrData.getEffectiveDestinationNumber() != null) {
                 employeeLookupService.findEmployeeByExtensionOrAuthCode(
                                 cdrData.getEffectiveDestinationNumber(), null,
                                 null, // Search globally for destination internal extension
                                 cdrData.getDateTimeOrigination())
                    .ifPresent(destEmployee -> {
                        cdrData.setDestinationEmployeeId(destEmployee.getId());
                        cdrData.setDestinationEmployee(destEmployee);
                    });
            }

            // Handle transfer assignment (PHP: if (!ExtensionEncontrada($arreglo_fun) && !$es_interna && isset($info['funcionario_redir'])...))
            // PHP: $info['funcionario_redir']['comid'] == $COMUBICACION_ID
            if (cdrData.getEmployeeId() == null && !cdrData.isInternalCall() &&
                cdrData.getLastRedirectDn() != null && !cdrData.getLastRedirectDn().isEmpty()) {

                Employee redirEmployee = employeeLookupService.findEmployeeByExtensionOrAuthCode(
                                cdrData.getLastRedirectDn(), null,
                                commLocation.getId(), // Context for redir employee
                                cdrData.getDateTimeOrigination())
                        .orElse(null);

                if (redirEmployee != null &&
                    redirEmployee.getCommunicationLocation() != null && // Ensure conceptual employee from range has commLoc
                    Objects.equals(redirEmployee.getCommunicationLocation().getId(), commLocation.getId())) {
                    cdrData.setEmployeeId(redirEmployee.getId());
                    cdrData.setEmployee(redirEmployee);
                    cdrData.setAssignmentCause(AssignmentCause.TRANSFER);
                    log.debug("Assigned call to redirecting employee: {}", redirEmployee.getExtension());
                }
            }


            // 3. Calculate Tariffs (if not an error call or no consumption)
            // PHP: if ($tiempo <= 0 && $tipotele_id > 0 && $tipotele_id != _TIPOTELE_ERRORES) $tipotele_id = _TIPOTELE_SINCONSUMO;
            if (cdrData.getDurationSeconds() <= appConfigService.getMinCallDurationForTariffing()) {
                if (cdrData.getTelephonyTypeId() != null &&
                    cdrData.getTelephonyTypeId() > 0 && // Must have a type
                    cdrData.getTelephonyTypeId() != TelephonyTypeEnum.ERRORS.getValue()) {
                    cdrData.setTelephonyTypeId(TelephonyTypeEnum.NO_CONSUMPTION.getValue());
                    cdrData.setTelephonyTypeName(telephonyTypeLookupService.getTelephonyTypeName(TelephonyTypeEnum.NO_CONSUMPTION.getValue()));
                    cdrData.setBilledAmount(BigDecimal.ZERO); // Ensure billed amount is zero
                }
            } else if (cdrData.getTelephonyTypeId() != null &&
                       cdrData.getTelephonyTypeId() != TelephonyTypeEnum.ERRORS.getValue() &&
                       cdrData.getTelephonyTypeId() != TelephonyTypeEnum.NO_CONSUMPTION.getValue()) {
                tariffCalculationService.calculateTariffs(cdrData, commLocation);
            }


            // 4. Finalize Transfer Info (PHP: $ext_transfer !== '' && ($ext_transfer === $extension || $ext_transfer === $tel_destino) && $info_transfer != IMDEX_TRANSFER_CONFERENCIA)
            if (cdrData.getTransferCause() != TransferCause.NONE &&
                cdrData.getLastRedirectDn() != null && !cdrData.getLastRedirectDn().isEmpty()) {
                cdrData.setEmployeeTransferExtension(cdrData.getLastRedirectDn());

                boolean transferToSelf = false;
                String extToCompareAgainst = (cdrData.getCallDirection() == CallDirection.INCOMING) ?
                                             cdrData.getFinalCalledPartyNumber() : // Our extension
                                             cdrData.getCallingPartyNumber();    // Our extension

                if (Objects.equals(cdrData.getLastRedirectDn(), extToCompareAgainst)) {
                    transferToSelf = true;
                } else {
                    // PHP also checks against the *other* party ($tel_destino for outgoing, $tel_origen for incoming)
                    String otherParty = (cdrData.getCallDirection() == CallDirection.INCOMING) ?
                                        cdrData.getCallingPartyNumber() : // External number
                                        cdrData.getFinalCalledPartyNumber(); // Dialed number
                    if (Objects.equals(cdrData.getLastRedirectDn(), otherParty)) {
                        transferToSelf = true;
                    }
                }

                if (transferToSelf && cdrData.getTransferCause() != TransferCause.CONFERENCE) {
                    log.debug("Clearing transfer info as it's a transfer to self/current party for non-conference.");
                    cdrData.setEmployeeTransferExtension(null);
                    cdrData.setTransferCause(TransferCause.NONE);
                }
            }


        } catch (Exception e) {
            log.error("Error during CDR enrichment for line: {}", cdrData.getRawCdrLine(), e);
            cdrData.setMarkedForQuarantine(true);
            cdrData.setQuarantineReason("Enrichment failed: " + e.getMessage());
            cdrData.setQuarantineStep("enrichCdr");
        }

        return cdrData;
    }
}