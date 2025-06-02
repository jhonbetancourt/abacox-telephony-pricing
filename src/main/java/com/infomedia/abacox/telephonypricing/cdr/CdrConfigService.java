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

    private String getParameter(String paramName, String defaultValue, Long plantTypeId, String clientBdName) {
        if ("CAPTURAS_TIEMPOCERO".equals(paramName)) return "0";
        if ("CAPTURAS_TIEMPOMAX".equals(paramName)) return "2880"; // minutes
        if ("CAPTURAS_FECHAMIN".equals(paramName)) return "2000-01-01";
        if ("CAPTURAS_FECHAMAX".equals(paramName)) return "90"; // days
        if ("CAPTURAS_CREARFUN".equals(paramName)) return "1"; // 0=false, >0=true
        if ("CAPTURAS_INTERNADEF".equals(paramName)) return String.valueOf(TelephonyTypeEnum.NATIONAL_IP.getValue());
        if (("EXT_GLOBALES_PLANT_" + plantTypeId).equals(paramName) || "EXT_GLOBALES".equals(paramName)) return "0";
        if (("CLAVES_GLOBALES_PLANT_" + plantTypeId).equals(paramName) || "CLAVES_GLOBALES".equals(paramName)) return "0";
        if ("TRUNK_NORMALIZATION_ENABLED".equals(paramName)) return "1"; // Default to true for normalization

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
        return Math.max(0, val);
    }

    public int getMaxCallDurationSeconds() {
        int maxMinutes = getIntParameter("CAPTURAS_TIEMPOMAX", 2880, null, null);
        if (maxMinutes < 0) maxMinutes = 2880;
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
        String valStr = getParameter("CAPTURAS_INTERNADEF", String.valueOf(TelephonyTypeEnum.NATIONAL_IP.getValue()), null, null);
        try {
            long val = Long.parseLong(valStr);
            return val > 0 ? val : null;
        } catch (NumberFormatException e) {
            return TelephonyTypeEnum.NATIONAL_IP.getValue();
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

    public boolean isTrunkNormalizationEnabled(Long plantTypeId) {
        // PHP: && $existe_troncal['normalizar'] - this flag doesn't exist in Trunk entity.
        // Assuming normalization is generally enabled if the initial trunk evaluation is poor.
        return getBooleanParameter("TRUNK_NORMALIZATION_ENABLED", true, plantTypeId, null);
    }
}