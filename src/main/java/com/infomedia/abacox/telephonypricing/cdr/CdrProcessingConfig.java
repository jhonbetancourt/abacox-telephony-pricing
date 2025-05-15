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
            this.specialSyntaxExtensions = specialSyntaxExtensions != null ? Collections.unmodifiableSet(new HashSet<>(specialSyntaxExtensions)) : Collections.emptySet();
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
        // Cap length to avoid overflow for int, assuming extensions don't exceed 9 digits for this calculation
        int safeLength = Math.min(length, 9);

        if (isMin) {
            if (safeLength == 1) return 0; // Min 1-digit can be 0 (PHP logic for operadora)
            // Calculate 10^(length-1)
            return (int) Math.pow(10, safeLength - 1);
        } else {
            // Calculate (10^length) - 1
            return (int) (Math.pow(10, safeLength) - 1);
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

        if (finalMinNumeric > finalMaxNumeric && finalMaxNumeric > 0) { // if max is 0, min can be 0
             log.warn("Derived min numeric value {} is greater than max {}. Setting min to max.", finalMinNumeric, finalMaxNumeric);
             finalMinNumeric = finalMaxNumeric;
        } else if (finalMinNumeric > finalMaxNumeric && finalMaxNumeric == 0) {
            // This case can happen if maxDbLength was 0 or invalid, leading to finalMaxNumeric = 0
            // and minDbLength was also 0 or invalid, leading to finalMinNumeric (e.g. for length 1 -> 0)
            // or if minDbLength was 1 (->0) and maxDbLength was 0 (->0).
            // If min is 0 and max is 0, it's a valid state for single digit '0' extension.
        }
        
        finalMaxNumeric = Math.min(finalMaxNumeric, MAX_POSSIBLE_EXTENSION_VALUE);
        finalMinNumeric = Math.min(finalMinNumeric, finalMaxNumeric);


        Set<String> specialSyntaxExtensions = getSpecialSyntaxExtensions(commLocationId);

        log.debug("Extension config for commLocationId {}: minVal={}, maxVal={}, specialCount={}",
                commLocationId, finalMinNumeric, finalMaxNumeric, specialSyntaxExtensions.size());
        return new ExtensionLengthConfig(finalMinNumeric, finalMaxNumeric, specialSyntaxExtensions);
    }

    private Set<String> getSpecialSyntaxExtensions(Long commLocationId) {
        // In a real system, this would query a DB table or configuration source
        // specific to the commLocationId or globally.
        // Example: "SELECT extension_code FROM special_extensions WHERE comm_location_id = :commLocationId OR is_global = true"
        // For now, returning a hardcoded example set.
        log.trace("getSpecialSyntaxExtensions for commLocationId {} (currently returns example set)", commLocationId);
        // This should be dynamic based on PHP's `ObtenerExtensionesEspeciales`
        // For now, returning empty as per previous implementation.
        return Collections.emptySet();
    }


    public Set<String> getIgnoredAuthCodes() {
        return ignoredAuthCodes;
    }

    public int getMinCallDurationForBilling() {
        return minCallDurationForBilling;
    }

    public List<Long> getIncomingTelephonyTypeClassificationOrder() {
        return incomingTelephonyTypeClassificationOrder;
    }
}