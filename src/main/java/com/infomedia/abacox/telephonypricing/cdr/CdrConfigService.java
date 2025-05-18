
package com.infomedia.abacox.telephonypricing.cdr;

import com.infomedia.abacox.telephonypricing.entity.CommunicationLocation;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

@Service
@RequiredArgsConstructor
public class CdrConfigService {

    private final LookupService lookupService;

    // Default values, can be overridden by specific configurations if needed
    public static final int DEFAULT_MIN_CALL_DURATION = 0;
    public static final int DEFAULT_MAX_CALL_DURATION_CAP = 172800; // 48 hours in seconds
    public static final List<String> DEFAULT_IGNORED_AUTH_CODES = Arrays.asList("Invalid Authorization Code", "Invalid Authorization Level");
    public static final boolean DEFAULT_GLOBAL_EXTENSIONS_ENABLED = false; // Default to false
    public static final Long DEFAULT_INTERNAL_NATIONAL_IP_TYPE_ID = TelephonyTypeConstants.INTERNA_NACIONAL; // Example, from PHP CAPTURAS_INTERNADEF
    public static final Long MAX_EXTENSION_NUMERIC_LENGTH_FOR_LIMITS = 1000000L; // PHP _ACUMTOTAL_MAXEXT

    public int getMinCallDurationForProcessing() {
        return DEFAULT_MIN_CALL_DURATION;
    }

    public int getMaxCallDurationCap() {
        return DEFAULT_MAX_CALL_DURATION_CAP;
    }

    public List<String> getPbxOutputPrefixes(CommunicationLocation commLocation) {
        if (commLocation == null || commLocation.getPbxPrefix() == null || commLocation.getPbxPrefix().isEmpty()) {
            return Collections.emptyList();
        }
        return Arrays.asList(commLocation.getPbxPrefix().split(","));
    }

    public InternalExtensionLimitsDto getInternalExtensionLimits(Long originCountryId, Long commLocationId) {
        return lookupService.getInternalExtensionLimits(originCountryId, commLocationId);
    }

    public List<String> getIgnoredAuthCodes() {
        return DEFAULT_IGNORED_AUTH_CODES;
    }

    public boolean isGlobalExtensionsEnabled(Long plantTypeId) {
        // This would ideally come from a configuration related to the plantType or globally.
        // PHP: ObtenerGlobales($link, 'ext_globales');
        // For now, returning a default. In a real system, this needs a lookup.
        return DEFAULT_GLOBAL_EXTENSIONS_ENABLED;
    }
    public Long getDefaultInternalTelephonyTypeId() {
        // Corresponds to PHP CAPTURAS_INTERNADEF
        return DEFAULT_INTERNAL_NATIONAL_IP_TYPE_ID;
    }
}