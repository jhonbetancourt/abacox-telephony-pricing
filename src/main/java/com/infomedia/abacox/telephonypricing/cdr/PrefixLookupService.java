// File: com/infomedia/abacox/telephonypricing/cdr/PrefixLookupService.java
package com.infomedia.abacox.telephonypricing.cdr;

import com.infomedia.abacox.telephonypricing.entity.CommunicationLocation;
import com.infomedia.abacox.telephonypricing.entity.Prefix;
import com.infomedia.abacox.telephonypricing.entity.TelephonyTypeConfig;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.Tuple;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.stream.Collectors;

@Service
@Log4j2
@RequiredArgsConstructor
public class PrefixLookupService {

    @PersistenceContext
    private EntityManager entityManager;
    private final PhoneNumberTransformationService phoneNumberTransformationService;
    private final TelephonyTypeLookupService telephonyTypeLookupService; // For internal types list

    /**
     * PHP equivalent: buscarPrefijo
     */
    @Transactional(readOnly = true)
    public List<PrefixInfo> findMatchingPrefixes(String dialedNumber,
                                                 CommunicationLocation commLocation,
                                                 boolean isTrunkCall,
                                                 List<Long> trunkTelephonyTypeIds) {

        String numberForLookup = dialedNumber;
        if (commLocation != null && commLocation.getIndicator() != null &&
            commLocation.getIndicator().getOriginCountryId() != null) {
            TransformationResult transformResult =
                phoneNumberTransformationService.transformForPrefixLookup(dialedNumber, commLocation);
            if (transformResult.isTransformed()) {
                numberForLookup = transformResult.getTransformedNumber();
            }
        }
        final String finalNumberForLookup = numberForLookup;

        String queryStr = "SELECT p.*, ttc.min_value as ttc_min, ttc.max_value as ttc_max, " +
                "(SELECT COUNT(*) FROM band b WHERE b.prefix_id = p.id AND b.active = true) as bands_count " +
                "FROM prefix p " +
                "JOIN operator o ON p.operator_id = o.id " +
                "JOIN telephony_type tt ON p.telephone_type_id = tt.id " +
                "LEFT JOIN telephony_type_config ttc ON tt.id = ttc.telephony_type_id AND ttc.origin_country_id = :originCountryId AND ttc.active = true " +
                "WHERE p.active = true AND o.active = true AND tt.active = true AND o.origin_country_id = :originCountryId " +
                "AND p.telephone_type_id != :specialServicesTypeId ";

        if (isTrunkCall && trunkTelephonyTypeIds != null && !trunkTelephonyTypeIds.isEmpty()) {
            queryStr += "AND p.telephone_type_id IN (:trunkTelephonyTypeIds) ";
        }
        queryStr += "ORDER BY LENGTH(p.code) DESC, ttc.min_value DESC, p.telephone_type_id";

        jakarta.persistence.Query nativeQuery = entityManager.createNativeQuery(queryStr, Tuple.class);
        nativeQuery.setParameter("originCountryId", commLocation.getIndicator().getOriginCountryId());
        nativeQuery.setParameter("specialServicesTypeId", TelephonyTypeEnum.SPECIAL_SERVICES.getValue());
        if (isTrunkCall && trunkTelephonyTypeIds != null && !trunkTelephonyTypeIds.isEmpty()) {
            nativeQuery.setParameter("trunkTelephonyTypeIds", trunkTelephonyTypeIds);
        }

        List<Tuple> results = nativeQuery.getResultList();
        List<PrefixInfo> allRelevantPrefixes = results.stream().map(tuple -> {
            Prefix p = entityManager.find(Prefix.class, tuple.get("id", Number.class).longValue());
            TelephonyTypeConfig cfg = new TelephonyTypeConfig();
            cfg.setMinValue(tuple.get("ttc_min", Number.class) != null ? tuple.get("ttc_min", Number.class).intValue() : 0);
            cfg.setMaxValue(tuple.get("ttc_max", Number.class) != null ? tuple.get("ttc_max", Number.class).intValue() : 99);
            int bandsCount = tuple.get("bands_count", Number.class).intValue();
            return new PrefixInfo(p, cfg, bandsCount);
        }).collect(Collectors.toList());

        List<PrefixInfo> matchedPrefixes = new ArrayList<>();
        if (!isTrunkCall) {
            String bestMatchPrefixCode = null;
            for (PrefixInfo pi : allRelevantPrefixes) {
                if (pi.getPrefixCode() != null && !pi.getPrefixCode().isEmpty() && finalNumberForLookup.startsWith(pi.getPrefixCode())) {
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
            for (PrefixInfo pi : allRelevantPrefixes) {
                if (pi.getPrefixCode() != null && !pi.getPrefixCode().isEmpty() && finalNumberForLookup.startsWith(pi.getPrefixCode())) {
                    matchedPrefixes.add(pi);
                } else if (pi.getPrefixCode() == null || pi.getPrefixCode().isEmpty()) {
                    matchedPrefixes.add(pi);
                }
            }
        }

        if (!isTrunkCall && (matchedPrefixes.isEmpty() || matchedPrefixes.stream().allMatch(pi -> pi.getPrefixCode() == null || pi.getPrefixCode().isEmpty() || !finalNumberForLookup.startsWith(pi.getPrefixCode())))) {
             allRelevantPrefixes.stream()
                    .filter(pi -> pi.getTelephonyTypeId() == TelephonyTypeEnum.LOCAL.getValue() &&
                                 (pi.getPrefixCode() == null || pi.getPrefixCode().isEmpty() || finalNumberForLookup.startsWith(pi.getPrefixCode())) &&
                                 finalNumberForLookup.length() >= (pi.getTelephonyTypeMinLength() != null ? pi.getTelephonyTypeMinLength() : 0)
                           )
                    .findFirst()
                    .ifPresent(localPrefixInfo -> {
                        boolean onlyPrefixlessMatches = matchedPrefixes.stream().allMatch(p -> p.getPrefixCode() == null || p.getPrefixCode().isEmpty());
                        if (matchedPrefixes.isEmpty() || onlyPrefixlessMatches) {
                            if (!matchedPrefixes.stream().anyMatch(p -> p.getTelephonyTypeId() == TelephonyTypeEnum.LOCAL.getValue() && (p.getPrefixCode() == null || p.getPrefixCode().isEmpty()))) {
                                matchedPrefixes.add(localPrefixInfo);
                            }
                        }
                    });
        }

        matchedPrefixes.sort(Comparator.comparing((PrefixInfo pi) -> pi.getPrefixCode() != null ? pi.getPrefixCode().length() : 0)
                .reversed()
                .thenComparing(Comparator.comparingInt((PrefixInfo pi) -> pi.getTelephonyTypeMinLength() != null ? pi.getTelephonyTypeMinLength() : 0).reversed()));

        return matchedPrefixes;
    }

    /**
     * PHP equivalent: prefijos_OrdenarInternos and the loop in tipo_llamada_interna
     * Returns a map sorted by prefix string length descending.
     */
    @Transactional(readOnly = true)
    public Map<String, Long> getInternalTelephonyTypePrefixes(Long originCountryId) {
        // PHP: $tt_internas = _tipotele_Internas($link, true);
        List<Long> internalTypeIds = telephonyTypeLookupService.getInternalTypeIds();
        if (internalTypeIds.isEmpty()) {
            return Collections.emptyMap();
        }

        // PHP: foreach ($prefijos['prefijo'] as $prefijo_txt => $infoprefijos)
        // PHP: $prefijos['prefijo'] is already sorted by length DESC. We achieve this by querying and sorting.
        String queryStr = "SELECT p.code, p.telephone_type_id " +
                          "FROM prefix p JOIN operator o ON p.operator_id = o.id " +
                          "WHERE p.active = true AND o.active = true AND o.origin_country_id = :originCountryId " +
                          "AND p.telephone_type_id IN (:internalTypeIds) AND p.code IS NOT NULL AND p.code != '' " +
                          "ORDER BY LENGTH(p.code) DESC, p.code DESC"; // Ensure consistent ordering for same-length prefixes

        jakarta.persistence.Query nativeQuery = entityManager.createNativeQuery(queryStr, Tuple.class);
        nativeQuery.setParameter("originCountryId", originCountryId);
        nativeQuery.setParameter("internalTypeIds", internalTypeIds);

        List<Tuple> results = nativeQuery.getResultList();
        // PHP's krsort($arreglo_tt) sorts by key (prefix string) descending.
        // A TreeMap with reverseOrder will achieve this.
        // However, PHP's logic for $_lista_Prefijos['ttin'] is to iterate prefixes sorted by length,
        // and for each prefix, take the telephony type. If multiple prefixes have the same text,
        // the one associated with the *last processed prefix_id* (within that text group) wins.
        // The SQL ORDER BY LENGTH(p.code) DESC ensures we process longer prefixes first.
        // For identical prefix strings, the one from the "later" (in SQL result due to secondary sort) prefix_id would overwrite.
        // To mimic PHP's krsort on the *keys* of the final map:
        Map<String, Long> internalPrefixMap = new TreeMap<>(Comparator.reverseOrder());
        for (Tuple row : results) {
            String prefixCode = row.get("code", String.class);
            Long telephonyTypeId = row.get("telephone_type_id", Number.class).longValue();
            // If multiple prefixes have the same text, the one from the "later" (in SQL result due to secondary sort)
            // prefix_id would overwrite, which is fine.
            internalPrefixMap.put(prefixCode, telephonyTypeId);
        }
        log.debug("Loaded {} internal telephony type prefixes for country {}", internalPrefixMap.size(), originCountryId);
        return internalPrefixMap;
    }
}