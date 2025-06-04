// File: com/infomedia/abacox/telephonypricing/cdr/CdrValidationService.java
package com.infomedia.abacox.telephonypricing.component.cdrprocessing;

import lombok.extern.log4j.Log4j2;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Service
@Log4j2
public class CdrValidationService {

    private final CdrConfigService appConfigService;

    public CdrValidationService(CdrConfigService appConfigService) {
        this.appConfigService = appConfigService;
    }

    /**
     * PHP equivalent: ValidarCampos_CDR
     * This method now directly modifies cdrData.isMarkedForQuarantine and sets reason/step.
     * It returns true if CDR is valid, false if errors occurred.
     */
    public boolean validateInitialCdrData(CdrData cdrData) {
        log.debug("Validating initial CDR data: {}", cdrData.getCtlHash());
        List<String> errorMessages = new ArrayList<>();
        List<String> warningMessages = new ArrayList<>(); // PHP's CRNPREV type

        if (cdrData.getDateTimeOrigination() == null) {
            errorMessages.add("Missing or invalid origination date/time (PHP: _ESPERABA_FECHA).");
        } else {
            LocalDateTime minDate = DateTimeUtil.stringToLocalDateTime(appConfigService.getMinAllowedCaptureDate() + " 00:00:00");
            if (minDate != null && cdrData.getDateTimeOrigination().isBefore(minDate)) {
                warningMessages.add("Origination date " + cdrData.getDateTimeOrigination() + " is before minimum allowed " + minDate + " (PHP: _FECHANO Min).");
            }
            int maxDaysFuture = appConfigService.getMaxAllowedCaptureDateDaysInFuture();
            if (maxDaysFuture > 0 && cdrData.getDateTimeOrigination().isAfter(LocalDateTime.now().plusDays(maxDaysFuture))) {
                warningMessages.add("Origination date " + cdrData.getDateTimeOrigination() + " is too far in the future (max " + maxDaysFuture + " days) (PHP: _FECHANO Max).");
            }
        }

        if (cdrData.getCallingPartyNumber() != null && cdrData.getCallingPartyNumber().contains(" ")) {
            errorMessages.add("Calling party number '" + cdrData.getCallingPartyNumber() + "' contains spaces (PHP: _ESPERABA_NUMERO).");
        }

        if (cdrData.getFinalCalledPartyNumber() != null && cdrData.getFinalCalledPartyNumber().contains(" ")) {
            errorMessages.add("Final called party number '" + cdrData.getFinalCalledPartyNumber() + "' contains spaces (PHP: _ESPERABA_NUMERO).");
        }
        // PHP: ValidarTelefono checks for non-numeric, #, *, +
        if (cdrData.getFinalCalledPartyNumber() != null && !cdrData.getFinalCalledPartyNumber().isEmpty() &&
            !cdrData.getFinalCalledPartyNumber().equalsIgnoreCase("ANONYMOUS") &&
            !cdrData.getFinalCalledPartyNumber().matches("^[0-9#*+]+$")) {
            // PHP: Reporta error solo si hay espacios, de lo contrario lo reporta asi sea como "no relacionado"
            // The PHP logic is a bit nuanced here. If it contains spaces, it's an error.
            // If it contains other invalid chars but no spaces, it might still proceed.
            // For Java, let's be stricter: if it's not empty, not ANONYMOUS, and contains invalid chars, it's an error.
            errorMessages.add("Final called party number '" + cdrData.getFinalCalledPartyNumber() + "' contains invalid characters (PHP: _ESPERABA_NUMERO).");
        }


        if (cdrData.getDurationSeconds() == null || cdrData.getDurationSeconds() < 0) {
            errorMessages.add("Invalid call duration: " + cdrData.getDurationSeconds() + " (PHP: _ESPERABA_NUMEROPOS).");
        } else {
            if (cdrData.getDurationSeconds() < appConfigService.getMinCallDurationForTariffing()) {
                // PHP: This was a warning (CRNPREV) if $min_tiempo > 0
                if (appConfigService.getMinCallDurationForTariffing() > 0) {
                     warningMessages.add("Call duration " + cdrData.getDurationSeconds() + "s is less than minimum " + appConfigService.getMinCallDurationForTariffing() + "s (PHP: _TIEMPONO Min).");
                }
            }
            if (appConfigService.getMaxCallDurationSeconds() > 0 && cdrData.getDurationSeconds() > appConfigService.getMaxCallDurationSeconds()) {
                warningMessages.add("Call duration " + cdrData.getDurationSeconds() + "s exceeds maximum allowed " + appConfigService.getMaxCallDurationSeconds() + "s (PHP: _TIEMPONO Max).");
            }
        }

        // PHP: if (isset($info_cdr['cuarentena']) && is_string($info_cdr['cuarentena']) && $info_cdr['cuarentena'] != '')
        // This is handled if the parser itself sets quarantine reason.
        if (cdrData.isMarkedForQuarantine() && cdrData.getQuarantineReason() != null &&
            cdrData.getQuarantineStep() != null && cdrData.getQuarantineStep().startsWith("evaluateFormat")) {
            // If parser already marked it, ensure its reason is included if no other errors found yet.
            if (errorMessages.isEmpty()) {
                errorMessages.add("Marked for quarantine by parser: " + cdrData.getQuarantineReason());
            }
        }


        if (!errorMessages.isEmpty()) {
            cdrData.setMarkedForQuarantine(true);
            // If parser already set a reason, keep it, otherwise use combined errors.
            if (cdrData.getQuarantineReason() == null || !cdrData.getQuarantineReason().startsWith("Marked for quarantine by parser:")) {
                cdrData.setQuarantineReason(String.join("; ", errorMessages));
            }
            if (cdrData.getQuarantineStep() == null || !cdrData.getQuarantineStep().startsWith("evaluateFormat")) {
                cdrData.setQuarantineStep(QuarantineErrorType.INITIAL_VALIDATION_ERROR.name());
            }
            log.warn("CDR validation errors: {}. Quarantine set.", errorMessages);
            return false; // Errors found
        } else if (!warningMessages.isEmpty()) {
            cdrData.setMarkedForQuarantine(true);
            cdrData.setQuarantineReason(String.join("; ", warningMessages));
            cdrData.setQuarantineStep(QuarantineErrorType.INITIAL_VALIDATION_WARNING.name());
            log.warn("CDR validation warnings (leading to quarantine): {}. Quarantine set.", warningMessages);
            return true; // No hard errors, but warnings lead to quarantine
        }

        log.debug("CDR data passed initial validation without errors or quarantinable warnings.");
        return true; // Valid
    }
}