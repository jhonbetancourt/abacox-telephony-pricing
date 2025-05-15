package com.infomedia.abacox.telephonypricing.cdr;

import com.infomedia.abacox.telephonypricing.entity.*;
import com.infomedia.abacox.telephonypricing.repository.OperatorRepository;
import com.infomedia.abacox.telephonypricing.repository.TelephonyTypeRepository;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Log4j2
public class CdrProcessingConfig {

    private final OperatorRepository operatorRepository;
    private final TelephonyTypeRepository telephonyTypeRepository;

    // --- Constants based on PHP defines/usage ---
    public static final long TIPOTELE_LOCAL = 3L;
    public static final long TIPOTELE_NACIONAL = 4L;
    public static final long TIPOTELE_INTERNACIONAL = 5L;
    public static final long TIPOTELE_CELULAR = 2L;
    public static final long TIPOTELE_CELUFIJO = 1L;
    public static final long TIPOTELE_ESPECIALES = 11L;
    public static final long TIPOTELE_LOCAL_EXT = 12L;
    public static final long TIPOTELE_SATELITAL = 10L;
    public static final long TIPOTELE_INTERNA_IP = 7L;
    public static final long TIPOTELE_LOCAL_IP = 8L;
    public static final long TIPOTELE_NACIONAL_IP = 9L;
    public static final long TIPOTELE_INTERNACIONAL_IP = 14L;
    public static final long TIPOTELE_SINCONSUMO = 16L;
    public static final long TIPOTELE_ERRORES = 99L;
    public static final long TIPOTELE_PAGO_REVERTIDO = 13L;
    public static final long TIPOTELE_SERVICIOS_VARIOS = 6L;

    public static final int DEFAULT_MIN_EXT_LENGTH = 2;
    public static final int DEFAULT_MAX_EXT_LENGTH = 7;
    public static final int MAX_POSSIBLE_EXTENSION_VALUE = 9999999;

    private static Set<Long> internalTelephonyTypeIds;
    private static Long defaultInternalCallTypeId;
    private static Set<String> ignoredAuthCodes;
    private static int minCallDurationForBilling = 0; // Default: bill even 0-second calls if rate applies
    private final ConfigurationLookupService configurationLookupService;
    private final EntityLookupService entityLookupService;
    private final ExtensionLookupService extensionLookupService;

    @Getter
    public static class ExtensionLengthConfig {
        private final int minLength;
        private final int maxLength;
        private final int maxExtensionValue;

        public ExtensionLengthConfig(int minLength, int maxLength, int maxExtensionValue) {
            this.minLength = minLength;
            this.maxLength = maxLength;
            this.maxExtensionValue = maxExtensionValue;
        }
    }


    static {
        internalTelephonyTypeIds = Set.of(
                TIPOTELE_INTERNA_IP, TIPOTELE_LOCAL_IP, TIPOTELE_NACIONAL_IP, TIPOTELE_INTERNACIONAL_IP
        );
        defaultInternalCallTypeId = TIPOTELE_INTERNA_IP;
        ignoredAuthCodes = Set.of("Invalid Authorization Code", "Invalid Authorization Level");
        // minCallDurationForBilling can be loaded from DB or properties if needed
    }

    
    public List<String> getPbxPrefixes(Long communicationLocationId) {
        log.debug("Fetching PBX prefixes for commLocationId: {}", communicationLocationId);
        Optional<String> prefixStringOpt = configurationLookupService.findPbxPrefixByCommLocationId(communicationLocationId);
        if (prefixStringOpt.isPresent() && !prefixStringOpt.get().isEmpty()) {
            List<String> prefixes = Arrays.stream(prefixStringOpt.get().split(","))
                                          .map(String::trim)
                                          .filter(s -> !s.isEmpty())
                                          .collect(Collectors.toList());
            log.trace("Found PBX prefixes: {}", prefixes);
            return prefixes;
        }
        log.trace("No PBX prefixes found for commLocationId: {}", communicationLocationId);
        return Collections.emptyList();
    }

    
    public Set<Long> getInternalTelephonyTypeIds() {
        return internalTelephonyTypeIds;
    }

