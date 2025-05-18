package com.infomedia.abacox.telephonypricing.cdr;

import com.infomedia.abacox.telephonypricing.entity.Prefix;
import com.infomedia.abacox.telephonypricing.entity.TelephonyTypeConfig;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.Tuple;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

@Service
@Log4j2
public class PrefixLookupService {

    @PersistenceContext
    private EntityManager entityManager;

    @Transactional(readOnly = true)
    public List<PrefixInfo> findMatchingPrefixes(String dialedNumber, Long originCountryId, boolean isTrunkCall, List<Long> trunkTelephonyTypeIds) {
        // PHP's buscarPrefijo logic:
        // 1. Load all prefixes if not already cached (CargarPrefijos).
        // 2. If trunk call, filter prefixes by telephony types allowed for that trunk.
        // 3. If not trunk, iterate from max prefix length down to min, find first match.
        // 4. Always add LOCAL type as a last resort if not a trunk call.

        // Step 1: Load relevant prefixes (simplified, PHP caches all)
        String queryStr = "SELECT p.*, ttc.min_value as ttc_min, ttc.max_value as ttc_max, " +
                "(SELECT COUNT(*) FROM band b WHERE b.prefix_id = p.id AND b.active = true) as bands_count " +
                "FROM prefix p " +
                "JOIN operator o ON p.operator_id = o.id " +
                "JOIN telephony_type tt ON p.telephone_type_id = tt.id " +
                "LEFT JOIN telephony_type_config ttc ON tt.id = ttc.telephony_type_id AND ttc.origin_country_id = :originCountryId AND ttc.active = true " +
                "WHERE p.active = true AND o.active = true AND tt.active = true AND o.origin_country_id = :originCountryId " +
                "AND p.telephone_type_id != :specialServicesTypeId "; // Exclude special services

        if (isTrunkCall && trunkTelephonyTypeIds != null && !trunkTelephonyTypeIds.isEmpty()) {
            queryStr += "AND p.telephone_type_id IN (:trunkTelephonyTypeIds) ";
        }
        queryStr += "ORDER BY LENGTH(p.code) DESC, ttc.min_value DESC, p.telephone_type_id";

        jakarta.persistence.Query nativeQuery = entityManager.createNativeQuery(queryStr, Tuple.class); // Using Tuple for mixed results
        nativeQuery.setParameter("originCountryId", originCountryId);
        nativeQuery.setParameter("specialServicesTypeId", TelephonyTypeEnum.SPECIAL_SERVICES.getValue());
        if (isTrunkCall && trunkTelephonyTypeIds != null && !trunkTelephonyTypeIds.isEmpty()) {
            nativeQuery.setParameter("trunkTelephonyTypeIds", trunkTelephonyTypeIds);
        }

        List<Tuple> results = nativeQuery.getResultList();
        List<PrefixInfo> allRelevantPrefixes = results.stream().map(tuple -> {
            Prefix p = entityManager.find(Prefix.class, tuple.get("id", Number.class).longValue()); // Re-fetch to get associations
            TelephonyTypeConfig cfg = new TelephonyTypeConfig();
            cfg.setMinValue(tuple.get("ttc_min", Number.class) != null ? tuple.get("ttc_min", Number.class).intValue() : 0);
            cfg.setMaxValue(tuple.get("ttc_max", Number.class) != null ? tuple.get("ttc_max", Number.class).intValue() : 99);
            int bandsCount = tuple.get("bands_count", Number.class).intValue();
            return new PrefixInfo(p, cfg, bandsCount);
        }).collect(Collectors.toList());


        List<PrefixInfo> matchedPrefixes = new ArrayList<>();
        if (!isTrunkCall) {
            // Find best prefix match by iterating length
            String bestMatchPrefixCode = null;
            for (PrefixInfo pi : allRelevantPrefixes) {
                if (pi.getPrefixCode() != null && !pi.getPrefixCode().isEmpty() && dialedNumber.startsWith(pi.getPrefixCode())) {
                    if (bestMatchPrefixCode == null || pi.getPrefixCode().length() > bestMatchPrefixCode.length()) {
                        bestMatchPrefixCode = pi.getPrefixCode();
                    }
                }
            }
            if (bestMatchPrefixCode != null) {
                final String finalBestMatchPrefixCode = bestMatchPrefixCode;
                allRelevantPrefixes.stream()
                        .filter(pi -> finalBestMatchPrefixCode.equals(pi.getPrefixCode()))
                        .forEach(matchedPrefixes::add);
            }
        } else {
            // For trunk calls, PHP logic implies all prefixes for the allowed telephony types are considered.
            // The filtering by trunkTelephonyTypeIds already did this.
            // We need to check if the dialedNumber actually starts with any of these.
            for (PrefixInfo pi : allRelevantPrefixes) {
                if (pi.getPrefixCode() != null && !pi.getPrefixCode().isEmpty() && dialedNumber.startsWith(pi.getPrefixCode())) {
                    matchedPrefixes.add(pi);
                } else if (pi.getPrefixCode() == null || pi.getPrefixCode().isEmpty()) { // Prefixless types (like LOCAL if configured for trunk)
                    matchedPrefixes.add(pi);
                }
            }
        }

        // Add LOCAL type as a fallback if not a trunk call and no other prefix matched well enough
        // or if it's a trunk call and LOCAL is an allowed type without a specific prefix.
        final String finalDialedNumber = dialedNumber;
        if (!isTrunkCall && matchedPrefixes.isEmpty() || (isTrunkCall && (trunkTelephonyTypeIds == null || trunkTelephonyTypeIds.contains(TelephonyTypeEnum.LOCAL.getValue())))) {
            allRelevantPrefixes.stream()
                    .filter(pi -> pi.getTelephonyTypeId() == TelephonyTypeEnum.LOCAL.getValue() && (pi.getPrefixCode() == null || pi.getPrefixCode().isEmpty() || finalDialedNumber.startsWith(pi.getPrefixCode())))
                    .findFirst() // Assuming only one "LOCAL" definition without a specific prefix code
                    .ifPresent(localPrefixInfo -> {
                        if (!matchedPrefixes.stream().anyMatch(p -> p.getTelephonyTypeId() == TelephonyTypeEnum.LOCAL.getValue())) {
                            matchedPrefixes.add(localPrefixInfo);
                        }
                    });
        }

        // PHP sorts by prefix length desc, then by min length desc.
        // Here, we sort to ensure more specific (longer) prefixes are preferred.
        matchedPrefixes.sort(Comparator.comparing((PrefixInfo pi) -> pi.getPrefixCode() != null ? pi.getPrefixCode().length() : 0)
                .reversed()
                .thenComparing(Comparator.comparingInt(PrefixInfo::getTelephonyTypeMinLength).reversed()));

        log.debug("Found {} matching prefixes for dialed number '{}'", matchedPrefixes.size(), dialedNumber);
        return matchedPrefixes;
    }
}
