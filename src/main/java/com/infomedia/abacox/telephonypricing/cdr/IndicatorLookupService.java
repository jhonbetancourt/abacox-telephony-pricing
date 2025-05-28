// File: com/infomedia/abacox/telephonypricing/cdr/IndicatorLookupService.java
package com.infomedia.abacox.telephonypricing.cdr;

import jakarta.persistence.EntityManager;
import jakarta.persistence.NoResultException;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.Tuple;
import lombok.extern.log4j.Log4j2;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.stream.Collectors;

@Service
@Log4j2
public class IndicatorLookupService {

    @PersistenceContext
    private EntityManager entityManager;

    // ... (getIndicatorConfigForTelephonyType remains the same)
    @Transactional(readOnly = true)
    public IndicatorConfig getIndicatorConfigForTelephonyType(Long telephonyTypeId, Long originCountryId) {
        log.debug("Getting indicator config for telephonyTypeId: {}, originCountryId: {}", telephonyTypeId, originCountryId);
        String queryStr = "SELECT " +
                          "COALESCE(MIN(LENGTH(s.ndc::text)), 0) as min_ndc_len, " +
                          "COALESCE(MAX(LENGTH(s.ndc::text)), 0) as max_ndc_len, " +
                          "(SELECT LENGTH(s2.initial_number::text) FROM series s2 JOIN indicator i2 ON s2.indicator_id = i2.id WHERE i2.telephony_type_id = :telephonyTypeId AND i2.origin_country_id = :originCountryId AND s2.active = true AND i2.active = true AND s2.initial_number >= 0 GROUP BY LENGTH(s2.initial_number::text) ORDER BY COUNT(*) DESC LIMIT 1) as common_series_len " +
                          "FROM series s JOIN indicator i ON s.indicator_id = i.id " +
                          "WHERE i.telephony_type_id = :telephonyTypeId AND i.origin_country_id = :originCountryId " +
                          "AND s.active = true AND i.active = true AND s.initial_number >= 0";

        jakarta.persistence.Query nativeQuery = entityManager.createNativeQuery(queryStr, Tuple.class);
        nativeQuery.setParameter("telephonyTypeId", telephonyTypeId);
        nativeQuery.setParameter("originCountryId", originCountryId);

        IndicatorConfig config = new IndicatorConfig();
        try {
            Tuple result = (Tuple) nativeQuery.getSingleResult();
            config.minNdcLength = result.get("min_ndc_len", Number.class).intValue();
            config.maxNdcLength = result.get("max_ndc_len", Number.class).intValue();
            Number commonSeriesLenNum = result.get("common_series_len", Number.class);
            config.seriesNumberLength = (commonSeriesLenNum != null) ? commonSeriesLenNum.intValue() : 0;

            if (telephonyTypeId != null && telephonyTypeId.equals(TelephonyTypeEnum.CELLULAR.getValue())) {
                if (config.minNdcLength == 0) config.minNdcLength = 3;
                if (config.maxNdcLength == 0) config.maxNdcLength = 3;
                if (config.seriesNumberLength == 0) config.seriesNumberLength = 7;
            }
            log.debug("Indicator config found: {}", config);
        } catch (NoResultException e) {
            log.warn("No indicator configuration found for telephonyTypeId {} and originCountryId {}", telephonyTypeId, originCountryId);
            if (telephonyTypeId != null && telephonyTypeId.equals(TelephonyTypeEnum.CELLULAR.getValue())) {
                config.minNdcLength = 3;
                config.maxNdcLength = 3;
                config.seriesNumberLength = 7;
                log.warn("Using default cellular config due to NoResultException: {}", config);
            }
        }
        return config;
    }


