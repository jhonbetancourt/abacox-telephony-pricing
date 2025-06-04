// File: com/infomedia/abacox/telephonypricing/cdr/IndicatorLookupService.java
package com.infomedia.abacox.telephonypricing.component.cdrprocessing;

import jakarta.persistence.EntityManager;
import jakarta.persistence.NoResultException;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.Tuple;
import lombok.extern.log4j.Log4j2;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigInteger;
import java.util.*;
import java.util.stream.Collectors;

@Service
@Log4j2
public class IndicatorLookupService {

    @PersistenceContext
    private EntityManager entityManager;

    // Constructor if needed, or @RequiredArgsConstructor if fields are final

    @Transactional(readOnly = true)
    public IndicatorConfig getIndicatorConfigForTelephonyType(Long telephonyTypeId, Long originCountryId) {
        log.debug("Getting indicator config for telephonyTypeId: {}, originCountryId: {}", telephonyTypeId, originCountryId);

        String queryStr = "SELECT " +
                          "COALESCE(MIN(LENGTH(s.ndc::text)), 0) as min_ndc_len, " +
                          "COALESCE(MAX(LENGTH(s.ndc::text)), 0) as max_ndc_len, " +
                          "(SELECT LENGTH(s2.initial_number::text) FROM series s2 JOIN indicator i2 ON s2.indicator_id = i2.id WHERE i2.telephony_type_id = :telephonyTypeId " +
                          (isInternationalOrSatellite(telephonyTypeId) ? "" : "AND i2.origin_country_id = :originCountryId ") +
                          "AND s2.active = true AND i2.active = true AND s2.initial_number >= 0 GROUP BY LENGTH(s2.initial_number::text) ORDER BY COUNT(*) DESC LIMIT 1) as common_series_len " +
                          "FROM series s JOIN indicator i ON s.indicator_id = i.id " +
                          "WHERE i.telephony_type_id = :telephonyTypeId " +
                          (isInternationalOrSatellite(telephonyTypeId) ? "" : "AND i.origin_country_id = :originCountryId ") +
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

            if (isInternationalOrSatellite(telephonyTypeId) && config.minNdcLength == 0 && config.maxNdcLength == 0) {
                log.warn("No specific NDC length config found for International/Satellite type {}. Defaulting min/max NDC length to 1-4.", telephonyTypeId);
                config.minNdcLength = 1;
                config.maxNdcLength = 4;
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
            String phoneNumberToMatch, Long telephonyTypeId, int minTotalLengthForType,
            Long originIndicatorIdForBandContext, Long prefixIdFromCallingFunction, Long originCountryId,
            boolean prefixHasAssociatedBands, boolean isOperatorPrefixAlreadyStripped, String operatorPrefixToStripIfPresent) {

        log.debug("Finding destination indicator. Input: phoneNumberToMatch='{}', telephonyTypeId={}, minTotalLengthForType={}, originIndForBand={}, prefixIdFunc={}, originCountryId={}, prefixHasBands={}, opPrefixStripped={}, opPrefixToStrip='{}'",
                phoneNumberToMatch, telephonyTypeId, minTotalLengthForType, originIndicatorIdForBandContext, prefixIdFromCallingFunction, originCountryId, prefixHasAssociatedBands, isOperatorPrefixAlreadyStripped, operatorPrefixToStripIfPresent);

        if (phoneNumberToMatch == null || phoneNumberToMatch.isEmpty()) {
            log.debug("Phone number to match is empty.");
            return Optional.empty();
        }

        String numberForProcessing = phoneNumberToMatch;
        if (numberForProcessing.startsWith("+")) {
            numberForProcessing = numberForProcessing.substring(1);
        }

        if (!isOperatorPrefixAlreadyStripped && operatorPrefixToStripIfPresent != null && !operatorPrefixToStripIfPresent.isEmpty() &&
            numberForProcessing.startsWith(operatorPrefixToStripIfPresent)) {
            numberForProcessing = numberForProcessing.substring(operatorPrefixToStripIfPresent.length());
            log.debug("Operator prefix '{}' stripped internally. Number for processing is now: '{}'", operatorPrefixToStripIfPresent, numberForProcessing);
        }
        String finalNumberUsedForMatching = numberForProcessing; // This is the number after op-prefix strip

        Long effectiveTelephonyTypeId = telephonyTypeId;
        if (isLocalType(telephonyTypeId)) {
            String localNdc = findLocalNdcForIndicator(originIndicatorIdForBandContext);
            if (localNdc != null && !localNdc.isEmpty() && !finalNumberUsedForMatching.startsWith(localNdc)) {
                finalNumberUsedForMatching = localNdc + finalNumberUsedForMatching;
                effectiveTelephonyTypeId = TelephonyTypeEnum.NATIONAL.getValue();
                log.debug("Local type, prepended local NDC '{}'. Effective number for matching: '{}', Effective Type: NATIONAL", localNdc, finalNumberUsedForMatching);
            }
        }

        IndicatorConfig config = getIndicatorConfigForTelephonyType(effectiveTelephonyTypeId, originCountryId);
        log.debug("Indicator config for effective type {}: {}", effectiveTelephonyTypeId, config);
        if (config.maxNdcLength == 0 && !isLocalType(effectiveTelephonyTypeId) && !isInternationalOrSatellite(effectiveTelephonyTypeId)) {
             log.debug("Max NDC length is 0 for non-local/international/satellite type {}, cannot find destination.", effectiveTelephonyTypeId);
            return Optional.empty();
        }

        List<String> ndcCandidates = new ArrayList<>();
        int phoneLenForNdcSeries = finalNumberUsedForMatching.length();

        for (int i = config.minNdcLength; i <= config.maxNdcLength; i++) {
            if (i > 0 && phoneLenForNdcSeries >= i) {
                // minTotalLengthForType is the length *after* operator prefix stripping
                if (phoneLenForNdcSeries >= minTotalLengthForType) {
                    String candidateNdc = finalNumberUsedForMatching.substring(0, i);
                    if (candidateNdc.matches("-?\\d+")) { // Allows negative NDCs for approximate matches
                        ndcCandidates.add(candidateNdc);
                        log.trace("Added NDC candidate: '{}' (length {}), subscriber part would be length {}", candidateNdc, i, phoneLenForNdcSeries - i);
                    }
                } else {
                     log.trace("Skipping NDC candidate of length {}: total phone length {} < min required total length {} for type", i, phoneLenForNdcSeries, minTotalLengthForType);
                }
            }
        }

        if (isLocalType(effectiveTelephonyTypeId) && phoneLenForNdcSeries >= minTotalLengthForType) {
             if (config.minNdcLength == 0 && config.maxNdcLength == 0 && !ndcCandidates.contains("0")) {
                 ndcCandidates.add("0");
                 log.trace("Added '0' as NDC candidate for local type processing as min/max NDC length is 0.");
             }
        }
        log.debug("NDC candidates for '{}': {}", finalNumberUsedForMatching, ndcCandidates);

        if (ndcCandidates.isEmpty()) {
             log.debug("No NDC candidates for '{}'.", finalNumberUsedForMatching);
             return Optional.empty();
        }

        StringBuilder queryBuilder = new StringBuilder(
            "SELECT i.id as indicator_id, i.operator_id as indicator_operator_id, s.ndc as ndc_val, i.department_country, i.city_name, s.initial_number, s.final_number, b.id as band_id " +
            "FROM series s JOIN indicator i ON s.indicator_id = i.id ");

        StringJoiner whereClauses = new StringJoiner(" AND ");
        whereClauses.add("i.active = true");
        whereClauses.add("s.active = true");
        whereClauses.add("i.telephony_type_id = :effectiveTelephonyTypeId");

        List<Integer> ndcIntCandidates = ndcCandidates.stream()
                                                      .filter(sVal -> !sVal.isEmpty() && sVal.matches("-?\\d+"))
                                                      .map(Integer::parseInt)
                                                      .collect(Collectors.toList());
        if (!ndcIntCandidates.isEmpty()) {
            whereClauses.add("s.ndc IN (:ndcCandidatesInt)");
        } else {
             log.warn("No valid numeric NDC candidates after filtering. This will likely lead to no results.");
             return Optional.empty();
        }

        if (prefixHasAssociatedBands && prefixIdFromCallingFunction != null) {
            queryBuilder.append("LEFT JOIN band b ON b.prefix_id = :prefixIdFunc AND b.active = true ");
            queryBuilder.append("LEFT JOIN band_indicator bi ON bi.band_id = b.id AND bi.indicator_id = i.id ");
            whereClauses.add("(b.origin_indicator_id = 0 OR b.origin_indicator_id = :originIndicatorIdForBandContext)");
        } else {
            queryBuilder.append("LEFT JOIN band b ON 1=0 ");
             if (prefixIdFromCallingFunction != null) {
                 whereClauses.add("(i.operator_id = 0 OR i.operator_id = (SELECT p.operator_id FROM prefix p WHERE p.id = :prefixIdFunc AND p.active = true))");
            }
        }

        if (!isInternationalOrSatellite(effectiveTelephonyTypeId)) {
            whereClauses.add("i.origin_country_id = :originCountryId");
        }
        queryBuilder.append("WHERE ").append(whereClauses.toString());

        String orderBy = "";
        if (prefixHasAssociatedBands) {
            orderBy += "b.origin_indicator_id DESC NULLS LAST, ";
        }
        orderBy += "s.ndc DESC, s.initial_number ASC, s.final_number ASC";
        queryBuilder.append(" ORDER BY ").append(orderBy);

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

        List<Tuple> results = nativeQuery.getResultList();
        log.debug("Destination indicator query returned {} results.", results.size());

        List<DestinationInfo> validMatches = new ArrayList<>();
        DestinationInfo approximateMatch = null;

        for (Tuple row : results) {
            String dbNdcStr = String.valueOf(row.get("ndc_val", Number.class).intValue());
            String subscriberPartOfEffectiveNumber;

            if (finalNumberUsedForMatching.startsWith(dbNdcStr)) {
                 subscriberPartOfEffectiveNumber = finalNumberUsedForMatching.substring(dbNdcStr.length());
            } else if ((dbNdcStr.equals("0") || dbNdcStr.isEmpty()) && isLocalType(effectiveTelephonyTypeId)) {
                subscriberPartOfEffectiveNumber = finalNumberUsedForMatching;
            } else {
                log.trace("DB NDC '{}' does not prefix finalNumberUsedForMatching '{}', skipping row.", dbNdcStr, finalNumberUsedForMatching);
                continue;
            }

            if (!subscriberPartOfEffectiveNumber.matches("\\d*")) {
                log.trace("Subscriber part '{}' is not purely numeric (or empty), skipping series check for this row.", subscriberPartOfEffectiveNumber);
                continue;
            }

            Integer seriesInitialInt = row.get("initial_number", Integer.class);
            Integer seriesFinalInt = row.get("final_number", Integer.class);

            log.trace("Comparing finalNumForMatch: '{}' (subscriber part: '{}') against DB series NDC: '{}', initial: '{}', final: '{}'",
                finalNumberUsedForMatching, subscriberPartOfEffectiveNumber, dbNdcStr, seriesInitialInt, seriesFinalInt);

            if (Integer.parseInt(dbNdcStr) < 0) {
                if (approximateMatch == null) {
                    approximateMatch = new DestinationInfo();
                    PaddedSeriesResult paddedApprox = rellenaSerieEquivalents(subscriberPartOfEffectiveNumber, seriesInitialInt.toString(), seriesFinalInt.toString());
                    String approxComparableInitial = dbNdcStr + paddedApprox.getPaddedInitial();
                    String approxComparableFinal = dbNdcStr + paddedApprox.getPaddedFinal();
                    fillDestinationInfo(approximateMatch, row, dbNdcStr, finalNumberUsedForMatching, prefixIdFromCallingFunction, true, seriesInitialInt, seriesFinalInt, approxComparableInitial, approxComparableFinal);
                    log.debug("Found approximate match (negative NDC): {}", approximateMatch);
                }
                continue;
            }

            PaddedSeriesResult paddedSeries = rellenaSerieEquivalents(subscriberPartOfEffectiveNumber, seriesInitialInt.toString(), seriesFinalInt.toString());
            String fullComparableSeriesInitial = dbNdcStr + paddedSeries.getPaddedInitial();
            String fullComparableSeriesFinal = dbNdcStr + paddedSeries.getPaddedFinal();
            String numberToCompareAgainstSeries = finalNumberUsedForMatching; // Use the full number (NDC + subscriber) for comparison

            BigInteger numToCompareBI;
            BigInteger seriesInitialBI;
            BigInteger seriesFinalBI;
            try {
                // Ensure strings are purely numeric before BigInteger conversion, or handle non-numeric NDCs if they are possible
                if (!numberToCompareAgainstSeries.matches("\\d+") || !fullComparableSeriesInitial.matches("\\d+") || !fullComparableSeriesFinal.matches("\\d+")) {
                     log.warn("Non-numeric string for BigInteger comparison. Num: '{}', Initial: '{}', Final: '{}'. Skipping series.",
                             numberToCompareAgainstSeries, fullComparableSeriesInitial, fullComparableSeriesFinal);
                     continue;
                }
                numToCompareBI = new BigInteger(numberToCompareAgainstSeries);
                seriesInitialBI = new BigInteger(fullComparableSeriesInitial);
                seriesFinalBI = new BigInteger(fullComparableSeriesFinal);
            } catch (NumberFormatException e) {
                log.warn("Could not parse numbers for BigInteger comparison: num='{}', initial='{}', final='{}'. Skipping series.",
                        numberToCompareAgainstSeries, fullComparableSeriesInitial, fullComparableSeriesFinal);
                continue;
            }

            if (numToCompareBI.compareTo(seriesInitialBI) >= 0 &&
                numToCompareBI.compareTo(seriesFinalBI) <= 0) {
                addMatch(validMatches, row, dbNdcStr, finalNumberUsedForMatching, prefixIdFromCallingFunction, false, seriesInitialInt, seriesFinalInt, fullComparableSeriesInitial, fullComparableSeriesFinal);
                log.debug("Series match: Number '{}' (used for compare: '{}') in range [{}-{}]. Match: {}",
                          finalNumberUsedForMatching, numberToCompareAgainstSeries, fullComparableSeriesInitial, fullComparableSeriesFinal, validMatches.get(validMatches.size()-1));
            }
        }

        if (!validMatches.isEmpty()) {
            validMatches.sort(Comparator.comparingLong(DestinationInfo::getPaddedSeriesRangeSize));
            log.debug("Found {} valid series matches. Best match after sorting: {}", validMatches.size(), validMatches.get(0));
            return Optional.of(validMatches.get(0));
        } else if (approximateMatch != null) {
            log.debug("No exact series match, returning approximate match: {}", approximateMatch);
            return Optional.of(approximateMatch);
        }

        log.debug("No destination indicator found for effective phone number: {}", finalNumberUsedForMatching);
        return Optional.empty();
    }

    private void addMatch(List<DestinationInfo> matches, Tuple row, String ndc, String originalPhoneNumberUsedForMatch, Long prefixId, boolean isApprox, Integer seriesInitial, Integer seriesFinal, String comparableInitial, String comparableFinal) {
        DestinationInfo di = new DestinationInfo();
        fillDestinationInfo(di, row, ndc, originalPhoneNumberUsedForMatch, prefixId, isApprox, seriesInitial, seriesFinal, comparableInitial, comparableFinal);
        matches.add(di);
    }

    private void fillDestinationInfo(DestinationInfo di, Tuple row, String ndc, String originalPhoneNumberUsedForMatch, Long prefixId, boolean isApprox, Integer seriesInitial, Integer seriesFinal, String comparableInitial, String comparableFinal) {
        di.setIndicatorId(row.get("indicator_id", Number.class).longValue());
        Number indicatorOpIdNum = row.get("indicator_operator_id", Number.class);
        di.setOperatorId(indicatorOpIdNum != null ? indicatorOpIdNum.longValue() : null);
        di.setNdc(ndc);
        di.setDestinationDescription(formatDestinationDescription(row.get("city_name", String.class), row.get("department_country", String.class)));
        di.setMatchedPhoneNumber(originalPhoneNumberUsedForMatch);
        di.setPrefixId(prefixId);
        Number bandIdNum = row.get("band_id", Number.class);
        di.setBandId(bandIdNum != null ? bandIdNum.longValue() : null);
        di.setApproximateMatch(isApprox);
        di.setSeriesInitial(seriesInitial);
        di.setSeriesFinal(seriesFinal);
        di.setComparableInitialValue(comparableInitial);
        di.setComparableFinalValue(comparableFinal);
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

    private PaddedSeriesResult rellenaSerieEquivalents(String inputSubscriberPart, String dbSeriesInitial, String dbSeriesFinal) {
        // Ensure dbSeriesInitial and dbSeriesFinal are not null
        String safeDbSeriesInitial = dbSeriesInitial != null ? dbSeriesInitial : "0";
        String safeDbSeriesFinal = dbSeriesFinal != null ? dbSeriesFinal : "0";

        int lenInitialDb = safeDbSeriesInitial.length();
        int lenFinalDb = safeDbSeriesFinal.length();
        String equalizedDbInitial = safeDbSeriesInitial;
        String equalizedDbFinal = safeDbSeriesFinal;

        if (lenInitialDb < lenFinalDb) {
            equalizedDbInitial = String.format("%0" + lenFinalDb + "d", Long.parseLong(safeDbSeriesInitial));
        } else if (lenFinalDb < lenInitialDb) {
            equalizedDbFinal = String.format("%-" + lenInitialDb + "s", safeDbSeriesFinal).replace(' ', '9');
        }

        int inputSubLen = inputSubscriberPart.length();
        int currentEqualizedSeriesLen = equalizedDbInitial.length();

        String finalPaddedInitial = equalizedDbInitial;
        String finalPaddedFinal = equalizedDbFinal;

        if (inputSubLen != currentEqualizedSeriesLen) {
            if (inputSubLen == 0) {
                finalPaddedInitial = ""; // Represents the "empty" subscriber part
                finalPaddedFinal = "";
            } else {
                 finalPaddedInitial = String.format("%-" + inputSubLen + "s", equalizedDbInitial).replace(' ', '0');
                 finalPaddedFinal = String.format("%-" + inputSubLen + "s", equalizedDbFinal).replace(' ', '9');
            }
        }
        return new PaddedSeriesResult(finalPaddedInitial, finalPaddedFinal);
    }

    public long getPaddedSeriesRangeSize(String comparableInitialValue, String comparableFinalValue) {
        if (comparableInitialValue == null || comparableFinalValue == null) {
            return Long.MAX_VALUE;
        }
        try {
            // Use BigInteger for safety with potentially long phone numbers
            // Ensure strings are purely numeric before BigInteger conversion
            if (!comparableInitialValue.matches("\\d*") || !comparableFinalValue.matches("\\d*")) {
                log.warn("Non-numeric comparableInitialValue ('{}') or comparableFinalValue ('{}') for range size calculation.", comparableInitialValue, comparableFinalValue);
                // Handle cases where NDC might be non-numeric (e.g. negative for approximate)
                // or padding resulted in non-numeric, though it shouldn't.
                // If they are not numeric, a simple length comparison or default max might be better.
                // For now, if non-numeric, assume largest range to push it down in sort.
                if (comparableInitialValue.isEmpty() && comparableFinalValue.isEmpty()) return 0; // Empty range
                return Long.MAX_VALUE;
            }
            if (comparableInitialValue.isEmpty() && !comparableFinalValue.isEmpty()) return Long.MAX_VALUE;
            if (!comparableInitialValue.isEmpty() && comparableFinalValue.isEmpty()) return Long.MAX_VALUE;
            if (comparableInitialValue.isEmpty() && comparableFinalValue.isEmpty()) return 0;


            BigInteger initial = new BigInteger(comparableInitialValue);
            BigInteger finalVal = new BigInteger(comparableFinalValue);
            return finalVal.subtract(initial).longValueExact();
        } catch (NumberFormatException e) {
            log.error("NumberFormatException during getPaddedSeriesRangeSize for initial='{}', final='{}'. Returning MAX_VALUE.", comparableInitialValue, comparableFinalValue, e);
            return Long.MAX_VALUE;
        } catch (ArithmeticException e) {
            log.error("ArithmeticException (likely longValueExact overflow) during getPaddedSeriesRangeSize for initial='{}', final='{}'. Returning MAX_VALUE.", comparableInitialValue, comparableFinalValue, e);
            return Long.MAX_VALUE;
        }
    }
}