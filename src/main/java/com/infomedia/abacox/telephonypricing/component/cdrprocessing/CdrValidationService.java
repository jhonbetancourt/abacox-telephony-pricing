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
        List<String> warningMessages = new ArrayList<>();

        // PHP: if (trim($info_cdr['ext']) == '') { InvertirLlamada($info_cdr); ... }
        if ((cdrData.getCallingPartyNumber() == null || cdrData.getCallingPartyNumber().isEmpty()) &&
            (cdrData.getFinalCalledPartyNumber() != null && !cdrData.getFinalCalledPartyNumber().isEmpty())) {
            log.warn("CallingPartyNumber is blank but FinalCalledPartyNumber is not. Performing full swap and treating as INCOMING. CDR: {}", cdrData.getCtlHash());
            CdrUtil.swapFull(cdrData, true); // Full swap including trunks
            cdrData.setCallDirection(CallDirection.INCOMING);
            cdrData.setInternalCall(false); // Ensure it's not treated as internal after this swap
        }

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

        if (cdrData.getFinalCalledPartyNumber() != null && !cdrData.getFinalCalledPartyNumber().isEmpty() &&
            !cdrData.getFinalCalledPartyNumber().equalsIgnoreCase("ANONYMOUS") &&
            !cdrData.getFinalCalledPartyNumber().matches("^[0-9#*+]+$")) {
            errorMessages.add("Final called party number '" + cdrData.getFinalCalledPartyNumber() + "' contains invalid characters (PHP: _ESPERABA_NUMERO).");
        }

        if (cdrData.getDurationSeconds() == null || cdrData.getDurationSeconds() < 0) {
            errorMessages.add("Invalid call duration: " + cdrData.getDurationSeconds() + " (PHP: _ESPERABA_NUMEROPOS).");
        } else {
            if (cdrData.getDurationSeconds() < appConfigService.getMinCallDurationForTariffing()) {
                if (appConfigService.getMinCallDurationForTariffing() > 0) {
                     warningMessages.add("Call duration " + cdrData.getDurationSeconds() + "s is less than minimum " + appConfigService.getMinCallDurationForTariffing() + "s (PHP: _TIEMPONO Min).");
                }
            }
            if (appConfigService.getMaxCallDurationSeconds() > 0 && cdrData.getDurationSeconds() > appConfigService.getMaxCallDurationSeconds()) {
                warningMessages.add("Call duration " + cdrData.getDurationSeconds() + "s exceeds maximum allowed " + appConfigService.getMaxCallDurationSeconds() + "s (PHP: _TIEMPONO Max).");
            }
        }

        if (cdrData.isMarkedForQuarantine() && cdrData.getQuarantineReason() != null &&
            cdrData.getQuarantineStep() != null && cdrData.getQuarantineStep().startsWith("evaluateFormat")) {
            if (errorMessages.isEmpty()) {
                errorMessages.add("Marked for quarantine by parser: " + cdrData.getQuarantineReason());
            }
        }

        if (!errorMessages.isEmpty()) {
            cdrData.setMarkedForQuarantine(true);
            if (cdrData.getQuarantineReason() == null || !cdrData.getQuarantineReason().startsWith("Marked for quarantine by parser:")) {
                cdrData.setQuarantineReason(String.join("; ", errorMessages));
            }
            if (cdrData.getQuarantineStep() == null || !cdrData.getQuarantineStep().startsWith("evaluateFormat")) {
                cdrData.setQuarantineStep(QuarantineErrorType.INITIAL_VALIDATION_ERROR.name());
            }
            log.warn("CDR validation errors: {}. Quarantine set.", errorMessages);
            return false;
        } else if (!warningMessages.isEmpty()) {
            cdrData.setMarkedForQuarantine(true);
            cdrData.setQuarantineReason(String.join("; ", warningMessages));
            cdrData.setQuarantineStep(QuarantineErrorType.INITIAL_VALIDATION_WARNING.name());
            log.warn("CDR validation warnings (leading to quarantine): {}. Quarantine set.", warningMessages);
            return true;
        }

        log.debug("CDR data passed initial validation without errors or quarantinable warnings.");
        return true;
    }
}