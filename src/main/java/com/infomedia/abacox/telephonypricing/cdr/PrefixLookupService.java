package com.infomedia.abacox.telephonypricing.cdr;

import com.infomedia.abacox.telephonypricing.entity.CommunicationLocation; // Added for transformForPrefixLookup
import com.infomedia.abacox.telephonypricing.entity.Prefix;
import com.infomedia.abacox.telephonypricing.entity.TelephonyTypeConfig;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.Tuple;
import lombok.RequiredArgsConstructor; // Added
import lombok.extern.log4j.Log4j2;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

@Service
@Log4j2
@RequiredArgsConstructor // Added
public class PrefixLookupService {

    @PersistenceContext
    private EntityManager entityManager;
    private final PhoneNumberTransformationService phoneNumberTransformationService; // Added

    @Transactional(readOnly = true)
    public List<PrefixInfo> findMatchingPrefixes(String dialedNumber,
                                                 CommunicationLocation commLocation, // Changed from originCountryId
                                                 boolean isTrunkCall,
                                                 List<Long> trunkTelephonyTypeIds) {

        String numberForLookup = dialedNumber;
        if (commLocation != null && commLocation.getIndicator() != null &&
            commLocation.getIndicator().getOriginCountryId() != null &&
            commLocation.getIndicator().getOriginCountryId() == 1L) { // Colombia
            // Apply _esCelular_fijo transformation before prefix lookup
            PhoneNumberTransformationService.TransformationResult transformResult =
                phoneNumberTransformationService.transformForPrefixLookup(dialedNumber, commLocation);
            if (transformResult.isTransformed()) {
                numberForLookup = transformResult.getTransformedNumber();
                log.debug("Number transformed for prefix lookup (Colombia): {} -> {}", dialedNumber, numberForLookup);
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
                final String finalBestMatchPrefixCode = bestMatchPrefixCode; // Effectively final for lambda
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
                        // Add LOCAL only if no other *prefix-based* match was found, or if the only matches were prefixless
                        boolean onlyPrefixlessMatches = matchedPrefixes.stream().allMatch(p -> p.getPrefixCode() == null || p.getPrefixCode().isEmpty());
                        if (matchedPrefixes.isEmpty() || onlyPrefixlessMatches) {
                            if (!matchedPrefixes.stream().anyMatch(p -> p.getTelephonyTypeId() == TelephonyTypeEnum.LOCAL.getValue())) {
                                matchedPrefixes.add(localPrefixInfo);
                                log.debug("Added LOCAL prefix as fallback for {}", finalNumberForLookup);
                            }
                        }
                    });
        }


        matchedPrefixes.sort(Comparator.comparing((PrefixInfo pi) -> pi.getPrefixCode() != null ? pi.getPrefixCode().length() : 0)
                .reversed()
                .thenComparing(Comparator.comparingInt(PrefixInfo::getTelephonyTypeMinLength).reversed()));

        log.debug("Found {} matching prefixes for (potentially transformed) number '{}'", matchedPrefixes.size(), finalNumberForLookup);
        return matchedPrefixes;
    }
}