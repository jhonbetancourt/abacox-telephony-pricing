package com.infomedia.abacox.telephonypricing.cdr;

import com.infomedia.abacox.telephonypricing.entity.*;
import com.infomedia.abacox.telephonypricing.repository.OperatorRepository;
import com.infomedia.abacox.telephonypricing.repository.TelephonyTypeRepository;
import jakarta.annotation.PostConstruct;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Log4j2
public class ConfigurationService {

    private final OperatorRepository operatorRepository;
    private final TelephonyTypeRepository telephonyTypeRepository;
    private final LookupService lookupService;

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

    private Set<Long> internalTelephonyTypeIds;

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


    @PostConstruct
    private void initializeInternalTypes() {
        // Define which telephony types are considered 'internal' based on PHP logic/constants
        internalTelephonyTypeIds = Set.of(
                TIPOTELE_INTERNA_IP, TIPOTELE_LOCAL_IP, TIPOTELE_NACIONAL_IP, TIPOTELE_INTERNACIONAL_IP
        );
        log.info("Initialized internal telephony type IDs: {}", internalTelephonyTypeIds);
    }

    @Cacheable("pbxPrefixes")
    public List<String> getPbxPrefixes(Long communicationLocationId) {
        log.debug("Fetching PBX prefixes for commLocationId: {}", communicationLocationId);
        Optional<String> prefixStringOpt = lookupService.findPbxPrefixByCommLocationId(communicationLocationId);
        if (prefixStringOpt.isPresent() && !prefixStringOpt.get().isEmpty()) {
            // Split the comma-separated string into a list
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

    @Cacheable("internalTelephonyTypes")
    public Set<Long> getInternalTelephonyTypeIds() {
        // Return the set initialized in @PostConstruct
        return internalTelephonyTypeIds;
    }

    public boolean isInternalTelephonyType(Long telephonyTypeId) {
        return telephonyTypeId != null && internalTelephonyTypeIds.contains(telephonyTypeId);
    }

    @Cacheable("telephonyTypeMinMax")
    public Map<String, Integer> getTelephonyTypeMinMax(Long telephonyTypeId, Long originCountryId) {
        log.debug("Fetching min/max length config for telephonyTypeId: {}, originCountryId: {}", telephonyTypeId, originCountryId);
        return lookupService.findTelephonyTypeMinMaxConfig(telephonyTypeId, originCountryId);
    }

    @Cacheable(value = "internalOperator", key = "#telephonyTypeId + '-' + #originCountryId")
    public Optional<Operator> getOperatorInternal(Long telephonyTypeId, Long originCountryId) {
        log.debug("Fetching internal operator for telephonyTypeId: {}, originCountryId: {}", telephonyTypeId, originCountryId);
        // This relies on the LookupService to find the operator associated with internal types
        return lookupService.findOperatorByTelephonyTypeAndOrigin(telephonyTypeId, originCountryId);
    }

    @Cacheable("telephonyTypeById")
    public Optional<TelephonyType> getTelephonyTypeById(Long id) {
        return lookupService.findTelephonyTypeById(id);
    }

    @Cacheable("operatorById")
    public Optional<Operator> getOperatorById(Long id) {
        return lookupService.findOperatorById(id);
    }

    // Renamed from getInternalCallTypeIds for clarity
    public Set<Long> getInternalIpCallTypeIds() {
        return internalTelephonyTypeIds; // Return the initialized set
    }

    // Corrected call to lookupService
    @Cacheable(value = "extensionLengthConfig", key = "{#commLocationId}")
    public ExtensionLengthConfig getExtensionLengthConfig(Long commLocationId) {
        log.debug("Fetching extension length config for commLocationId: {}", commLocationId);
        // Get min/max length based on employees and ranges for the specific location
        Map<String, Integer> lengths = lookupService.findExtensionMinMaxLength(commLocationId);
        int minLength = lengths.getOrDefault("min", DEFAULT_MIN_EXT_LENGTH);
        int maxLength = lengths.getOrDefault("max", DEFAULT_MAX_EXT_LENGTH);
        int maxExtensionValue = MAX_POSSIBLE_EXTENSION_VALUE; // Default max value

        // Ensure min/max are logical and within bounds
        if (minLength <= 0) minLength = DEFAULT_MIN_EXT_LENGTH;
        if (maxLength <= 0 || maxLength < minLength) maxLength = Math.max(minLength, DEFAULT_MAX_EXT_LENGTH);

        // Calculate the maximum numeric value based on the max length
        if (maxLength > 0 && maxLength < String.valueOf(Integer.MAX_VALUE).length()) {
            try {
                 // Create a string of '9's with the maxLength
                 maxExtensionValue = Integer.parseInt("9".repeat(maxLength));
            } catch (NumberFormatException | OutOfMemoryError e) { // Added OutOfMemoryError just in case maxLength is huge
                 log.warn("Could not calculate max extension value for length {}, using default {}. Error: {}", maxLength, MAX_POSSIBLE_EXTENSION_VALUE, e.getMessage());
                 maxExtensionValue = MAX_POSSIBLE_EXTENSION_VALUE;
            }
        }
        log.debug("Extension config for commLocationId {}: minLen={}, maxLen={}, maxVal={}", commLocationId, minLength, maxLength, maxExtensionValue);
        return new ExtensionLengthConfig(minLength, maxLength, maxExtensionValue);
    }
}