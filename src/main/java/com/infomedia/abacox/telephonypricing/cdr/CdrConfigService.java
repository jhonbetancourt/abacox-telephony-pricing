package com.infomedia.abacox.telephonypricing.cdr;

import com.infomedia.abacox.telephonypricing.entity.CommunicationLocation;
import com.infomedia.abacox.telephonypricing.entity.TelephonyTypeConfig;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class CdrConfigService {

    private final LookupService lookupService;

    // Default values, can be overridden by specific configurations if needed
    private static final int DEFAULT_MIN_CALL_DURATION = 0;
    private static final int DEFAULT_MAX_CALL_DURATION_CAP = 172800; // 48 hours in seconds
    private static final List<String> DEFAULT_IGNORED_AUTH_CODES = Arrays.asList("Invalid Authorization Code", "Invalid Authorization Level");
    private static final boolean DEFAULT_GLOBAL_EXTENSIONS_ENABLED = false; // Default to false

    public int getMinCallDurationForProcessing() {
        // Could fetch from a configuration table if needed
        return DEFAULT_MIN_CALL_DURATION;
    }

    public int getMaxCallDurationCap() {
        // Could fetch from a configuration table if needed
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
        // Could fetch from a configuration table if needed
        return DEFAULT_IGNORED_AUTH_CODES;
    }

    public boolean isGlobalExtensionsEnabled(Long plantTypeId) {
        // This would ideally come from a configuration related to the plantType or globally.
        // PHP: ObtenerGlobales($link, 'ext_globales');
        // For now, returning a default. In a real system, this needs a lookup.
        return DEFAULT_GLOBAL_EXTENSIONS_ENABLED;
    }
    
    public int getMinLengthForTelephonyType(Long telephonyTypeId, Long originCountryId) {
        Optional<TelephonyTypeConfig> config = lookupService.findTelephonyTypeConfigByNumberLength(telephonyTypeId, originCountryId, 0); // Length 0 to get any config
        return config.map(TelephonyTypeConfig::getMinValue).orElse(0); // Default to 0 if no config
    }

    public int getMaxLengthForTelephonyType(Long telephonyTypeId, Long originCountryId) {
        Optional<TelephonyTypeConfig> config = lookupService.findTelephonyTypeConfigByNumberLength(telephonyTypeId, originCountryId, Integer.MAX_VALUE); // Length MAX_VALUE to get any config
        return config.map(TelephonyTypeConfig::getMaxValue).orElse(Integer.MAX_VALUE); // Default to very large if no config
    }
}