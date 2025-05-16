// FILE: com/infomedia/abacox/telephonypricing/cdr/CdrProcessingConfig.java
package com.infomedia.abacox.telephonypricing.cdr;

import com.infomedia.abacox.telephonypricing.entity.Operator;
import com.infomedia.abacox.telephonypricing.entity.TelephonyType;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.stereotype.Service;

import java.util.*;

@Service
@Getter
@Log4j2
@RequiredArgsConstructor
public class CdrProcessingConfig {

    private final ConfigurationLookupService configurationLookupService;
    private final EntityLookupService entityLookupService;

    // Telephony Type IDs (based on PHP constants)
    public static final long TIPOTELE_CELUFIJO = 1L;
    public static final long TIPOTELE_CELULAR = 2L;
    public static final long TIPOTELE_LOCAL = 3L;
    public static final long TIPOTELE_NACIONAL = 4L;
    public static final long TIPOTELE_INTERNACIONAL = 5L;
    public static final long TIPOTELE_SERVICIOS_VARIOS = 6L; // Includes 01800, 01900
    public static final long TIPOTELE_INTERNA_IP = 7L;
    public static final long TIPOTELE_LOCAL_IP = 8L;
    public static final long TIPOTELE_NACIONAL_IP = 9L;
    public static final long TIPOTELE_SATELITAL = 10L;
    public static final long TIPOTELE_ESPECIALES = 11L; // 11x numbers
    public static final long TIPOTELE_LOCAL_EXT = 12L;
    public static final long TIPOTELE_PAGO_REVERTIDO = 13L;
    public static final long TIPOTELE_INTERNACIONAL_IP = 16L;
    public static final long TIPOTELE_SINCONSUMO = 98L;
    public static final long TIPOTELE_ERRORES = 99L;

    // Special Country IDs
    public static final long COLOMBIA_ORIGIN_COUNTRY_ID = 1L; // Assuming 1 is Colombia

    // Special Prefix IDs (if needed for reference, like for national calls without explicit operator prefix)
    public static final long NATIONAL_REFERENCE_PREFIX_ID = 7000012L; // Example, from PHP logic for finding national series details

    // Colombian specific prefixes
    public static final String COLOMBIAN_MOBILE_INTERNAL_PREFIX = "03";


    private static final Set<Long> INTERNAL_IP_CALL_TYPES = Set.of(
            TIPOTELE_INTERNA_IP, TIPOTELE_LOCAL_IP, TIPOTELE_NACIONAL_IP, TIPOTELE_INTERNACIONAL_IP
    );

    private static final List<Long> INCOMING_CLASSIFICATION_ORDER = List.of(
            TIPOTELE_CELULAR,
            TIPOTELE_NACIONAL,
            TIPOTELE_INTERNACIONAL,
            TIPOTELE_SATELITAL,
            TIPOTELE_LOCAL, // Local is often a fallback
            TIPOTELE_SERVICIOS_VARIOS // Less common for incoming direct classification
    );

    @Getter
    public static class ExtensionLengthConfig {
        private final int minLength;
        private final int maxLength;
        private final Set<String> specialLengthExtensions; // For extensions like "0", "*123"

        public ExtensionLengthConfig(int min, int max, Set<String> special) {
            this.minLength = min;
            this.maxLength = max;
            this.specialLengthExtensions = Collections.unmodifiableSet(new HashSet<>(special));
        }
    }

    // Cache for extension length configs to avoid repeated DB lookups
    private final Map<Long, ExtensionLengthConfig> extensionLengthConfigCache = new HashMap<>();
    private final Map<Long, List<String>> pbxPrefixCache = new HashMap<>();
    private final Map<Long, Optional<Operator>> internalOperatorCache = new HashMap<>();
     private final Map<Long, Optional<TelephonyType>> telephonyTypeCache = new HashMap<>();
     private final Map<Long, Optional<Operator>> operatorCache = new HashMap<>();
     private final Map<String, Map<String, Integer>> telephonyTypeMinMaxCache = new HashMap<>();


    public List<String> getPbxPrefixes(Long commLocationId) {
        return pbxPrefixCache.computeIfAbsent(commLocationId, id ->
            configurationLookupService.findPbxPrefixByCommLocationId(id)
                .map(s -> Arrays.asList(s.split(",")))
                .orElse(Collections.emptyList())
        );
    }

