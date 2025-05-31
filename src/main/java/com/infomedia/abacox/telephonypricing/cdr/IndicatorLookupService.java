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
        // For International/Satellite, originCountryId filter on Indicator might not apply or needs to be handled differently
        // if indicators for all countries are in the same table.
        // The PHP logic removes the MPORIGEN_ID filter for these types.
        // Let's assume indicators for all destination countries are available.
        // The query for min/max NDC length should consider all relevant indicators for that telephony type.

        String queryStr = "SELECT " +
                          "COALESCE(MIN(LENGTH(s.ndc::text)), 0) as min_ndc_len, " +
                          "COALESCE(MAX(LENGTH(s.ndc::text)), 0) as max_ndc_len, " +
                          "(SELECT LENGTH(s2.initial_number::text) FROM series s2 JOIN indicator i2 ON s2.indicator_id = i2.id WHERE i2.telephony_type_id = :telephonyTypeId " +
                          (isInternationalOrSatellite(telephonyTypeId) ? "" : "AND i2.origin_country_id = :originCountryId ") + // Conditional originCountryId
                          "AND s2.active = true AND i2.active = true AND s2.initial_number >= 0 GROUP BY LENGTH(s2.initial_number::text) ORDER BY COUNT(*) DESC LIMIT 1) as common_series_len " +
                          "FROM series s JOIN indicator i ON s.indicator_id = i.id " +
                          "WHERE i.telephony_type_id = :telephonyTypeId " +
                          (isInternationalOrSatellite(telephonyTypeId) ? "" : "AND i.origin_country_id = :originCountryId ") + // Conditional originCountryId
                          "AND s.active = true AND i.active = true AND s.initial_number >= 0";

        jakarta.persistence.Query nativeQuery = entityManager.createNativeQuery(queryStr, Tuple.class);
        nativeQuery.setParameter("telephonyTypeId", telephonyTypeId);
        if (!isInternationalOrSatellite(telephonyTypeId)) {
            nativeQuery.setParameter("originCountryId", originCountryId);
        }


        IndicatorConfig config = new IndicatorConfig();
        try {
            Tuple result = (Tuple) nativeQuery.getSingleResult();
            config.minNdcLength = result.get("min_ndc_len", Number.class).intValue();
            config.maxNdcLength = result.get("max_ndc_len", Number.class).intValue();
            Number commonSeriesLenNum = result.get("common_series_len", Number.class);
            config.seriesNumberLength = (commonSeriesLenNum != null) ? commonSeriesLenNum.intValue() : 0;

            // If international/satellite and min/max NDC length is 0 (no specific country codes found as NDCs),
            // set a sensible default for country code lengths (e.g., 1 to 3 or 4 digits).
            if (isInternationalOrSatellite(telephonyTypeId) && config.minNdcLength == 0 && config.maxNdcLength == 0) {
                log.warn("No specific NDC length config found for International/Satellite type {}. Defaulting min/max NDC length to 1-4.", telephonyTypeId);
                config.minNdcLength = 1; // Smallest country code length
                config.maxNdcLength = 4; // Longest typical country code length
            }

            log.debug("Indicator config found: {}", config);
        } catch (NoResultException e) {
            log.warn("No indicator configuration found for telephonyTypeId {} (originCountryId {} if applicable). Using defaults (0).", telephonyTypeId, originCountryId);
             if (isInternationalOrSatellite(telephonyTypeId)) {
                log.warn("Defaulting min/max NDC length to 1-4 for International/Satellite type {} due to NoResultException.", telephonyTypeId);
                config.minNdcLength = 1;
                config.maxNdcLength = 4;
            }
        }
        return config;
    }


    @Transactional(readOnly = true)
    public Optional<DestinationInfo> findDestinationIndicator(
            String phoneNumberToMatch, Long telephonyTypeId, int minSubscriberLength,
            Long originIndicatorIdForBandContext, Long prefixIdFromCallingFunction, Long originCountryId,
            boolean prefixHasAssociatedBands, boolean isOperatorPrefixAlreadyStripped, String operatorPrefixToStripIfPresent) {

        log.debug("Finding destination indicator for phoneNumberToMatch: '{}', telephonyTypeId: {}, minTotalLengthForType: {}, originIndForBand: {}, prefixIdFunc: {}, originCountryId: {}, prefixHasBands: {}, opPrefixStripped: {}, opPrefixToStrip: {}",
                phoneNumberToMatch, telephonyTypeId, minSubscriberLength, originIndicatorIdForBandContext, prefixIdFromCallingFunction, originCountryId, prefixHasAssociatedBands, isOperatorPrefixAlreadyStripped, operatorPrefixToStripIfPresent);

        if (phoneNumberToMatch == null || phoneNumberToMatch.isEmpty()) {
            log.debug("Phone number to match is empty, cannot find destination.");
            return Optional.empty();
        }

        String effectiveNumberForNdcSeriesLookup = phoneNumberToMatch;
        if (effectiveNumberForNdcSeriesLookup.startsWith("+")) {
            effectiveNumberForNdcSeriesLookup = effectiveNumberForNdcSeriesLookup.substring(1);
        }

        if (!isOperatorPrefixAlreadyStripped && operatorPrefixToStripIfPresent != null && !operatorPrefixToStripIfPresent.isEmpty() &&
            effectiveNumberForNdcSeriesLookup.startsWith(operatorPrefixToStripIfPresent)) {
            effectiveNumberForNdcSeriesLookup = effectiveNumberForNdcSeriesLookup.substring(operatorPrefixToStripIfPresent.length());
            log.debug("Operator prefix '{}' stripped for NDC/Series lookup. Number is now: {}", operatorPrefixToStripIfPresent, effectiveNumberForNdcSeriesLookup);
        }
        String originalNumberForMatchStorage = effectiveNumberForNdcSeriesLookup; // Store this version

        Long effectiveTelephonyTypeId = telephonyTypeId;
        if (isLocalType(telephonyTypeId)) {
            String localNdc = findLocalNdcForIndicator(originIndicatorIdForBandContext);
            if (localNdc != null && !localNdc.isEmpty() && !effectiveNumberForNdcSeriesLookup.startsWith(localNdc)) {
                effectiveNumberForNdcSeriesLookup = localNdc + effectiveNumberForNdcSeriesLookup;
                effectiveTelephonyTypeId = TelephonyTypeEnum.NATIONAL.getValue();
                log.debug("Local type, prepended local NDC '{}'. Effective number for NDC/Series: {}, Effective Type: NATIONAL", localNdc, effectiveNumberForNdcSeriesLookup);
            }
        }

        IndicatorConfig config = getIndicatorConfigForTelephonyType(effectiveTelephonyTypeId, originCountryId);
        log.debug("Indicator config for type {}: {}", effectiveTelephonyTypeId, config);
        if (config.maxNdcLength == 0 && !isLocalType(effectiveTelephonyTypeId) && !isInternationalOrSatellite(effectiveTelephonyTypeId)) {
             log.debug("Max NDC length is 0 and not local/international/satellite type, cannot find destination.");
            return Optional.empty();
        }


        List<String> ndcCandidates = new ArrayList<>();
        int phoneLenForNdcSeries = effectiveNumberForNdcSeriesLookup.length();
        int configuredMinTotalLengthForType = minSubscriberLength;

        if (phoneLenForNdcSeries >= configuredMinTotalLengthForType) {
            log.trace("Phone length {} >= configured min total length {} for type {}. Generating NDC candidates.",
                phoneLenForNdcSeries, configuredMinTotalLengthForType, effectiveTelephonyTypeId);
            for (int i = config.minNdcLength; i <= config.maxNdcLength; i++) {
                if (i > 0 && phoneLenForNdcSeries >= i) {
                    String candidateNdc = effectiveNumberForNdcSeriesLookup.substring(0, i);
                    if (candidateNdc.matches("-?\\d+")) {
                        ndcCandidates.add(candidateNdc);
                        log.trace("Added NDC candidate: '{}' (length {})", candidateNdc, i);
                    }
                }
            }
        } else {
            log.debug("Effective number length {} is less than configured min total length {} for type {}. No NDC candidates generated.",
                phoneLenForNdcSeries, configuredMinTotalLengthForType, effectiveTelephonyTypeId);
        }

        if (isLocalType(effectiveTelephonyTypeId) && phoneLenForNdcSeries >= configuredMinTotalLengthForType) {
             if (config.minNdcLength == 0 && config.maxNdcLength == 0 && !ndcCandidates.contains("0")) {
                 ndcCandidates.add("0");
                 log.trace("Added '0' as NDC candidate for local type processing as min/max NDC length is 0.");
             }
        }
        log.debug("NDC candidates for '{}': {}", effectiveNumberForNdcSeriesLookup, ndcCandidates);

        if (ndcCandidates.isEmpty()) {
             log.debug("No NDC candidates for '{}', cannot find destination.", effectiveNumberForNdcSeriesLookup);
             return Optional.empty();
        }

        StringBuilder queryBuilder = new StringBuilder(
            "SELECT i.id as indicator_id, i.operator_id as indicator_operator_id, s.ndc as ndc_val, i.department_country, i.city_name, s.initial_number, s.final_number, b.id as band_id " +
            "FROM series s JOIN indicator i ON s.indicator_id = i.id ");

        if (prefixHasAssociatedBands && prefixIdFromCallingFunction != null) {
            queryBuilder.append("LEFT JOIN band b ON b.prefix_id = :prefixIdFunc AND b.active = true ");
            queryBuilder.append("LEFT JOIN band_indicator bi ON bi.band_id = b.id AND bi.indicator_id = i.id ");
            queryBuilder.append("AND (b.origin_indicator_id = 0 OR b.origin_indicator_id = :originIndicatorIdForBandContext) ");
        } else {
            queryBuilder.append("LEFT JOIN band b ON 1=0 ");
             if (prefixIdFromCallingFunction != null) {
                 queryBuilder.append("AND (i.operator_id = 0 OR i.operator_id = (SELECT p.operator_id FROM prefix p WHERE p.id = :prefixIdFunc AND p.active = true)) ");
            }
        }

        queryBuilder.append("WHERE i.active = true AND s.active = true AND i.telephony_type_id = :effectiveTelephonyTypeId ");
        List<Integer> ndcIntCandidates = ndcCandidates.stream()
                                                      .filter(sVal -> !sVal.isEmpty() && sVal.matches("-?\\d+"))
                                                      .map(Integer::parseInt)
                                                      .collect(Collectors.toList());
        if (!ndcIntCandidates.isEmpty()) {
            queryBuilder.append("AND s.ndc IN (:ndcCandidatesInt) ");
        } else {
             log.warn("No valid numeric NDC candidates. This might lead to no results.");
             return Optional.empty();
        }

        // PHP: $filtro_origen is NOT applied for International/Satellite
        if (!isInternationalOrSatellite(effectiveTelephonyTypeId)) {
            queryBuilder.append("AND i.origin_country_id = :originCountryId ");
        }
        
        if (prefixHasAssociatedBands) {
            queryBuilder.append("ORDER BY b.origin_indicator_id DESC NULLS LAST, LENGTH(s.ndc::text) DESC, s.initial_number ASC, s.final_number ASC");
        } else {
            queryBuilder.append("ORDER BY LENGTH(s.ndc::text) DESC, s.initial_number ASC, s.final_number ASC");
        }

        jakarta.persistence.Query nativeQuery = entityManager.createNativeQuery(queryBuilder.toString(), Tuple.class);
        nativeQuery.setParameter("effectiveTelephonyTypeId", effectiveTelephonyTypeId);
        if (!ndcIntCandidates.isEmpty()) {
            nativeQuery.setParameter("ndcCandidatesInt", ndcIntCandidates);
        }

        if (prefixHasAssociatedBands && prefixIdFromCallingFunction != null) {
            nativeQuery.setParameter("prefixIdFunc", prefixIdFromCallingFunction);
            nativeQuery.setParameter("originIndicatorIdForBandContext", originIndicatorIdForBandContext);
        } else if (prefixIdFromCallingFunction != null) {
            nativeQuery.setParameter("prefixIdFunc", prefixIdFromCallingFunction);
        }
        if (!isInternationalOrSatellite(effectiveTelephonyTypeId)) {
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

            if (effectiveNumberForNdcSeriesLookup.startsWith(currentNdcStr)) {
                 subscriberPartOfEffectiveNumber = effectiveNumberForNdcSeriesLookup.substring(currentNdcStr.length());
            } else if ((currentNdcStr.equals("0") || currentNdcStr.isEmpty()) && isLocalType(effectiveTelephonyTypeId)) {
                subscriberPartOfEffectiveNumber = effectiveNumberForNdcSeriesLookup;
            } else {
                log.trace("NDC {} does not prefix effectiveNumberForNdcSeriesLookup {}, skipping row.", currentNdcStr, effectiveNumberForNdcSeriesLookup);
                continue;
            }
            
            if (!subscriberPartOfEffectiveNumber.matches("\\d*")) {
                log.trace("Subscriber part '{}' is not purely numeric, skipping series check for this row.", subscriberPartOfEffectiveNumber);
                continue;
            }
            
            Integer seriesInitialInt = row.get("initial_number", Integer.class);
            Integer seriesFinalInt = row.get("final_number", Integer.class);
            
            log.trace("Comparing effectiveNum: '{}' (subscriber part: '{}') against series NDC: '{}', initial: '{}', final: '{}'",
                effectiveNumberForNdcSeriesLookup, subscriberPartOfEffectiveNumber, currentNdcStr, seriesInitialInt, seriesFinalInt);

            if (Integer.parseInt(currentNdcStr) < 0) { // PHP: if (1 * $info_indica['SERIE_NDC'] < 0)
                if (approximateMatch == null) { // Take the first one found for negative NDCs
                    approximateMatch = new DestinationInfo();
                    fillDestinationInfo(approximateMatch, row, currentNdcStr, originalNumberForMatchStorage, prefixIdFromCallingFunction, true, seriesInitialInt, seriesFinalInt);
                    log.debug("Found approximate match (negative NDC): {}", approximateMatch);
                }
                continue; // Don't process further for this row if it's an approximate match
            }
            
            String seriesInitialOriginalStr = seriesInitialInt.toString();
            String seriesFinalOriginalStr = seriesFinalInt.toString();
            int subscriberLength = subscriberPartOfEffectiveNumber.length();

            String seriesInitialPadded = seriesInitialOriginalStr;
            String seriesFinalPadded = seriesFinalOriginalStr;

            int diffLenSeries = seriesFinalOriginalStr.length() - seriesInitialOriginalStr.length();
            if (diffLenSeries > 0) {
                seriesInitialPadded = String.format("%0" + seriesFinalOriginalStr.length() + "d", seriesInitialInt);
            } else if (diffLenSeries < 0) {
                seriesFinalPadded = String.format("%-" + seriesInitialOriginalStr.length() + "s", seriesFinalOriginalStr).replace(' ', '9');
            }
            
            int currentPaddedSeriesLength = seriesInitialPadded.length();
            if (subscriberLength != currentPaddedSeriesLength) {
                seriesInitialPadded = String.format("%-" + subscriberLength + "s", seriesInitialPadded).replace(' ', '0');
                seriesFinalPadded = String.format("%-" + subscriberLength + "s", seriesFinalPadded).replace(' ', '9');
            }

            String fullComparableSeriesInitial = currentNdcStr + seriesInitialPadded;
            String fullComparableSeriesFinal = currentNdcStr + seriesFinalPadded;
            
            String numberToCompareAgainstSeries = currentNdcStr + subscriberPartOfEffectiveNumber;

            if (numberToCompareAgainstSeries.compareTo(fullComparableSeriesInitial) >= 0 &&
                numberToCompareAgainstSeries.compareTo(fullComparableSeriesFinal) <= 0) {
                addMatch(validMatches, row, currentNdcStr, originalNumberForMatchStorage, prefixIdFromCallingFunction, false, seriesInitialInt, seriesFinalInt);
                log.debug("Series match: Number '{}' (used for compare: '{}') in range [{}-{}]. Match: {}",
                          effectiveNumberForNdcSeriesLookup, numberToCompareAgainstSeries, fullComparableSeriesInitial, fullComparableSeriesFinal, validMatches.get(validMatches.size()-1));
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
        
        log.debug("No destination indicator found for effective phone number: {}", effectiveNumberForNdcSeriesLookup);
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
        di.setMatchedPhoneNumber(originalPhoneNumber); // Store the number that was used for matching after operator prefix strip
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
            String ndc = result != null ? String.valueOf(((Number)result).intValue()) : "";
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
        List<Number> localNdcsNum = entityManager.createNativeQuery(ndcsForLocalOriginQuery)
                                    .setParameter("localOriginIndicatorId", localOriginIndicatorId)
                                    .getResultList();
        List<String> localNdcs = localNdcsNum.stream().map(n -> String.valueOf(n.intValue())).collect(Collectors.toList());
        
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