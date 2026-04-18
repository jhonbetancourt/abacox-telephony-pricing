package com.infomedia.abacox.telephonypricing.component.cdrprocessing;

import com.infomedia.abacox.telephonypricing.db.entity.CommunicationLocation;
import com.infomedia.abacox.telephonypricing.multitenancy.TenantContext;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.Tuple;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Service
@Log4j2
@RequiredArgsConstructor
public class PrefixLookupService {

    @PersistenceContext
    private EntityManager entityManager;
    private final PhoneNumberTransformationService phoneNumberTransformationService;
    private final TelephonyTypeLookupService telephonyTypeLookupService;

    // CACHE: (tenant, countryId) -> List of all Prefixes. Schema-per-tenant means
    // the same countryId across tenants resolves to different prefix rows, so the
    // cache key MUST include the tenant identifier.
    private final Map<String, List<PrefixInfo>> prefixCache = new ConcurrentHashMap<>();
    private final Map<String, Instant> cacheLastUpdated = new ConcurrentHashMap<>();
    private static final long CACHE_TTL_SECONDS = 1800; // 30 Minutes

    private static String cacheKey(Long countryId) {
        String tenant = TenantContext.getTenant();
        return (tenant != null ? tenant : "public") + ":" + countryId;
    }

    @Transactional(readOnly = true)
    public List<PrefixInfo> findMatchingPrefixes(String dialedNumber,
                                                 CommunicationLocation commLocation,
                                                 boolean isTrunkCall,
                                                 List<Long> trunkTelephonyTypeIds) {
        
        // 1. Transform Number
        String numberForLookup = dialedNumber;
        Long hintedTelephonyTypeId = null;
        
        if (commLocation != null && commLocation.getIndicator() != null &&
            commLocation.getIndicator().getOriginCountryId() != null) {
            TransformationResult res = phoneNumberTransformationService.transformForPrefixLookup(dialedNumber, commLocation);
            log.info("TRACE_PREFIX: input dialedNumber='{}' isTrunk={} transformResult: transformed={} transformedNumber='{}' hint={}",
                    dialedNumber, isTrunkCall, res.isTransformed(), res.getTransformedNumber(), res.getNewTelephonyTypeId());
            if (res.isTransformed()) {
                numberForLookup = res.getTransformedNumber();
                hintedTelephonyTypeId = res.getNewTelephonyTypeId();
            }
        }
        final String finalNumber = numberForLookup;
        log.info("TRACE_PREFIX: finalNumber='{}' hintedType={}", finalNumber, hintedTelephonyTypeId);
        Long countryId = commLocation.getIndicator().getOriginCountryId();

        // 2. Get All Prefixes for Country from Cache (or load DB)
        List<PrefixInfo> countryPrefixes = getPrefixesForCountry(countryId);

        // 3. In-Memory Matching
        List<PrefixInfo> matchedPrefixes = new ArrayList<>();
        
        if (isTrunkCall && trunkTelephonyTypeIds != null && !trunkTelephonyTypeIds.isEmpty()) {
             // Trunk Call: Filter by Allowed Trunk Types, ignore code matching strictness
             matchedPrefixes = countryPrefixes.stream()
                 .filter(p -> trunkTelephonyTypeIds.contains(p.getTelephonyTypeId()))
                 .collect(Collectors.toList());
        } else {
             // Standard Call: Match using startsWith
             String bestMatchPrefixCode = null;
             
             // Since list is sorted by Length DESC in loader, the first match is the longest match
             for (PrefixInfo pi : countryPrefixes) {
                 if (pi.getPrefixCode() != null && !pi.getPrefixCode().isEmpty() && finalNumber.startsWith(pi.getPrefixCode())) {
                     bestMatchPrefixCode = pi.getPrefixCode();
                     break;
                 }
             }
             
             if (bestMatchPrefixCode != null) {
                 final String match = bestMatchPrefixCode;
                 matchedPrefixes = countryPrefixes.stream()
                     .filter(pi -> match.equals(pi.getPrefixCode()))
                     .collect(Collectors.toList());
             }
        }

        // 4. Fallback Logic: Add Local if not present and length is valid
        if (!isTrunkCall) {
            boolean hasLocal = matchedPrefixes.stream()
                .anyMatch(pi -> pi.getTelephonyTypeId().equals(TelephonyTypeEnum.LOCAL.getValue()));
            
            if (!hasLocal) {
                List<PrefixInfo> finalMatchedPrefixes = matchedPrefixes;
                countryPrefixes.stream()
                    .filter(pi -> pi.getTelephonyTypeId().equals(TelephonyTypeEnum.LOCAL.getValue()) &&
                                 (pi.getPrefixCode() == null || pi.getPrefixCode().isEmpty() || finalNumber.startsWith(pi.getPrefixCode())) &&
                                 finalNumber.length() >= (pi.getTelephonyTypeMinLength() != null ? pi.getTelephonyTypeMinLength() : 0))
                    .forEach(pi -> {
                        if (finalMatchedPrefixes.stream().noneMatch(mp -> mp.getPrefixId().equals(pi.getPrefixId()))) {
                            finalMatchedPrefixes.add(pi);
                        }
                    });
            }
        }

        // 5. Hint Optimization
        if (hintedTelephonyTypeId != null && !matchedPrefixes.isEmpty()) {
            Long hint = hintedTelephonyTypeId;
            List<PrefixInfo> hintedMatches = matchedPrefixes.stream()
                .filter(pi -> pi.getTelephonyTypeId().equals(hint))
                .collect(Collectors.toList());
            if (!hintedMatches.isEmpty()) matchedPrefixes = hintedMatches;
        }

        // 6. Sort results for logic priority
        matchedPrefixes.sort(Comparator
                .comparing((PrefixInfo pi) -> pi.getPrefixCode() != null ? pi.getPrefixCode().length() : 0, Comparator.reverseOrder())
                .thenComparing((PrefixInfo pi) -> pi.getTelephonyTypeMinLength() != null ? pi.getTelephonyTypeMinLength() : 0));

        if (log.isInfoEnabled()) {
            StringBuilder sb = new StringBuilder();
            for (PrefixInfo pi : matchedPrefixes) {
                sb.append("[id=").append(pi.getPrefixId())
                  .append(" code='").append(pi.getPrefixCode())
                  .append("' type=").append(pi.getTelephonyTypeId())
                  .append(" op=").append(pi.getOperatorId())
                  .append("] ");
            }
            log.info("TRACE_PREFIX: matchedPrefixes ({} total) -> {}", matchedPrefixes.size(), sb.toString().trim());
        }
        return matchedPrefixes;
    }

