package com.infomedia.abacox.telephonypricing.cdr;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Arrays;
import java.util.List;

@Service
public class CdrConfigService {

    public static final int ACUMTOTAL_MAX_EXTENSION_LENGTH_FOR_INTERNAL_CHECK = 1000000; // _ACUMTOTAL_MAXEXT
    public static final int CDR_PROCESSING_BATCH_SIZE = 500; // _IMDEX_MAXLINEAS
    public static final String NN_VALIDA_PARTITION = "NN-VALIDA";

    public static final long DEFAULT_OPERATOR_ID_FOR_INTERNAL = 0L; // Example, if not found via specific logic

    public String getDefaultIgnoredAuthCodeDescription1() {
        return "Invalid Authorization Code";
    }
    public String getDefaultIgnoredAuthCodeDescription2() {
        return "Invalid Authorization Level";
    }

    public int getMinCallDurationForTariffing() { return 0; } // CAPTURAS_TIEMPOCERO
    public int getMaxCallDurationSeconds() { return 172800; } // CAPTURAS_TIEMPOMAX (2 days)

    public String getAssumedText() { return "(ASUMIDO)"; } // _ASUMIDO
    public String getOriginText() { return "(ORIGEN)"; } // _ORIGEN
    public String getPrefixText() { return "(PREFIJO)"; } // _PREFIJO

    public List<String> getIgnoredAuthCodeDescriptions() {
        return Arrays.asList("Invalid Authorization Code", "Invalid Authorization Level");
    }

    public String getMinAllowedCaptureDate() { return "2000-01-01"; } // CAPTURAS_FECHAMIN
    public int getMaxAllowedCaptureDateDaysInFuture() { return 90; } // CAPTURAS_FECHAMAX (days)

    public boolean createEmployeesAutomaticallyFromRange() { return true; } // CAPTURAS_CREARFUN (example)

    public String getDefaultInternalCallTypeName() { return "Internal (Default)"; } // CAPTURAS_INTERNADEF (name for it)
    public Long getDefaultInternalCallTypeId() { return TelephonyTypeEnum.INTERNAL_SIMPLE.getValue(); } // CAPTURAS_INTERNADEF (ID)


    public boolean areExtensionsGlobal(Long plantTypeId) {
        // This is a placeholder. PHP's ObtenerGlobales checks a 'parametros' table.
        // You'd need to implement a similar lookup.
        // Example: return parameterLookupService.getBooleanParameter("EXT_GLOBALES", plantTypeId, false);
        return false; // Defaulting to false (not global)
    }

}
