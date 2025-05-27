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
    private EntityManager entityManager;

    public static final int ACUMTOTAL_MAX_EXTENSION_LENGTH_FOR_INTERNAL_CHECK = 1000000;
    public static final int CDR_PROCESSING_BATCH_SIZE = 500;
    public static final String NN_VALIDA_PARTITION = "NN-VALIDA";
    public static final long DEFAULT_OPERATOR_ID_FOR_INTERNAL = 0L; // Or a specific ID if one exists
    private String defaultTimezoneId = "America/Bogota"; // Example: GMT-5, align with PHP server's timezone

    public String getDefaultTimezoneId() {
        // In a real system, this might be configurable per client or system-wide
        return defaultTimezoneId;
    }

    @Transactional(readOnly = true)
    public int getMinCallDurationForTariffing() {
        return 0;
    }

    @Transactional(readOnly = true)
    public int getMaxCallDurationSeconds() {
        int maxMinutes = 2880;
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
        return "2000-01-01";
    }

    @Transactional(readOnly = true)
    public int getMaxAllowedCaptureDateDaysInFuture() {
        return 90;
    }

    @Transactional(readOnly = true)
    public boolean createEmployeesAutomaticallyFromRange() {
        return true;
    }

    @Transactional(readOnly = true)
    public Long getDefaultTelephonyTypeForUnresolvedInternalCalls() {
        log.debug("getDefaultTelephonyTypeForUnresolvedInternalCalls returning: {}", TelephonyTypeEnum.NATIONAL_IP.getValue());
        return TelephonyTypeEnum.NATIONAL_IP.getValue();
    }

    @Transactional(readOnly = true)
    public Long getDefaultInternalCallTypeId() {
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
        return false;
    }

    @Transactional(readOnly = true)
    public boolean areAuthCodesGlobal(Long plantTypeId) {
        return false;
    }
}