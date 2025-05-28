// File: com/infomedia/abacox/telephonypricing/cdr/PrefixLookupService.java
package com.infomedia.abacox.telephonypricing.cdr;

import com.infomedia.abacox.telephonypricing.entity.CommunicationLocation;
import com.infomedia.abacox.telephonypricing.entity.Operator;
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
    private final TelephonyTypeLookupService telephonyTypeLookupService;

    /**
     * PHP equivalent: buscarPrefijo
     */
    @Transactional(readOnly = true)
    public List<PrefixInfo> findMatchingPrefixes(String dialedNumber,
                                                 CommunicationLocation commLocation,
                                                 boolean isTrunkCall,
                                                 List<Long> trunkTelephonyTypeIds) {
        log.debug("Finding matching prefixes for dialedNumber: '{}', isTrunkCall: {}, trunkTelephonyTypeIds: {}",
                dialedNumber, isTrunkCall, trunkTelephonyTypeIds);

        String numberForLookup = dialedNumber;
        if (commLocation != null && commLocation.getIndicator() != null &&
            commLocation.getIndicator().getOriginCountryId() != null) {
            TransformationResult transformResult =
                phoneNumberTransformationService.transformForPrefixLookup(dialedNumber, commLocation);
            if (transformResult.isTransformed()) {
                numberForLookup = transformResult.getTransformedNumber();
                log.debug("Dialed number '{}' transformed for prefix lookup to '{}'", dialedNumber, numberForLookup);
            }
        }
        final String finalNumberForLookup = numberForLookup;

        String queryStr = "SELECT p.*, ttc.min_value as ttc_min, ttc.max_value as ttc_max, " +
                "(SELECT COUNT(*) FROM band b WHERE b.prefix_id = p.id AND b.active = true) as bands_count " +
                "FROM prefix p " +
                "JOIN operator o ON p.operator_id = o.id " +
                "JOIN telephony_type tt ON p.telephony_type_id = tt.id " +
                "LEFT JOIN telephony_type_config ttc ON tt.id = ttc.telephony_type_id AND ttc.origin_country_id = :originCountryId AND ttc.active = true " +
                "WHERE p.active = true AND o.active = true AND tt.active = true AND o.origin_country_id = :originCountryId " +
                "AND p.telephony_type_id != :specialServicesTypeId ";

        if (isTrunkCall && trunkTelephonyTypeIds != null && !trunkTelephonyTypeIds.isEmpty()) {
            queryStr += "AND p.telephony_type_id IN (:trunkTelephonyTypeIds) ";
            log.debug("Trunk call, filtering by telephonyTypeIds: {}", trunkTelephonyTypeIds);
        }
        // PHP's CargarPrefijos sorts by LENGTH(PREFIJO_PREFIJO) DESC, then TIPOTELECFG_MIN DESC
        queryStr += "ORDER BY LENGTH(p.code) DESC, ttc.min_value DESC, p.telephony_type_id";


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
        log.debug("All relevant prefixes fetched: {}", allRelevantPrefixes.size());

        List<PrefixInfo> matchedPrefixes = new ArrayList<>();
        if (!isTrunkCall) {
            String bestMatchPrefixCode = null;
            for (PrefixInfo pi : allRelevantPrefixes) { // allRelevantPrefixes is already sorted by length DESC
                if (pi.getPrefixCode() != null && !pi.getPrefixCode().isEmpty() && finalNumberForLookup.startsWith(pi.getPrefixCode())) {
                    // Found a prefix that matches the start of the number.
                    // Since allRelevantPrefixes is sorted by length DESC, the first one we find
                    // that matches is the longest one.
                    bestMatchPrefixCode = pi.getPrefixCode();
                    break; // Found the longest matching prefix code
                }
            }
            if (bestMatchPrefixCode != null) {
                final String finalBestMatchPrefixCode = bestMatchPrefixCode;
                allRelevantPrefixes.stream()
                        .filter(pi -> finalBestMatchPrefixCode.equals(pi.getPrefixCode()))
                        .forEach(matchedPrefixes::add);
                log.debug("Non-trunk call, best matching prefix code: '{}', found {} matches.", bestMatchPrefixCode, matchedPrefixes.size());
            } else {
                log.debug("Non-trunk call, no prefix code matched for '{}'", finalNumberForLookup);
            }
        } else { // isTrunkCall
            // For trunk calls, PHP logic adds all prefixes belonging to the allowed telephony types.
            // The `allRelevantPrefixes` list is already filtered by `trunkTelephonyTypeIds` in the SQL query.
            // So, we just add all of them.
            matchedPrefixes.addAll(allRelevantPrefixes);
            log.debug("Trunk call, added {} prefixes associated with allowed trunk telephony types.", matchedPrefixes.size());
        }

        // PHP: if ($id_local > 0 && !in_array($id_local, $arr_prefijo_id) && $existe_troncal === false)
        if (!isTrunkCall) {
            boolean localPrefixAlreadyMatched = matchedPrefixes.stream()
                .anyMatch(pi -> pi.getTelephonyTypeId() == TelephonyTypeEnum.LOCAL.getValue() &&
                                (pi.getPrefixCode() == null || pi.getPrefixCode().isEmpty()));

            if (!localPrefixAlreadyMatched) {
                allRelevantPrefixes.stream()
                    .filter(pi -> pi.getTelephonyTypeId() == TelephonyTypeEnum.LOCAL.getValue() &&
                                 (pi.getPrefixCode() == null || pi.getPrefixCode().isEmpty() || finalNumberForLookup.startsWith(pi.getPrefixCode())) &&
                                 finalNumberForLookup.length() >= (pi.getTelephonyTypeMinLength() != null ? pi.getTelephonyTypeMinLength() : 0)
                           )
                    // PHP adds all LOCAL prefixes if multiple are defined and match criteria.
                    // Here, we'll add all that fit.
                    .forEach(localPrefixInfo -> {
                        // Add if not already present (e.g., if a LOCAL prefix *with* a code was already matched)
                        if (!matchedPrefixes.stream().anyMatch(mp -> mp.getPrefixId().equals(localPrefixInfo.getPrefixId()))) {
                             matchedPrefixes.add(localPrefixInfo);
                             log.debug("Added LOCAL prefix as fallback/additional for non-trunk call: {}", localPrefixInfo);
                        }
                    });
            }
        }

        // PHP: krsort($arr_retornar); (sorts by key, which is prefix string + counter, effectively longest prefix first)
        // Java sort: Prioritize prefixes with actual codes over those without (e.g. LOCAL type often has empty code).
        // Then, by prefix code length descending.
        // Then, by min telephony type length descending (more specific types).
        matchedPrefixes.sort(Comparator
                .comparing((PrefixInfo pi) -> (pi.getPrefixCode() == null || pi.getPrefixCode().isEmpty()) ? 1 : 0) // Empty/null codes last within same length
                .thenComparing((PrefixInfo pi) -> pi.getPrefixCode() != null ? pi.getPrefixCode().length() : 0, Comparator.reverseOrder())
                .thenComparing((PrefixInfo pi) -> pi.getTelephonyTypeMinLength() != null ? pi.getTelephonyTypeMinLength() : 0, Comparator.reverseOrder()));

        log.debug("Final sorted matched prefixes ({}): {}", matchedPrefixes.size(), matchedPrefixes);
        return matchedPrefixes;
    }

    // ... (getInternalTelephonyTypePrefixes and findOperatorNameById remain the same)
    /**
     * PHP equivalent: prefijos_OrdenarInternos and the loop in tipo_llamada_interna
     * Returns a map sorted by prefix string length descending.
     */
    @Transactional(readOnly = true)
    public Map<String, Long> getInternalTelephonyTypePrefixes(Long originCountryId) {
        List<Long> internalTypeIds = telephonyTypeLookupService.getInternalTypeIds();
        if (internalTypeIds.isEmpty()) {
            log.debug("No internal telephony type IDs defined.");
            return Collections.emptyMap();
        }

        String queryStr = "SELECT p.code, p.telephony_type_id " +
                          "FROM prefix p JOIN operator o ON p.operator_id = o.id " +
                          "WHERE p.active = true AND o.active = true AND o.origin_country_id = :originCountryId " +
                          "AND p.telephony_type_id IN (:internalTypeIds) AND p.code IS NOT NULL AND p.code != '' " +
                          "ORDER BY LENGTH(p.code) DESC, p.code DESC";

        jakarta.persistence.Query nativeQuery = entityManager.createNativeQuery(queryStr, Tuple.class);
        nativeQuery.setParameter("originCountryId", originCountryId);
        nativeQuery.setParameter("internalTypeIds", internalTypeIds);

        List<Tuple> results = nativeQuery.getResultList();
        // Use TreeMap with custom comparator for length-based sorting, then alphabetical for ties
        Map<String, Long> internalPrefixMap = new TreeMap<>((s1, s2) -> {
            int lenComp = Integer.compare(s2.length(), s1.length()); // Longer first
            if (lenComp != 0) return lenComp;
            return s2.compareTo(s1); // Then reverse alphabetical (like PHP's krsort on string keys)
        });

        for (Tuple row : results) {
            String prefixCode = row.get("code", String.class);
            Long telephonyTypeId = row.get("telephony_type_id", Number.class).longValue();
            internalPrefixMap.put(prefixCode, telephonyTypeId);
        }
        log.debug("Loaded {} internal telephony type prefixes for country {}: {}", internalPrefixMap.size(), originCountryId, internalPrefixMap);
        return internalPrefixMap;
    }


    /**
     * Helper to fetch operator name by ID.
     * This could be moved to an OperatorLookupService.
     */
    @Transactional(readOnly = true)
    public String findOperatorNameById(Long operatorId) {
        if (operatorId == null || operatorId == 0L) {
            return "Unknown Operator";
        }
        try {
            Operator operator = entityManager.find(Operator.class, operatorId);
            return operator != null ? operator.getName() : "OperatorID:" + operatorId;
        } catch (Exception e) {
            log.warn("Could not find operator name for ID: {}", operatorId, e);
            return "OperatorID:" + operatorId;
        }
    }
}