    public boolean isInternalTelephonyType(Long telephonyTypeId) {
        return telephonyTypeId != null && internalTelephonyTypeIds.contains(telephonyTypeId);
    }

    
    public Map<String, Integer> getTelephonyTypeMinMax(Long telephonyTypeId, Long originCountryId) {
        log.debug("Fetching min/max length config for telephonyTypeId: {}, originCountryId: {}", telephonyTypeId, originCountryId);
        return configurationLookupService.findTelephonyTypeMinMaxConfig(telephonyTypeId, originCountryId);
    }

    
    public Optional<Operator> getOperatorInternal(Long telephonyTypeId, Long originCountryId) {
        log.debug("Fetching internal operator for telephonyTypeId: {}, originCountryId: {}", telephonyTypeId, originCountryId);
        return configurationLookupService.findOperatorByTelephonyTypeAndOrigin(telephonyTypeId, originCountryId);
    }

    
    public Optional<TelephonyType> getTelephonyTypeById(Long id) {
        return entityLookupService.findTelephonyTypeById(id);
    }

    
    public Optional<Operator> getOperatorById(Long id) {
        return entityLookupService.findOperatorById(id);
    }

    public static Set<Long> getInternalIpCallTypeIds() {
        return internalTelephonyTypeIds;
    }

    public static Long getDefaultInternalCallTypeId() {
        return defaultInternalCallTypeId;
    }

    
    public ExtensionLengthConfig getExtensionLengthConfig(Long commLocationId) {
        log.debug("Fetching extension length config for commLocationId: {}", commLocationId);
        Map<String, Integer> lengths = extensionLookupService.findExtensionMinMaxLength(commLocationId);
        int minLength = lengths.getOrDefault("min", DEFAULT_MIN_EXT_LENGTH);
        int maxLength = lengths.getOrDefault("max", DEFAULT_MAX_EXT_LENGTH);
        int maxExtensionValue = MAX_POSSIBLE_EXTENSION_VALUE;

        if (minLength <= 0) minLength = DEFAULT_MIN_EXT_LENGTH;
        if (maxLength <= 0 || maxLength < minLength) maxLength = Math.max(minLength, DEFAULT_MAX_EXT_LENGTH);

        if (maxLength > 0 && maxLength < String.valueOf(Integer.MAX_VALUE).length()) {
            try {
                 maxExtensionValue = Integer.parseInt("9".repeat(Math.max(0, maxLength)));
            } catch (NumberFormatException | OutOfMemoryError e) {
                 log.warn("Could not calculate max extension value for length {}, using default {}. Error: {}", maxLength, MAX_POSSIBLE_EXTENSION_VALUE, e.getMessage());
                 maxExtensionValue = MAX_POSSIBLE_EXTENSION_VALUE;
            }
        } else if (maxLength >= String.valueOf(Integer.MAX_VALUE).length()) {
            maxExtensionValue = MAX_POSSIBLE_EXTENSION_VALUE;
            log.warn("Extension maxLength {} is too large, capping max extension value at {}", maxLength, maxExtensionValue);
        }

        log.debug("Extension config for commLocationId {}: minLen={}, maxLen={}, maxVal={}", commLocationId, minLength, maxLength, maxExtensionValue);
        return new ExtensionLengthConfig(minLength, maxLength, maxExtensionValue);
    }

    public Set<String> getIgnoredAuthCodes() {
        return ignoredAuthCodes;
    }

    public int getMinCallDurationForBilling() {
        // This could be loaded from DB/properties
        // PHP: $min_tiempo = defineParamCliente('CAPTURAS_TIEMPOCERO', $link);
        //      if (!is_numeric($min_tiempo) || $min_tiempo < 0) { $min_tiempo = 0; }
        // For now, hardcoding to 0 (meaning any duration > 0 is billable if rate applies)
        return minCallDurationForBilling;
    }
}