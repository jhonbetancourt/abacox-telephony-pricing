// File: com/infomedia/abacox/telephonypricing/cdr/CdrConfigService.java
package com.infomedia.abacox.telephonypricing.cdr;

import jakarta.persistence.EntityManager;
import jakarta.persistence.NoResultException;
import jakarta.persistence.PersistenceContext;
import lombok.extern.log4j.Log4j2;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.ZoneId;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

@Service
@Log4j2
public class CdrConfigService {

    @PersistenceContext
    private EntityManager entityManager; // Kept for potential future use if config moves to DB

    // --- Constants based on PHP defines ---
    public static final int ACUMTOTAL_MAX_EXTENSION_LENGTH_FOR_INTERNAL_CHECK = 1000000; // PHP: _ACUMTOTAL_MAXEXT
    public static final int CDR_PROCESSING_BATCH_SIZE = 500; // PHP: _IMDEX_MAXLINEAS
    public static final long DEFAULT_OPERATOR_ID_FOR_INTERNAL = 0L; // Implied default if operador_interno fails

    // --- Default values for configurable parameters ---
    private static final String DEFAULT_TARGET_DATABASE_TIMEZONE_ID = "America/Bogota";
    private static final List<String> DEFAULT_IGNORED_AUTH_CODE_DESCRIPTIONS =
            Collections.unmodifiableList(Arrays.asList("Invalid Authorization Code", "Invalid Authorization Level")); // PHP: $_FUN_IGNORAR_CLAVE
    private static final boolean DEFAULT_SPECIAL_VALUE_TARIFFING_ENABLED = true; // PHP: $usar_valorespecial (derived)
    private static final int DEFAULT_MIN_CALL_DURATION_FOR_TARIFFING = 0; // seconds
    private static final int DEFAULT_MAX_CALL_DURATION_MINUTES = 2880; // 48 hours
    private static final String DEFAULT_MIN_ALLOWED_CAPTURE_DATE = "2000-01-01";
    private static final int DEFAULT_MAX_ALLOWED_CAPTURE_DATE_DAYS_IN_FUTURE = 90;
    private static final boolean DEFAULT_CREATE_EMPLOYEES_AUTOMATICALLY_FROM_RANGE = false;
    private static final Long DEFAULT_TELEPHONY_TYPE_FOR_UNRESOLVED_INTERNAL = TelephonyTypeEnum.NATIONAL_IP.getValue();
    private static final boolean DEFAULT_EXTENSIONS_GLOBAL = false;
    private static final boolean DEFAULT_AUTH_CODES_GLOBAL = false;
    private static final String DEFAULT_ASSUMED_TEXT = "(ASUMIDO)"; // PHP: _ASUMIDO
    private static final String DEFAULT_ORIGIN_TEXT = "(ORIGEN)";   // PHP: _ORIGEN
    private static final String DEFAULT_PREFIX_TEXT = "(PREFIJO)";  // PHP: _PREFIJO