    @Transactional(readOnly = true)
    public Optional<DestinationInfo> findDestinationIndicator(
            String phoneNumber, Long telephonyTypeId, int minTotalNumberLength,
            Long originIndicatorIdForBandContext, Long prefixIdFromCallingFunction, Long originCountryId,
            boolean useBandSpecificLookup, boolean isOperatorPrefixAlreadyStripped, String operatorPrefixToStripIfPresent) {

        log.debug("Finding destination indicator for phoneNumber: '{}', telephonyTypeId: {}, minTotalNumLen: {}, originIndForBand: {}, prefixIdFunc: {}, originCountryId: {}, useBandLookup: {}, opPrefixStripped: {}, opPrefixToStrip: {}",
                phoneNumber, telephonyTypeId, minTotalNumberLength, originIndicatorIdForBandContext, prefixIdFromCallingFunction, originCountryId, useBandSpecificLookup, isOperatorPrefixAlreadyStripped, operatorPrefixToStripIfPresent);

        if (phoneNumber == null || phoneNumber.isEmpty()) {
            log.debug("Phone number is empty, cannot find destination.");
            return Optional.empty();
        }

        String effectivePhoneNumber = phoneNumber;
        if (effectivePhoneNumber.startsWith("+")) {
            effectivePhoneNumber = effectivePhoneNumber.substring(1);
            log.trace("Stripped leading '+' from phone number. Now: {}", effectivePhoneNumber);
        }

        // PHP: if ($prefijotrim != '' && substr($telefono, 0, $len_prefijo ) == $prefijotrim && !$reducir) { $telefono = substr($telefono, $len_prefijo ); }
        if (!isOperatorPrefixAlreadyStripped && operatorPrefixToStripIfPresent != null && !operatorPrefixToStripIfPresent.isEmpty() && effectivePhoneNumber.startsWith(operatorPrefixToStripIfPresent)) {
            effectivePhoneNumber = effectivePhoneNumber.substring(operatorPrefixToStripIfPresent.length());
            log.debug("Operator prefix '{}' stripped. Effective number for NDC/Series: {}", operatorPrefixToStripIfPresent, effectivePhoneNumber);
        }


        if (isLocalType(telephonyTypeId)) {
            String localNdc = findLocalNdcForIndicator(originIndicatorIdForBandContext); // This is the plant's local NDC
            if (localNdc != null && !localNdc.isEmpty() && !effectivePhoneNumber.startsWith(localNdc)) {
                // Only prepend if the number doesn't already start with it (e.g. if localNdc is "1" and number is "5463000")
                effectivePhoneNumber = localNdc + effectivePhoneNumber;
                log.debug("Local type, prepended local NDC '{}'. Effective number for NDC/Series: {}", localNdc, effectivePhoneNumber);
            }
        }

        IndicatorConfig config = getIndicatorConfigForTelephonyType(telephonyTypeId, originCountryId);
        log.debug("Indicator config for type {}: {}", telephonyTypeId, config);
        if (config.maxNdcLength == 0 && !isLocalType(telephonyTypeId)) {
            log.debug("Max NDC length is 0 and not local type, cannot find destination.");
            return Optional.empty();
        }

        List<String> ndcCandidates = new ArrayList<>();
        int phoneLen = effectivePhoneNumber.length();

        for (int i = config.minNdcLength; i <= config.maxNdcLength; i++) {
            if (i > 0 && phoneLen >= i) {
                // minTotalNumberLength is the minimum length of (NDC + SubscriberPart)
                // minSubscriberPartLength is minTotalNumberLength - current_NDC_length (i)
                int minSubscriberPartLength = minTotalNumberLength - i;
                if ((phoneLen - i) >= minSubscriberPartLength) {
                    String candidateNdc = effectivePhoneNumber.substring(0, i);
                    if (candidateNdc.matches("\\d+")) {
                        ndcCandidates.add(candidateNdc);
                    }
                }
            }
        }
        // For local types, if no NDC candidates were formed (e.g. number is shorter than minNdcLength),
        // we might still need to check series with an empty/default NDC if the number itself is long enough.
        if (ndcCandidates.isEmpty() && isLocalType(telephonyTypeId)) {
            String localNdc = findLocalNdcForIndicator(originIndicatorIdForBandContext);
            if (localNdc != null && !localNdc.isEmpty() && effectivePhoneNumber.startsWith(localNdc)) {
                 ndcCandidates.add(localNdc);
            } else if (localNdc != null && localNdc.equals("0")) { // Handle "0" as a valid NDC for some local contexts
                 ndcCandidates.add("0");
            } else if (phoneLen >= minTotalNumberLength) { // If no specific local NDC, but number is long enough, allow check with empty effective NDC
                 ndcCandidates.add(""); // This will allow matching series where NDC is effectively empty for local
            }
        }
        log.debug("NDC candidates: {}", ndcCandidates);

        if (ndcCandidates.isEmpty() && !isLocalType(telephonyTypeId)) {
             log.debug("No NDC candidates and not local type, cannot find destination.");
             return Optional.empty();
        }


        StringBuilder queryBuilder = new StringBuilder(
            "SELECT i.id as indicator_id, i.operator_id as indicator_operator_id, s.ndc as ndc_val, i.department_country, i.city_name, s.initial_number, s.final_number, b.id as band_id " +
            "FROM series s JOIN indicator i ON s.indicator_id = i.id ");

        if (useBandSpecificLookup && prefixIdFromCallingFunction != null) {
            queryBuilder.append("LEFT JOIN band b ON b.prefix_id = :prefixIdFunc AND b.active = true ");
            queryBuilder.append("LEFT JOIN band_indicator bi ON bi.band_id = b.id AND bi.indicator_id = i.id ");
            // PHP: AND BANDA_INDICAORIGEN_ID in (0, $indicativo_origen_id)
            queryBuilder.append("AND (b.origin_indicator_id = 0 OR b.origin_indicator_id = :originIndicatorIdForBandContext) ");
        } else {
            queryBuilder.append("LEFT JOIN band b ON 1=0 "); // No band join effectively
             if (prefixIdFromCallingFunction != null) { // Still might need to filter indicator by prefix's operator
                 queryBuilder.append("AND (i.operator_id = 0 OR i.operator_id = (SELECT p.operator_id FROM prefix p WHERE p.id = :prefixIdFunc AND p.active = true)) ");
            }
        }

        queryBuilder.append("WHERE i.active = true AND s.active = true AND i.telephony_type_id = :telephonyTypeId ");
        if (!ndcCandidates.isEmpty()) {
            List<Integer> ndcIntCandidates = ndcCandidates.stream()
                                                          .filter(sVal -> !sVal.isEmpty() && sVal.matches("-?\\d+")) // Allow negative for special case
                                                          .map(Integer::parseInt)
                                                          .collect(Collectors.toList());
            if (!ndcIntCandidates.isEmpty()) {
                queryBuilder.append("AND s.ndc IN (:ndcCandidates) ");
            } else if (ndcCandidates.contains("")) { // Case for local type with empty effective NDC
                queryBuilder.append("AND (s.ndc = 0 OR s.ndc IS NULL) "); // Match series with NDC 0 or NULL
            } else if (!isLocalType(telephonyTypeId)) {
                 log.warn("No valid numeric NDC candidates for non-local type. This might lead to no results.");
                 return Optional.empty();
            }
        }

        if (!isInternationalOrSatellite(telephonyTypeId)) {
            queryBuilder.append("AND i.origin_country_id = :originCountryId ");
        }
        
        // PHP: $condicionum = "AND ( $condicionum )"; (SERIE_INICIAL <= $restomin AND SERIE_FINAL >= $restomin)
        // This part is handled in Java code after fetching, which is acceptable.

        if (useBandSpecificLookup) {
            queryBuilder.append("ORDER BY b.origin_indicator_id DESC NULLS LAST, LENGTH(s.ndc::text) DESC, s.initial_number ASC, s.final_number ASC");
        } else {
            queryBuilder.append("ORDER BY LENGTH(s.ndc::text) DESC, s.initial_number ASC, s.final_number ASC");
        }

        jakarta.persistence.Query nativeQuery = entityManager.createNativeQuery(queryBuilder.toString(), Tuple.class);
        nativeQuery.setParameter("telephonyTypeId", telephonyTypeId);
        if (!ndcCandidates.isEmpty()) {
            List<Integer> ndcIntCandidates = ndcCandidates.stream()
                                                          .filter(sVal -> !sVal.isEmpty() && sVal.matches("-?\\d+"))
                                                          .map(Integer::parseInt)
                                                          .collect(Collectors.toList());
            if (!ndcIntCandidates.isEmpty()) {
                nativeQuery.setParameter("ndcCandidates", ndcIntCandidates);
            }
        }


        if (useBandSpecificLookup && prefixIdFromCallingFunction != null) {
            nativeQuery.setParameter("prefixIdFunc", prefixIdFromCallingFunction);
            nativeQuery.setParameter("originIndicatorIdForBandContext", originIndicatorIdForBandContext);
        } else if (prefixIdFromCallingFunction != null) {
            nativeQuery.setParameter("prefixIdFunc", prefixIdFromCallingFunction);
        }
        if (!isInternationalOrSatellite(telephonyTypeId)) {
            nativeQuery.setParameter("originCountryId", originCountryId);
        }
        log.debug("Executing destination indicator query: {}", queryBuilder.toString().replaceAll(":(\\w+)", "?"));


        List<Tuple> results = nativeQuery.getResultList();
        log.debug("Destination indicator query returned {} results.", results.size());
        List<DestinationInfo> validMatches = new ArrayList<>();
        DestinationInfo approximateMatch = null;

        for (Tuple row : results) {
            String currentNdcStr = String.valueOf(row.get("ndc_val", Number.class).intValue());
            String subscriberPartOfEffectiveNumber;

            if (effectivePhoneNumber.startsWith(currentNdcStr)) {
                 subscriberPartOfEffectiveNumber = effectivePhoneNumber.substring(currentNdcStr.length());
            } else if ((currentNdcStr.equals("0") || currentNdcStr.isEmpty()) && isLocalType(telephonyTypeId)) { // "" NDC means local number without explicit NDC
                subscriberPartOfEffectiveNumber = effectivePhoneNumber;
            } else {
                log.trace("NDC {} does not prefix effectivePhoneNumber {}, skipping row.", currentNdcStr, effectivePhoneNumber);
                continue;
            }
            
            if (!subscriberPartOfEffectiveNumber.matches("\\d*")) {
                log.trace("Subscriber part '{}' is not purely numeric, skipping series check for this row.", subscriberPartOfEffectiveNumber);
                continue;
            }
            
            Integer seriesInitial = row.get("initial_number", Integer.class);
            Integer seriesFinal = row.get("final_number", Integer.class);
            
            log.trace("Comparing effectiveNum: '{}' (subscriber part: '{}') against series NDC: '{}', initial: '{}', final: '{}'",
                effectivePhoneNumber, subscriberPartOfEffectiveNumber, currentNdcStr, seriesInitial, seriesFinal);

            if (Integer.parseInt(currentNdcStr) < 0) { // PHP: if (1 * $info_indica['SERIE_NDC'] < 0)
                if (approximateMatch == null) { // Take the first approximate match
                    approximateMatch = new DestinationInfo();
                    fillDestinationInfo(approximateMatch, row, currentNdcStr, effectivePhoneNumber, prefixIdFromCallingFunction, true, seriesInitial, seriesFinal);
                    log.debug("Found approximate match (negative NDC): {}", approximateMatch);
                }
                continue;
            }
            
            // PHP: $arreglo_serie = rellenaSerie($telefono, $info_indica['SERIE_NDC'], $info_indica['SERIE_INICIAL'], $info_indica['SERIE_FINAL']);
            // PHP: if ($arreglo_serie['inicial'] <= $telefono && $arreglo_serie['final'] >= $telefono)
            // This means the full effectivePhoneNumber (after NDC prepending for local) is compared against fully constructed series numbers.
            
            // Construct full series numbers for comparison, similar to PHP's rellenaSerie
            int seriesLengthToMatch = subscriberPartOfEffectiveNumber.length(); // Length of the part of the number *after* the NDC
            if (config.seriesNumberLength > 0) { // If a common series length is defined, use it
                seriesLengthToMatch = config.seriesNumberLength;
            }
            if (seriesLengthToMatch == 0 && subscriberPartOfEffectiveNumber.length() > 0) { // Fallback if seriesNumberLength is 0 but subscriber part exists
                 seriesLengthToMatch = subscriberPartOfEffectiveNumber.length();
            }


            String seriesInitialPaddedStr = String.format("%0" + seriesLengthToMatch + "d", seriesInitial);
            String seriesFinalPaddedStr = String.format("%0" + seriesLengthToMatch + "d", seriesFinal);

            // Adjust padding if original series numbers were shorter than seriesLengthToMatch
            // PHP's str_pad($serie_inicial, $diferencia, '0') vs str_pad($serie_final, $diferencia, '9')
            // If seriesInitial was "30" and seriesLengthToMatch is 7, it becomes "0000030"
            // If seriesFinal was "199" and seriesLengthToMatch is 7, it becomes "0000199"
            // The PHP logic for padding series_final with '9' at the end if initial is longer is complex.
            // Here, we assume series initial/final define a numeric range.
            // The comparison should be on the numeric value of the subscriber part.

            if (subscriberPartOfEffectiveNumber.isEmpty() && seriesInitial == 0 && seriesFinal == 0) { // Match for "empty" subscriber part if series is 0-0
                 addMatch(validMatches, row, currentNdcStr, effectivePhoneNumber, prefixIdFromCallingFunction, false, seriesInitial, seriesFinal);
                 log.debug("Series match for empty subscriber part (series 0-0): {}", validMatches.get(validMatches.size()-1));
            } else if (!subscriberPartOfEffectiveNumber.isEmpty()) {
                try {
                    long subscriberNum = Long.parseLong(subscriberPartOfEffectiveNumber);
                    // We need to compare the subscriber part against the series range, considering their actual lengths.
                    // Example: series 100-199. Number 1500. Subscriber part 1500.
                    // If series_length is 3, we compare 150 against 100-199.
                    String subscriberForCompareStr = subscriberPartOfEffectiveNumber;
                    if (seriesLengthToMatch > 0 && subscriberForCompareStr.length() > seriesLengthToMatch) {
                        subscriberForCompareStr = subscriberForCompareStr.substring(0, seriesLengthToMatch);
                    }
                    long subscriberForCompareNum = Long.parseLong(subscriberForCompareStr);

                    if (seriesInitial.longValue() <= subscriberForCompareNum && seriesFinal.longValue() >= subscriberForCompareNum) {
                        addMatch(validMatches, row, currentNdcStr, effectivePhoneNumber, prefixIdFromCallingFunction, false, seriesInitial, seriesFinal);
                        log.debug("Series match: SubscriberPart '{}' (compared as {}) in range [{}-{}]. Match: {}",
                                  subscriberPartOfEffectiveNumber, subscriberForCompareNum, seriesInitial, seriesFinal, validMatches.get(validMatches.size()-1));
                    }
                } catch (NumberFormatException e) {
                    log.trace("Could not parse subscriber part '{}' as number.", subscriberPartOfEffectiveNumber);
                }
            }
        }
        
        if (!validMatches.isEmpty()) {
            validMatches.sort(Comparator.comparingLong(DestinationInfo::getSeriesRangeSize) // Smallest range first
                                         .thenComparing(di -> di.getNdc() != null ? di.getNdc().length() : 0, Comparator.reverseOrder())); // Then longest NDC
            log.debug("Found {} valid series matches. Best match: {}", validMatches.size(), validMatches.get(0));
            return Optional.of(validMatches.get(0));
        } else if (approximateMatch != null) {
            log.debug("No exact series match, returning approximate match: {}", approximateMatch);
            return Optional.of(approximateMatch);
        }
        
        log.debug("No destination indicator found for effective phone number: {}", effectivePhoneNumber);
        return Optional.empty();
    }