    private List<PrefixInfo> getPrefixesForCountry(Long countryId) {
        String key = cacheKey(countryId);
        Instant lastUpdate = cacheLastUpdated.get(key);
        if (lastUpdate != null && !lastUpdate.isBefore(Instant.now().minusSeconds(CACHE_TTL_SECONDS))) {
            return prefixCache.getOrDefault(key, Collections.emptyList());
        }
        // Atomic refresh via ConcurrentHashMap; only one thread per (tenant, countryId) executes the
        // loader, other threads block on that specific key (not a global monitor).
        return prefixCache.compute(key, (k, existing) -> {
            Instant currentUpdate = cacheLastUpdated.get(k);
            if (existing != null && currentUpdate != null &&
                    !currentUpdate.isBefore(Instant.now().minusSeconds(CACHE_TTL_SECONDS))) {
                return existing;
            }
            log.debug("Reloading Prefix Cache for key: {}", k);
            List<PrefixInfo> loaded = loadPrefixesFromDb(countryId);
            cacheLastUpdated.put(k, Instant.now());
            return loaded;
        });
    }

    private List<PrefixInfo> loadPrefixesFromDb(Long originCountryId) {
        // Flat-select everything needed for PrefixInfo so we avoid a per-row entityManager.find(Prefix.class, id).
        String queryStr = "SELECT p.id as prefix_id, p.code as prefix_code, p.telephony_type_id as tt_id, " +
                "tt.name as tt_name, p.operator_id as op_id, o.name as op_name, p.band_ok as band_ok, " +
                "ttc.min_value as ttc_min, ttc.max_value as ttc_max, " +
                "(SELECT COUNT(*) FROM band b WHERE b.prefix_id = p.id AND b.active = true) as bands_count " +
                "FROM prefix p " +
                "JOIN operator o ON p.operator_id = o.id " +
                "JOIN telephony_type tt ON p.telephony_type_id = tt.id " +
                "LEFT JOIN telephony_type_config ttc ON tt.id = ttc.telephony_type_id AND ttc.origin_country_id = :originCountryId AND ttc.active = true " +
                "WHERE p.active = true AND o.active = true AND tt.active = true AND o.origin_country_id = :originCountryId " +
                "AND p.telephony_type_id != :specialServicesTypeId " +
                "ORDER BY LENGTH(p.code) DESC, ttc.min_value DESC, p.telephony_type_id";

        jakarta.persistence.Query nativeQuery = entityManager.createNativeQuery(queryStr, Tuple.class);
        nativeQuery.setParameter("originCountryId", originCountryId);
        nativeQuery.setParameter("specialServicesTypeId", TelephonyTypeEnum.SPECIAL_SERVICES.getValue());

        List<Tuple> results = nativeQuery.getResultList();
        return results.stream().map(tuple -> PrefixInfo.fromFlat(
                tuple.get("prefix_id", Number.class).longValue(),
                tuple.get("prefix_code", String.class),
                tuple.get("tt_id", Number.class).longValue(),
                tuple.get("tt_name", String.class),
                tuple.get("op_id", Number.class).longValue(),
                tuple.get("op_name", String.class),
                tuple.get("ttc_min", Number.class) != null ? tuple.get("ttc_min", Number.class).intValue() : null,
                tuple.get("ttc_max", Number.class) != null ? tuple.get("ttc_max", Number.class).intValue() : null,
                Boolean.TRUE.equals(tuple.get("band_ok", Boolean.class)),
                tuple.get("bands_count", Number.class).intValue()
        )).collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public Map<String, Long> getInternalTelephonyTypePrefixes(Long originCountryId) {
        // Caching this simple map is also recommended, but for now reusing the DB structure. 
        // Can be added similarly if needed.
        List<Long> internalTypeIds = telephonyTypeLookupService.getInternalTypeIds();
        if (internalTypeIds.isEmpty()) return Collections.emptyMap();

        String queryStr = "SELECT p.code, p.telephony_type_id " +
                          "FROM prefix p JOIN operator o ON p.operator_id = o.id " +
                          "WHERE p.active = true AND o.active = true AND o.origin_country_id = :originCountryId " +
                          "AND p.telephony_type_id IN (:internalTypeIds) AND p.code IS NOT NULL AND p.code != '' " +
                          "ORDER BY LENGTH(p.code) DESC, p.code DESC";

        jakarta.persistence.Query nativeQuery = entityManager.createNativeQuery(queryStr, Tuple.class);
        nativeQuery.setParameter("originCountryId", originCountryId);
        nativeQuery.setParameter("internalTypeIds", internalTypeIds);

        List<Tuple> results = nativeQuery.getResultList();
        Map<String, Long> internalPrefixMap = new TreeMap<>(Collections.reverseOrder());

        for (Tuple row : results) {
            internalPrefixMap.put(row.get("code", String.class), row.get("telephony_type_id", Number.class).longValue());
        }
        return internalPrefixMap;
    }
}