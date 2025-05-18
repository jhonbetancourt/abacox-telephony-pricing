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
    // private final ParameterLookupService parameterLookupService; // If CAPTURAS_FECHAMIN etc. are dynamic

    public CdrValidationService(CdrConfigService appConfigService) {
        this.appConfigService = appConfigService;
    }

    public List<String> validateInitialCdrData(CdrData cdrData, Long originCountryId) {
        List<String> errors = new ArrayList<>();

        // Date validation (PHP: _formatea_fecha, CAPTURAS_FECHAMIN, CAPTURAS_FECHAMAX)
        if (cdrData.getDateTimeOrigination() == null) {
            errors.add("Missing or invalid origination date/time.");
        } else {
            // Example: CAPTURAS_FECHAMIN = "2000-01-01"
            // LocalDateTime minDate = parameterLookupService.getDateTimeParameter("CAPTURAS_FECHAMIN", originCountryId, LocalDateTime.of(2000,1,1,0,0));
            LocalDateTime minDate = LocalDateTime.of(2000, 1, 1, 0, 0); // Hardcoded for example
            if (cdrData.getDateTimeOrigination().isBefore(minDate)) {
                errors.add("Origination date " + cdrData.getDateTimeOrigination() + " is before minimum allowed " + minDate);
            }
            // Example: CAPTURAS_FECHAMAX = 90 (days in future)
            // int maxDaysFuture = parameterLookupService.getIntParameter("CAPTURAS_FECHAMAX_DAYS", originCountryId, 90);
            int maxDaysFuture = 90; // Hardcoded
            if (cdrData.getDateTimeOrigination().isAfter(LocalDateTime.now().plusDays(maxDaysFuture))) {
                errors.add("Origination date " + cdrData.getDateTimeOrigination() + " is too far in the future (max " + maxDaysFuture + " days).");
            }
        }

        // Extension validation (PHP: ValidarTelefono on ext)
        // The PHP logic for ext being blank and then inverting is complex and tied to call typing.
        // Here, we just check if it looks like a valid phone number component if present.
        if (cdrData.getCallingPartyNumber() != null && !cdrData.getCallingPartyNumber().isEmpty() &&
                !CdrParserUtil.cleanPhoneNumber(cdrData.getCallingPartyNumber(), null, false).equals(cdrData.getCallingPartyNumber())) {
            // If cleaning changes it significantly (other than trim), it might have invalid chars
            // errors.add("Calling party number contains invalid characters: " + cdrData.getCallingPartyNumber());
            // PHP's ValidarTelefono allows #, *, +. The cleanPhoneNumber should handle this.
        }


        // Dialed number validation (PHP: ValidarTelefono on dial_number)
        if (cdrData.getFinalCalledPartyNumber() != null && !cdrData.getFinalCalledPartyNumber().isEmpty() &&
                !CdrParserUtil.cleanPhoneNumber(cdrData.getFinalCalledPartyNumber(), null, false).equals(cdrData.getFinalCalledPartyNumber())) {
            // errors.add("Final called party number contains invalid characters: " + cdrData.getFinalCalledPartyNumber());
        }
        if (cdrData.getFinalCalledPartyNumber() == null || cdrData.getFinalCalledPartyNumber().isEmpty()) {
            // errors.add("Final called party number is missing."); // Cisco CDRs can have this empty, then originalCalledPartyNumber is used.
        }


        // Duration validation (PHP: is_numeric, >=0, CAPTURAS_TIEMPOMAX, CAPTURAS_TIEMPOCERO)
        if (cdrData.getDurationSeconds() == null || cdrData.getDurationSeconds() < 0) {
            errors.add("Invalid call duration: " + cdrData.getDurationSeconds());
        } else {
            if (cdrData.getDurationSeconds() < appConfigService.getMinCallDurationForTariffing()) {
                // This might be a warning or lead to NO_CONSUMPTION type, not necessarily an error for quarantine.
                // PHP logic: if ($tiempo < $min_tiempo) $infoaviso[] = _TIEMPONO;
                log.debug("Call duration {}s is less than minimum {}s.", cdrData.getDurationSeconds(), appConfigService.getMinCallDurationForTariffing());
            }
            if (cdrData.getDurationSeconds() > appConfigService.getMaxCallDurationSeconds()) {
                errors.add("Call duration " + cdrData.getDurationSeconds() + "s exceeds maximum allowed " + appConfigService.getMaxCallDurationSeconds() + "s.");
            }
        }

        // Quarantine from parser (PHP: $info_cdr['cuarentena'])
        // This would be set by the ICdrTypeProcessor if it detects an unrecoverable parsing issue.
        if (cdrData.isMarkedForQuarantine() && cdrData.getQuarantineReason() != null) {
            errors.add("Marked for quarantine by parser: " + cdrData.getQuarantineReason());
        }

        return errors;
    }
}