    // ... (addMatch, fillDestinationInfo, formatDestinationDescription, findLocalNdcForIndicator, isLocalExtended, isInternationalOrSatellite remain the same)
    private void addMatch(List<DestinationInfo> matches, Tuple row, String ndc, String originalPhoneNumber, Long prefixId, boolean isApprox, Integer seriesInitial, Integer seriesFinal) {
        DestinationInfo di = new DestinationInfo();
        fillDestinationInfo(di, row, ndc, originalPhoneNumber, prefixId, isApprox, seriesInitial, seriesFinal);
        matches.add(di);
    }
    
    private void fillDestinationInfo(DestinationInfo di, Tuple row, String ndc, String originalPhoneNumber, Long prefixId, boolean isApprox, Integer seriesInitial, Integer seriesFinal) {
        di.setIndicatorId(row.get("indicator_id", Number.class).longValue());
        Number indicatorOpIdNum = row.get("indicator_operator_id", Number.class);
        di.setOperatorId(indicatorOpIdNum != null ? indicatorOpIdNum.longValue() : null);
        di.setNdc(ndc);
        di.setDestinationDescription(formatDestinationDescription(row.get("city_name", String.class), row.get("department_country", String.class)));
        di.setMatchedPhoneNumber(originalPhoneNumber);
        di.setPrefixIdUsed(prefixId);
        Number bandIdNum = row.get("band_id", Number.class);
        di.setBandId(bandIdNum != null ? bandIdNum.longValue() : null);
        di.setApproximateMatch(isApprox);
        di.setSeriesInitial(seriesInitial);
        di.setSeriesFinal(seriesFinal);
        if (isApprox) {
            di.setDestinationDescription(di.getDestinationDescription() + " (aprox)");
        }
    }


