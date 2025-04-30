package com.infomedia.abacox.telephonypricing.cdr;

import com.infomedia.abacox.telephonypricing.entity.CommunicationLocation; // Added
import com.infomedia.abacox.telephonypricing.entity.Indicator; // Added
import com.infomedia.abacox.telephonypricing.entity.Operator;
import com.infomedia.abacox.telephonypricing.entity.TelephonyType;
import com.infomedia.abacox.telephonypricing.repository.OperatorRepository;
import com.infomedia.abacox.telephonypricing.repository.TelephonyTypeRepository;
import jakarta.annotation.PostConstruct;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Log4j2
public class ConfigurationService {

    private final OperatorRepository operatorRepository;
    private final TelephonyTypeRepository telephonyTypeRepository;
    private final LookupService lookupService;

    // --- Constants ---
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
    public static final long TIPOTELE_ERRORES = 99L; // Example ID for errors

    private static final int DEFAULT_MIN_EXT_LENGTH = 2;
    private static final int DEFAULT_MAX_EXT_LENGTH = 7;
    public static final int MAX_POSSIBLE_EXTENSION_VALUE = 9999999;

    private Set<Long> internalTelephonyTypeIds;
    private Map<Long, Operator> internalOperators = new HashMap<>();

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
        internalTelephonyTypeIds = Set.of(
                TIPOTELE_INTERNA_IP, TIPOTELE_LOCAL_IP, TIPOTELE_NACIONAL_IP, TIPOTELE_INTERNACIONAL_IP
        );
        log.info("Initialized internal telephony type IDs: {}", internalTelephonyTypeIds);
    }

    @Cacheable("pbxPrefixes")
    public List<String> getPbxPrefixes(Long communicationLocationId) {
        log.debug("Fetching PBX prefixes for commLocationId: {}", communicationLocationId);
        Optional<String> prefixString = lookupService.findPbxPrefixByCommLocationId(communicationLocationId);
        if (prefixString.isPresent() && !prefixString.get().isEmpty()) {
            return Arrays.asList(prefixString.get().split(","));
        }
        return Collections.emptyList();
    }

    @Cacheable("internalTelephonyTypes")
    public Set<Long> getInternalTelephonyTypeIds() {
        return internalTelephonyTypeIds;
    }

    public boolean isInternalTelephonyType(Long telephonyTypeId) {
        return internalTelephonyTypeIds.contains(telephonyTypeId);
    }

    @Cacheable("telephonyTypeMinMax")
    public Map<String, Integer> getTelephonyTypeMinMax(Long telephonyTypeId, Long originCountryId) {
        log.debug("Fetching min/max for telephonyTypeId: {}, originCountryId: {}", telephonyTypeId, originCountryId);
        return lookupService.findTelephonyTypeMinMaxConfig(telephonyTypeId, originCountryId);
    }

    @Cacheable(value = "internalOperator", key = "#telephonyTypeId + '-' + #originCountryId")
    public Optional<Operator> getOperatorInternal(Long telephonyTypeId, Long originCountryId) {
        log.debug("Fetching internal operator for telephonyTypeId: {}, originCountryId: {}", telephonyTypeId, originCountryId);
        return lookupService.findOperatorByTelephonyTypeAndOrigin(telephonyTypeId, originCountryId);
    }

    @Cacheable("telephonyTypeById")
    public Optional<TelephonyType> getTelephonyTypeById(Long id) {
        return telephonyTypeRepository.findById(id);
    }

    @Cacheable("operatorById")
    public Optional<Operator> getOperatorById(Long id) {
        return operatorRepository.findById(id);
    }

    public Set<Long> getInternalCallTypeIds() {
        return Set.of(TIPOTELE_INTERNA_IP, TIPOTELE_LOCAL_IP, TIPOTELE_NACIONAL_IP, TIPOTELE_INTERNACIONAL_IP);
    }

    // Corrected call to lookupService
    @Cacheable(value = "extensionLengthConfig", key = "{#commLocationId}")
    public ExtensionLengthConfig getExtensionLengthConfig(Long commLocationId) {
        log.debug("Fetching extension length config for commLocationId: {}", commLocationId);
        // Origin country isn't strictly needed for length calculation based on PHP logic,
        // but pass commLocationId for context if queries need it.
        Map<String, Integer> lengths = lookupService.findExtensionMinMaxLength(commLocationId);
        int minLength = lengths.getOrDefault("min", DEFAULT_MIN_EXT_LENGTH);
        int maxLength = lengths.getOrDefault("max", DEFAULT_MAX_EXT_LENGTH);
        int maxExtensionValue = MAX_POSSIBLE_EXTENSION_VALUE;

        if (minLength <= 0) minLength = DEFAULT_MIN_EXT_LENGTH;
        if (maxLength <= 0 || maxLength < minLength) maxLength = Math.max(minLength, DEFAULT_MAX_EXT_LENGTH);

        if (maxLength > 0 && maxLength < String.valueOf(Integer.MAX_VALUE).length()) {
            try {
                 maxExtensionValue = Integer.parseInt("9".repeat(maxLength));
            } catch (NumberFormatException e) {
                 log.warn("Could not calculate max extension value for length {}, using default {}", maxLength, MAX_POSSIBLE_EXTENSION_VALUE);
                 maxExtensionValue = MAX_POSSIBLE_EXTENSION_VALUE;
            }
        }
        return new ExtensionLengthConfig(minLength, maxLength, maxExtensionValue);
    }
}