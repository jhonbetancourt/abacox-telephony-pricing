package com.infomedia.abacox.telephonypricing.component.cdrprocessing;

import com.infomedia.abacox.telephonypricing.config.ConfigKey;
import com.infomedia.abacox.telephonypricing.service.ConfigService;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.stereotype.Service;

import java.time.ZoneId;
import java.time.ZoneOffset;

@Service
@Log4j2
@RequiredArgsConstructor
public class CdrConfigService {

    private final ConfigService configService;

    // --- Constants based on PHP defines ---
    // Original PHP: define('_ACUMTOTAL_MAXEXT', 1000000);
    public static final int MAX_EXTENSION_LENGTH_FOR_INTERNAL_CHECK = 1000000;
    // Original PHP: define('_IMDEX_MAXLINEAS', 500);
    public static final int CDR_PROCESSING_BATCH_SIZE = 500;
    // Original PHP: Hardcoded value 0 for internal operator lookups
    public static final long DEFAULT_OPERATOR_ID_FOR_INTERNAL = 0L;

    public ZoneId getTargetDatabaseZoneId() {
        // This is a new concept for Java's timezone handling, no direct PHP equivalent.
        // It replaces implicit server timezone assumptions.
        String zoneIdStr = configService.getValue(ConfigKey.SERVICE_DATE_HOUR_OFFSET);
        try {
            int offsetHours = Integer.parseInt(zoneIdStr);
            return ZoneOffset.ofHours(offsetHours);
        } catch (Exception e) {
            log.error("Invalid ZoneId configured: '{}'. Defaulting to UTC.", zoneIdStr, e);
            return ZoneId.of("UTC");
        }
    }

    public boolean isSpecialValueTariffingEnabled() {
        // Original PHP: $usar_valorespecial = ValidarUso($link, 'valorespecial');
        return Boolean.parseBoolean(configService.getValue(ConfigKey.SPECIAL_VALUE_TARIFFING));
    }

    public int getMinCallDurationForTariffing() {
        // Original PHP: $min_tiempo = defineParamCliente('CAPTURAS_TIEMPOCERO', $link);
        int val = Integer.parseInt(configService.getValue(ConfigKey.MIN_CALL_DURATION_FOR_TARIFFING));
        return Math.max(0, val);
    }

    public int getMaxCallDurationSeconds() {
        // Original PHP: $max_tiempo = defineParamCliente('CAPTURAS_TIEMPOMAX', $link); (in minutes)
        int maxMinutes = Integer.parseInt(configService.getValue(ConfigKey.MAX_CALL_DURATION_MINUTES));
        return maxMinutes * 60; // Convert minutes to seconds
    }

    public String getMinAllowedCaptureDate() {
        // Original PHP: $fechamin = defineParamCliente('CAPTURAS_FECHAMIN', $link);
        return configService.getValue(ConfigKey.MIN_ALLOWED_CAPTURE_DATE);
    }

    public int getMaxAllowedCaptureDateDaysInFuture() {
        // Original PHP: $dias_add = defineParamCliente('CAPTURAS_FECHAMAX', $link);
        int val = Integer.parseInt(configService.getValue(ConfigKey.MAX_ALLOWED_CAPTURE_DATE_DAYS_IN_FUTURE));
        return Math.max(0, val);
    }

    public boolean createEmployeesAutomaticallyFromRange() {
        // Original PHP: $auto_fun = ObtenerGlobales($link, 'auto_fun');
        return Boolean.parseBoolean(configService.getValue(ConfigKey.CREATE_EMPLOYEES_AUTOMATICALLY_FROM_RANGE));
    }

    public Long getDefaultTelephonyTypeForUnresolvedInternalCalls() {
        // Original PHP: $interna_defecto = defineParamCliente('CAPTURAS_INTERNADEF', $link);
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
        // Original PHP: $ext_globales = ObtenerGlobales($link, 'ext_globales');
        return Boolean.parseBoolean(configService.getValue(ConfigKey.EXTENSIONS_GLOBAL));
    }

    public boolean areAuthCodesGlobal() {
        // Original PHP: $claves_globales = ObtenerGlobales($link, 'claves_globales');
        return Boolean.parseBoolean(configService.getValue(ConfigKey.AUTH_CODES_GLOBAL));
    }

    public String getAssumedText() {
        // Original PHP: define('_ASUMIDO', ' (Asumido)');
        return configService.getValue(ConfigKey.ASSUMED_TEXT);
    }
    public String getOriginText() {
        // Original PHP: define('_ORIGEN', 'Origen');
        return configService.getValue(ConfigKey.ORIGIN_TEXT);
    }
    public String getPrefixText() {
        // Original PHP: define('_PREFIJO', 'Prefijo');
        return configService.getValue(ConfigKey.PREFIX_TEXT);
    }

    /**
     * PHP equivalent: _FUNCIONARIO
     * @return The default prefix for auto-created employee names from ranges.
     */
    public String getEmployeeNamePrefixFromRange() {
        // Original PHP: $prefijo = trim($row["RANGOEXT_PREFIJO"]); if ($prefijo == '') { $prefijo = _FUNCIONARIO; }
        return configService.getValue(ConfigKey.EMPLOYEE_NAME_PREFIX_FROM_RANGE);
    }

    /**
     * PHP equivalent: _NN_VALIDA
     * @return The placeholder string for an empty but valid partition.
     */
    public String getNoPartitionPlaceholder() {
        // Original PHP: define('_NN_VALIDA', 'NN-VALIDA');
        return configService.getValue(ConfigKey.NO_PARTITION_PLACEHOLDER);
    }

    public Long getDefaultInternalCallTypeId() {
        // Original PHP: Hardcoded value 7 for "Interna"
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