// File: com/infomedia/abacox/telephonypricing/cdr/CdrConfigService.java
package com.infomedia.abacox.telephonypricing.cdr;

import jakarta.persistence.EntityManager;
import jakarta.persistence.NoResultException;
import jakarta.persistence.PersistenceContext;
import lombok.extern.log4j.Log4j2;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

@Service
@Log4j2
public class CdrConfigService {

    @PersistenceContext
    private EntityManager entityManager;

    public static final int ACUMTOTAL_MAX_EXTENSION_LENGTH_FOR_INTERNAL_CHECK = 1000000; // PHP: _ACUMTOTAL_MAXEXT
    public static final int CDR_PROCESSING_BATCH_SIZE = 500; // PHP: _IMDEX_MAXLINEAS
    public static final String NN_VALIDA_PARTITION = "NN-VALIDA";
    public static final long DEFAULT_OPERATOR_ID_FOR_INTERNAL = 0L; // Default if not found by operador_interno

    private static final List<String> IGNORED_AUTH_CODE_DESCRIPTIONS =
            Collections.unmodifiableList(Arrays.asList("Invalid Authorization Code", "Invalid Authorization Level"));

    // Simulates fetching a parameter from a database table like 'parametros_cliente' or 'global_parameters'
    // In a real system, this would query the database.
    private String getParameter(String paramName, String defaultValue, Long plantTypeId, String clientBdName) {
        // log.debug("Fetching parameter: {} (PlantType: {}, Client: {})", paramName, plantTypeId, clientBdName);
        // Example: SELECT valor FROM client_parameters WHERE client_db = :clientBdName AND param_key = :paramName
        // For now, return hardcoded defaults based on PHP analysis
        if ("CAPTURAS_TIEMPOCERO".equals(paramName)) return "0";
        if ("CAPTURAS_TIEMPOMAX".equals(paramName)) return "2880"; // minutes
        if ("CAPTURAS_FECHAMIN".equals(paramName)) return "2000-01-01";
        if ("CAPTURAS_FECHAMAX".equals(paramName)) return "90"; // days
        if ("CAPTURAS_CREARFUN".equals(paramName)) return "1"; // 0=false, >0=true
        if ("CAPTURAS_INTERNADEF".equals(paramName)) return String.valueOf(TelephonyTypeEnum.NATIONAL_IP.getValue()); // Default from PHP
        if (("EXT_GLOBALES_PLANT_" + plantTypeId).equals(paramName) || "EXT_GLOBALES".equals(paramName)) return "0"; // 0=false
        if (("CLAVES_GLOBALES_PLANT_" + plantTypeId).equals(paramName) || "CLAVES_GLOBALES".equals(paramName)) return "0"; // 0=false

        return defaultValue;
    }

    private int getIntParameter(String paramName, int defaultValue, Long plantTypeId, String clientBdName) {
        try {
            return Integer.parseInt(getParameter(paramName, String.valueOf(defaultValue), plantTypeId, clientBdName));
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    private boolean getBooleanParameter(String paramName, boolean defaultValue, Long plantTypeId, String clientBdName) {
        String val = getParameter(paramName, defaultValue ? "1" : "0", plantTypeId, clientBdName);
        return "1".equals(val) || "true".equalsIgnoreCase(val);
    }


    public int getMinCallDurationForTariffing() {
        int val = getIntParameter("CAPTURAS_TIEMPOCERO", 0, null, null);
        return Math.max(0, val); // PHP: if (!is_numeric($min_tiempo) || $min_tiempo < 0) { $min_tiempo = 0; }
    }

    public int getMaxCallDurationSeconds() {
        int maxMinutes = getIntParameter("CAPTURAS_TIEMPOMAX", 2880, null, null);
        if (maxMinutes < 0) maxMinutes = 2880; // PHP default if invalid
        return maxMinutes * 60;
    }

    public String getAssumedText() { return "(ASUMIDO)"; }
    public String getOriginText() { return "(ORIGEN)"; }
    public String getPrefixText() { return "(PREFIJO)"; }

    public List<String> getIgnoredAuthCodeDescriptions() {
        return IGNORED_AUTH_CODE_DESCRIPTIONS;
    }

    public String getMinAllowedCaptureDate() {
        return getParameter("CAPTURAS_FECHAMIN", "2000-01-01", null, null);
    }

    public int getMaxAllowedCaptureDateDaysInFuture() {
        int val = getIntParameter("CAPTURAS_FECHAMAX", 90, null, null);
        return Math.max(0, val);
    }

    public boolean createEmployeesAutomaticallyFromRange() {
        return getBooleanParameter("CAPTURAS_CREARFUN", true, null, null);
    }

    public Long getDefaultTelephonyTypeForUnresolvedInternalCalls() {
        // PHP: if (!in_array($interna_defecto, $tt_internas)) { $interna_defecto = -1; }
        // This implies checking if the configured default is actually an internal type.
        // For simplicity now, we just return the configured value.
        // The PHP logic for Cisco CM 6.0 seems to default to NATIONAL_IP if not found.
        String valStr = getParameter("CAPTURAS_INTERNADEF", String.valueOf(TelephonyTypeEnum.NATIONAL_IP.getValue()), null, null);
        try {
            long val = Long.parseLong(valStr);
            // Further validation if it's a valid internal type could be added here if needed.
            return val > 0 ? val : null;
        } catch (NumberFormatException e) {
            return TelephonyTypeEnum.NATIONAL_IP.getValue(); // Fallback
        }
    }

    public Long getDefaultInternalCallTypeId() {
        return TelephonyTypeEnum.INTERNAL_SIMPLE.getValue();
    }

    public boolean areExtensionsGlobal(Long plantTypeId) {
        return getBooleanParameter("EXT_GLOBALES", false, plantTypeId, null);
    }

    public boolean areAuthCodesGlobal(Long plantTypeId) {
        return getBooleanParameter("CLAVES_GLOBALES", false, plantTypeId, null);
    }
}