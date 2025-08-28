package com.infomedia.abacox.telephonypricing.component.cdrprocessing;

import com.infomedia.abacox.telephonypricing.component.configmanager.ConfigKey;
import com.infomedia.abacox.telephonypricing.component.configmanager.ConfigService;
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
    public static final Long DEFAULT_OPERATOR_ID_FOR_INTERNAL = null;

    public ZoneId getTargetDatabaseZoneId() {
        // This is a new concept for Java's timezone handling, no direct PHP equivalent.
        // It replaces implicit server timezone assumptions.
        String zoneIdStr = configService.getValue(ConfigKey.SERVICE_DATE_HOUR_OFFSET).asString();
        try {
            int offsetHours = Integer.parseInt(zoneIdStr);
            return ZoneOffset.ofHours(offsetHours);
        } catch (Exception e) {
            log.debug("Invalid ZoneId configured: '{}'. Defaulting to UTC.", zoneIdStr, e);
            return ZoneId.of("UTC");
        }
    }

    public boolean isSpecialValueTariffingEnabled() {
        // Original PHP: $usar_valorespecial = ValidarUso($link, 'valorespecial');
        return configService.getValue(ConfigKey.SPECIAL_VALUE_TARIFFING).asBoolean();
    }

    public int getMinCallDurationForTariffing() {
        // Original PHP: $min_tiempo = defineParamCliente('CAPTURAS_TIEMPOCERO', $link);
        int val = configService.getValue(ConfigKey.MIN_CALL_DURATION_FOR_TARIFFING).asInt();
        return Math.max(0, val);
    }

    public int getMaxCallDurationSeconds() {
        // Original PHP: $max_tiempo = defineParamCliente('CAPTURAS_TIEMPOMAX', $link); (in minutes)
        int maxMinutes = configService.getValue(ConfigKey.MAX_CALL_DURATION_MINUTES).asInt();
        return maxMinutes * 60; // Convert minutes to seconds
    }

    public String getMinAllowedCaptureDate() {
        // Original PHP: $fechamin = defineParamCliente('CAPTURAS_FECHAMIN', $link);
        return configService.getValue(ConfigKey.MIN_ALLOWED_CAPTURE_DATE).asString();
    }

    public int getMaxAllowedCaptureDateDaysInFuture() {
        // Original PHP: $dias_add = defineParamCliente('CAPTURAS_FECHAMAX', $link);
        int val = configService.getValue(ConfigKey.MAX_ALLOWED_CAPTURE_DATE_DAYS_IN_FUTURE).asInt();
        return Math.max(0, val);
    }

    public boolean createEmployeesAutomaticallyFromRange() {
        // Original PHP: $auto_fun = ObtenerGlobales($link, 'auto_fun');
        return configService.getValue(ConfigKey.CREATE_EMPLOYEES_AUTOMATICALLY_FROM_RANGE).asBoolean();
    }

    public Long getDefaultTelephonyTypeForUnresolvedInternalCalls() {
        // Original PHP: $interna_defecto = defineParamCliente('CAPTURAS_INTERNADEF', $link);
        String valStr = configService.getValue(ConfigKey.DEFAULT_UNRESOLVED_INTERNAL_CALL_TYPE_ID).asString();
        try {
            long val = Long.parseLong(valStr);
            return val > 0 ? val : null;
        } catch (NumberFormatException e) {
            log.debug("NFE for DEFAULT_TELEPHONY_TYPE_FOR_UNRESOLVED_INTERNAL, using default {}", ConfigKey.DEFAULT_UNRESOLVED_INTERNAL_CALL_TYPE_ID.getDefaultValue());
            return Long.parseLong(ConfigKey.DEFAULT_UNRESOLVED_INTERNAL_CALL_TYPE_ID.getDefaultValue());
        }
    }

    public boolean areExtensionsGlobal() {
        // Original PHP: $ext_globales = ObtenerGlobales($link, 'ext_globales');
        return configService.getValue(ConfigKey.EXTENSIONS_GLOBAL).asBoolean();
    }

    public boolean areAuthCodesGlobal() {
        // Original PHP: $claves_globales = ObtenerGlobales($link, 'claves_globales');
        return configService.getValue(ConfigKey.AUTH_CODES_GLOBAL).asBoolean();
    }

    public String getAssumedText() {
        // Original PHP: define('_ASUMIDO', ' (Asumido)');
        return configService.getValue(ConfigKey.ASSUMED_TEXT).asString();
    }
    public String getOriginText() {
        // Original PHP: define('_ORIGEN', 'Origen');
        return configService.getValue(ConfigKey.ORIGIN_TEXT).asString();
    }
    public String getPrefixText() {
        // Original PHP: define('_PREFIJO', 'Prefijo');
        return configService.getValue(ConfigKey.PREFIX_TEXT).asString();
    }

    /**
     * PHP equivalent: _FUNCIONARIO
     * @return The default prefix for auto-created employee names from ranges.
     */
    public String getEmployeeNamePrefixFromRange() {
        // Original PHP: $prefijo = trim($row["RANGOEXT_PREFIJO"]); if ($prefijo == '') { $prefijo = _FUNCIONARIO; }
        return configService.getValue(ConfigKey.EMPLOYEE_NAME_PREFIX_FROM_RANGE).asString();
    }

    /**
     * PHP equivalent: _NN_VALIDA
     * @return The placeholder string for an empty but valid partition.
     */
    public String getNoPartitionPlaceholder() {
        // Original PHP: define('_NN_VALIDA', 'NN-VALIDA');
        return configService.getValue(ConfigKey.NO_PARTITION_PLACEHOLDER).asString();
    }

    public Long getDefaultInternalCallTypeId() {
        // Original PHP: Hardcoded value 7 for "Interna"
        String valStr = configService.getValue(ConfigKey.DEFAULT_INTERNAL_CALL_TYPE_ID).asString();
        try {
            long val = Long.parseLong(valStr);
            return val > 0 ? val : null;
        } catch (NumberFormatException e) {
            log.debug("NFE for DEFAULT_INTERNAL_CALL_TYPE_ID, using default {}", ConfigKey.DEFAULT_INTERNAL_CALL_TYPE_ID.getDefaultValue());
            return Long.parseLong(ConfigKey.DEFAULT_INTERNAL_CALL_TYPE_ID.getDefaultValue());
        }
    }
}