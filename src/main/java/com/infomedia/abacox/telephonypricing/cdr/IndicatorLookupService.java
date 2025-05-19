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


    /**
     * PHP equivalent: CargarIndicativos
     */
    @Transactional(readOnly = true)
    public IndicatorConfig getIndicatorConfigForTelephonyType(Long telephonyTypeId, Long originCountryId) {
        log.debug("Getting indicator config for telephonyTypeId: {}, originCountryId: {}", telephonyTypeId, originCountryId);
        String queryStr = "SELECT " +
                          "COALESCE(MIN(LENGTH(s.ndc::text)), 0) as min_ndc_len, " +
                          "COALESCE(MAX(LENGTH(s.ndc::text)), 0) as max_ndc_len, " +
                          // Get the most common length of initial_number for this telephony type and country
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
                if (config.minNdcLength == 0) config.minNdcLength = 3; // Default for cellular NDCs like 3xx
                if (config.maxNdcLength == 0) config.maxNdcLength = 3;
                if (config.seriesNumberLength == 0) config.seriesNumberLength = 7; // Default for 7-digit subscriber part
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

    /**
     * PHP equivalent: buscarDestino (database lookup part)
     * @param phoneNumber The phone number to analyze. If `isPrefixAlreadyStripped` is false, this number
     *                    is assumed to potentially contain an operator prefix that `buscarPrefijo` would handle.
     *                    If `isPrefixAlreadyStripped` is true, this number is assumed to be after operator prefix removal.
     * @param telephonyTypeId The determined telephony type ID.
     * @param minTotalNumberLength The minimum total length expected for this telephony type (from TelephonyTypeConfig).
     *                             This is used to calculate `minPhoneNumberLengthAfterNdc`.
     * @param originIndicatorIdForBandContext Indicator ID of the calling plant, for band context.
     * @param prefixIdFromCallingFunction The ID of the operator prefix if one was matched by `buscarPrefijo`. Null otherwise.
     * @param originCountryId The origin country ID.
     * @param useBandSpecificLookup True if band-specific rates should be considered.
     * @param isPrefixAlreadyStripped True if `phoneNumber` has already had an operator prefix removed (e.g., when called from `evaluarDestino_pos` after `buscarPrefijo`).
     *                               False if `phoneNumber` is raw and might contain an operator prefix (e.g., when called from `buscarOrigen`).
     */
    @Transactional(readOnly = true)
    public Optional<DestinationInfo> findDestinationIndicator(
            String phoneNumber, Long telephonyTypeId, int minTotalNumberLength,
            Long originIndicatorIdForBandContext, Long prefixIdFromCallingFunction, Long originCountryId,
            boolean useBandSpecificLookup, boolean isPrefixAlreadyStripped) {

        log.debug("Finding destination indicator for phoneNumber: '{}', telephonyTypeId: {}, minTotalNumLen: {}, originIndForBand: {}, prefixIdFunc: {}, originCountryId: {}, useBandLookup: {}, prefixStripped: {}",
                phoneNumber, telephonyTypeId, minTotalNumberLength, originIndicatorIdForBandContext, prefixIdFromCallingFunction, originCountryId, useBandSpecificLookup, isPrefixAlreadyStripped);

        if (phoneNumber == null || phoneNumber.isEmpty()) {
            log.debug("Phone number is empty, cannot find destination.");
            return Optional.empty();
        }

        String effectivePhoneNumber = phoneNumber; // This is the number to be parsed for NDC and subscriber part
        // If the prefix was NOT already stripped (e.g. coming from buscarOrigen), then effectivePhoneNumber is the raw number.
        // If it WAS stripped (e.g. coming from evaluarDestino_pos), then effectivePhoneNumber is the number after prefix removal.

        if (isLocalType(telephonyTypeId)) {
            String localNdc = findLocalNdcForIndicator(originIndicatorIdForBandContext);
            if (localNdc != null && !localNdc.isEmpty() && !effectivePhoneNumber.startsWith(localNdc)) {
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
                // minPhoneNumberLengthAfterNdc is minTotalNumberLength - currentNdcLength (i)
                int minSubscriberPartLength = minTotalNumberLength - i;
                if ((phoneLen - i) >= minSubscriberPartLength) {
                    String candidateNdc = effectivePhoneNumber.substring(0, i);
                    if (candidateNdc.matches("\\d+")) {
                        ndcCandidates.add(candidateNdc);
                    }
                }
            }
        }

        if (ndcCandidates.isEmpty() && isLocalType(telephonyTypeId)) {
            String localNdc = findLocalNdcForIndicator(originIndicatorIdForBandContext);
            if (localNdc != null && !localNdc.isEmpty() && effectivePhoneNumber.startsWith(localNdc)) {
                ndcCandidates.add(localNdc);
            } else if (localNdc != null && localNdc.equals("0")) {
                 ndcCandidates.add("0");
            }
        }
        log.debug("NDC candidates: {}", ndcCandidates);

        if (ndcCandidates.isEmpty() && !isLocalType(telephonyTypeId)) {
             log.debug("No NDC candidates and not local type, cannot find destination.");
             return Optional.empty();
        }

        StringBuilder queryBuilder = new StringBuilder(
            "SELECT i.id as indicator_id, i.operator_id as operator_id, s.ndc as ndc_val, i.department_country, i.city_name, s.initial_number, s.final_number, b.id as band_id " +
            "FROM series s JOIN indicator i ON s.indicator_id = i.id ");

        if (useBandSpecificLookup && prefixIdFromCallingFunction != null) {
            queryBuilder.append("LEFT JOIN band b ON b.prefix_id = :prefixIdFunc AND b.active = true ");
            queryBuilder.append("LEFT JOIN band_indicator bi ON bi.band_id = b.id AND bi.indicator_id = i.id ");
            queryBuilder.append("AND (b.origin_indicator_id = 0 OR b.origin_indicator_id = :originIndicatorIdForBandContext) ");
        } else {
            queryBuilder.append("LEFT JOIN band b ON 1=0 ");
            if (prefixIdFromCallingFunction != null) {
                 queryBuilder.append("AND (i.operator_id = 0 OR i.operator_id = (SELECT p.operator_id FROM prefix p WHERE p.id = :prefixIdFunc AND p.active = true)) ");
            }
        }

        queryBuilder.append("WHERE i.active = true AND s.active = true AND i.telephony_type_id = :telephonyTypeId ");
        if (!ndcCandidates.isEmpty()) {
            List<Integer> ndcIntCandidates = ndcCandidates.stream()
                                                          .filter(sVal -> sVal.matches("\\d+"))
                                                          .map(Integer::parseInt)
                                                          .collect(Collectors.toList());
            if (!ndcIntCandidates.isEmpty()) {
                queryBuilder.append("AND s.ndc IN (:ndcCandidates) ");
            } else if (!isLocalType(telephonyTypeId)) {
                 log.warn("No valid numeric NDC candidates for non-local type. This might lead to no results.");
                 return Optional.empty();
            }
        }

        if (!isInternationalOrSatellite(telephonyTypeId)) {
            queryBuilder.append("AND i.origin_country_id = :originCountryId ");
        }
        
        // PHP: $condicionum = "AND ( (SERIE_INICIAL <= $restomin AND SERIE_FINAL >= $restomin) )"
        // This part is tricky. The PHP code builds $condicionum based on $restomin which is derived from
        // the subscriber part of the number *after* the current NDC candidate ($i) is removed.
        // And $maxtam ($lindicasel['len_series']) is the expected length of this subscriber part.
        // We will filter this in Java after fetching, as $restomin changes per NDC candidate.

        if (useBandSpecificLookup) {
            queryBuilder.append("ORDER BY b.origin_indicator_id DESC NULLS LAST, LENGTH(s.ndc::text) DESC, s.initial_number ASC, s.final_number ASC");
        } else {
            queryBuilder.append("ORDER BY LENGTH(s.ndc::text) DESC, s.initial_number ASC, s.final_number ASC");
        }

        jakarta.persistence.Query nativeQuery = entityManager.createNativeQuery(queryBuilder.toString(), Tuple.class);
        nativeQuery.setParameter("telephonyTypeId", telephonyTypeId);
        if (!ndcCandidates.isEmpty()) {
             List<Integer> ndcIntCandidates = ndcCandidates.stream()
                                                          .filter(sVal -> sVal.matches("\\d+"))
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
            } else if (currentNdcStr.equals("0") && isLocalType(telephonyTypeId)) {
                subscriberPartOfEffectiveNumber = effectivePhoneNumber; // For local "0" NDC, the whole number is subscriber part
            } else {
                log.trace("NDC {} does not prefix effectivePhoneNumber {}, skipping row.", currentNdcStr, effectivePhoneNumber);
                continue;
            }
            
            if (!subscriberPartOfEffectiveNumber.matches("\\d*")) {
                log.trace("Subscriber part '{}' is not purely numeric, skipping series check for this row.", subscriberPartOfEffectiveNumber);
                continue;
            }
            // If subscriber part is empty, use 0 for numeric comparison, but it might indicate an issue.
            Integer subscriberPartNum = subscriberPartOfEffectiveNumber.isEmpty() ? 0 : Integer.parseInt(subscriberPartOfEffectiveNumber);

            Integer seriesInitial = row.get("initial_number", Integer.class);
            Integer seriesFinal = row.get("final_number", Integer.class);
            
            log.trace("Comparing effectiveNum: '{}' (subscriber part: '{}' -> num: {}) against series NDC: '{}', initial: '{}', final: '{}'",
                effectivePhoneNumber, subscriberPartOfEffectiveNumber, subscriberPartNum, currentNdcStr, seriesInitial, seriesFinal);

            if (Integer.parseInt(currentNdcStr) < 0) {
                if (approximateMatch == null) {
                    approximateMatch = new DestinationInfo();
                    fillDestinationInfo(approximateMatch, row, currentNdcStr, effectivePhoneNumber, prefixIdFromCallingFunction, true, seriesInitial, seriesFinal);
                    log.debug("Found approximate match (negative NDC): {}", approximateMatch);
                }
                continue;
            }
            
            // PHP: $maxtam = $lindicasel['len_series'];
            // PHP: $restomin = 1 * substr($resto, 0, $maxtam);
            // PHP: $condicion = "SERIE_INICIAL <= $restomin AND SERIE_FINAL >= $restomin";
            // This means the comparison is done on a potentially truncated subscriber part.
            int seriesComparisonLength = config.seriesNumberLength;
            if (seriesComparisonLength <= 0) { // If not configured, use actual length of subscriber part
                seriesComparisonLength = subscriberPartOfEffectiveNumber.length();
            }

            String subscriberPartForComparisonStr = subscriberPartOfEffectiveNumber;
            if (subscriberPartOfEffectiveNumber.length() > seriesComparisonLength && seriesComparisonLength > 0) {
                subscriberPartForComparisonStr = subscriberPartOfEffectiveNumber.substring(0, seriesComparisonLength);
            }
            
            Integer subscriberPartForComparisonNum;
            if (subscriberPartForComparisonStr.isEmpty()) {
                subscriberPartForComparisonNum = 0; // Or handle as error if empty subscriber part is not allowed
            } else if (!subscriberPartForComparisonStr.matches("\\d+")) {
                 log.trace("Subscriber part for comparison '{}' is not numeric. Skipping.", subscriberPartForComparisonStr);
                 continue;
            } else {
                subscriberPartForComparisonNum = Integer.parseInt(subscriberPartForComparisonStr);
            }


            if (seriesInitial <= subscriberPartForComparisonNum && seriesFinal >= subscriberPartForComparisonNum) {
                // PHP: $arreglo_serie = rellenaSerie($telefono, $info_indica['SERIE_NDC'], $info_indica['SERIE_INICIAL'], $info_indica['SERIE_FINAL']);
                // PHP: if ($arreglo_serie['inicial'] <= $telefono && $arreglo_serie['final'] >= $telefono)
                // The rellenaSerie logic is complex. The key part for cellular was that if lengths matched, no padding.
                // For cellular, `effectivePhoneNumber` is 3173631355. `currentNdcStr` is 317.
                // `longitud_serie` (from config.seriesNumberLength) is 7.
                // `longitud` (of effectivePhoneNumber) is 10. `longitud_indicativo` (of currentNdcStr) is 3.
                // `diferencia = longitud - longitud_indicativo` = 10 - 3 = 7.
                // `if (diferencia != longitud_serie)` => `if (7 != 7)` is false. So no padding.
                // Then it compares the full `effectivePhoneNumber` with `ndc + series_initial` and `ndc + series_final`.
                // This seems to be the crucial difference. The SQL filter is on the subscriber part, but the final PHP check is on the full number.

                String seriesInitialFull = currentNdcStr + String.format("%0" + (seriesComparisonLength > 0 ? seriesComparisonLength : subscriberPartOfEffectiveNumber.length()) + "d", seriesInitial);
                String seriesFinalFull = currentNdcStr + String.format("%0" + (seriesComparisonLength > 0 ? seriesComparisonLength : subscriberPartOfEffectiveNumber.length()) + "d", seriesFinal).replace(" ", "9"); // Pad with 9s if shorter

                // Ensure effectivePhoneNumber is long enough for this comparison
                String effectivePhoneForFullCompare = effectivePhoneNumber;
                if (effectivePhoneForFullCompare.length() > seriesInitialFull.length()) {
                    effectivePhoneForFullCompare = effectivePhoneForFullCompare.substring(0, seriesInitialFull.length());
                }


                if (effectivePhoneForFullCompare.compareTo(seriesInitialFull) >= 0 &&
                    effectivePhoneForFullCompare.compareTo(seriesFinalFull) <= 0) {
                    addMatch(validMatches, row, currentNdcStr, effectivePhoneNumber, prefixIdFromCallingFunction, false, seriesInitial, seriesFinal);
                    log.debug("Full number series match found: {}", validMatches.get(validMatches.size()-1));
                } else {
                     log.trace("Full number comparison failed: Effective='{}', SeriesInitialFull='{}', SeriesFinalFull='{}'", effectivePhoneForFullCompare, seriesInitialFull, seriesFinalFull);
                }
            }
        }
        
        if (!validMatches.isEmpty()) {
            validMatches.sort(Comparator.comparingLong(DestinationInfo::getSeriesRangeSize));
            log.debug("Found {} valid series matches. Best match: {}", validMatches.size(), validMatches.get(0));
            return Optional.of(validMatches.get(0));
        } else if (approximateMatch != null) {
            log.debug("No exact series match, returning approximate match: {}", approximateMatch);
            return Optional.of(approximateMatch);
        }
        
        log.debug("No destination indicator found for effective phone number: {}", effectivePhoneNumber);
        return Optional.empty();
    }

    private void addMatch(List<DestinationInfo> matches, Tuple row, String ndc, String originalPhoneNumber, Long prefixId, boolean isApprox, Integer seriesInitial, Integer seriesFinal) {
        DestinationInfo di = new DestinationInfo();
        fillDestinationInfo(di, row, ndc, originalPhoneNumber, prefixId, isApprox, seriesInitial, seriesFinal);
        matches.add(di);
    }
    
    private void fillDestinationInfo(DestinationInfo di, Tuple row, String ndc, String originalPhoneNumber, Long prefixId, boolean isApprox, Integer seriesInitial, Integer seriesFinal) {
        di.setIndicatorId(row.get("indicator_id", Number.class).longValue());
        Number opIdNum = row.get("operator_id", Number.class);
        di.setOperatorId(opIdNum != null ? opIdNum.longValue() : null);
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