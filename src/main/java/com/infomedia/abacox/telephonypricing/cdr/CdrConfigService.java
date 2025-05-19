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
import java.util.stream.Collectors;

@Service
@Log4j2
@RequiredArgsConstructor
public class CdrConfigService {

    @PersistenceContext
    private EntityManager entityManager;

    public static final int ACUMTOTAL_MAX_EXTENSION_LENGTH_FOR_INTERNAL_CHECK = 1000000; // PHP: _ACUMTOTAL_MAXEXT
    public static final int CDR_PROCESSING_BATCH_SIZE = 500; // PHP: _IMDEX_MAXLINEAS (used as a general batch/limit)
    public static final String NN_VALIDA_PARTITION = "NN-VALIDA"; // PHP: _NN_VALIDA
    public static final long DEFAULT_OPERATOR_ID_FOR_INTERNAL = 0L; // PHP: Default if not found by operador_interno

    /**
     * PHP equivalent: defineParamCliente('CAPTURAS_TIEMPOCERO', $link);
     */
    @Transactional(readOnly = true)
    public int getMinCallDurationForTariffing() {
        // This would query a 'parametros' table in a full system. Hardcoding for now.
        return 0; // Default: calls of 0 seconds can still be processed (e.g., to NO_CONSUMPTION)
    }

    /**
     * PHP equivalent: defineParamCliente('CAPTURAS_TIEMPOMAX', $link); (returns minutes)
     */
    @Transactional(readOnly = true)
    public int getMaxCallDurationSeconds() {
        int maxMinutes = 2880; // PHP default (2 days * 24 * 60)
        return maxMinutes * 60;
    }

    // PHP: _ASUMIDO
    public String getAssumedText() { return "(ASUMIDO)"; }
    // PHP: _ORIGEN
    public String getOriginText() { return "(ORIGEN)"; }
    // PHP: _PREFIJO
    public String getPrefixText() { return "(PREFIJO)"; }

    /**
     * PHP equivalent: $_FUN_IGNORAR_CLAVE
     */
    public List<String> getIgnoredAuthCodeDescriptions() {
        return Arrays.asList("Invalid Authorization Code", "Invalid Authorization Level");
    }

    /**
     * PHP equivalent: defineParamCliente('CAPTURAS_FECHAMIN', $link);
     */
    @Transactional(readOnly = true)
    public String getMinAllowedCaptureDate() {
        return "2000-01-01"; // Default, should be configurable
    }

    /**
     * PHP equivalent: defineParamCliente('CAPTURAS_FECHAMAX', $link); (returns days)
     */
    @Transactional(readOnly = true)
    public int getMaxAllowedCaptureDateDaysInFuture() {
        return 90; // Default, should be configurable
    }

    /**
     * PHP equivalent: ObtenerGlobales($link, 'auto_fun');
     * Corresponds to PHP's defineParamCliente('CAPTURAS_CREARFUN', $link)
     */
    @Transactional(readOnly = true)
    public boolean createEmployeesAutomaticallyFromRange() {
        return true; // Defaulting to true, should be configurable
    }

    /**
     * PHP equivalent: defineParamCliente('CAPTURAS_INTERNADEF', $link);
     */
    @Transactional(readOnly = true)
    public Long getDefaultInternalCallTypeId() {
        // The PHP logic checks if the defined default is in _tipotele_Internas.
        // Here, we'll just return a sensible default from our enum.
        return TelephonyTypeEnum.INTERNAL_SIMPLE.getValue();
    }

    @Transactional(readOnly = true)
    public String getDefaultInternalCallTypeName() {
        Long typeId = getDefaultInternalCallTypeId();
        try {
            return entityManager.createQuery("SELECT tt.name FROM TelephonyType tt WHERE tt.id = :id AND tt.active = true", String.class)
                    .setParameter("id", typeId)
                    .getSingleResult();
        } catch (NoResultException e) {
            return TelephonyTypeEnum.fromId(typeId).getDefaultName();
        }
    }

    /**
     * PHP equivalent: ObtenerGlobales($link, 'ext_globales')
     */
    @Transactional(readOnly = true)
    public boolean areExtensionsGlobal(Long plantTypeId) {
        // This would typically query a central configuration table for the given plant type or client.
        // For now, returning false as per PHP's default if not explicitly set.
        // A more complete implementation would query a 'global_configs' table based on plantTypeId or a client identifier.
        return false;
    }

    /**
     * PHP equivalent: ObtenerGlobales($link, 'claves_globales')
     */
    @Transactional(readOnly = true)
    public boolean areAuthCodesGlobal(Long plantTypeId) {
        return false;
    }
}