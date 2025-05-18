package com.infomedia.abacox.telephonypricing.cdr;

import org.springframework.stereotype.Service;

@Service
public class CdrConfigService {

    // Constants from PHP global scope or defines
    public static final int ACUMTOTAL_MAX_EXTENSION_LENGTH_FOR_INTERNAL_CHECK = 1000000; // _ACUMTOTAL_MAXEXT
    public static final int CDR_PROCESSING_BATCH_SIZE = 500; // _IMDEX_MAXLINEAS (for web, can be different for batch)
    public static final String DEFAULT_PBX_EXIT_PREFIX = "9"; // Example, should be configurable per commLocation

    public String getDefaultIgnoredAuthCodeDescription1() {
        return "Invalid Authorization Code";
    }

    public String getDefaultIgnoredAuthCodeDescription2() {
        return "Invalid Authorization Level";
    }

    public int getMinCallDurationForTariffing() {
        // CAPTURAS_TIEMPOCERO in PHP
        return 0; // seconds
    }

    public int getMaxCallDurationSeconds() {
        // CAPTURAS_TIEMPOMAX in PHP (default 172800 seconds = 2 days)
        return 172800;
    }

    public String getAssumedText() {
        return "(ASUMIDO)"; // _ASUMIDO
    }

    public String getOriginText() {
        return "(ORIGEN)"; // _ORIGEN
    }

    public String getPrefixText() {
        return "(PREFIJO)"; // _PREFIJO
    }
}