    // In a real system, these would query a configuration table or use Spring @Value
    // For now, they return hardcoded defaults or values based on PHP analysis.
    // The plantTypeId and clientBdName parameters are for potential future per-client/plant overrides.
    private String getParameter(String paramName, String defaultValue) {
        // log.trace("Fetching parameter: {} (PlantType: {}, Client: {})", paramName, plantTypeId, clientBdName);
        // This method would contain the logic to fetch from DB or properties.
        // For this exercise, we directly use the defaults defined above.
        switch (paramName) {
            case "TARGET_DATABASE_TIMEZONE": return DEFAULT_TARGET_DATABASE_TIMEZONE_ID;
            case "SPECIAL_VALUE_TARIFFING_ENABLED": return DEFAULT_SPECIAL_VALUE_TARIFFING_ENABLED ? "1" : "0";
            case "CAPTURAS_TIEMPOCERO": return String.valueOf(DEFAULT_MIN_CALL_DURATION_FOR_TARIFFING);
            case "CAPTURAS_TIEMPOMAX": return String.valueOf(DEFAULT_MAX_CALL_DURATION_MINUTES);
            case "CAPTURAS_FECHAMIN": return DEFAULT_MIN_ALLOWED_CAPTURE_DATE;
            case "CAPTURAS_FECHAMAX": return String.valueOf(DEFAULT_MAX_ALLOWED_CAPTURE_DATE_DAYS_IN_FUTURE);
            case "CAPTURAS_CREARFUN": return DEFAULT_CREATE_EMPLOYEES_AUTOMATICALLY_FROM_RANGE ? "1" : "0";
            case "CAPTURAS_INTERNADEF": return String.valueOf(DEFAULT_TELEPHONY_TYPE_FOR_UNRESOLVED_INTERNAL);
            case "EXT_GLOBALES":
                return DEFAULT_EXTENSIONS_GLOBAL ? "1" : "0";
            case "CLAVES_GLOBALES":
                 return DEFAULT_AUTH_CODES_GLOBAL ? "1" : "0";
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

    /**
     * PHP equivalent: N/A (New for Java, based on PHP's UTC storage and need for consistent display/storage)
     */
    public ZoneId getTargetDatabaseZoneId() {
        String zoneIdStr = getParameter("TARGET_DATABASE_TIMEZONE", DEFAULT_TARGET_DATABASE_TIMEZONE_ID);
        try {
            return ZoneId.of(zoneIdStr);
        } catch (Exception e) {
            log.error("Invalid ZoneId configured: '{}'. Defaulting to UTC.", zoneIdStr, e);
            return ZoneId.of("UTC");
        }
    }

    /**
     * PHP equivalent: $_FUN_IGNORAR_CLAVE
     */
    public List<String> getIgnoredAuthCodeDescriptions() {
        return DEFAULT_IGNORED_AUTH_CODE_DESCRIPTIONS;
    }

    /**
     * PHP equivalent: $usar_valorespecial (derived from `ValidarUso($link, 'valorespecial')`)
     */
    public boolean isSpecialValueTariffingEnabled() {
        return getBooleanParameter("SPECIAL_VALUE_TARIFFING_ENABLED", DEFAULT_SPECIAL_VALUE_TARIFFING_ENABLED);
    }

    /**
     * PHP equivalent: defineParamCliente('CAPTURAS_TIEMPOCERO', $link)
     */
    public int getMinCallDurationForTariffing() {
        int val = getIntParameter("CAPTURAS_TIEMPOCERO", DEFAULT_MIN_CALL_DURATION_FOR_TARIFFING);
        return Math.max(0, val);
    }

    /**
     * PHP equivalent: defineParamCliente('CAPTURAS_TIEMPOMAX', $link)
     */
    public int getMaxCallDurationSeconds() {
        int maxMinutes = getIntParameter("CAPTURAS_TIEMPOMAX", DEFAULT_MAX_CALL_DURATION_MINUTES);
        if (maxMinutes < 0) maxMinutes = DEFAULT_MAX_CALL_DURATION_MINUTES;
        return maxMinutes * 60;
    }

    /**
     * PHP equivalent: defineParamCliente('CAPTURAS_FECHAMIN', $link)
     */
    public String getMinAllowedCaptureDate() {
        return getParameter("CAPTURAS_FECHAMIN", DEFAULT_MIN_ALLOWED_CAPTURE_DATE);
    }

    /**
     * PHP equivalent: defineParamCliente('CAPTURAS_FECHAMAX', $link)
     */
    public int getMaxAllowedCaptureDateDaysInFuture() {
        int val = getIntParameter("CAPTURAS_FECHAMAX", DEFAULT_MAX_ALLOWED_CAPTURE_DATE_DAYS_IN_FUTURE);
        return Math.max(0, val);
    }

    /**
     * PHP equivalent: defineParamCliente('CAPTURAS_CREARFUN', $link)
     * Also related to PHP's `ValidarLicFun()` if licensing was tied to this.
     */
    public boolean createEmployeesAutomaticallyFromRange() {
        return getBooleanParameter("CAPTURAS_CREARFUN", DEFAULT_CREATE_EMPLOYEES_AUTOMATICALLY_FROM_RANGE);
    }

    /**
     * PHP equivalent: defineParamCliente('CAPTURAS_INTERNADEF', $link)
     */
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

    /**
     * PHP equivalent: ObtenerGlobales($link, 'ext_globales')
     */
    public boolean areExtensionsGlobal() {
        return getBooleanParameter("EXT_GLOBALES", DEFAULT_EXTENSIONS_GLOBAL);
    }

    /**
     * PHP equivalent: ObtenerGlobales($link, 'claves_globales')
     */
    public boolean areAuthCodesGlobal() {
        return getBooleanParameter("CLAVES_GLOBALES", DEFAULT_AUTH_CODES_GLOBAL);
    }

    /**
     * PHP equivalent: _ASUMIDO
     */
    public String getAssumedText() { return DEFAULT_ASSUMED_TEXT; }
    /**
     * PHP equivalent: _ORIGEN
     */
    public String getOriginText() { return DEFAULT_ORIGIN_TEXT; }
    /**
     * PHP equivalent: _PREFIJO
     */
    public String getPrefixText() { return DEFAULT_PREFIX_TEXT; }

    /**
     * PHP equivalent: Implicit default for internal calls if not otherwise determined.
     */
    public Long getDefaultInternalCallTypeId() {
        return TelephonyTypeEnum.INTERNAL_SIMPLE.getValue();
    }
}