    public ExtensionLengthConfig getExtensionLengthConfig(Long commLocationId) {
        // For now, using a global config. If it needs to be per commLocationId,
        // this method would fetch/cache it based on that ID.
        // PHP's $_LIM_INTERNAS seems global after initialization.
        return extensionLengthConfigCache.computeIfAbsent(0L, id -> { // Using 0L as key for global config
            // These would be fetched from a configuration source (DB, properties)
            // For now, hardcoding typical values based on PHP's _ACUMTOTAL_MAXEXT
            int min = 1; // Smallest extension
            int max = 7; // Max length of typical extension (before _ACUMTOTAL_MAXEXT)
                         // _ACUMTOTAL_MAXEXT was 1,000,000, so length 7.
            Set<String> special = Set.of("0"); // Example for operator
            log.info("Initialized global extension length config: min={}, max={}, special={}", min, max, special);
            return new ExtensionLengthConfig(min, max, special);
        });
    }

    public Set<String> getIgnoredAuthCodes() {
        // These would be fetched from a configuration source
        return Set.of("Invalid Authorization Code", "Invalid Authorization Level");
    }

    public int getMinCallDurationForBilling() {
        // This would be fetched from a configuration source (e.g. TIPOTELECFG_MIN for SINCONSUMO type)
        // PHP logic for SINCONSUMO is `tiempo <= 0`. So, 0 means it's not billable.
        return 0; // seconds
    }

    public static Set<Long> getInternalIpCallTypeIds() {
        return INTERNAL_IP_CALL_TYPES;
    }

    public static Long getDefaultInternalCallTypeId() {
        return TIPOTELE_INTERNA_IP; // Or another configured default
    }

    public List<Long> getIncomingTelephonyTypeClassificationOrder() {
        return INCOMING_CLASSIFICATION_ORDER;
    }

    public Optional<Operator> getOperatorInternal(Long telephonyTypeId, Long originCountryId) {
        if (telephonyTypeId == null || originCountryId == null) return Optional.empty();
        Long cacheKey = telephonyTypeId * 100000 + originCountryId; // Simple composite key
        return internalOperatorCache.computeIfAbsent(cacheKey, k ->
            configurationLookupService.findOperatorByTelephonyTypeAndOrigin(telephonyTypeId, originCountryId)
        );
    }

    public Optional<TelephonyType> getTelephonyTypeById(Long id) {
        if (id == null) return Optional.empty();
        return telephonyTypeCache.computeIfAbsent(id, entityLookupService::findTelephonyTypeById);
    }
     public Optional<Operator> getOperatorById(Long id) {
        if (id == null) return Optional.empty();
        return operatorCache.computeIfAbsent(id, entityLookupService::findOperatorById);
    }

    public Map<String, Integer> getTelephonyTypeMinMax(Long telephonyTypeId, Long originCountryId) {
        if (telephonyTypeId == null || originCountryId == null) {
            Map<String, Integer> defaultConfig = new HashMap<>();
            defaultConfig.put("min", 0);
            defaultConfig.put("max", 0);
            return defaultConfig;
        }
        String cacheKey = telephonyTypeId + "_" + originCountryId;
        return telephonyTypeMinMaxCache.computeIfAbsent(cacheKey, k ->
            configurationLookupService.findTelephonyTypeMinMaxConfig(telephonyTypeId, originCountryId)
        );
    }

    public String mapCompanyToNationalOperatorPrefix(String companyName) {
        // This mapping would ideally come from a configuration table or properties
        // Based on PHP's _esNacional function
        if (companyName == null) return "";
        String upperCompany = companyName.toUpperCase();
        if (upperCompany.contains("TELMEX") || upperCompany.contains("CLARO")) { // CLARO HOGAR FIJO
            return "0456"; // Or whatever the current Claro prefix is for national calls
        } else if (upperCompany.contains("COLOMBIA TELECOMUNICACIONES")) { // Telefonica/Movistar Fijo
            return "09";
        } else if (upperCompany.contains("UNE EPM") || upperCompany.contains("UNE TELCO")) { // UNE
            return "05"; // Or "050" if it's three digits
        } else if (upperCompany.contains("EMPRESA DE TELECOMUNICACIONES DE BOGOTÁ") || upperCompany.contains("ETB")) { // ETB
            return "07";
        }
        return ""; // Default or no mapping
    }

}