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
        log.debug("Validating initial CDR data: {}", cdrData.getRawCdrLine());
        List<String> errors = new ArrayList<>();
        List<String> warnings = new ArrayList<>();

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

        if (cdrData.getCallingPartyNumber() != null && cdrData.getCallingPartyNumber().contains(" ")) {
            errors.add("Calling party number '" + cdrData.getCallingPartyNumber() + "' contains spaces (PHP: _ESPERABA_NUMERO).");
        }

        if (cdrData.getFinalCalledPartyNumber() != null && cdrData.getFinalCalledPartyNumber().contains(" ")) {
            errors.add("Final called party number '" + cdrData.getFinalCalledPartyNumber() + "' contains spaces (PHP: _ESPERABA_NUMERO).");
        }
        if (cdrData.getFinalCalledPartyNumber() != null && !cdrData.getFinalCalledPartyNumber().isEmpty() &&
            !cdrData.getFinalCalledPartyNumber().equalsIgnoreCase("ANONYMOUS") &&
            !cdrData.getFinalCalledPartyNumber().matches("^[0-9#*+]+$")) {
            errors.add("Final called party number '" + cdrData.getFinalCalledPartyNumber() + "' contains invalid characters (PHP: _ESPERABA_NUMERO).");
        }

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

        if (cdrData.isMarkedForQuarantine() && cdrData.getQuarantineReason() != null &&
            cdrData.getQuarantineStep() != null && cdrData.getQuarantineStep().startsWith("evaluateFormat")) {
            if (errors.isEmpty()) {
                errors.add("Marked for quarantine by parser: " + cdrData.getQuarantineReason());
            }
        }

        if (!errors.isEmpty()) {
            cdrData.setMarkedForQuarantine(true);
            if (cdrData.getQuarantineReason() == null || !cdrData.getQuarantineReason().startsWith("Marked for quarantine by parser:")) {
                cdrData.setQuarantineReason(String.join("; ", errors));
            }
            if (cdrData.getQuarantineStep() == null || !cdrData.getQuarantineStep().startsWith("evaluateFormat")) {
                cdrData.setQuarantineStep("InitialValidation_Error");
            }
            log.warn("CDR validation errors: {}. Quarantine set.", errors);
        } else if (!warnings.isEmpty()) {
            cdrData.setMarkedForQuarantine(true);
            cdrData.setQuarantineReason(String.join("; ", warnings));
            cdrData.setQuarantineStep("InitialValidation_Warning");
            log.warn("CDR validation warnings (leading to quarantine): {}. Quarantine set.", warnings);
        } else {
            log.debug("CDR data passed initial validation.");
        }
        return errors;
    }
}