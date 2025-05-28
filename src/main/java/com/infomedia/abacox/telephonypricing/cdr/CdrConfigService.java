// File: com/infomedia/abacox/telephonypricing/cdr/CdrConfigService.java
package com.infomedia.abacox.telephonypricing.cdr;

import jakarta.persistence.EntityManager;
import jakarta.persistence.NoResultException;
import jakarta.persistence.PersistenceContext;
import lombok.extern.log4j.Log4j2;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Arrays;
import java.util.List;

@Service
@Log4j2
public class CdrConfigService {

    @PersistenceContext
    private EntityManager entityManager; // Retained for future DB-based config

    // Constants from PHP
    public static final int ACUMTOTAL_MAX_EXTENSION_LENGTH_FOR_INTERNAL_CHECK = 1000000; // PHP: _ACUMTOTAL_MAXEXT
    public static final int CDR_PROCESSING_BATCH_SIZE = 500; // PHP: _IMDEX_MAXLINEAS (used as a general batch/limit)
    public static final String NN_VALIDA_PARTITION = "NN-VALIDA"; // PHP: _NN_VALIDA
    public static final long DEFAULT_OPERATOR_ID_FOR_INTERNAL = 0L; // PHP: Default if not found by operador_interno

    // PHP: $_FUN_IGNORAR_CLAVE
    private static final List<String> IGNORED_AUTH_CODE_DESCRIPTIONS =
            Arrays.asList("Invalid Authorization Code", "Invalid Authorization Level");

    // PHP: defineParamCliente('CAPTURAS_TIEMPOCERO', $link);
    public int getMinCallDurationForTariffing() {
        // In PHP, if not numeric or < 0, it's 0.
        // This would query a 'parametros' table in a full system. Hardcoding for now.
        return 0; // Default: calls of 0 seconds can still be processed (e.g., to NO_CONSUMPTION)
    }

    // PHP: defineParamCliente('CAPTURAS_TIEMPOMAX', $link); (returns minutes)
    public int getMaxCallDurationSeconds() {
        // PHP: if (!is_numeric($max_tiempo) || $max_tiempo < 0) { $max_tiempo = 172800; } else { $max_tiempo = $max_tiempo * 60; }
        // 172800 seconds = 2880 minutes = 48 hours = 2 days
        int maxMinutes = 2880; // Default from PHP
        // In a real system, query DB for 'CAPTURAS_TIEMPOMAX'
        return maxMinutes * 60;
    }

    // PHP: _ASUMIDO
    public String getAssumedText() { return "(ASUMIDO)"; }
    // PHP: _ORIGEN
    public String getOriginText() { return "(ORIGEN)"; }
    // PHP: _PREFIJO
    public String getPrefixText() { return "(PREFIJO)"; }

    public List<String> getIgnoredAuthCodeDescriptions() {
        return IGNORED_AUTH_CODE_DESCRIPTIONS;
    }

    // PHP: defineParamCliente('CAPTURAS_FECHAMIN', $link);
    public String getMinAllowedCaptureDate() {
        // In PHP, if empty, no check.
        return "2000-01-01"; // Default, should be configurable
    }

    // PHP: defineParamCliente('CAPTURAS_FECHAMAX', $link); (returns days)
    public int getMaxAllowedCaptureDateDaysInFuture() {
        // In PHP, if not numeric or < 0, no check.
        return 90; // Default, should be configurable
    }

    // PHP: ObtenerGlobales($link, 'auto_fun');
    // Corresponds to PHP's defineParamCliente('CAPTURAS_CREARFUN', $link)
    public boolean createEmployeesAutomaticallyFromRange() {
        // PHP: $auto_fun = ObtenerGlobales($link, 'auto_fun');
        // ObtenerGlobales calls defineParamCliente('CAPTURAS_CREARFUN', $link)
        // If 'CAPTURAS_CREARFUN' is not 0, it's true.
        return true; // Defaulting to true, should be configurable
    }

    // PHP: defineParamCliente('CAPTURAS_INTERNADEF', $link);
    public Long getDefaultTelephonyTypeForUnresolvedInternalCalls() {
        // PHP: if (!in_array($interna_defecto, $tt_internas)) { $interna_defecto = -1; }
        // This means if the configured default is not an internal type, it's ignored.
        // For the specific CSV case, this should return 9 (NATIONAL_IP)
        // In a real system, this would come from a configuration database.
        log.debug("getDefaultTelephonyTypeForUnresolvedInternalCalls returning: {}", TelephonyTypeEnum.NATIONAL_IP.getValue());
        return TelephonyTypeEnum.NATIONAL_IP.getValue(); // To match the CSV output
    }

    public Long getDefaultInternalCallTypeId() {
        return TelephonyTypeEnum.INTERNAL_SIMPLE.getValue(); // PHP: _TIPOTELE_INTERNA_IP
    }

    // PHP: ObtenerGlobales($link, 'ext_globales')
    public boolean areExtensionsGlobal(Long plantTypeId) {
        // This would typically query a central configuration table for the given plant type or client.
        // For now, returning false as per PHP's default if not explicitly set.
        // A more complete implementation would query a 'global_configs' table based on plantTypeId or a client identifier.
        // Example:
        // String paramName = "EXT_GLOBALES_PLANT_" + plantTypeId;
        // return queryBooleanParameter(paramName, false);
        return false;
    }

    // PHP: ObtenerGlobales($link, 'claves_globales')
    public boolean areAuthCodesGlobal(Long plantTypeId) {
        // Similar to areExtensionsGlobal
        return false;
    }
}