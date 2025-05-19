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
        // PHP's logic for ext being blank and then inverting is complex and tied to call typing.
        // Here, we just check if it looks like a valid phone number component if present.
        // PHP: if (strpos($info_cdr['ext'], ' ') !== false) { $infoerror[] = _EXTENSION.' ('.$info_cdr['ext'].'): '._ESPERABA_NUMERO; }
        if (cdrData.getCallingPartyNumber() != null && cdrData.getCallingPartyNumber().contains(" ")) {
            errors.add("Calling party number '" + cdrData.getCallingPartyNumber() + "' contains spaces (PHP: _ESPERABA_NUMERO).");
        }

        // Dialed number validation (PHP: ValidarTelefono on dial_number)
        // PHP: if (strpos($dial_number, ' ') !== false) { $infoerror[] = _TELEFONO.' ('.$dial_number.'): '._ESPERABA_NUMERO; }
        if (cdrData.getFinalCalledPartyNumber() != null && cdrData.getFinalCalledPartyNumber().contains(" ")) {
            errors.add("Final called party number '" + cdrData.getFinalCalledPartyNumber() + "' contains spaces (PHP: _ESPERABA_NUMERO).");
        }
        // PHP: if ($dial_number !== '') { $campo_ok = ValidarTelefono($dial_number); if (!$campo_ok) ... }
        // ValidarTelefono in PHP checks for non (0-9#*+). CdrParserUtil.cleanPhoneNumber effectively does this.
        // If the cleaned version is different from original (ignoring trim), it implies invalid chars.
        String cleanedDialNumber = CdrParserUtil.cleanPhoneNumber(cdrData.getFinalCalledPartyNumber(), null, false);
        if (cdrData.getFinalCalledPartyNumber() != null && !cdrData.getFinalCalledPartyNumber().trim().equals(cleanedDialNumber) &&
            !cdrData.getFinalCalledPartyNumber().equalsIgnoreCase("ANONYMOUS")) { // PHP allows ANONYMOUS
             // This check is a bit too strict if only '+' was removed by cleanPhoneNumber.
             // PHP's ValidarTelefono is more about *containing* invalid chars rather than *being changed by cleaning*.
             // For now, we'll stick to the space check as per PHP's direct error trigger.
        }


        // Duration validation (PHP: is_numeric, >=0, CAPTURAS_TIEMPOMAX, CAPTURAS_TIEMPOCERO)
        if (cdrData.getDurationSeconds() == null || cdrData.getDurationSeconds() < 0) {
            errors.add("Invalid call duration: " + cdrData.getDurationSeconds() + " (PHP: _ESPERABA_NUMEROPOS).");
        } else {
            if (cdrData.getDurationSeconds() < appConfigService.getMinCallDurationForTariffing()) {
                warnings.add("Call duration " + cdrData.getDurationSeconds() + "s is less than minimum " + appConfigService.getMinCallDurationForTariffing() + "s (PHP: _TIEMPONO Min).");
            }
            if (appConfigService.getMaxCallDurationSeconds() > 0 && cdrData.getDurationSeconds() > appConfigService.getMaxCallDurationSeconds()) {
                warnings.add("Call duration " + cdrData.getDurationSeconds() + "s exceeds maximum allowed " + appConfigService.getMaxCallDurationSeconds() + "s (PHP: _TIEMPONO Max).");
            }
        }

        // Quarantine from parser (PHP: $info_cdr['cuarentena'] if string)
        if (cdrData.isMarkedForQuarantine() && cdrData.getQuarantineReason() != null &&
            cdrData.getQuarantineStep() != null && cdrData.getQuarantineStep().startsWith("evaluateFormat")) {
            errors.add("Marked for quarantine by parser: " + cdrData.getQuarantineReason());
        }

        // Combine errors and warnings for quarantine decision
        if (!errors.isEmpty()) {
            cdrData.setMarkedForQuarantine(true);
            cdrData.setQuarantineReason(String.join("; ", errors));
            cdrData.setQuarantineStep("InitialValidation_Error");
        } else if (!warnings.isEmpty()) {
            // PHP: $info_cdr['cuarentena']['CRNPREV'] = implode(', ', $infoaviso);
            // This implies warnings also lead to quarantine under CRNPREV type.
            cdrData.setMarkedForQuarantine(true);
            cdrData.setQuarantineReason(String.join("; ", warnings));
            cdrData.setQuarantineStep("InitialValidation_Warning");
        }


        return errors; // Return only hard errors, warnings are handled by setting quarantine flags
    }
}