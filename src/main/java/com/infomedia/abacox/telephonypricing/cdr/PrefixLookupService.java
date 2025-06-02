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

    @Transactional(readOnly = true)
    public List<PrefixInfo> findMatchingPrefixes(String dialedNumber,
                                                 CommunicationLocation commLocation,
                                                 boolean isTrunkCall,
                                                 List<Long> trunkTelephonyTypeIds) {
        log.debug("Finding matching prefixes for dialedNumber: '{}', isTrunkCall: {}, trunkTelephonyTypeIds: {}",
                dialedNumber, isTrunkCall, trunkTelephonyTypeIds);

        String numberForLookup = dialedNumber;
        Long hintedTelephonyTypeIdFromTransform = null;
        if (commLocation != null && commLocation.getIndicator() != null &&
            commLocation.getIndicator().getOriginCountryId() != null) {
            TransformationResult transformResult =
                phoneNumberTransformationService.transformForPrefixLookup(dialedNumber, commLocation);
            if (transformResult.isTransformed()) {
                numberForLookup = transformResult.getTransformedNumber();
                hintedTelephonyTypeIdFromTransform = transformResult.getNewTelephonyTypeId();
                log.debug("Dialed number '{}' transformed for prefix lookup to '{}', hinted type: {}", dialedNumber, numberForLookup, hintedTelephonyTypeIdFromTransform);
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
            for (PrefixInfo pi : allRelevantPrefixes) {
                if (pi.getPrefixCode() != null && !pi.getPrefixCode().isEmpty() && finalNumberForLookup.startsWith(pi.getPrefixCode())) {
                    bestMatchPrefixCode = pi.getPrefixCode();
                    break;
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
        } else {
            matchedPrefixes.addAll(allRelevantPrefixes);
            log.debug("Trunk call, added {} prefixes associated with allowed trunk telephony types.", matchedPrefixes.size());
        }

        if (!isTrunkCall) {
            boolean localPrefixAlreadyMatched = matchedPrefixes.stream()
                .anyMatch(pi -> pi.getTelephonyTypeId().equals(TelephonyTypeEnum.LOCAL.getValue()));

            if (!localPrefixAlreadyMatched) {
                List<PrefixInfo> finalMatchedPrefixes = matchedPrefixes;
                allRelevantPrefixes.stream()
                    .filter(pi -> pi.getTelephonyTypeId().equals(TelephonyTypeEnum.LOCAL.getValue()) &&
                                 (pi.getPrefixCode() == null || pi.getPrefixCode().isEmpty() || finalNumberForLookup.startsWith(pi.getPrefixCode())) &&
                                 finalNumberForLookup.length() >= (pi.getTelephonyTypeMinLength() != null ? pi.getTelephonyTypeMinLength() : 0)
                           )
                    .forEach(localPrefixInfo -> {
                        if (!finalMatchedPrefixes.stream().anyMatch(mp -> mp.getPrefixId().equals(localPrefixInfo.getPrefixId()))) {
                             finalMatchedPrefixes.add(localPrefixInfo);
                             log.debug("Added LOCAL prefix as fallback/additional for non-trunk call: {}", localPrefixInfo);
                        }
                    });
            }
        }
        
        if (hintedTelephonyTypeIdFromTransform != null && !matchedPrefixes.isEmpty()) {
            Long finalHintedTelephonyTypeIdFromTransform = hintedTelephonyTypeIdFromTransform;
            List<PrefixInfo> hintedTypeMatches = matchedPrefixes.stream()
                .filter(pi -> pi.getTelephonyTypeId().equals(finalHintedTelephonyTypeIdFromTransform) &&
                              (pi.getPrefixCode() == null || pi.getPrefixCode().isEmpty() || finalNumberForLookup.startsWith(pi.getPrefixCode())))
                .collect(Collectors.toList());
            if (!hintedTypeMatches.isEmpty()) {
                log.debug("Prioritizing {} matches for hinted telephony type {} from transformation.", hintedTypeMatches.size(), hintedTelephonyTypeIdFromTransform);
                matchedPrefixes = hintedTypeMatches;
            } else {
                log.debug("No matching prefixes found for hinted telephony type {} from transformation. Using original matches.", hintedTelephonyTypeIdFromTransform);
            }
        }

        // PHP: krsort($arr_retornar); (sorts by key which was sprintf("%05s.%s",$lprefijo, $kpos))
        // The PHP $kpos was decremented, so smaller $kpos (later in original $arr_prefijo_id) came first.
        // $arr_prefijo_id was from $_lista_Prefijos['prefijo'][$eval_prefijo] or $_lista_Prefijos['tipotele'][$tipotele_destino].
        // The original query for $_lista_Prefijos was `ORDER BY $campo_len DESC, TIPOTELECFG_MIN DESC, TIPOTELE_ID`.
        // This means for the same prefix code length, those with larger min cfg length came first.
        // The Java sort should be:
        // 1. Longer prefix code (already implicitly handled by how we selected `bestMatchPrefixCode` or by initial query for trunks)
        // 2. Within same prefix code (or for trunks where all are considered), by TelephonyTypeConfig.minValue descending (PHP: TIPOTELECFG_MIN DESC)
        // 3. Then by TelephonyType.id ascending (PHP: TIPOTELE_ID)
        matchedPrefixes.sort(Comparator
                .comparing((PrefixInfo pi) -> pi.getPrefixCode() != null ? pi.getPrefixCode().length() : 0, Comparator.reverseOrder())
                .thenComparing((PrefixInfo pi) -> pi.getTelephonyTypeMinLength() != null ? pi.getTelephonyTypeMinLength() : 0, Comparator.reverseOrder())
                .thenComparing(PrefixInfo::getTelephonyTypeId, Comparator.naturalOrder()));


        log.debug("Final sorted matched prefixes ({}): {}", matchedPrefixes.size(), matchedPrefixes);
        return matchedPrefixes;
    }

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
        Map<String, Long> internalPrefixMap = new TreeMap<>(Collections.reverseOrder());


        for (Tuple row : results) {
            String prefixCode = row.get("code", String.class);
            Long telephonyTypeId = row.get("telephony_type_id", Number.class).longValue();
            internalPrefixMap.put(prefixCode, telephonyTypeId);
        }
        log.debug("Loaded {} internal telephony type prefixes for country {}: {}", internalPrefixMap.size(), originCountryId, internalPrefixMap);
        return internalPrefixMap;
    }
}