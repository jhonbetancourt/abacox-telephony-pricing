// File: com/infomedia/abacox/telephonypricing/cdr/IndicatorLookupService.java
package com.infomedia.abacox.telephonypricing.cdr;

import com.infomedia.abacox.telephonypricing.entity.Indicator; // Assuming you have this
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
        // PHP: SELECT INDICATIVO_TIPOTELE_ID, MIN(SERIE_NDC) AS MIN, MAX(SERIE_NDC) AS MAX, ...
        // PHP: MIN(SERIE_INICIAL) AS MINSERIEINI, MAX(SERIE_INICIAL) AS MAXSERIEINI, ...
        // PHP: $lenok = 0; if (strlen($minserieini) == strlen($maxserieini)) { $maxini = strlen($maxserieini); } ...
        // PHP: if ($maxini == $maxfin) { $lenok = $maxfin; }
        // This means it finds the most common length of series numbers for that type.

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

        if (phoneNumber == null || phoneNumber.isEmpty()) return Optional.empty();

        String effectivePhoneNumber = phoneNumber;
        // PHP: if (_esLocal($tipotele_id)) { $telefono = $indicativo_origen.$telefono; }
        if (telephonyTypeId.equals(TelephonyTypeEnum.LOCAL.getValue()) || telephonyTypeId.equals(TelephonyTypeEnum.LOCAL_EXTENDED.getValue())) {
            String localNdc = findLocalNdcForIndicator(originIndicatorIdForBandContext); // Assuming originIndicatorIdForBandContext is the plant's indicator
            if (localNdc != null && !localNdc.isEmpty() && !phoneNumber.startsWith(localNdc)) {
                effectivePhoneNumber = localNdc + phoneNumber;
                log.debug("Prepended local NDC {} to {} for local call lookup, now: {}", localNdc, phoneNumber, effectivePhoneNumber);
            }
        }


        IndicatorConfig config = getIndicatorConfigForTelephonyType(telephonyTypeId, originCountryId);
        if (config.maxNdcLength == 0 && !isLocalType(telephonyTypeId)) {
            log.debug("No NDC length configuration for non-local telephony type {}, cannot find destination.", telephonyTypeId);
            return Optional.empty();
        }

        List<String> ndcCandidates = new ArrayList<>();
        int phoneLen = effectivePhoneNumber.length();

        for (int i = config.minNdcLength; i <= config.maxNdcLength; i++) {
            if (i > 0 && phoneLen >= i) {
                // PHP: $tipotele_min_tmp = $tipotele_min - $i;
                // PHP: if ($len_telefono - $i >= $tipotele_min_tmp)
                // This means (phoneLen - i) >= (minPhoneNumberLengthAfterNdc - i), which simplifies to phoneLen >= minPhoneNumberLengthAfterNdc
                // However, PHP's $tipotele_min is the min length of the *subscriber part* after prefix and NDC.
                // So, (phoneLen - i) must be >= minPhoneNumberLengthAfterNdc (which is the min subscriber part length)
                if ((phoneLen - i) >= minPhoneNumberLengthAfterNdc) {
                    String candidateNdc = effectivePhoneNumber.substring(0, i);
                    if (candidateNdc.matches("\\d+")) {
                        ndcCandidates.add(candidateNdc);
                    }
                }
            }
        }
        // For local types, if no NDC candidates, it might mean the series.ndc is 0 or the local area code.
        // The effectivePhoneNumber should already include the local NDC if it was a local call.
        if (ndcCandidates.isEmpty() && isLocalType(telephonyTypeId)) {
            // If it's local and no specific NDC part was long enough to be a candidate,
            // it implies the whole number might be a subscriber number for an NDC like "0" or the plant's NDC.
            // We can add the plant's NDC as a candidate if not already present.
            String localNdc = findLocalNdcForIndicator(originIndicatorIdForBandContext);
            if (localNdc != null && !localNdc.isEmpty() && effectivePhoneNumber.startsWith(localNdc)) {
                ndcCandidates.add(localNdc);
            } else if (localNdc != null && localNdc.equals("0")) { // Handle case where local NDC is '0'
                 ndcCandidates.add("0");
            }
        }


        if (ndcCandidates.isEmpty() && !isLocalType(telephonyTypeId)) {
             log.debug("No NDC candidates generated for phone {} (effective: {}) and type {}", phoneNumber, effectivePhoneNumber, telephonyTypeId);
             return Optional.empty();
        }


        StringBuilder queryBuilder = new StringBuilder(
            "SELECT i.id as indicator_id, i.operator_id as operator_id, s.ndc as ndc_val, i.department_country, i.city_name, s.initial_number, s.final_number, b.id as band_id " +
            "FROM series s JOIN indicator i ON s.indicator_id = i.id ");

        if (useBandSpecificLookup && prefixId != null) {
            queryBuilder.append("LEFT JOIN band b ON b.prefix_id = :prefixId AND b.active = true ");
            queryBuilder.append("LEFT JOIN band_indicator bi ON bi.band_id = b.id AND bi.indicator_id = i.id "); // Join condition
            queryBuilder.append("AND (b.origin_indicator_id = 0 OR b.origin_indicator_id = :originIndicatorIdForBandContext) ");
        } else {
            queryBuilder.append("LEFT JOIN band b ON 1=0 "); // Dummy join for band_id column
            if (prefixId != null) {
                 queryBuilder.append("AND (i.operator_id = 0 OR EXISTS (SELECT 1 FROM prefix p WHERE p.id = :prefixId AND p.operator_id = i.operator_id AND p.active = true)) ");
            }
        }

        queryBuilder.append("WHERE i.active = true AND s.active = true AND i.telephony_type_id = :telephonyTypeId ");
        if (!ndcCandidates.isEmpty()) {
            // Convert ndcCandidates to List<Integer> for query
            List<Integer> ndcIntCandidates = ndcCandidates.stream().map(Integer::parseInt).collect(Collectors.toList());
            queryBuilder.append("AND s.ndc IN (:ndcCandidates) ");
        } else if (isLocalType(telephonyTypeId)) {
            // If local and no specific NDC candidates, allow matching series with NDC 0 (or implicit local)
            // This might be covered if findLocalNdcForIndicator returns "0" and it's added.
            // Or, if series for local numbers have a specific NDC (like the area code).
        }


        if (!isInternationalOrSatellite(telephonyTypeId)) {
            queryBuilder.append("AND i.origin_country_id = :originCountryId ");
        }
        
        // PHP: ORDER BY SERIE_NDC DESC,$orden_cond SERIE_INICIAL, SERIE_FINAL
        // $orden_cond = BANDA_INDICAORIGEN_ID DESC
        // This means prefer matches with specific band origin, then longer NDCs, then by series range.
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

        List<Tuple> results = nativeQuery.getResultList();
        List<DestinationInfo> validMatches = new ArrayList<>();
        DestinationInfo approximateMatch = null;


        for (Tuple row : results) {
            String currentNdcStr = String.valueOf(row.get("ndc_val", Number.class).intValue());
            String numberAfterNdcStr = effectivePhoneNumber;
            if (effectivePhoneNumber.startsWith(currentNdcStr)) {
                 numberAfterNdcStr = effectivePhoneNumber.substring(currentNdcStr.length());
            } else if (currentNdcStr.equals("0") && isLocalType(telephonyTypeId)) {
                // For local calls where series.ndc might be 0, numberAfterNdcStr is the full local number part
            } else {
                continue; // NDC doesn't match start of phone number
            }

            Integer seriesInitial = row.get("initial_number", Integer.class);
            Integer seriesFinal = row.get("final_number", Integer.class);

            // PHP's rellenaSerie logic:
            // $diferencia = ($longitud - $longitud_indicativo); (length of subscriber part)
            // $serie_inicial = str_pad($serie_inicial, $diferencia, '0');
            // $serie_final = str_pad($serie_final, $diferencia, '9');
            // $arreglo_serie = array('inicial' => $indicativo.$serie_inicial, 'final'=>$indicativo.$serie_final);
            // if ($arreglo_serie['inicial'] <= $telefono && $arreglo_serie['final'] >= $telefono)
            // This means the comparison is done on the full number against a fully formed (NDC + padded series) number.

            int subscriberPartLength = numberAfterNdcStr.length();
            if (subscriberPartLength == 0 && seriesInitial == 0 && seriesFinal == 0 && currentNdcStr.equals(effectivePhoneNumber)) {
                // Exact match on NDC only, for series like 0-0 (e.g. special short codes that are just NDCs)
                 addMatch(validMatches, row, currentNdcStr, effectivePhoneNumber, prefixId, false);
                 continue;
            }
            if (subscriberPartLength == 0) continue; // No subscriber part to match against series

            String paddedSeriesInitialStr = String.valueOf(seriesInitial);
            String paddedSeriesFinalStr = String.valueOf(seriesFinal);

            // Pad initial with leading zeros to match subscriber part length
            if (paddedSeriesInitialStr.length() < subscriberPartLength) {
                paddedSeriesInitialStr = String.format("%0" + subscriberPartLength + "d", seriesInitial);
            } else if (paddedSeriesInitialStr.length() > subscriberPartLength) {
                // This case is tricky. PHP's logic would effectively compare a truncated series.
                // For simplicity, if series is longer than subscriber part, it's unlikely a match unless subscriber part is a prefix of series.
                // Let's assume for now it won't match if series is longer.
                // Or, more closely to PHP, pad the *number* if it's shorter than series, but PHP pads series to number length.
            }

            // Pad final with trailing nines to match subscriber part length
            if (paddedSeriesFinalStr.length() < subscriberPartLength) {
                paddedSeriesFinalStr = String.format("%-" + subscriberPartLength + "s", String.valueOf(seriesFinal)).replace(' ', '9');
            } else if (paddedSeriesFinalStr.length() > subscriberPartLength) {
                 // Similar to initial, if series is longer.
            }
            
            String fullNumberToCompareInitial = currentNdcStr + paddedSeriesInitialStr;
            String fullNumberToCompareFinal = currentNdcStr + paddedSeriesFinalStr;
            
            // The effectivePhoneNumber is the one to compare against the constructed range
            if (effectivePhoneNumber.compareTo(fullNumberToCompareInitial) >= 0 &&
                effectivePhoneNumber.compareTo(fullNumberToCompareFinal) <= 0) {
                addMatch(validMatches, row, currentNdcStr, effectivePhoneNumber, prefixId, false);
            } else if (Integer.parseInt(currentNdcStr) < 0) { // PHP: if (1 * $info_indica['SERIE_NDC'] < 0)
                if (approximateMatch == null) {
                    approximateMatch = new DestinationInfo();
                    fillDestinationInfo(approximateMatch, row, currentNdcStr, effectivePhoneNumber, prefixId, true);
                }
            }
        }
        
        if (!validMatches.isEmpty()) {
            validMatches.sort(Comparator
                .comparing((DestinationInfo di) -> {
                    Optional<Tuple> rowOpt = results.stream().filter(r -> r.get("indicator_id", Number.class).longValue() == di.getIndicatorId() && String.valueOf(r.get("ndc_val", Number.class).intValue()).equals(di.getNdc())).findFirst();
                    if(rowOpt.isPresent()){
                        Integer sInit = rowOpt.get().get("initial_number", Integer.class);
                        Integer sFin = rowOpt.get().get("final_number", Integer.class);
                        if(sInit != null && sFin != null) return sFin - sInit; // Smaller range is better
                    }
                    return Integer.MAX_VALUE;
                })
                .thenComparing(di -> di.getNdc().length(), Comparator.reverseOrder()) // Longer NDC is more specific
                .thenComparing(di -> {
                     Optional<Tuple> rowOpt = results.stream().filter(r -> r.get("indicator_id", Number.class).longValue() == di.getIndicatorId() && String.valueOf(r.get("ndc_val", Number.class).intValue()).equals(di.getNdc())).findFirst();
                     return rowOpt.map(tuple -> tuple.get("initial_number", Integer.class)).orElse(Integer.MAX_VALUE); // Lower initial number
                })
            );
            return Optional.of(validMatches.get(0));
        } else if (approximateMatch != null) {
            return Optional.of(approximateMatch);
        }
        
        return Optional.empty();
    }

    private void addMatch(List<DestinationInfo> matches, Tuple row, String ndc, String originalPhoneNumber, Long prefixId, boolean isApprox) {
        DestinationInfo di = new DestinationInfo();
        fillDestinationInfo(di, row, ndc, originalPhoneNumber, prefixId, isApprox);
        matches.add(di);
    }
    
    private void fillDestinationInfo(DestinationInfo di, Tuple row, String ndc, String originalPhoneNumber, Long prefixId, boolean isApprox) {
        di.setIndicatorId(row.get("indicator_id", Number.class).longValue());
        Number opIdNum = row.get("operator_id", Number.class);
        di.setOperatorId(opIdNum != null ? opIdNum.longValue() : null);
        di.setNdc(ndc);
        di.setDestinationDescription(formatDestinationDescription(row.get("city_name", String.class), row.get("department_country", String.class)));
        di.setMatchedPhoneNumber(originalPhoneNumber); // Store the number that was used for matching (could be transformed)
        di.setPrefixIdUsed(prefixId);
        Number bandIdNum = row.get("band_id", Number.class);
        di.setBandId(bandIdNum != null ? bandIdNum.longValue() : null);
        di.setApproximateMatch(isApprox);
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
            return result != null ? String.valueOf(result) : "";
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
        List<Integer> localNdcsInt = entityManager.createNativeQuery(ndcsForLocalOriginQuery, Integer.class)
                                    .setParameter("localOriginIndicatorId", localOriginIndicatorId)
                                    .getResultList();
        List<String> localNdcs = localNdcsInt.stream().map(String::valueOf).collect(Collectors.toList());
        
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