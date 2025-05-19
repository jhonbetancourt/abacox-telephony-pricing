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
            log.debug("Indicator config found: {}", config);
        } catch (NoResultException e) {
            log.warn("No indicator configuration found for telephonyTypeId {} and originCountryId {}", telephonyTypeId, originCountryId);
        }
        return config;
    }

    /**
     * PHP equivalent: buscarDestino (database lookup part)
     */
    @Transactional(readOnly = true)
    public Optional<DestinationInfo> findDestinationIndicator(
            String phoneNumber, Long telephonyTypeId, int minPhoneNumberLengthAfterNdc,
            Long originIndicatorIdForBandContext, Long prefixId, Long originCountryId, boolean useBandSpecificLookup) {
        log.debug("Finding destination indicator for phoneNumber: '{}', telephonyTypeId: {}, minLenAfterNdc: {}, originIndicatorIdForBand: {}, prefixId: {}, originCountryId: {}, useBandLookup: {}",
                phoneNumber, telephonyTypeId, minPhoneNumberLengthAfterNdc, originIndicatorIdForBandContext, prefixId, originCountryId, useBandSpecificLookup);

        if (phoneNumber == null || phoneNumber.isEmpty()) {
            log.debug("Phone number is empty, cannot find destination.");
            return Optional.empty();
        }

        String effectivePhoneNumber = phoneNumber;
        // PHP: if (_esLocal($tipotele_id)) { $telefono = $indicativo_origen.$telefono; }
        if (isLocalType(telephonyTypeId)) {
            String localNdc = findLocalNdcForIndicator(originIndicatorIdForBandContext); // PHP: $indicativo_origen = BuscarIndicativoLocal(...)
            if (localNdc != null && !localNdc.isEmpty() && !phoneNumber.startsWith(localNdc)) {
                effectivePhoneNumber = localNdc + phoneNumber;
                log.debug("Local type, prepended local NDC '{}'. Effective number: {}", localNdc, effectivePhoneNumber);
            }
        }

        IndicatorConfig config = getIndicatorConfigForTelephonyType(telephonyTypeId, originCountryId);
        log.debug("Indicator config for type {}: {}", telephonyTypeId, config);
        if (config.maxNdcLength == 0 && !isLocalType(telephonyTypeId)) { // Local type might have NDC 0
            log.debug("Max NDC length is 0 and not local type, cannot find destination.");
            return Optional.empty();
        }

        List<String> ndcCandidates = new ArrayList<>();
        int phoneLen = effectivePhoneNumber.length();

        for (int i = config.minNdcLength; i <= config.maxNdcLength; i++) {
            if (i > 0 && phoneLen >= i) { // NDC length must be positive
                // PHP: if ($len_telefono - $i >= $tipotele_min_tmp)
                if ((phoneLen - i) >= minPhoneNumberLengthAfterNdc) {
                    String candidateNdc = effectivePhoneNumber.substring(0, i);
                    if (candidateNdc.matches("\\d+")) { // is_numeric
                        ndcCandidates.add(candidateNdc);
                    }
                }
            }
        }
        // PHP: if ($serievalida) { ... $maxtam = $lindicasel['len_series']; if ($maxtam > 0) { $condicion = "SERIE_INICIAL <= $restomin AND SERIE_FINAL >= $restomin"; } }
        // This series length check is applied in the loop below.

        // Handle case where NDC might be "0" or empty for local calls if not found above
        if (ndcCandidates.isEmpty() && isLocalType(telephonyTypeId)) {
            String localNdc = findLocalNdcForIndicator(originIndicatorIdForBandContext);
            if (localNdc != null && !localNdc.isEmpty() && effectivePhoneNumber.startsWith(localNdc)) {
                ndcCandidates.add(localNdc);
            } else if (localNdc != null && localNdc.equals("0")) { // Special case for "0" NDC
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

        if (useBandSpecificLookup && prefixId != null) {
            queryBuilder.append("LEFT JOIN band b ON b.prefix_id = :prefixId AND b.active = true ");
            queryBuilder.append("LEFT JOIN band_indicator bi ON bi.band_id = b.id AND bi.indicator_id = i.id "); // Link band_indicator to the current indicator 'i'
            queryBuilder.append("AND (b.origin_indicator_id = 0 OR b.origin_indicator_id = :originIndicatorIdForBandContext) ");
        } else {
            queryBuilder.append("LEFT JOIN band b ON 1=0 "); // Ensure band_id is null if not using band lookup
            if (prefixId != null) { // PHP: $local_cond = "AND (INDICATIVO_OPERADOR_ID = 0 OR INDICATIVO_OPERADOR_ID in (SELECT PREFIJO_OPERADOR_ID FROM prefijo WHERE PREFIJO_ID = $prefijo_id))";
                 queryBuilder.append("AND (i.operator_id = 0 OR i.operator_id = (SELECT p.operator_id FROM prefix p WHERE p.id = :prefixId AND p.active = true)) ");
            }
        }

        queryBuilder.append("WHERE i.active = true AND s.active = true AND i.telephony_type_id = :telephonyTypeId ");
        if (!ndcCandidates.isEmpty()) {
            List<Integer> ndcIntCandidates = ndcCandidates.stream().map(Integer::parseInt).collect(Collectors.toList());
            queryBuilder.append("AND s.ndc IN (:ndcCandidates) ");
        }

        if (!isInternationalOrSatellite(telephonyTypeId)) {
            queryBuilder.append("AND i.origin_country_id = :originCountryId ");
        }
        
        if (useBandSpecificLookup) {
            queryBuilder.append("ORDER BY b.origin_indicator_id DESC NULLS LAST, LENGTH(s.ndc::text) DESC, s.initial_number ASC, s.final_number ASC");
        } else {
            queryBuilder.append("ORDER BY LENGTH(s.ndc::text) DESC, s.initial_number ASC, s.final_number ASC");
        }

        jakarta.persistence.Query nativeQuery = entityManager.createNativeQuery(queryBuilder.toString(), Tuple.class);
        nativeQuery.setParameter("telephonyTypeId", telephonyTypeId);
        if (!ndcCandidates.isEmpty()) {
            List<Integer> ndcIntCandidates = ndcCandidates.stream().map(Integer::parseInt).collect(Collectors.toList());
            nativeQuery.setParameter("ndcCandidates", ndcIntCandidates);
        }
        if (useBandSpecificLookup && prefixId != null) {
            nativeQuery.setParameter("prefixId", prefixId);
            nativeQuery.setParameter("originIndicatorIdForBandContext", originIndicatorIdForBandContext);
        } else if (prefixId != null) {
            nativeQuery.setParameter("prefixId", prefixId);
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
            String numberAfterNdcStr = effectivePhoneNumber;
            if (effectivePhoneNumber.startsWith(currentNdcStr)) {
                 numberAfterNdcStr = effectivePhoneNumber.substring(currentNdcStr.length());
            } else if (currentNdcStr.equals("0") && isLocalType(telephonyTypeId)) {
                // For local calls where NDC is "0", numberAfterNdcStr is the full effectivePhoneNumber
            } else {
                log.trace("NDC {} does not prefix effectivePhoneNumber {}, skipping row.", currentNdcStr, effectivePhoneNumber);
                continue;
            }

            Integer seriesInitial = row.get("initial_number", Integer.class);
            Integer seriesFinal = row.get("final_number", Integer.class);
            int subscriberPartLength = numberAfterNdcStr.length();

            // PHP: if (1 * $info_indica['SERIE_NDC'] < 0) { $arreglo_aprox = SeriesArreglo(... true); }
            if (Integer.parseInt(currentNdcStr) < 0) { // Special handling for negative NDCs (approximate match)
                if (approximateMatch == null) { // Take the first one found
                    approximateMatch = new DestinationInfo();
                    fillDestinationInfo(approximateMatch, row, currentNdcStr, effectivePhoneNumber, prefixId, true, seriesInitial, seriesFinal);
                    log.debug("Found approximate match (negative NDC): {}", approximateMatch);
                }
                continue; // Don't process further for this row
            }

            // PHP: $serievalida = true; ... $maxtam = $lindicasel['len_series']; if ($maxtam > 0) { $restomin = 1 * substr($resto, 0, $maxtam); $condicion = "SERIE_INICIAL <= $restomin AND SERIE_FINAL >= $restomin"; }
            // The PHP logic for $condicionum is complex. Here we simplify by checking the full number against padded series.
            // PHP's rellenaSerie logic:
            String paddedSeriesInitialStr = String.valueOf(seriesInitial);
            String paddedSeriesFinalStr = String.valueOf(seriesFinal);
            int seriesLength = config.seriesNumberLength > 0 ? config.seriesNumberLength : subscriberPartLength; // Use common length if available

            if (seriesLength > 0) { // Only pad if there's a meaningful length
                if (paddedSeriesInitialStr.length() < seriesLength) {
                    paddedSeriesInitialStr = String.format("%0" + seriesLength + "d", seriesInitial);
                } else if (paddedSeriesInitialStr.length() > seriesLength) {
                    paddedSeriesInitialStr = paddedSeriesInitialStr.substring(0, seriesLength); // Truncate if too long
                }

                if (paddedSeriesFinalStr.length() < seriesLength) {
                    paddedSeriesFinalStr = String.format("%-" + seriesLength + "s", String.valueOf(seriesFinal)).replace(' ', '9');
                } else if (paddedSeriesFinalStr.length() > seriesLength) {
                    paddedSeriesFinalStr = paddedSeriesFinalStr.substring(0, seriesLength); // Truncate
                }
            }

            String subscriberPartToCompare = numberAfterNdcStr;
            if (seriesLength > 0 && subscriberPartToCompare.length() > seriesLength) {
                subscriberPartToCompare = subscriberPartToCompare.substring(0, seriesLength);
            }
            
            log.trace("Comparing effectiveNum: '{}' (subscriber part: '{}') against series NDC: '{}', initial: '{}' (padded: '{}'), final: '{}' (padded: '{}')",
                effectivePhoneNumber, numberAfterNdcStr, currentNdcStr, seriesInitial, paddedSeriesInitialStr, seriesFinal, paddedSeriesFinalStr);

            if (subscriberPartToCompare.compareTo(paddedSeriesInitialStr) >= 0 &&
                subscriberPartToCompare.compareTo(paddedSeriesFinalStr) <= 0) {
                addMatch(validMatches, row, currentNdcStr, effectivePhoneNumber, prefixId, false, seriesInitial, seriesFinal);
                log.debug("Series match found: {}", validMatches.get(validMatches.size()-1));
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