// FILE: com/infomedia/abacox/telephonypricing/cdr/CdrProcessingConfig.java
package com.infomedia.abacox.telephonypricing.cdr;

import com.infomedia.abacox.telephonypricing.entity.Operator;
import com.infomedia.abacox.telephonypricing.entity.TelephonyType;
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

    // Default values if DB lookup fails or returns invalid ranges
    public static final int DEFAULT_MIN_EXT_LENGTH_FALLBACK = 2;
    public static final int DEFAULT_MAX_EXT_LENGTH_FALLBACK = 7;
    public static final int MAX_POSSIBLE_EXTENSION_VALUE = 9999999; // Max value for a 7-digit extension

    public static final Long COLOMBIA_ORIGIN_COUNTRY_ID = 1L;
    public static final Long NATIONAL_REFERENCE_PREFIX_ID = 7000012L;


    private static Set<Long> internalTelephonyTypeIds;
    private static Long defaultInternalCallTypeId;
    private static Set<String> ignoredAuthCodes;
    private static int minCallDurationForBilling = 0; // Default to 0, can be configured

    private static List<Long> incomingTelephonyTypeClassificationOrder;


    private final ConfigurationLookupService configurationLookupService;
    private final EntityLookupService entityLookupService;
    private final ExtensionLookupService extensionLookupService;

    @Getter
    public static class ExtensionLengthConfig {
        private final int minNumericValue; // Derived from min length
        private final int maxNumericValue; // Derived from max length
        private final Set<String> specialSyntaxExtensions; // PHP's $_LIM_INTERNAS['full']

        public ExtensionLengthConfig(int minNumericValue, int maxNumericValue, Set<String> specialSyntaxExtensions) {
            this.minNumericValue = minNumericValue;
            this.maxNumericValue = maxNumericValue;
            this.specialSyntaxExtensions = specialSyntaxExtensions != null ? specialSyntaxExtensions : Collections.emptySet();
        }
    }

    private static final Map<String, String> COMPANY_TO_NATIONAL_OPERATOR_PREFIX_MAP;

    static {
        internalTelephonyTypeIds = Set.of(
                TIPOTELE_INTERNA_IP, TIPOTELE_LOCAL_IP, TIPOTELE_NACIONAL_IP, TIPOTELE_INTERNACIONAL_IP
        );
        defaultInternalCallTypeId = TIPOTELE_INTERNA_IP;
        ignoredAuthCodes = Set.of("Invalid Authorization Code", "Invalid Authorization Level");

        incomingTelephonyTypeClassificationOrder = List.of(
                TIPOTELE_INTERNACIONAL,
                TIPOTELE_SATELITAL,
                TIPOTELE_CELULAR,
                TIPOTELE_NACIONAL,
                TIPOTELE_LOCAL_EXT,
                TIPOTELE_LOCAL
        );

        COMPANY_TO_NATIONAL_OPERATOR_PREFIX_MAP = new HashMap<>();
        COMPANY_TO_NATIONAL_OPERATOR_PREFIX_MAP.put("TELMEX TELECOMUNICACIONES S.A. ESP", "0456");
        COMPANY_TO_NATIONAL_OPERATOR_PREFIX_MAP.put("COLOMBIA TELECOMUNICACIONES S.A. ESP", "09");
        COMPANY_TO_NATIONAL_OPERATOR_PREFIX_MAP.put("UNE EPM TELECOMUNICACIONES S.A. E.S.P. - UNE EPM TELCO S.A.", "05");
        COMPANY_TO_NATIONAL_OPERATOR_PREFIX_MAP.put("EMPRESA DE TELECOMUNICACIONES DE BOGOTÁ S.A. ESP.", "07");
    }

    public String mapCompanyToNationalOperatorPrefix(String companyName) {
        if (companyName == null || companyName.trim().isEmpty()) {
            return "";
        }
        for (Map.Entry<String, String> entry : COMPANY_TO_NATIONAL_OPERATOR_PREFIX_MAP.entrySet()) {
            // Case-insensitive comparison and check if the companyName *contains* the key,
            // as DB values might have extra details.
            if (companyName.toUpperCase().contains(entry.getKey().toUpperCase())) {
                return entry.getValue();
            }
        }
        return "";
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

    private int deriveNumericValueFromLength(int length, boolean isMin) {
        if (length <= 0) return 0;
        if (length > 9) length = 9; // Cap length to avoid overflow for int
        if (isMin) {
            if (length == 1) return 0; // Min 1-digit can be 0 (PHP logic for operadora)
            return Integer.parseInt("1" + "0".repeat(Math.max(0, length - 1)));
        } else {
            return Integer.parseInt("9".repeat(Math.max(0, length)));
        }
    }

    public ExtensionLengthConfig getExtensionLengthConfig(Long commLocationId) {
        log.debug("Fetching extension length config for commLocationId: {}", commLocationId);
        Map<String, Integer> dbLengths = extensionLookupService.findExtensionMinMaxLength(commLocationId);

        int minDbLength = dbLengths.getOrDefault("min", 0);
        int maxDbLength = dbLengths.getOrDefault("max", 0);

        int finalMinNumeric, finalMaxNumeric;

        if (minDbLength > 0) {
            finalMinNumeric = deriveNumericValueFromLength(minDbLength, true);
        } else {
            finalMinNumeric = deriveNumericValueFromLength(DEFAULT_MIN_EXT_LENGTH_FALLBACK, true);
        }

        if (maxDbLength > 0) {
            finalMaxNumeric = deriveNumericValueFromLength(maxDbLength, false);
        } else {
            finalMaxNumeric = deriveNumericValueFromLength(DEFAULT_MAX_EXT_LENGTH_FALLBACK, false);
        }
        
        // Ensure min is not greater than max, and cap at MAX_POSSIBLE_EXTENSION_VALUE
        if (finalMinNumeric > finalMaxNumeric) finalMinNumeric = finalMaxNumeric;
        finalMaxNumeric = Math.min(finalMaxNumeric, MAX_POSSIBLE_EXTENSION_VALUE);
        finalMinNumeric = Math.min(finalMinNumeric, finalMaxNumeric);


        // Placeholder for special syntax extensions (PHP's $_LIM_INTERNAS['full'])
        // This would typically be loaded from a configuration or a specific DB query
        // if there are extensions like "0", "*123", "#456" that are considered valid extensions
        // but don't fit the numeric min/max length criteria.
        Set<String> specialSyntaxExtensions = getSpecialSyntaxExtensions(commLocationId);

        log.debug("Extension config for commLocationId {}: minVal={}, maxVal={}, specialCount={}",
                commLocationId, finalMinNumeric, finalMaxNumeric, specialSyntaxExtensions.size());
        return new ExtensionLengthConfig(finalMinNumeric, finalMaxNumeric, specialSyntaxExtensions);
    }

    // Placeholder: Implement this method to fetch extensions like "0", "*100", etc.
    // This would be equivalent to PHP's `ObtenerExtensionesEspeciales`
    private Set<String> getSpecialSyntaxExtensions(Long commLocationId) {
        // Example: Query a table or use a fixed list for extensions that are valid
        // but might not be purely numeric or within the standard length-derived numeric range.
        // For now, returning an empty set.
        log.trace("getSpecialSyntaxExtensions for commLocationId {} (currently returns empty set)", commLocationId);
        return Collections.emptySet();
    }


    public Set<String> getIgnoredAuthCodes() {
        return ignoredAuthCodes;
    }

    public int getMinCallDurationForBilling() {
        // This could be fetched from a dynamic configuration source if needed
        return minCallDurationForBilling;
    }

    public List<Long> getIncomingTelephonyTypeClassificationOrder() {
        return incomingTelephonyTypeClassificationOrder;
    }
}