    private String formatDestinationDescription(String city, String deptCountry) {
        StringBuilder sb = new StringBuilder();
        if (city != null && !city.isEmpty()) sb.append(city);
        if (deptCountry != null && !deptCountry.isEmpty()) {
            if (sb.length() > 0) sb.append(", ");
            sb.append(deptCountry);
        }
        return sb.toString();
    }

    @Transactional(readOnly = true)
    public String findLocalNdcForIndicator(Long indicatorId) {
        if (indicatorId == null) return "";
        String queryStr = "SELECT s.ndc FROM series s WHERE s.indicator_id = :indicatorId AND s.active = true " +
                          "GROUP BY s.ndc ORDER BY COUNT(*) DESC, s.ndc ASC LIMIT 1";
        jakarta.persistence.Query nativeQuery = entityManager.createNativeQuery(queryStr);
        nativeQuery.setParameter("indicatorId", indicatorId);
        try {
            Object result = nativeQuery.getSingleResult();
            String ndc = result != null ? String.valueOf(result) : "";
            log.debug("Local NDC for indicatorId {}: '{}'", indicatorId, ndc);
            return ndc;
        } catch (NoResultException e) {
            log.debug("No local NDC found for indicatorId {}", indicatorId);
            return "";
        }
    }

    @Transactional(readOnly = true)
    public boolean isLocalExtended(String destinationNdc, Long localOriginIndicatorId, Long destinationIndicatorId) {
        if (Objects.equals(localOriginIndicatorId, destinationIndicatorId) || destinationIndicatorId == null || localOriginIndicatorId == null || destinationIndicatorId <= 0 || localOriginIndicatorId <= 0 || destinationNdc == null) {
            return false;
        }
        
        String ndcsForLocalOriginQuery = "SELECT DISTINCT s.ndc FROM series s WHERE s.indicator_id = :localOriginIndicatorId AND s.active = true";
        List<Integer> localNdcsInt = entityManager.createNativeQuery(ndcsForLocalOriginQuery, Integer.class)
                                    .setParameter("localOriginIndicatorId", localOriginIndicatorId)
                                    .getResultList();
        List<String> localNdcs = localNdcsInt.stream().map(String::valueOf).collect(Collectors.toList());
        
        boolean isExtended = localNdcs.contains(destinationNdc);
        log.debug("IsLocalExtended check: DestNDC='{}', LocalOriginIndicatorId={}, DestIndicatorId={}. LocalNDCs for origin: {}. Result: {}",
                destinationNdc, localOriginIndicatorId, destinationIndicatorId, localNdcs, isExtended);
        return isExtended;
    }

    private boolean isLocalType(Long telephonyTypeId) {
        return telephonyTypeId != null &&
                (telephonyTypeId.equals(TelephonyTypeEnum.LOCAL.getValue()) ||
                 telephonyTypeId.equals(TelephonyTypeEnum.LOCAL_EXTENDED.getValue()));
    }

    private boolean isInternationalOrSatellite(Long telephonyTypeId) {
        return telephonyTypeId != null &&
               (telephonyTypeId.equals(TelephonyTypeEnum.INTERNATIONAL.getValue()) ||
                telephonyTypeId.equals(TelephonyTypeEnum.SATELLITE.getValue()));
    }
}