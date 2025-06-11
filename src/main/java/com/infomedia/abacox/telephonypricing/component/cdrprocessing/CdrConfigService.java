// File: com/infomedia/abacox/telephonypricing/component/cdrprocessing/CdrConfigService.java
package com.infomedia.abacox.telephonypricing.component.cdrprocessing;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import lombok.extern.log4j.Log4j2;
import org.springframework.stereotype.Service;

import java.time.ZoneId;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

@Service
@Log4j2
public class CdrConfigService {

    @PersistenceContext
    private EntityManager entityManager;

    // --- Constants based on PHP defines ---
    public static final int MAX_EXTENSION_LENGTH_FOR_INTERNAL_CHECK = 1000000;
    public static final int CDR_PROCESSING_BATCH_SIZE = 500;
    public static final long DEFAULT_OPERATOR_ID_FOR_INTERNAL = 0L;

    // --- Default values for configurable parameters ---
    private static final String DEFAULT_TARGET_DATABASE_TIMEZONE_ID = "America/Bogota";
    private static final boolean DEFAULT_SPECIAL_VALUE_TARIFFING_ENABLED = true;
    private static final int DEFAULT_MIN_CALL_DURATION_FOR_TARIFFING = 0;
    private static final int DEFAULT_MAX_CALL_DURATION_MINUTES = 2880;
    private static final String DEFAULT_MIN_ALLOWED_CAPTURE_DATE = "2000-01-01";
    private static final int DEFAULT_MAX_ALLOWED_CAPTURE_DATE_DAYS_IN_FUTURE = 90;
    private static final boolean DEFAULT_CREATE_EMPLOYEES_AUTOMATICALLY_FROM_RANGE = false;
    private static final Long DEFAULT_TELEPHONY_TYPE_FOR_UNRESOLVED_INTERNAL = TelephonyTypeEnum.NATIONAL_IP.getValue();
    private static final boolean DEFAULT_EXTENSIONS_GLOBAL = false;
    private static final boolean DEFAULT_AUTH_CODES_GLOBAL = false;
    private static final String DEFAULT_ASSUMED_TEXT = "(ASUMIDO)";
    private static final String DEFAULT_ORIGIN_TEXT = "(ORIGEN)";
    private static final String DEFAULT_PREFIX_TEXT = "(PREFIJO)";
    private static final String DEFAULT_EMPLOYEE_NAME_PREFIX_FROM_RANGE = "Funcionario"; // PHP: _FUNCIONARIO
    private static final String DEFAULT_NO_PARTITION_PLACEHOLDER = "NN-VALIDA"; // PHP: _NN_VALIDA


    private String getParameter(String paramName, String defaultValue) {
        switch (paramName) {
            case "TARGET_DATABASE_TIMEZONE": return DEFAULT_TARGET_DATABASE_TIMEZONE_ID;
            case "SPECIAL_VALUE_TARIFFING_ENABLED": return DEFAULT_SPECIAL_VALUE_TARIFFING_ENABLED ? "1" : "0";
            case "CAPTURAS_TIEMPOCERO": return String.valueOf(DEFAULT_MIN_CALL_DURATION_FOR_TARIFFING);
            case "CAPTURAS_TIEMPOMAX": return String.valueOf(DEFAULT_MAX_CALL_DURATION_MINUTES);
            case "CAPTURAS_FECHAMIN": return DEFAULT_MIN_ALLOWED_CAPTURE_DATE;
            case "CAPTURAS_FECHAMAX": return String.valueOf(DEFAULT_MAX_ALLOWED_CAPTURE_DATE_DAYS_IN_FUTURE);
            case "CAPTURAS_CREARFUN": return DEFAULT_CREATE_EMPLOYEES_AUTOMATICALLY_FROM_RANGE ? "1" : "0";
            case "CAPTURAS_INTERNADEF": return String.valueOf(DEFAULT_TELEPHONY_TYPE_FOR_UNRESOLVED_INTERNAL);
            case "EXT_GLOBALES": return DEFAULT_EXTENSIONS_GLOBAL ? "1" : "0";
            case "CLAVES_GLOBALES": return DEFAULT_AUTH_CODES_GLOBAL ? "1" : "0";
            // --- NEWLY ADDED GETTERS ---
            case "EMPLOYEE_NAME_PREFIX_FROM_RANGE": return DEFAULT_EMPLOYEE_NAME_PREFIX_FROM_RANGE;
            case "NO_PARTITION_PLACEHOLDER": return DEFAULT_NO_PARTITION_PLACEHOLDER;
            default:
                return defaultValue;
        }
    }

