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
import java.util.stream.Collectors;

@Service
@Log4j2
@RequiredArgsConstructor
public class CdrConfigService {

    @PersistenceContext
    private EntityManager entityManager;

    public static final int ACUMTOTAL_MAX_EXTENSION_LENGTH_FOR_INTERNAL_CHECK = 1000000;
    public static final int CDR_PROCESSING_BATCH_SIZE = 500;
    public static final String NN_VALIDA_PARTITION = "NN-VALIDA";
    public static final long DEFAULT_OPERATOR_ID_FOR_INTERNAL = 0L; // Default if not found

    @Transactional(readOnly = true)
    public int getMinCallDurationForTariffing() {
        // PHP: defineParamCliente('CAPTURAS_TIEMPOCERO', $link);
        // This would query a 'parametros' table. Hardcoding for now.
        return 0; // Default: calls of 0 seconds can still be processed (e.g., to NO_CONSUMPTION)
    }

    @Transactional(readOnly = true)
    public int getMaxCallDurationSeconds() {
        // PHP: defineParamCliente('CAPTURAS_TIEMPOMAX', $link); (returns minutes)
        int maxMinutes = 120; // Default 2 hours, PHP default was 2 days (2880 min)
        // int maxMinutes = 2880; // PHP default
        return maxMinutes * 60;
    }

    public String getAssumedText() { return "(ASUMIDO)"; }
    public String getOriginText() { return "(ORIGEN)"; }
    public String getPrefixText() { return "(PREFIJO)"; }

    public List<String> getIgnoredAuthCodeDescriptions() {
        return Arrays.asList("Invalid Authorization Code", "Invalid Authorization Level");
    }

    @Transactional(readOnly = true)
    public String getMinAllowedCaptureDate() {
        // PHP: defineParamCliente('CAPTURAS_FECHAMIN', $link);
        return "2000-01-01"; // Default
    }

    @Transactional(readOnly = true)
    public int getMaxAllowedCaptureDateDaysInFuture() {
        // PHP: defineParamCliente('CAPTURAS_FECHAMAX', $link); (returns days)
        return 90; // Default
    }

    @Transactional(readOnly = true)
    public boolean createEmployeesAutomaticallyFromRange() {
        // PHP: ObtenerGlobales($link, 'auto_fun');
        return true; // Defaulting to true
    }

    @Transactional(readOnly = true)
    public Long getDefaultInternalCallTypeId() {
        // PHP: defineParamCliente('CAPTURAS_INTERNADEF', $link);
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

    @Transactional(readOnly = true)
    public boolean areExtensionsGlobal(Long plantTypeId) {
        // PHP: ObtenerGlobales($link, 'ext_globales')
        // This would typically query a central configuration table.
        // For now, returning false as per PHP's default if not explicitly set.
        return false;
    }

    @Transactional(readOnly = true)
    public boolean areAuthCodesGlobal(Long plantTypeId) {
        // PHP: ObtenerGlobales($link, 'claves_globales')
        return false;
    }
}