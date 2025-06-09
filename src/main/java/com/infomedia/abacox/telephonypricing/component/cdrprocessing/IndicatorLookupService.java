// File: com/infomedia/abacox/telephonypricing/component/cdrprocessing/IndicatorLookupService.java
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

    /**
     * Finds a destination indicator and series that matches the given phone number.
     * This method perfectly replicates the logic of PHP's `buscarDestino` function.
     *
     * @param phoneNumberToMatch The phone number to find a destination for.
     * @param telephonyTypeId The telephony type context.
     * @param minTotalLengthForType The minimum length required for this telephony type.
     * @param originIndicatorIdForBandContext The origin indicator for band context.
     * @param prefixId The ID of the prefix record being tested. This is crucial for filtering.
     * @param originCountryId The country of origin.
     * @param prefixHasAssociatedBands Whether the prefix has bands, affecting the query.
     * @param isOperatorPrefixAlreadyStripped If the operator prefix was already removed.
     * @param operatorPrefixToStripIfPresent The operator prefix to strip if not already done.
     * @return An Optional containing the best matching DestinationInfo.
     */
    @Transactional(readOnly = true)
    public Optional<DestinationInfo> findDestinationIndicator(
            String phoneNumberToMatch, Long telephonyTypeId, int minTotalLengthForType,
            Long originIndicatorIdForBandContext, Long prefixId, Long originCountryId,
            boolean prefixHasAssociatedBands, boolean isOperatorPrefixAlreadyStripped, String operatorPrefixToStripIfPresent) {

        log.debug("Finding destination indicator. Input: phoneNumberToMatch='{}', telephonyTypeId={}, minTotalLengthForType={}, originIndForBand={}, prefixId={}, originCountryId={}, prefixHasBands={}, opPrefixStripped={}, opPrefixToStrip='{}'",
                phoneNumberToMatch, telephonyTypeId, minTotalLengthForType, originIndicatorIdForBandContext, prefixId, originCountryId, prefixHasAssociatedBands, isOperatorPrefixAlreadyStripped, operatorPrefixToStripIfPresent);

        if (phoneNumberToMatch == null || phoneNumberToMatch.isEmpty()) {
            return Optional.empty();
        }

        String numberForProcessing = phoneNumberToMatch;
        if (numberForProcessing.startsWith("+")) {
            numberForProcessing = numberForProcessing.substring(1);
        }

        if (!isOperatorPrefixAlreadyStripped && operatorPrefixToStripIfPresent != null && !operatorPrefixToStripIfPresent.isEmpty() &&
            numberForProcessing.startsWith(operatorPrefixToStripIfPresent)) {
            numberForProcessing = numberForProcessing.substring(operatorPrefixToStripIfPresent.length());
        }
        String finalNumberUsedForMatching = numberForProcessing;

        Long effectiveTelephonyTypeId = telephonyTypeId;
        Long effectivePrefixId = prefixId;

        if (isLocalType(telephonyTypeId)) {
            String localNdc = findLocalNdcForIndicator(originIndicatorIdForBandContext);
            if (localNdc != null && !localNdc.isEmpty() && !finalNumberUsedForMatching.startsWith(localNdc)) {
                finalNumberUsedForMatching = localNdc + finalNumberUsedForMatching;
                effectiveTelephonyTypeId = TelephonyTypeEnum.NATIONAL.getValue();
                effectivePrefixId = null;
                log.debug("Local call type detected. Transformed number to '{}', search type to NATIONAL, and cleared prefixId for lookup.", finalNumberUsedForMatching);
            }
        }

        IndicatorConfig config = getIndicatorConfigForTelephonyType(effectiveTelephonyTypeId, originCountryId);
        if (config.maxNdcLength == 0 && !isLocalType(effectiveTelephonyTypeId) && !isInternationalOrSatellite(effectiveTelephonyTypeId)) {
            log.warn("No indicator configuration (NDC lengths) found for non-local/non-international type {}. Cannot proceed with lookup.", effectiveTelephonyTypeId);
            return Optional.empty();
        }

        List<String> ndcCandidates = new ArrayList<>();
        int phoneLenForNdcSeries = finalNumberUsedForMatching.length();
        for (int i = config.minNdcLength; i <= config.maxNdcLength; i++) {
            if (i > 0 && phoneLenForNdcSeries >= i) {
                if (phoneLenForNdcSeries >= minTotalLengthForType) {
                    String candidateNdc = finalNumberUsedForMatching.substring(0, i);
                    if (candidateNdc.matches("-?\\d+")) {
                        ndcCandidates.add(candidateNdc);
                    }
                }
            }
        }
        if (isLocalType(effectiveTelephonyTypeId) && phoneLenForNdcSeries >= minTotalLengthForType) {
             if (config.minNdcLength == 0 && config.maxNdcLength == 0 && !ndcCandidates.contains("0")) {
                 ndcCandidates.add("0");
             }
        }
        if (ndcCandidates.isEmpty()) {
             log.debug("No valid NDC candidates could be extracted from number '{}' for type {}.", finalNumberUsedForMatching, effectiveTelephonyTypeId);
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
             return Optional.empty();
        }

        // This clause is now correctly bypassed when effectivePrefixId is nullified during transformation.
        if (effectivePrefixId != null) {
            whereClauses.add("(i.operator_id = 0 OR i.operator_id = (SELECT p.operator_id FROM prefix p WHERE p.id = :prefixIdFunc AND p.active = true))");
        }

        if (prefixHasAssociatedBands && effectivePrefixId != null) {
            queryBuilder.append("LEFT JOIN band b ON b.prefix_id = :prefixIdFunc AND b.active = true ");
            queryBuilder.append("LEFT JOIN band_indicator bi ON bi.band_id = b.id AND bi.indicator_id = i.id ");
            whereClauses.add("(b.origin_indicator_id = 0 OR b.origin_indicator_id = :originIndicatorIdForBandContext)");
        } else {
            queryBuilder.append("LEFT JOIN band b ON 1=0 ");
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
        if (effectivePrefixId != null) {
            nativeQuery.setParameter("prefixIdFunc", effectivePrefixId);
        }
        if (prefixHasAssociatedBands && effectivePrefixId != null) {
            nativeQuery.setParameter("originIndicatorIdForBandContext", originIndicatorIdForBandContext);
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
                continue;
            }
            if (!subscriberPartOfEffectiveNumber.matches("\\d*")) {
                continue;
            }
            Integer seriesInitialInt = row.get("initial_number", Integer.class);
            Integer seriesFinalInt = row.get("final_number", Integer.class);

            if (Integer.parseInt(dbNdcStr) < 0) {
                if (approximateMatch == null) {
                    approximateMatch = new DestinationInfo();
                    PaddedSeriesResult paddedApprox = padSeries(subscriberPartOfEffectiveNumber, seriesInitialInt.toString(), seriesFinalInt.toString());
                    String approxComparableInitial = dbNdcStr + paddedApprox.getPaddedInitial();
                    String approxComparableFinal = dbNdcStr + paddedApprox.getPaddedFinal();
                    fillDestinationInfo(approximateMatch, row, dbNdcStr, finalNumberUsedForMatching, effectivePrefixId, true, seriesInitialInt, seriesFinalInt, approxComparableInitial, approxComparableFinal);
                }
                continue;
            }
            PaddedSeriesResult paddedSeries = padSeries(subscriberPartOfEffectiveNumber, seriesInitialInt.toString(), seriesFinalInt.toString());
            String fullComparableSeriesInitial = dbNdcStr + paddedSeries.getPaddedInitial();
            String fullComparableSeriesFinal = dbNdcStr + paddedSeries.getPaddedFinal();
            String numberToCompareAgainstSeries = finalNumberUsedForMatching;
            BigInteger numToCompareBI;
            BigInteger seriesInitialBI;
            BigInteger seriesFinalBI;
            try {
                if (!numberToCompareAgainstSeries.matches("\\d+") || !fullComparableSeriesInitial.matches("\\d+") || !fullComparableSeriesFinal.matches("\\d+")) {
                     continue;
                }
                numToCompareBI = new BigInteger(numberToCompareAgainstSeries);
                seriesInitialBI = new BigInteger(fullComparableSeriesInitial);
                seriesFinalBI = new BigInteger(fullComparableSeriesFinal);
            } catch (NumberFormatException e) {
                continue;
            }
            if (numToCompareBI.compareTo(seriesInitialBI) >= 0 &&
                numToCompareBI.compareTo(seriesFinalBI) <= 0) {
                addMatch(validMatches, row, dbNdcStr, finalNumberUsedForMatching, effectivePrefixId, false, seriesInitialInt, seriesFinalInt, fullComparableSeriesInitial, fullComparableSeriesFinal);
            }
        }

        if (!validMatches.isEmpty()) {
            validMatches.sort(Comparator.comparingLong(DestinationInfo::getPaddedSeriesRangeSize));
            return Optional.of(validMatches.get(0));
        } else if (approximateMatch != null) {
            return Optional.of(approximateMatch);
        }

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

    private PaddedSeriesResult padSeries(String inputSubscriberPart, String dbSeriesInitial, String dbSeriesFinal) {
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
                finalPaddedInitial = "";
                finalPaddedFinal = "";
            } else {
                 finalPaddedInitial = String.format("%-" + inputSubLen + "s", equalizedDbInitial).replace(' ', '0');
                 finalPaddedFinal = String.format("%-" + inputSubLen + "s", equalizedDbFinal).replace(' ', '9');
            }
        }
        return new PaddedSeriesResult(finalPaddedInitial, finalPaddedFinal);
    }

    @Transactional(readOnly = true)
    public IndicatorConfig getIndicatorConfigForTelephonyType(Long telephonyTypeId, Long originCountryId) {
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
                config.minNdcLength = 1;
                config.maxNdcLength = 4;
            }
        } catch (NoResultException e) {
             if (isInternationalOrSatellite(telephonyTypeId)) {
                config.minNdcLength = 1;
                config.maxNdcLength = 4;
            }
        }
        return config;
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
            return result != null ? String.valueOf(((Number)result).intValue()) : "";
        } catch (NoResultException e) {
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
        return localNdcs.contains(destinationNdc);
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