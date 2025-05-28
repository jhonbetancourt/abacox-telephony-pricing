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

    @Transactional(readOnly = true)
    public IndicatorConfig getIndicatorConfigForTelephonyType(Long telephonyTypeId, Long originCountryId) {
        log.debug("Getting indicator config for telephonyTypeId: {}, originCountryId: {}", telephonyTypeId, originCountryId);
        // PHP: CargarIndicativos -> SELECT INDICATIVO_TIPOTELE_ID, MIN(SERIE_NDC) AS MIN, MAX(SERIE_NDC) AS MAX, ...
        // PHP: $indicamin = strlen($lindicasel['min'].''); $indicamax = strlen($lindicasel['max'].'');
        // PHP: $lenok = 0; if (strlen($minserieini) == strlen($maxserieini)) { $maxini = strlen($maxserieini); } ... if ($maxini == $maxfin) { $lenok = $maxfin; }

        String ndcStatsQueryStr = "SELECT " +
                "COALESCE(MIN(LENGTH(s.ndc::text)), 0) as min_ndc_len, " +
                "COALESCE(MAX(LENGTH(s.ndc::text)), 0) as max_ndc_len " +
                "FROM series s JOIN indicator i ON s.indicator_id = i.id " +
                "WHERE i.telephony_type_id = :telephonyTypeId AND i.origin_country_id = :originCountryId " +
                "AND s.active = true AND i.active = true AND s.initial_number >= 0"; // Ensure ndc is treated as string for length

        jakarta.persistence.Query ndcStatsQuery = entityManager.createNativeQuery(ndcStatsQueryStr, Tuple.class);
        ndcStatsQuery.setParameter("telephonyTypeId", telephonyTypeId);
        ndcStatsQuery.setParameter("originCountryId", originCountryId);

        IndicatorConfig config = new IndicatorConfig();
        try {
            Tuple ndcResult = (Tuple) ndcStatsQuery.getSingleResult();
            config.minNdcLength = ndcResult.get("min_ndc_len", Number.class).intValue();
            config.maxNdcLength = ndcResult.get("max_ndc_len", Number.class).intValue();
        } catch (NoResultException e) {
            log.warn("No NDC stats found for telephonyTypeId {} and originCountryId {}. Min/Max NDC length will be 0.", telephonyTypeId, originCountryId);
            config.minNdcLength = 0;
            config.maxNdcLength = 0;
        }

        // Get common series length (most frequent length of initial_number)
        String commonSeriesLenQueryStr = "SELECT LENGTH(s.initial_number::text) as series_len, COUNT(*) as occurrences " +
                "FROM series s JOIN indicator i ON s.indicator_id = i.id " +
                "WHERE i.telephony_type_id = :telephonyTypeId AND i.origin_country_id = :originCountryId " +
                "AND s.active = true AND i.active = true AND s.initial_number >= 0 " +
                "GROUP BY LENGTH(s.initial_number::text) ORDER BY occurrences DESC, series_len DESC LIMIT 1"; // Get most common, then longest if tie

        jakarta.persistence.Query commonSeriesLenQuery = entityManager.createNativeQuery(commonSeriesLenQueryStr, Tuple.class);
        commonSeriesLenQuery.setParameter("telephonyTypeId", telephonyTypeId);
        commonSeriesLenQuery.setParameter("originCountryId", originCountryId);

        try {
            Tuple seriesLenResult = (Tuple) commonSeriesLenQuery.getSingleResult();
            config.seriesNumberLength = seriesLenResult.get("series_len", Number.class).intValue();
        } catch (NoResultException e) {
            log.warn("No common series length found for telephonyTypeId {} and originCountryId {}. Series length will be 0.", telephonyTypeId, originCountryId);
            config.seriesNumberLength = 0;
        }


        // PHP specific fallback for CELLULAR if no config found
        if (telephonyTypeId != null && telephonyTypeId.equals(TelephonyTypeEnum.CELLULAR.getValue())) {
            if (config.minNdcLength == 0) config.minNdcLength = 3; // Default for cellular
            if (config.maxNdcLength == 0) config.maxNdcLength = 3; // Default for cellular
            if (config.seriesNumberLength == 0) config.seriesNumberLength = 7; // Default for cellular
        }
        log.debug("Indicator config resolved: {}", config);
        return config;
    }


    @Transactional(readOnly = true)
    public Optional<DestinationInfo> findDestinationIndicator(
            String phoneNumber, Long telephonyTypeId, int minTotalNumberLengthForType, // This is TIPOTELECFG_MIN (length of NDC + Subscriber)
            Long originIndicatorIdForBandContext, Long prefixIdFromCallingFunction, Long originCountryId,
            boolean useBandSpecificLookup, boolean isOperatorPrefixAlreadyStripped, String operatorPrefixToStripIfPresent) {

        log.debug("Finding destination indicator for phoneNumber: '{}', telephonyTypeId: {}, minTotalNumLenForType: {}, originIndForBand: {}, prefixIdFunc: {}, originCountryId: {}, useBandLookup: {}, opPrefixStripped: {}, opPrefixToStrip: {}",
                phoneNumber, telephonyTypeId, minTotalNumberLengthForType, originIndicatorIdForBandContext, prefixIdFromCallingFunction, originCountryId, useBandSpecificLookup, isOperatorPrefixAlreadyStripped, operatorPrefixToStripIfPresent);

        if (phoneNumber == null || phoneNumber.isEmpty()) {
            log.debug("Phone number is empty, cannot find destination.");
            return Optional.empty();
        }

        String effectivePhoneNumber = phoneNumber;
        if (effectivePhoneNumber.startsWith("+")) {
            effectivePhoneNumber = effectivePhoneNumber.substring(1);
            log.trace("Stripped leading '+' from phone number. Now: {}", effectivePhoneNumber);
        }

        if (!isOperatorPrefixAlreadyStripped && operatorPrefixToStripIfPresent != null && !operatorPrefixToStripIfPresent.isEmpty() && effectivePhoneNumber.startsWith(operatorPrefixToStripIfPresent)) {
            effectivePhoneNumber = effectivePhoneNumber.substring(operatorPrefixToStripIfPresent.length());
            log.debug("Operator prefix '{}' stripped. Effective number for NDC/Series: {}", operatorPrefixToStripIfPresent, effectivePhoneNumber);
        }

        if (isLocalType(telephonyTypeId)) {
            String localNdc = findLocalNdcForIndicator(originIndicatorIdForBandContext);
            if (localNdc != null && !localNdc.isEmpty() && !effectivePhoneNumber.startsWith(localNdc)) {
                effectivePhoneNumber = localNdc + effectivePhoneNumber;
                log.debug("Local type, prepended local NDC '{}'. Effective number for NDC/Series: {}", localNdc, effectivePhoneNumber);
            }
        }

        IndicatorConfig config = getIndicatorConfigForTelephonyType(telephonyTypeId, originCountryId);
        log.debug("Indicator config for type {}: {}", telephonyTypeId, config);
        if (config.maxNdcLength == 0 && !isLocalType(telephonyTypeId)) { // Local type might have empty NDC
            log.debug("Max NDC length is 0 and not local type, cannot find destination.");
            return Optional.empty();
        }

        List<String> ndcCandidates = new ArrayList<>();
        int phoneLen = effectivePhoneNumber.length();

        // PHP: for ($i = $indicamin; $i <= $indicamax; $i ++)
        for (int currentNdcLength = config.minNdcLength; currentNdcLength <= config.maxNdcLength; currentNdcLength++) {
            if (currentNdcLength > 0 && phoneLen >= currentNdcLength) {
                // PHP: $tipotele_min_tmp = $tipotele_min - $i;
                // PHP: if ($len_telefono - $i >= $tipotele_min_tmp)
                // This translates to: length of subscriber part >= (minTotalNumberLengthForType - currentNdcLength)
                int minSubscriberPartLength = minTotalNumberLengthForType - currentNdcLength;
                if ((phoneLen - currentNdcLength) >= minSubscriberPartLength) {
                    String candidateNdc = effectivePhoneNumber.substring(0, currentNdcLength);
                    if (candidateNdc.matches("-?\\d+")) { // Allow negative for special case NDC=-1
                        ndcCandidates.add(candidateNdc);
                    }
                }
            }
        }
        // If local type and no candidates, add empty string to try matching series with empty/zero NDC
        if (ndcCandidates.isEmpty() && isLocalType(telephonyTypeId) && phoneLen >= minTotalNumberLengthForType) {
            String localNdc = findLocalNdcForIndicator(originIndicatorIdForBandContext);
            if (localNdc != null && (localNdc.isEmpty() || localNdc.equals("0"))) {
                ndcCandidates.add(localNdc); // Add the actual local NDC (empty or "0")
            } else if (localNdc != null && effectivePhoneNumber.startsWith(localNdc)) {
                 ndcCandidates.add(localNdc);
            } else {
                 ndcCandidates.add(""); // Fallback to check series with no explicit NDC
            }
        }
        log.debug("NDC candidates: {}", ndcCandidates);

        if (ndcCandidates.isEmpty()) {
             log.debug("No NDC candidates, cannot find destination.");
             return Optional.empty();
        }

        StringBuilder queryBuilder = new StringBuilder(
            "SELECT i.id as indicator_id, i.operator_id as indicator_operator_id, s.ndc as ndc_val, i.department_country, i.city_name, s.initial_number, s.final_number, b.id as band_id " +
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
        List<Integer> ndcIntCandidates = ndcCandidates.stream()
                                                      .filter(sVal -> !sVal.isEmpty() && sVal.matches("-?\\d+"))
                                                      .map(Integer::parseInt)
                                                      .collect(Collectors.toList());
        if (!ndcIntCandidates.isEmpty()) {
            queryBuilder.append("AND s.ndc IN (:ndcCandidates) ");
        } else if (ndcCandidates.contains("")) {
             queryBuilder.append("AND (s.ndc = 0 OR s.ndc IS NULL) ");
        } else {
            log.warn("No valid numeric NDC candidates for query construction.");
            return Optional.empty();
        }

        if (!isInternationalOrSatellite(telephonyTypeId)) {
            queryBuilder.append("AND i.origin_country_id = :originCountryId ");
        }
        
        // PHP: $condicionum logic for SERIE_INICIAL/FINAL is complex if series lengths vary.
        // PHP: if ($maxtam > 0) { $restomin = 1 * substr($resto, 0, $maxtam); $condicion = "SERIE_INICIAL <= $restomin AND SERIE_FINAL >= $restomin"; }
        // This means if a common series length ($maxtam = config.seriesNumberLength) exists, it truncates the subscriber part
        // to that length for comparison. If not, it implies a full number comparison against potentially padded series.
        // We will handle this in Java after fetching.

        if (useBandSpecificLookup) {
            queryBuilder.append("ORDER BY b.origin_indicator_id DESC NULLS LAST, LENGTH(s.ndc::text) DESC, s.initial_number ASC, s.final_number ASC");
        } else {
            queryBuilder.append("ORDER BY LENGTH(s.ndc::text) DESC, s.initial_number ASC, s.final_number ASC");
        }

        jakarta.persistence.Query nativeQuery = entityManager.createNativeQuery(queryBuilder.toString(), Tuple.class);
        nativeQuery.setParameter("telephonyTypeId", telephonyTypeId);
        if (!ndcIntCandidates.isEmpty()) {
            nativeQuery.setParameter("ndcCandidates", ndcIntCandidates);
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
        DestinationInfo approximateMatch = null; // For NDC = -1

        for (Tuple row : results) {
            String currentNdcStr = String.valueOf(row.get("ndc_val", Number.class).intValue());
            String subscriberPartOfEffectiveNumber;

            if (effectivePhoneNumber.startsWith(currentNdcStr)) {
                 subscriberPartOfEffectiveNumber = effectivePhoneNumber.substring(currentNdcStr.length());
            } else if ((currentNdcStr.equals("0") || currentNdcStr.isEmpty()) && isLocalType(telephonyTypeId)) {
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
                if (approximateMatch == null) {
                    approximateMatch = new DestinationInfo();
                    fillDestinationInfo(approximateMatch, row, currentNdcStr, effectivePhoneNumber, prefixIdFromCallingFunction, true, seriesInitial, seriesFinal);
                    log.debug("Found approximate match (negative NDC): {}", approximateMatch);
                }
                continue;
            }
            
            // PHP: $arreglo_serie = rellenaSerie($telefono, $info_indica['SERIE_NDC'], $info_indica['SERIE_INICIAL'], $info_indica['SERIE_FINAL']);
            // PHP: if ($arreglo_serie['inicial'] <= $telefono && $arreglo_serie['final'] >= $telefono)
            // The PHP rellenaSerie logic is complex. It pads series_initial with '0's at the end and series_final with '9's at the end
            // to match the length of (effectivePhoneNumber - NDC).
            // Then it compares the full effectivePhoneNumber against these padded full numbers.

            String fullSeriesInitialPadded;
            String fullSeriesFinalPadded;
            int subscriberLength = subscriberPartOfEffectiveNumber.length();

            // Mimic PHP's rellenaSerie padding logic
            String seriesInitialStr = String.valueOf(seriesInitial);
            String seriesFinalStr = String.valueOf(seriesFinal);

            if (seriesInitialStr.length() < subscriberLength) {
                seriesInitialStr = String.format("%-" + subscriberLength + "s", seriesInitialStr).replace(' ', '0');
            }
            if (seriesFinalStr.length() < subscriberLength) {
                seriesFinalStr = String.format("%-" + subscriberLength + "s", seriesFinalStr).replace(' ', '9');
            }
            // If series were longer than subscriber part, they are used as is for comparison with truncated subscriber part.
            // This case is less common for well-defined series matching a shorter subscriber number.
            // The primary comparison is on the subscriber part against the defined series range.

            fullSeriesInitialPadded = currentNdcStr + seriesInitialStr;
            fullSeriesFinalPadded = currentNdcStr + seriesFinalStr;

            String numberToCompare = effectivePhoneNumber;
            if (effectivePhoneNumber.length() > fullSeriesInitialPadded.length()) { // If phone number is longer than padded series
                numberToCompare = effectivePhoneNumber.substring(0, fullSeriesInitialPadded.length());
            } else if (effectivePhoneNumber.length() < fullSeriesInitialPadded.length()) {
                // This case means the series definition is longer than the number, which is unusual for a match.
                // PHP's rellenaSerie would pad the series to match the (NDC + SubscriberPart) length.
                // If effectivePhoneNumber is "12345" (NDC="1", Sub="2345") and series is "234"-"235" (NDC="1", SubInitial="234", SubFinal="235")
                // PHP rellenaSerie would make series "12340" - "12359" if subscriberLength is 4.
                // The comparison below effectively does this by comparing the numeric subscriber part.
            }

            log.trace("Padded series for comparison: Initial='{}', Final='{}'. Number to compare: '{}'",
                      fullSeriesInitialPadded, fullSeriesFinalPadded, numberToCompare);

            try {
                // The core comparison should be on the subscriber part against the series range.
                long subscriberNumValue = Long.parseLong(subscriberPartOfEffectiveNumber);
                long seriesInitialValue = Long.parseLong(seriesInitialStr); // Use the padded/truncated string value
                long seriesFinalValue = Long.parseLong(seriesFinalStr);     // Use the padded/truncated string value

                if (seriesInitialValue <= subscriberNumValue && seriesFinalValue >= subscriberNumValue) {
                     addMatch(validMatches, row, currentNdcStr, effectivePhoneNumber, prefixIdFromCallingFunction, false, seriesInitial, seriesFinal);
                     log.debug("Series match: SubscriberPart '{}' (as {}) in range [{}-{}]. Match: {}",
                                  subscriberPartOfEffectiveNumber, subscriberNumValue, seriesInitialValue, seriesFinalValue, validMatches.get(validMatches.size()-1));
                }
            } catch (NumberFormatException e) {
                log.warn("NFE during series comparison for subscriber part '{}' or padded series '{}'-'{}'",
                         subscriberPartOfEffectiveNumber, seriesInitialStr, seriesFinalStr);
            }
        }
        
        if (!validMatches.isEmpty()) {
            validMatches.sort(Comparator.comparingLong(DestinationInfo::getSeriesRangeSize)
                                         .thenComparing(di -> di.getNdc() != null ? di.getNdc().length() : 0, Comparator.reverseOrder()));
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
        // PHP: SELECT SERIE_NDC, count(*) as N FROM serie WHERE SERIE_INDICATIVO_ID = '.$indicativo_id.' GROUP BY SERIE_NDC ORDER BY N DESC, SERIE_NDC
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
        // PHP: if ($indicativolocal_id != $indicativo_id && $indicativo_id > 0 && $indicativolocal_id > 0)
        if (Objects.equals(localOriginIndicatorId, destinationIndicatorId) || destinationIndicatorId == null || localOriginIndicatorId == null || destinationIndicatorId <= 0 || localOriginIndicatorId <= 0 || destinationNdc == null) {
            return false;
        }
        
        // PHP: SELECT distinct(SERIE_NDC) as SERIE_NDC FROM serie WHERE SERIE_INDICATIVO_ID = '.$indicativolocal_id;
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