    private int getIntParameter(String paramName, int defaultValue) {
        try {
            return Integer.parseInt(getParameter(paramName, String.valueOf(defaultValue)));
        } catch (NumberFormatException e) {
            log.warn("NFE for int param '{}', using default {}", paramName, defaultValue);
            return defaultValue;
        }
    }

    private boolean getBooleanParameter(String paramName, boolean defaultValue) {
        String val = getParameter(paramName, defaultValue ? "1" : "0");
        return "1".equals(val) || "true".equalsIgnoreCase(val);
    }

    public ZoneId getTargetDatabaseZoneId() {
        String zoneIdStr = getParameter("TARGET_DATABASE_TIMEZONE", DEFAULT_TARGET_DATABASE_TIMEZONE_ID);
        try {
            return ZoneId.of(zoneIdStr);
        } catch (Exception e) {
            log.error("Invalid ZoneId configured: '{}'. Defaulting to UTC.", zoneIdStr, e);
            return ZoneId.of("UTC");
        }
    }

    public boolean isSpecialValueTariffingEnabled() {
        return getBooleanParameter("SPECIAL_VALUE_TARIFFING_ENABLED", DEFAULT_SPECIAL_VALUE_TARIFFING_ENABLED);
    }

    public int getMinCallDurationForTariffing() {
        int val = getIntParameter("CAPTURAS_TIEMPOCERO", DEFAULT_MIN_CALL_DURATION_FOR_TARIFFING);
        return Math.max(0, val);
    }

    public int getMaxCallDurationSeconds() {
        int maxMinutes = getIntParameter("CAPTURAS_TIEMPOMAX", DEFAULT_MAX_CALL_DURATION_MINUTES);
        if (maxMinutes < 0) maxMinutes = DEFAULT_MAX_CALL_DURATION_MINUTES;
        return maxMinutes * 60;
    }

    public String getMinAllowedCaptureDate() {
        return getParameter("CAPTURAS_FECHAMIN", DEFAULT_MIN_ALLOWED_CAPTURE_DATE);
    }

    public int getMaxAllowedCaptureDateDaysInFuture() {
        int val = getIntParameter("CAPTURAS_FECHAMAX", DEFAULT_MAX_ALLOWED_CAPTURE_DATE_DAYS_IN_FUTURE);
        return Math.max(0, val);
    }

    public boolean createEmployeesAutomaticallyFromRange() {
        return getBooleanParameter("CAPTURAS_CREARFUN", DEFAULT_CREATE_EMPLOYEES_AUTOMATICALLY_FROM_RANGE);
    }

    public Long getDefaultTelephonyTypeForUnresolvedInternalCalls() {
        String valStr = getParameter("CAPTURAS_INTERNADEF", String.valueOf(DEFAULT_TELEPHONY_TYPE_FOR_UNRESOLVED_INTERNAL));
        try {
            long val = Long.parseLong(valStr);
            return val > 0 ? val : null;
        } catch (NumberFormatException e) {
            log.warn("NFE for CAPTURAS_INTERNADEF, using default {}", DEFAULT_TELEPHONY_TYPE_FOR_UNRESOLVED_INTERNAL);
            return DEFAULT_TELEPHONY_TYPE_FOR_UNRESOLVED_INTERNAL;
        }
    }

    public boolean areExtensionsGlobal() {
        return getBooleanParameter("EXT_GLOBALES", DEFAULT_EXTENSIONS_GLOBAL);
    }

    public boolean areAuthCodesGlobal() {
        return getBooleanParameter("CLAVES_GLOBALES", DEFAULT_AUTH_CODES_GLOBAL);
    }

    public String getAssumedText() { return DEFAULT_ASSUMED_TEXT; }
    public String getOriginText() { return DEFAULT_ORIGIN_TEXT; }
    public String getPrefixText() { return DEFAULT_PREFIX_TEXT; }

    /**
     * PHP equivalent: _FUNCIONARIO
     * @return The default prefix for auto-created employee names from ranges.
     */
    public String getEmployeeNamePrefixFromRange() {
        return getParameter("EMPLOYEE_NAME_PREFIX_FROM_RANGE", DEFAULT_EMPLOYEE_NAME_PREFIX_FROM_RANGE);
    }

    /**
     * PHP equivalent: _NN_VALIDA
     * @return The placeholder string for an empty but valid partition.
     */
    public String getNoPartitionPlaceholder() {
        return getParameter("NO_PARTITION_PLACEHOLDER", DEFAULT_NO_PARTITION_PLACEHOLDER);
    }

    public Long getDefaultInternalCallTypeId() {
        return TelephonyTypeEnum.INTERNAL_SIMPLE.getValue();
    }
}