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
                            commLocation.getId(), // Current commLocation for context
                            cdrData.getDateTimeOrigination())
                    .orElse(null);

            if (foundEmployee != null) {
                cdrData.setEmployeeId(foundEmployee.getId());
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
                } else if (authCodeProvided && !isIgnoredAuthCodeType) { // Auth code provided, but didn't match and is not an "ignored type"
                    cdrData.setAssignmentCause(AssignmentCause.IGNORED_AUTH_CODE); // PHP: IMDEX_IGNORA_CLAVE
                    // PHP: if ($esclave && $retornar['id'] <= 0); // $ignoraclave
                    // If CAPTURAS_NOCLAVES=0 (PHP default), and auth code failed (not an "ignored" one),
                    // it might not assign by extension. Here, we assume if employee was found by extension, it's EXTENSION.
                    // If employee was found by extension (e.g. auth code was null for employee but ext matched)
                    if (foundEmployee.getExtension() != null && foundEmployee.getExtension().equals(searchExtForEmployee)) {
                         // This case is tricky: auth code was provided, failed, but ext matched.
                         // PHP's logic for $ignoraclave and CAPTURAS_NOCLAVES is complex.
                         // For now, if auth code failed and it wasn't an "ignored" type, it's IGNORED_AUTH_CODE.
                         // If the employee was *only* found by extension (no auth code provided or auth code was ignored type), then it's EXTENSION.
                    }
                } else { // No auth code provided, or auth code was an "ignored type" (so we assign by extension)
                    cdrData.setAssignmentCause(AssignmentCause.EXTENSION);
                }

                // PHP: if ($funid <= 0 && $tiempo > 0) { $funid = ActualizarFuncionarios(...); }
                // This is for auto-creating employees from ranges, handled by findEmployeeByExtensionRange if primary lookup fails.
                // If foundEmployee came from a range, it's conceptually a new one.
                if (foundEmployee.getId() == null && cdrData.getDurationSeconds() > 0 && appConfigService.createEmployeesAutomaticallyFromRange()) {
                    // This employee is conceptual from a range.
                    cdrData.setAssignmentCause(AssignmentCause.RANGES);
                }

            } else { // No employee found by primary means
                 // PHP: if ($funid <= 0) { $funid = 0; if ($info_asigna == IMDEX_ASIGNA_EXT && ExtensionEncontrada($arreglo_fun)) $info_asigna = IMDEX_ASIGNA_RANGOS; }
                 // If no employee found by primary means, and it was an attempt by extension, it might be RANGES if conceptual employee was returned by range logic
                 // This is already handled by findEmployeeByExtensionOrAuthCode calling findEmployeeByExtensionRange.
                 // If still null here, it's NOT_ASSIGNED.
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
            if (cdrData.getEmployeeId() == null && !cdrData.isInternalCall() &&
                cdrData.getLastRedirectDn() != null && !cdrData.getLastRedirectDn().isEmpty()) {
                // PHP: $arreglo_funredir = ObtenerFuncionario_Arreglo($link, $ext_redir, '', 0, $info_cdr['date'], $funext, $COMUBICACION_ID, 0);
                // Type 0 for redir means search globally if ext_globales, or current plant if not.
                Employee redirEmployee = employeeLookupService.findEmployeeByExtensionOrAuthCode(
                                cdrData.getLastRedirectDn(), null,
                                commLocation.getId(), // Context for redir employee
                                cdrData.getDateTimeOrigination())
                        .orElse(null);
                // PHP: if (ExtensionEncontrada($arreglo_funredir)) { $arreglo_funredir['info_asigna'] = IMDEX_ASIGNA_TRANS; $info_cdr['funcionario_redir'] = $arreglo_funredir; }
                // PHP also checks: $info['funcionario_redir']['comid'] == $COMUBICACION_ID
                if (redirEmployee != null && Objects.equals(redirEmployee.getCommunicationLocationId(), commLocation.getId())) {
                    cdrData.setEmployeeId(redirEmployee.getId());
                    cdrData.setEmployee(redirEmployee);
                    cdrData.setAssignmentCause(AssignmentCause.TRANSFER);
                    log.debug("Assigned call to redirecting employee: {}", redirEmployee.getExtension());
                }
            }


            // 3. Calculate Tariffs (if not an error call or no consumption)
            // PHP: if ($tiempo <= 0 && $tipotele_id > 0 && $tipotele_id != _TIPOTELE_ERRORES) $tipotele_id = _TIPOTELE_SINCONSUMO;
            if (cdrData.getDurationSeconds() <= appConfigService.getMinCallDurationForTariffing()) { // PHP uses <= 0
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

                // Clean transfer if it's to self (PHP logic)
                boolean transferToSelf = false;
                // PHP: $ext_transfer === $extension (calling party) OR $ext_transfer === $tel_destino (final called party)
                if (Objects.equals(cdrData.getLastRedirectDn(), cdrData.getCallingPartyNumber())) {
                    transferToSelf = true;
                } else if (Objects.equals(cdrData.getLastRedirectDn(), cdrData.getFinalCalledPartyNumber())) {
                    transferToSelf = true;
                }
                // Note: PHP's $tel_destino can be $info['dial_number'] or $info['ext'] depending on incoming/outgoing.
                // Here, finalCalledPartyNumber is the callee for outgoing, and our extension (callee) for incoming.
                // CallingPartyNumber is caller for outgoing, and external number for incoming.

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