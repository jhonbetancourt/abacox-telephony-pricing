// File: com/infomedia/abacox/telephonypricing/cdr/CdrValidationService.java
package com.infomedia.abacox.telephonypricing.cdr;

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
     */
    public List<String> validateInitialCdrData(CdrData cdrData, Long originCountryId) {
        List<String> errors = new ArrayList<>();
        List<String> warnings = new ArrayList<>(); // PHP's $infoaviso

        // Date validation (PHP: _formatea_fecha, CAPTURAS_FECHAMIN, CAPTURAS_FECHAMAX)
        if (cdrData.getDateTimeOrigination() == null) {
            errors.add("Missing or invalid origination date/time (PHP: _ESPERABA_FECHA).");
        } else {
            LocalDateTime minDate = DateTimeUtil.stringToLocalDateTime(appConfigService.getMinAllowedCaptureDate() + " 00:00:00");
            if (minDate != null && cdrData.getDateTimeOrigination().isBefore(minDate)) {
                warnings.add("Origination date " + cdrData.getDateTimeOrigination() + " is before minimum allowed " + minDate + " (PHP: _FECHANO Min).");
            }
            int maxDaysFuture = appConfigService.getMaxAllowedCaptureDateDaysInFuture();
            if (maxDaysFuture > 0 && cdrData.getDateTimeOrigination().isAfter(LocalDateTime.now().plusDays(maxDaysFuture))) {
                warnings.add("Origination date " + cdrData.getDateTimeOrigination() + " is too far in the future (max " + maxDaysFuture + " days) (PHP: _FECHANO Max).");
            }
        }

        // Extension validation (PHP: ValidarTelefono on ext)
        // PHP: if (strpos($info_cdr['ext'], ' ') !== false) { $infoerror[] = _EXTENSION.' ('.$info_cdr['ext'].'): '._ESPERABA_NUMERO; }
        if (cdrData.getCallingPartyNumber() != null && cdrData.getCallingPartyNumber().contains(" ")) {
            errors.add("Calling party number '" + cdrData.getCallingPartyNumber() + "' contains spaces (PHP: _ESPERABA_NUMERO).");
        }
        // PHP's ValidarTelefono also checks for non (0-9#*+).
        // This is implicitly handled by later parsing/cleaning, but a direct check for other invalid chars could be added.
        // The PHP code only explicitly errors on space for this field.

        // Dialed number validation (PHP: ValidarTelefono on dial_number)
        // PHP: if (strpos($dial_number, ' ') !== false) { $infoerror[] = _TELEFONO.' ('.$dial_number.'): '._ESPERABA_NUMERO; }
        if (cdrData.getFinalCalledPartyNumber() != null && cdrData.getFinalCalledPartyNumber().contains(" ")) {
            errors.add("Final called party number '" + cdrData.getFinalCalledPartyNumber() + "' contains spaces (PHP: _ESPERABA_NUMERO).");
        }
        // PHP: if ($dial_number !== '') { $campo_ok = ValidarTelefono($dial_number); if (!$campo_ok) ... }
        // ValidarTelefono in PHP checks for non (0-9#*+).
        // If the number is not "ANONYMOUS" and contains characters other than 0-9, #, *, +, it's an error.
        if (cdrData.getFinalCalledPartyNumber() != null && !cdrData.getFinalCalledPartyNumber().isEmpty() &&
            !cdrData.getFinalCalledPartyNumber().equalsIgnoreCase("ANONYMOUS") &&
            !cdrData.getFinalCalledPartyNumber().matches("^[0-9#*+]+$")) {
            // This check is stricter than just spaces, aligning with PHP's ValidarTelefono.
            errors.add("Final called party number '" + cdrData.getFinalCalledPartyNumber() + "' contains invalid characters (PHP: _ESPERABA_NUMERO).");
        }


        // Duration validation (PHP: is_numeric, >=0, CAPTURAS_TIEMPOMAX, CAPTURAS_TIEMPOCERO)
        if (cdrData.getDurationSeconds() == null || cdrData.getDurationSeconds() < 0) {
            errors.add("Invalid call duration: " + cdrData.getDurationSeconds() + " (PHP: _ESPERABA_NUMEROPOS).");
        } else {
            if (cdrData.getDurationSeconds() < appConfigService.getMinCallDurationForTariffing()) {
                // PHP: $min_tiempo = defineParamCliente('CAPTURAS_TIEMPOCERO', $link);
                // PHP: if ($tiempo < $min_tiempo) { $infoaviso[] = _TIEMPONO.' '.$tiempo_txt.' ('.trim($infoerror_tmp).')'; }
                // This is treated as a warning leading to CRNPREV in PHP.
                warnings.add("Call duration " + cdrData.getDurationSeconds() + "s is less than minimum " + appConfigService.getMinCallDurationForTariffing() + "s (PHP: _TIEMPONO Min).");
            }
            if (appConfigService.getMaxCallDurationSeconds() > 0 && cdrData.getDurationSeconds() > appConfigService.getMaxCallDurationSeconds()) {
                warnings.add("Call duration " + cdrData.getDurationSeconds() + "s exceeds maximum allowed " + appConfigService.getMaxCallDurationSeconds() + "s (PHP: _TIEMPONO Max).");
            }
        }

        // Quarantine from parser (PHP: $info_cdr['cuarentena'] if string)
        if (cdrData.isMarkedForQuarantine() && cdrData.getQuarantineReason() != null &&
            cdrData.getQuarantineStep() != null && cdrData.getQuarantineStep().startsWith("evaluateFormat")) {
            // This is already set by the parser, just ensure it's captured.
            // If errors list is empty, this will become the primary reason.
            if (errors.isEmpty()) {
                errors.add("Marked for quarantine by parser: " + cdrData.getQuarantineReason());
            }
        }

        // Combine errors and warnings for quarantine decision
        if (!errors.isEmpty()) {
            cdrData.setMarkedForQuarantine(true);
            // If reason already set by parser, keep it, otherwise use validation errors.
            if (cdrData.getQuarantineReason() == null || !cdrData.getQuarantineReason().startsWith("Marked for quarantine by parser:")) {
                cdrData.setQuarantineReason(String.join("; ", errors));
            }
            if (cdrData.getQuarantineStep() == null || !cdrData.getQuarantineStep().startsWith("evaluateFormat")) {
                cdrData.setQuarantineStep("InitialValidation_Error");
            }
        } else if (!warnings.isEmpty()) {
            // PHP: $info_cdr['cuarentena']['CRNPREV'] = implode(', ', $infoaviso);
            // This implies warnings also lead to quarantine under CRNPREV type.
            cdrData.setMarkedForQuarantine(true);
            cdrData.setQuarantineReason(String.join("; ", warnings));
            cdrData.setQuarantineStep("InitialValidation_Warning"); // PHP uses CRNPREV for this
        }


        return errors; // Return only hard errors, warnings are handled by setting quarantine flags
    }
}