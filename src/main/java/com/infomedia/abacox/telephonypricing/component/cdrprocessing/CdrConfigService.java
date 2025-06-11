// File: com/infomedia/abacox/telephonypricing/component/cdrprocessing/CdrConfigService.java
package com.infomedia.abacox.telephonypricing.component.cdrprocessing;

import com.infomedia.abacox.telephonypricing.config.ConfigKey;
import com.infomedia.abacox.telephonypricing.service.ConfigService;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.stereotype.Service;

import java.time.ZoneId;

@Service
@Log4j2
@RequiredArgsConstructor
public class CdrConfigService {

    private final ConfigService configService;

    // --- Constants based on PHP defines ---
    public static final int MAX_EXTENSION_LENGTH_FOR_INTERNAL_CHECK = 1000000;
    public static final int CDR_PROCESSING_BATCH_SIZE = 500;
    public static final long DEFAULT_OPERATOR_ID_FOR_INTERNAL = 0L;

    public ZoneId getTargetDatabaseZoneId() {
        String zoneIdStr = configService.getValue(ConfigKey.TARGET_DATABASE_ZONE_ID);
        try {
            return ZoneId.of(zoneIdStr);
        } catch (Exception e) {
            log.error("Invalid ZoneId configured: '{}'. Defaulting to UTC.", zoneIdStr, e);
            return ZoneId.of("UTC");
        }
    }

    public boolean isSpecialValueTariffingEnabled() {
        return Boolean.parseBoolean(configService.getValue(ConfigKey.SPECIAL_VALUE_TARIFFING_ENABLED));
    }

    public int getMinCallDurationForTariffing() {
        int val = Integer.parseInt(configService.getValue(ConfigKey.MIN_CALL_DURATION_FOR_TARIFFING));
        return Math.max(0, val);
    }

    public int getMaxCallDurationSeconds() {
        int maxMinutes = Integer.parseInt(configService.getValue(ConfigKey.MAX_CALL_DURATION_MINUTES));
        return maxMinutes * 60; // Convert minutes to seconds
    }

    public String getMinAllowedCaptureDate() {
        return configService.getValue(ConfigKey.MIN_ALLOWED_CAPTURE_DATE);
    }

    public int getMaxAllowedCaptureDateDaysInFuture() {
        int val = Integer.parseInt(configService.getValue(ConfigKey.MAX_ALLOWED_CAPTURE_DATE_DAYS_IN_FUTURE));
        return Math.max(0, val);
    }

    public boolean createEmployeesAutomaticallyFromRange() {
        return Boolean.parseBoolean(configService.getValue(ConfigKey.CREATE_EMPLOYEES_AUTOMATICALLY_FROM_RANGE));
    }

    public Long getDefaultTelephonyTypeForUnresolvedInternalCalls() {
        String valStr = configService.getValue(ConfigKey.DEFAULT_UNRESOLVED_INTERNAL_CALL_TYPE_ID);
        try {
            long val = Long.parseLong(valStr);
            return val > 0 ? val : null;
        } catch (NumberFormatException e) {
            log.warn("NFE for DEFAULT_TELEPHONY_TYPE_FOR_UNRESOLVED_INTERNAL, using default {}", ConfigKey.DEFAULT_UNRESOLVED_INTERNAL_CALL_TYPE_ID.getDefaultValue());
            return Long.parseLong(ConfigKey.DEFAULT_UNRESOLVED_INTERNAL_CALL_TYPE_ID.getDefaultValue());
        }
    }

    public boolean areExtensionsGlobal() {
        return Boolean.parseBoolean(configService.getValue(ConfigKey.EXTENSIONS_GLOBAL));
    }

    public boolean areAuthCodesGlobal() {
        return Boolean.parseBoolean(configService.getValue(ConfigKey.AUTH_CODES_GLOBAL));
    }

    public String getAssumedText() {
        return configService.getValue(ConfigKey.ASSUMED_TEXT);
    }
    public String getOriginText() {
        return configService.getValue(ConfigKey.ORIGIN_TEXT);
    }
    public String getPrefixText() {
        return configService.getValue(ConfigKey.PREFIX_TEXT);
    }

    /**
     * PHP equivalent: _FUNCIONARIO
     * @return The default prefix for auto-created employee names from ranges.
     */
    public String getEmployeeNamePrefixFromRange() {
        return configService.getValue(ConfigKey.EMPLOYEE_NAME_PREFIX_FROM_RANGE);
    }

    /**
     * PHP equivalent: _NN_VALIDA
     * @return The placeholder string for an empty but valid partition.
     */
    public String getNoPartitionPlaceholder() {
        return configService.getValue(ConfigKey.NO_PARTITION_PLACEHOLDER);
    }

    public Long getDefaultInternalCallTypeId() {
        String valStr = configService.getValue(ConfigKey.DEFAULT_INTERNAL_CALL_TYPE_ID);
        try {
            long val = Long.parseLong(valStr);
            return val > 0 ? val : null;
        } catch (NumberFormatException e) {
            log.warn("NFE for DEFAULT_INTERNAL_CALL_TYPE_ID, using default {}", ConfigKey.DEFAULT_INTERNAL_CALL_TYPE_ID.getDefaultValue());
            return Long.parseLong(ConfigKey.DEFAULT_INTERNAL_CALL_TYPE_ID.getDefaultValue());
        }
    }
}