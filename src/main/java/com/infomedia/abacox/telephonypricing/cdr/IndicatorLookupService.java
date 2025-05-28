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
        // PHP: $queryConsultaCelulink = "SELECT INDICATIVO_TIPOTELE_ID, MIN(SERIE_NDC) AS MIN, MAX(SERIE_NDC) AS MAX, ..."
        // PHP: $indicamin = strlen($lindicasel['min'].''); $indicamax = strlen($lindicasel['max'].'');
        // PHP: $maxtam = $lindicasel['len_series']; (length of most common series_initial/final)
        String queryStr = "SELECT " +
                          "COALESCE(MIN(LENGTH(s.ndc::text)), 0) as min_ndc_len, " +
                          "COALESCE(MAX(LENGTH(s.ndc::text)), 0) as max_ndc_len, " +
                          "(SELECT LENGTH(s2.initial_number::text) FROM series s2 JOIN indicator i2 ON s2.indicator_id = i2.id WHERE i2.telephony_type_id = :telephonyTypeId AND i2.origin_country_id = :originCountryId AND s2.active = true AND i2.active = true AND s2.initial_number >= 0 GROUP BY LENGTH(s2.initial_number::text) ORDER BY COUNT(*) DESC LIMIT 1) as common_series_len " +
                          "FROM series s JOIN indicator i ON s.indicator_id = i.id " +
                          "WHERE i.telephony_type_id = :telephonyTypeId AND i.origin_country_id = :originCountryId " +
                          "AND s.active = true AND i.active = true AND s.initial_number >= 0"; // initial_number >= 0 from PHP

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

            // PHP doesn't have a specific fallback for cellular here, but it's a common case.
            // If no data, min/max NDC length will be 0.
            log.debug("Indicator config found: {}", config);
        } catch (NoResultException e) {
            log.warn("No indicator configuration found for telephonyTypeId {} and originCountryId {}. Using defaults (0).", telephonyTypeId, originCountryId);
            // Defaults are already 0 in IndicatorConfig constructor
        }
        return config;
    }


    @Transactional(readOnly = true)
    public Optional<DestinationInfo> findDestinationIndicator(
            String phoneNumber, Long telephonyTypeId, int minTotalNumberLengthFromPrefixConfig,
            Long originIndicatorIdForBandContext, Long prefixIdFromCallingFunction, Long originCountryId,
            boolean prefixHasAssociatedBands, boolean isOperatorPrefixAlreadyStripped, String operatorPrefixToStripIfPresent) {

        log.debug("Finding destination indicator for phoneNumber: '{}', telephonyTypeId: {}, minTotalNumLenFromPrefix: {}, originIndForBand: {}, prefixIdFunc: {}, originCountryId: {}, prefixHasBands: {}, opPrefixStripped: {}, opPrefixToStrip: {}",
                phoneNumber, telephonyTypeId, minTotalNumberLengthFromPrefixConfig, originIndicatorIdForBandContext, prefixIdFromCallingFunction, originCountryId, prefixHasAssociatedBands, isOperatorPrefixAlreadyStripped, operatorPrefixToStripIfPresent);

        if (phoneNumber == null || phoneNumber.isEmpty()) {
            log.debug("Phone number is empty, cannot find destination.");
            return Optional.empty();
        }

        String effectivePhoneNumber = phoneNumber;
        if (effectivePhoneNumber.startsWith("+")) {
            effectivePhoneNumber = effectivePhoneNumber.substring(1);
            log.trace("Stripped leading '+' from phone number. Now: {}", effectivePhoneNumber);
        }

        String numberForNdcSeriesLookup = effectivePhoneNumber;
        if (!isOperatorPrefixAlreadyStripped && operatorPrefixToStripIfPresent != null && !operatorPrefixToStripIfPresent.isEmpty() && numberForNdcSeriesLookup.startsWith(operatorPrefixToStripIfPresent)) {
            numberForNdcSeriesLookup = numberForNdcSeriesLookup.substring(operatorPrefixToStripIfPresent.length());
            log.debug("Operator prefix '{}' stripped. Number for NDC/Series lookup: {}", operatorPrefixToStripIfPresent, numberForNdcSeriesLookup);
        }

        String originalNumberForLocalPrepend = numberForNdcSeriesLookup; // Store before potential local NDC prepend

        if (isLocalType(telephonyTypeId)) {
            String localNdc = findLocalNdcForIndicator(originIndicatorIdForBandContext);
            if (localNdc != null && !localNdc.isEmpty() && !numberForNdcSeriesLookup.startsWith(localNdc)) {
                numberForNdcSeriesLookup = localNdc + numberForNdcSeriesLookup;
                log.debug("Local type, prepended local NDC '{}'. Effective number for NDC/Series: {}", localNdc, numberForNdcSeriesLookup);
            }
        }

        IndicatorConfig config = getIndicatorConfigForTelephonyType(telephonyTypeId, originCountryId);
        log.debug("Indicator config for type {}: {}", telephonyTypeId, config);
        if (config.maxNdcLength == 0 && !isLocalType(telephonyTypeId)) {
            log.debug("Max NDC length is 0 and not local type, cannot find destination.");
            return Optional.empty();
        }

        List<String> ndcCandidates = new ArrayList<>();
        int phoneLenForNdcSeries = numberForNdcSeriesLookup.length();

        for (int i = config.minNdcLength; i <= config.maxNdcLength; i++) {
            if (i > 0 && phoneLenForNdcSeries >= i) {
                // PHP: $tipotele_min_tmp = $tipotele_min - $i;
                // PHP: if ($len_telefono - $i >= $tipotele_min_tmp)
                // This means (length of subscriber part) >= (min length of subscriber part)
                // minTotalNumberLengthFromPrefixConfig is the min length of (NDC + SubscriberPart) from PREFIJO+TIPOTELECFG
                int minSubscriberPartLength = minTotalNumberLengthFromPrefixConfig - i;
                if (minSubscriberPartLength < 0) minSubscriberPartLength = 0; // Cannot be negative

                if ((phoneLenForNdcSeries - i) >= minSubscriberPartLength) {
                    String candidateNdc = numberForNdcSeriesLookup.substring(0, i);
                    if (candidateNdc.matches("-?\\d+")) { // Allow negative for special NDC like -1
                        ndcCandidates.add(candidateNdc);
                    }
                }
            }
        }
        if (isLocalType(telephonyTypeId) && config.minNdcLength == 0 && config.maxNdcLength == 0 && phoneLenForNdcSeries >= minTotalNumberLengthFromPrefixConfig) {
             // Handle case where local numbers might not have an explicit NDC in series table (NDC=0 or empty)
             // but the number itself is long enough.
             ndcCandidates.add(""); // Represents matching series with empty/zero NDC
        }
        log.debug("NDC candidates for '{}': {}", numberForNdcSeriesLookup, ndcCandidates);

        if (ndcCandidates.isEmpty()) {
             log.debug("No NDC candidates for '{}', cannot find destination.", numberForNdcSeriesLookup);
             return Optional.empty();
        }

        StringBuilder queryBuilder = new StringBuilder(
            "SELECT i.id as indicator_id, i.operator_id as indicator_operator_id, s.ndc as ndc_val, i.department_country, i.city_name, s.initial_number, s.final_number, b.id as band_id " +
            "FROM series s JOIN indicator i ON s.indicator_id = i.id ");

        if (prefixHasAssociatedBands && prefixIdFromCallingFunction != null) { // PHP: $bandas_ok > 0
            queryBuilder.append("LEFT JOIN band b ON b.prefix_id = :prefixIdFunc AND b.active = true ");
            queryBuilder.append("LEFT JOIN band_indicator bi ON bi.band_id = b.id AND bi.indicator_id = i.id ");
            queryBuilder.append("AND (b.origin_indicator_id = 0 OR b.origin_indicator_id = :originIndicatorIdForBandContext) ");
        } else {
            queryBuilder.append("LEFT JOIN band b ON 1=0 "); // No effective band join
             if (prefixIdFromCallingFunction != null) { // PHP: elseif ($prefijo_id > 0)
                 queryBuilder.append("AND (i.operator_id = 0 OR i.operator_id = (SELECT p.operator_id FROM prefix p WHERE p.id = :prefixIdFunc AND p.active = true)) ");
            }
        }

        queryBuilder.append("WHERE i.active = true AND s.active = true AND i.telephony_type_id = :telephonyTypeId ");
        List<Integer> ndcIntCandidates = ndcCandidates.stream()
                                                      .filter(sVal -> !sVal.isEmpty() && sVal.matches("-?\\d+"))
                                                      .map(Integer::parseInt)
                                                      .collect(Collectors.toList());
        if (!ndcIntCandidates.isEmpty()) {
            queryBuilder.append("AND s.ndc IN (:ndcCandidatesInt) ");
        } else if (ndcCandidates.contains("")) {
            queryBuilder.append("AND (s.ndc = 0 OR s.ndc IS NULL) ");
        } else {
             log.warn("No valid numeric NDC candidates. This might lead to no results.");
             return Optional.empty();
        }


        if (!isInternationalOrSatellite(telephonyTypeId)) {
            queryBuilder.append("AND i.origin_country_id = :originCountryId ");
        }
        
        if (prefixHasAssociatedBands) { // PHP: $orden_cond  = "BANDA_INDICAORIGEN_ID DESC, ";
            queryBuilder.append("ORDER BY b.origin_indicator_id DESC NULLS LAST, LENGTH(s.ndc::text) DESC, s.initial_number ASC, s.final_number ASC");
        } else {
            queryBuilder.append("ORDER BY LENGTH(s.ndc::text) DESC, s.initial_number ASC, s.final_number ASC");
        }

        jakarta.persistence.Query nativeQuery = entityManager.createNativeQuery(queryBuilder.toString(), Tuple.class);
        nativeQuery.setParameter("telephonyTypeId", telephonyTypeId);
        if (!ndcIntCandidates.isEmpty()) {
            nativeQuery.setParameter("ndcCandidatesInt", ndcIntCandidates);
        }

        if (prefixHasAssociatedBands && prefixIdFromCallingFunction != null) {
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
        DestinationInfo approximateMatch = null; // PHP: $arreglo_aprox

        for (Tuple row : results) {
            String currentNdcStr = String.valueOf(row.get("ndc_val", Number.class).intValue());
            String subscriberPartOfEffectiveNumber;

            if (numberForNdcSeriesLookup.startsWith(currentNdcStr)) {
                 subscriberPartOfEffectiveNumber = numberForNdcSeriesLookup.substring(currentNdcStr.length());
            } else if ((currentNdcStr.equals("0") || currentNdcStr.isEmpty()) && isLocalType(telephonyTypeId)) {
                subscriberPartOfEffectiveNumber = numberForNdcSeriesLookup; // The whole number is the subscriber part
            } else {
                log.trace("NDC {} does not prefix effectiveNumberForNdcSeriesLookup {}, skipping row.", currentNdcStr, numberForNdcSeriesLookup);
                continue;
            }
            
            if (!subscriberPartOfEffectiveNumber.matches("\\d*")) {
                log.trace("Subscriber part '{}' is not purely numeric, skipping series check for this row.", subscriberPartOfEffectiveNumber);
                continue;
            }
            
            Integer seriesInitial = row.get("initial_number", Integer.class);
            Integer seriesFinal = row.get("final_number", Integer.class);
            
            log.trace("Comparing effectiveNum: '{}' (subscriber part: '{}') against series NDC: '{}', initial: '{}', final: '{}'",
                numberForNdcSeriesLookup, subscriberPartOfEffectiveNumber, currentNdcStr, seriesInitial, seriesFinal);

            if (Integer.parseInt(currentNdcStr) < 0) {
                if (approximateMatch == null) {
                    approximateMatch = new DestinationInfo();
                    fillDestinationInfo(approximateMatch, row, currentNdcStr, originalNumberForLocalPrepend, prefixIdFromCallingFunction, true, seriesInitial, seriesFinal);
                    log.debug("Found approximate match (negative NDC): {}", approximateMatch);
                }
                continue;
            }
            
            // PHP: $arreglo_serie = rellenaSerie($telefono, $info_indica['SERIE_NDC'], $info_indica['SERIE_INICIAL'], $info_indica['SERIE_FINAL']);
            // PHP: if ($arreglo_serie['inicial'] <= $telefono && $arreglo_serie['final'] >= $telefono)
            // The PHP rellenaSerie pads the *series* to match the *phone number's subscriber part length*.
            // Then compares the full phone number (NDC + subscriber part) against (NDC + padded series).
            
            int seriesLengthToMatch = subscriberPartOfEffectiveNumber.length();
            if (config.seriesNumberLength > 0) {
                seriesLengthToMatch = config.seriesNumberLength;
            }
             if (seriesLengthToMatch == 0 && subscriberPartOfEffectiveNumber.length() > 0) {
                 seriesLengthToMatch = subscriberPartOfEffectiveNumber.length();
            }

            String fullSeriesInitialForCompare = currentNdcStr + String.format("%0" + seriesLengthToMatch + "d", seriesInitial);
            String fullSeriesFinalForCompare = currentNdcStr + String.format("%0" + seriesLengthToMatch + "d", seriesFinal);
            // The number to compare against these full series numbers is the numberForNdcSeriesLookup,
            // potentially truncated or padded to match the length of fullSeriesInitialForCompare.
            String comparableEffectiveNumber = numberForNdcSeriesLookup;
            if (numberForNdcSeriesLookup.length() > fullSeriesInitialForCompare.length()) {
                comparableEffectiveNumber = numberForNdcSeriesLookup.substring(0, fullSeriesInitialForCompare.length());
            } else if (numberForNdcSeriesLookup.length() < fullSeriesInitialForCompare.length()) {
                // This case is less likely if seriesLengthToMatch is derived from subscriberPart.
                // If series are shorter, padding the number might be needed, but PHP logic seems to pad series.
            }


            if (subscriberPartOfEffectiveNumber.isEmpty() && seriesInitial == 0 && seriesFinal == 0) {
                 addMatch(validMatches, row, currentNdcStr, originalNumberForLocalPrepend, prefixIdFromCallingFunction, false, seriesInitial, seriesFinal);
                 log.debug("Series match for empty subscriber part (series 0-0): {}", validMatches.get(validMatches.size()-1));
            } else if (!subscriberPartOfEffectiveNumber.isEmpty()) {
                try {
                    // Compare the numeric value of the subscriber part against the numeric series range.
                    // The PHP logic's padding implies a string comparison after padding, but a numeric comparison is more robust.
                    // We need to ensure the subscriber part is of the "expected" length for the series.
                    String subscriberForCompareStr = subscriberPartOfEffectiveNumber;
                    if (seriesLengthToMatch > 0 && subscriberForCompareStr.length() > seriesLengthToMatch) {
                        subscriberForCompareStr = subscriberForCompareStr.substring(0, seriesLengthToMatch);
                    } else if (seriesLengthToMatch > 0 && subscriberForCompareStr.length() < seriesLengthToMatch) {
                        // If subscriber part is shorter than expected series length, it might not match
                        // unless series_initial is like 0 and series_final is like 999999 for that length.
                        // PHP's rellenaSerie pads series to match subscriber part length.
                        // Let's try to match PHP: pad series to subscriber part length.
                        // This is complex. Simpler: compare numeric values if lengths are compatible.
                        // The PHP logic is: $arreglo_serie['inicial'] <= $telefono && $arreglo_serie['final'] >= $telefono
                        // where $telefono is the full number (NDC+subscriber) and $arreglo_serie is (NDC+padded_series).
                        // This means we should compare `numberForNdcSeriesLookup` with `fullSeriesInitialForCompare` and `fullSeriesFinalForCompare`
                        // after ensuring they are of comparable form (e.g. same length or numeric).
                        // For now, let's stick to comparing the subscriber part numerically.
                    }


                    if (!subscriberForCompareStr.isEmpty()) { // Ensure it's not empty after potential truncation
                        long subscriberForCompareNum = Long.parseLong(subscriberForCompareStr);
                        if (seriesInitial.longValue() <= subscriberForCompareNum && seriesFinal.longValue() >= subscriberForCompareNum) {
                            addMatch(validMatches, row, currentNdcStr, originalNumberForLocalPrepend, prefixIdFromCallingFunction, false, seriesInitial, seriesFinal);
                            log.debug("Series match: SubscriberPart '{}' (compared as {}) in range [{}-{}]. Match: {}",
                                      subscriberPartOfEffectiveNumber, subscriberForCompareNum, seriesInitial, seriesFinal, validMatches.get(validMatches.size()-1));
                        }
                    }
                } catch (NumberFormatException e) {
                    log.trace("Could not parse subscriber part '{}' as number.", subscriberPartOfEffectiveNumber);
                }
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
        
        log.debug("No destination indicator found for effective phone number: {}", numberForNdcSeriesLookup);
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
        di.setMatchedPhoneNumber(originalPhoneNumber); // Store the number that was used for the successful series match
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
        if (indicatorId == null) return ""; // PHP returns 0 if not found, which becomes ""
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
            return ""; // Match PHP returning 0 which becomes ""
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