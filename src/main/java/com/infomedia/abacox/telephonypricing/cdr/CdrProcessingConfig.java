package com.infomedia.abacox.telephonypricing.cdr;

import com.infomedia.abacox.telephonypricing.entity.*;
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

    // Inject specific lookup services needed for config retrieval
    private final ConfigLookupService configLookupService;
    private final EntityLookupService entityLookupService;
    private final PrefixLookupService prefixLookupService; // Needed for getOperatorInternal

    // --- Constants based on PHP defines/usage ---
    public static final long TIPOTELE_LOCAL = 3L;
    public static final long TIPOTELE_NACIONAL = 4L;
    public static final long TIPOTELE_INTERNACIONAL = 5L;
    public static final long TIPOTELE_CELULAR = 2L;
    public static final long TIPOTELE_CELUFIJO = 1L;
    public static final long TIPOTELE_ESPECIALES = 11L; // Matches PHP _TIPOTELE_ESPECIALES
    public static final long TIPOTELE_LOCAL_EXT = 12L; // Matches PHP _TIPOTELE_LOCAL_EXT
    public static final long TIPOTELE_SATELITAL = 10L;
    public static final long TIPOTELE_INTERNA_IP = 7L; // Matches PHP _TIPOTELE_INTERNA_IP
    public static final long TIPOTELE_LOCAL_IP = 8L; // Matches PHP _TIPOTELE_LOCAL_IP
    public static final long TIPOTELE_NACIONAL_IP = 9L; // Matches PHP _TIPOTELE_NACIONAL_IP
    public static final long TIPOTELE_INTERNACIONAL_IP = 14L; // Matches PHP _TIPOTELE_INTERNAL_IP (assuming typo in PHP)
    public static final long TIPOTELE_SINCONSUMO = 16L; // Matches PHP _TIPOTELE_SINCONSUMO
    public static final long TIPOTELE_ERRORES = 99L; // Matches PHP _TIPOTELE_ERRORES (example)
    public static final long TIPOTELE_PAGO_REVERTIDO = 13L; // Added based on PHP comments
    public static final long TIPOTELE_SERVICIOS_VARIOS = 6L; // Added based on PHP comments

    // Default extension lengths if DB lookup fails or returns invalid data
    public static final int DEFAULT_MIN_EXT_LENGTH = 2;
    public static final int DEFAULT_MAX_EXT_LENGTH = 7;
    // Absolute max value based on typical internal numbering plans (PHP: _ACUMTOTAL_MAXEXT)
    public static final int MAX_POSSIBLE_EXTENSION_VALUE = 9999999; // 7 digits

    private static Set<Long> internalTelephonyTypeIds;
    private static Long defaultInternalCallTypeId;

    @Getter
    public static class ExtensionLengthConfig {
        // ... (keep constructor and fields)
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
    }

    public List<String> getPbxPrefixes(Long communicationLocationId) {
        log.debug("Fetching PBX prefixes for commLocationId: {}", communicationLocationId);
        Optional<String> prefixStringOpt = configLookupService.findPbxPrefixByCommLocationId(communicationLocationId);
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

    public static Set<Long> getInternalIpCallTypeIds() {
        return internalTelephonyTypeIds;
    }

    public static Long getDefaultInternalCallTypeId() {
        return defaultInternalCallTypeId;
    }

    public Map<String, Integer> getTelephonyTypeMinMax(Long telephonyTypeId, Long originCountryId) {
        log.debug("Fetching min/max length config for telephonyTypeId: {}, originCountryId: {}", telephonyTypeId, originCountryId);
        // Use ConfigLookupService
        return configLookupService.findTelephonyTypeMinMaxConfig(telephonyTypeId, originCountryId);
    }

    public Optional<Operator> getOperatorInternal(Long telephonyTypeId, Long originCountryId) {
        log.debug("Fetching internal operator for telephonyTypeId: {}, originCountryId: {}", telephonyTypeId, originCountryId);
        // This now relies on PrefixLookupService finding a prefix and its operator
        // Find *any* prefix for this type/origin to get the associated operator
        List<Map<String, Object>> prefixes = prefixLookupService.findPrefixesByNumber("", originCountryId); // Use empty number, filter by type
        return prefixes.stream()
                .filter(p -> telephonyTypeId.equals(p.get("telephony_type_id")))
                .findFirst()
                .flatMap(p -> entityLookupService.findOperatorById((Long) p.get("operator_id")));
    }

    public Optional<TelephonyType> getTelephonyTypeById(Long id) {
        // Use EntityLookupService
        return entityLookupService.findTelephonyTypeById(id);
    }

    public Optional<Operator> getOperatorById(Long id) {
        // Use EntityLookupService
        return entityLookupService.findOperatorById(id);
    }

    public ExtensionLengthConfig getExtensionLengthConfig(Long commLocationId) {
        log.debug("Fetching extension length config for commLocationId: {}", commLocationId);
        // Use ConfigLookupService
        Map<String, Integer> lengths = configLookupService.findExtensionMinMaxLength(commLocationId);
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
        } else {
            maxExtensionValue = MAX_POSSIBLE_EXTENSION_VALUE;
            log.warn("Extension maxLength {} is too large, capping max extension value at {}", maxLength, maxExtensionValue);
        }

        log.debug("Extension config for commLocationId {}: minLen={}, maxLen={}, maxVal={}", commLocationId, minLength, maxLength, maxExtensionValue);
        return new ExtensionLengthConfig(minLength, maxLength, maxExtensionValue);
    }
}