// File: com/infomedia/abacox/telephonypricing/cdr/IndicatorLookupService.java
package com.infomedia.abacox.telephonypricing.cdr;

import com.infomedia.abacox.telephonypricing.entity.Indicator;
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
        if (telephonyTypeId.equals(TelephonyTypeEnum.LOCAL.getValue()) || telephonyTypeId.equals(TelephonyTypeEnum.LOCAL_EXTENDED.getValue())) {
            String localNdc = findLocalNdcForIndicator(originIndicatorIdForBandContext);
            if (localNdc != null && !localNdc.isEmpty() && !phoneNumber.startsWith(localNdc)) {
                effectivePhoneNumber = localNdc + phoneNumber;
            }
        }

        IndicatorConfig config = getIndicatorConfigForTelephonyType(telephonyTypeId, originCountryId);
        if (config.maxNdcLength == 0 && !isLocalType(telephonyTypeId)) {
            return Optional.empty();
        }

        List<String> ndcCandidates = new ArrayList<>();
        int phoneLen = effectivePhoneNumber.length();

        for (int i = config.minNdcLength; i <= config.maxNdcLength; i++) {
            if (i > 0 && phoneLen >= i) {
                if ((phoneLen - i) >= minPhoneNumberLengthAfterNdc) {
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

        if (ndcCandidates.isEmpty() && !isLocalType(telephonyTypeId)) {
             return Optional.empty();
        }

        StringBuilder queryBuilder = new StringBuilder(
            "SELECT i.id as indicator_id, i.operator_id as operator_id, s.ndc as ndc_val, i.department_country, i.city_name, s.initial_number, s.final_number, b.id as band_id " +
            "FROM series s JOIN indicator i ON s.indicator_id = i.id ");

        if (useBandSpecificLookup && prefixId != null) {
            queryBuilder.append("LEFT JOIN band b ON b.prefix_id = :prefixId AND b.active = true ");
            queryBuilder.append("LEFT JOIN band_indicator bi ON bi.band_id = b.id AND bi.indicator_id = i.id ");
            queryBuilder.append("AND (b.origin_indicator_id = 0 OR b.origin_indicator_id = :originIndicatorIdForBandContext) ");
        } else {
            queryBuilder.append("LEFT JOIN band b ON 1=0 ");
            if (prefixId != null) {
                 queryBuilder.append("AND (i.operator_id = 0 OR EXISTS (SELECT 1 FROM prefix p WHERE p.id = :prefixId AND p.operator_id = i.operator_id AND p.active = true)) ");
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

        List<Tuple> results = nativeQuery.getResultList();
        List<DestinationInfo> validMatches = new ArrayList<>();
        DestinationInfo approximateMatch = null;

        for (Tuple row : results) {
            String currentNdcStr = String.valueOf(row.get("ndc_val", Number.class).intValue());
            String numberAfterNdcStr = effectivePhoneNumber;
            if (effectivePhoneNumber.startsWith(currentNdcStr)) {
                 numberAfterNdcStr = effectivePhoneNumber.substring(currentNdcStr.length());
            } else if (currentNdcStr.equals("0") && isLocalType(telephonyTypeId)) {
                // Handled
            } else {
                continue;
            }

            Integer seriesInitial = row.get("initial_number", Integer.class);
            Integer seriesFinal = row.get("final_number", Integer.class);
            int subscriberPartLength = numberAfterNdcStr.length();

            if (subscriberPartLength == 0 && seriesInitial == 0 && seriesFinal == 0 && currentNdcStr.equals(effectivePhoneNumber)) {
                 addMatch(validMatches, row, currentNdcStr, effectivePhoneNumber, prefixId, false, seriesInitial, seriesFinal);
                 continue;
            }
            if (subscriberPartLength == 0) continue;

            String paddedSeriesInitialStr = String.valueOf(seriesInitial);
            String paddedSeriesFinalStr = String.valueOf(seriesFinal);

            if (paddedSeriesInitialStr.length() < subscriberPartLength) {
                paddedSeriesInitialStr = String.format("%0" + subscriberPartLength + "d", seriesInitial);
            }
            if (paddedSeriesFinalStr.length() < subscriberPartLength) {
                paddedSeriesFinalStr = String.format("%-" + subscriberPartLength + "s", String.valueOf(seriesFinal)).replace(' ', '9');
            }
            
            String fullNumberToCompareInitial = currentNdcStr + paddedSeriesInitialStr;
            String fullNumberToCompareFinal = currentNdcStr + paddedSeriesFinalStr;
            
            if (effectivePhoneNumber.compareTo(fullNumberToCompareInitial) >= 0 &&
                effectivePhoneNumber.compareTo(fullNumberToCompareFinal) <= 0) {
                addMatch(validMatches, row, currentNdcStr, effectivePhoneNumber, prefixId, false, seriesInitial, seriesFinal);
            } else if (Integer.parseInt(currentNdcStr) < 0) {
                if (approximateMatch == null) {
                    approximateMatch = new DestinationInfo();
                    fillDestinationInfo(approximateMatch, row, currentNdcStr, effectivePhoneNumber, prefixId, true, seriesInitial, seriesFinal);
                }
            }
        }
        
        if (!validMatches.isEmpty()) {
            // PHP: ksort($series_ok); $info_indica = array_shift($series_ok);
            // ksort on key 'S'.sprintf('%020d', $diff_series) means smallest range first.
            validMatches.sort(Comparator.comparingLong(DestinationInfo::getSeriesRangeSize));
            return Optional.of(validMatches.get(0));
        } else if (approximateMatch != null) {
            return Optional.of(approximateMatch);
        }
        
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
        // PHP: SELECT SERIE_NDC, count(*) as N FROM serie WHERE SERIE_INDICATIVO_ID = $indicativo_id GROUP BY SERIE_NDC ORDER BY N DESC, SERIE_NDC
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
        // PHP: if ($indicativolocal_id != $indicativo_id && $indicativo_id > 0 && $indicativolocal_id > 0)
        if (Objects.equals(localOriginIndicatorId, destinationIndicatorId) || destinationIndicatorId == null || localOriginIndicatorId == null || destinationIndicatorId <= 0 || localOriginIndicatorId <= 0 || destinationNdc == null) {
            return false;
        }
        
        // PHP: SELECT distinct(SERIE_NDC) as SERIE_NDC FROM serie WHERE SERIE_INDICATIVO_ID = $indicativolocal_id
        String ndcsForLocalOriginQuery = "SELECT DISTINCT s.ndc FROM series s WHERE s.indicator_id = :localOriginIndicatorId AND s.active = true";
        List<Integer> localNdcsInt = entityManager.createNativeQuery(ndcsForLocalOriginQuery, Integer.class)
                                    .setParameter("localOriginIndicatorId", localOriginIndicatorId)
                                    .getResultList();
        List<String> localNdcs = localNdcsInt.stream().map(String::valueOf).collect(Collectors.toList());
        
        // PHP: $localext = in_array($indicativo, $_indicativos_extendida[$indicativolocal_id]);
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