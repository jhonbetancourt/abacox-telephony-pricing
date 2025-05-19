// File: com/infomedia/abacox/telephonypricing/cdr/CdrConfigService.java
package com.infomedia.abacox.telephonypricing.cdr;

import jakarta.persistence.EntityManager;
import jakarta.persistence.NoResultException;
import jakarta.persistence.PersistenceContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Arrays;
import java.util.List;

@Service
@Log4j2
@RequiredArgsConstructor // For constructor injection
public class CdrConfigService {

    @PersistenceContext
    private EntityManager entityManager;

    // Constants mimicking PHP defines and global variables
    public static final int ACUMTOTAL_MAX_EXTENSION_LENGTH_FOR_INTERNAL_CHECK = 1000000; // PHP: _ACUMTOTAL_MAXEXT
    public static final int CDR_PROCESSING_BATCH_SIZE = 500; // PHP: _IMDEX_MAXLINEAS
    public static final String NN_VALIDA_PARTITION = "NN-VALIDA"; // PHP: _NN_VALIDA
    public static final long DEFAULT_OPERATOR_ID_FOR_INTERNAL = 0L; // Placeholder, PHP logic is more dynamic

    // Mimicking PHP's _defineParam('CAPTURAS_TIEMPOCERO', $link);
    @Transactional(readOnly = true)
    public int getMinCallDurationForTariffing() {
        // In PHP, this comes from a 'parametros' table.
        // For Java, this could be a dedicated Configuration entity or application properties.
        // Hardcoding for now as per "don't use properties" and no ParameterLookupService.
        // String sql = "SELECT parametro_valor FROM parametros WHERE parametro_nombre = 'CAPTURAS_TIEMPOCERO' AND cliente_bd = :clientDbName";
        // For simplicity, returning a default.
        return 0; // Default value if not found or if logic is simplified
    }

    // Mimicking PHP's _defineParam('CAPTURAS_TIEMPOMAX', $link);
    @Transactional(readOnly = true)
    public int getMaxCallDurationSeconds() {
        // int maxMinutes = parameterLookupService.getIntParameter("CAPTURAS_TIEMPOMAX_MINUTES", default_value);
        // return maxMinutes * 60;
        return 172800; // Default: 2 days in seconds (PHP default)
    }

    public String getAssumedText() { return "(ASUMIDO)"; } // PHP: _ASUMIDO
    public String getOriginText() { return "(ORIGEN)"; } // PHP: _ORIGEN
    public String getPrefixText() { return "(PREFIJO)"; } // PHP: _PREFIJO

    // PHP: $_FUN_IGNORAR_CLAVE
    public List<String> getIgnoredAuthCodeDescriptions() {
        return Arrays.asList("Invalid Authorization Code", "Invalid Authorization Level");
    }

    // PHP: _defineParam('CAPTURAS_FECHAMIN', $link);
    public String getMinAllowedCaptureDate() {
        return "2000-01-01"; // Default
    }

    // PHP: _defineParam('CAPTURAS_FECHAMAX', $link); (returns days)
    public int getMaxAllowedCaptureDateDaysInFuture() {
        return 90; // Default
    }

    // PHP: defineParamCliente('CAPTURAS_CREARFUN', $link); -> ObtenerGlobales($link, 'auto_fun');
    @Transactional(readOnly = true)
    public boolean createEmployeesAutomaticallyFromRange() {
        // This would query a 'parametros' table for 'AUTO_FUN' setting.
        // String sql = "SELECT parametro_valor FROM parametros WHERE parametro_nombre = 'AUTO_FUN' AND cliente_bd = :clientDbName";
        return true; // Defaulting to true
    }

    // PHP: defineParamCliente('CAPTURAS_INTERNADEF', $link);
    @Transactional(readOnly = true)
    public Long getDefaultInternalCallTypeId() {
        // This would query a 'parametros' table for 'CAPTURAS_INTERNADEF'.
        // String sql = "SELECT parametro_valor FROM parametros WHERE parametro_nombre = 'CAPTURAS_INTERNADEF' AND cliente_bd = :clientDbName";
        // The result from DB would be an ID, then map to TelephonyTypeEnum or return the ID.
        return TelephonyTypeEnum.INTERNAL_SIMPLE.getValue(); // Defaulting
    }

    @Transactional(readOnly = true)
    public String getDefaultInternalCallTypeName() {
        Long typeId = getDefaultInternalCallTypeId();
        try {
            return entityManager.createQuery("SELECT tt.name FROM TelephonyType tt WHERE tt.id = :id", String.class)
                    .setParameter("id", typeId)
                    .getSingleResult();
        } catch (NoResultException e) {
            return "Internal (Default)";
        }
    }

    /**
     * PHP equivalent: ObtenerGlobales($link, 'ext_globales') or 'claves_globales'
     * This needs a mechanism to fetch these global parameters, possibly per plant type or client.
     */
    @Transactional(readOnly = true)
    public boolean areExtensionsGlobal(Long plantTypeId) {
        // Placeholder: In PHP, this queries a 'parametros' table or similar.
        // Example: SELECT parametro_valor FROM parametros WHERE parametro_nombre = 'EXT_GLOBALES' AND (cliente_bd = :client OR tipoplanta_id = :plantTypeId)
        // For now, returning a default.
        if (plantTypeId != null && plantTypeId.equals(Long.parseLong(CiscoCm60CdrProcessor.PLANT_TYPE_IDENTIFIER))) {
            // Specific logic for Cisco CM 6.0 if needed, otherwise general.
        }
        return false; // Default: extensions are not global
    }

    @Transactional(readOnly = true)
    public boolean areAuthCodesGlobal(Long plantTypeId) {
        // Placeholder for similar logic as areExtensionsGlobal
        return false; // Default: auth codes are not global
    }
}