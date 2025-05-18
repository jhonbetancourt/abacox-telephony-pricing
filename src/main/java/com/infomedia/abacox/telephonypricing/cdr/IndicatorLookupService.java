package com.infomedia.abacox.telephonypricing.cdr;

import jakarta.persistence.EntityManager;
import jakarta.persistence.NoResultException;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.Tuple;
import lombok.extern.log4j.Log4j2;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

@Service
@Log4j2
public class IndicatorLookupService {

    @PersistenceContext
    private EntityManager entityManager;


    @Transactional(readOnly = true)
    public IndicatorConfig getIndicatorConfigForTelephonyType(Long telephonyTypeId, Long originCountryId) {
        String queryStr = "SELECT " +
                          "COALESCE(MIN(LENGTH(s.ndc::text)), 0) as min_ndc_len, " +
                          "COALESCE(MAX(LENGTH(s.ndc::text)), 0) as max_ndc_len, " +
                          "(SELECT LENGTH(s2.initial_number::text) FROM series s2 JOIN indicator i2 ON s2.indicator_id = i2.id WHERE i2.telephony_type_id = :telephonyTypeId AND i2.origin_country_id = :originCountryId AND s2.active = true AND i2.active = true GROUP BY LENGTH(s2.initial_number::text) ORDER BY COUNT(*) DESC LIMIT 1) as common_series_len " +
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
            // Defaults are already set in IndicatorConfig constructor
        }
        return config;
    }

    @Transactional(readOnly = true)
    public Optional<DestinationInfo> findDestinationIndicator(
            String phoneNumber, Long telephonyTypeId, int minPhoneNumberLengthAfterPrefix,
            Long originIndicatorId, Long prefixId, Long originCountryId, boolean useBandSpecificLookup) {

        if (phoneNumber == null || phoneNumber.isEmpty()) return Optional.empty();

        IndicatorConfig config = getIndicatorConfigForTelephonyType(telephonyTypeId, originCountryId);
        if (config.maxNdcLength == 0) {
            return Optional.empty();
        }

        List<String> ndcCandidates = new ArrayList<>();
        int phoneLen = phoneNumber.length();
        for (int i = config.minNdcLength; i <= config.maxNdcLength; i++) {
            if (i > 0 && phoneLen >= i) {
                if ((phoneLen - i) >= minPhoneNumberLengthAfterPrefix) {
                    String candidateNdc = phoneNumber.substring(0, i);
                    if (candidateNdc.matches("\\d+")) {
                        ndcCandidates.add(candidateNdc);
                    }
                }
            }
        }

        if (ndcCandidates.isEmpty()) return Optional.empty();

        StringBuilder queryBuilder = new StringBuilder(
            "SELECT i.id as indicator_id, i.operator_id as operator_id, s.ndc as ndc_val, i.department_country, i.city_name, s.initial_number, s.final_number " + // Changed s.ndc to s.ndc_val to avoid conflict
            "FROM series s JOIN indicator i ON s.indicator_id = i.id ");

        if (useBandSpecificLookup && prefixId != null) {
            queryBuilder.append("JOIN band b ON b.prefix_id = :prefixId AND b.active = true ");
            queryBuilder.append("JOIN band_indicator bi ON bi.band_id = b.id AND bi.indicator_id = i.id ");
            queryBuilder.append("AND (b.origin_indicator_id = 0 OR b.origin_indicator_id = :originIndicatorId) ");
        } else if (prefixId != null) {
             queryBuilder.append("AND (i.operator_id = 0 OR EXISTS (SELECT 1 FROM prefix p WHERE p.id = :prefixId AND p.operator_id = i.operator_id)) ");
        }

        queryBuilder.append("WHERE i.active = true AND s.active = true AND i.telephony_type_id = :telephonyTypeId ");
        queryBuilder.append("AND s.ndc IN (:ndcCandidates) ");

        if (telephonyTypeId != TelephonyTypeEnum.INTERNATIONAL.getValue() && telephonyTypeId != TelephonyTypeEnum.SATELLITE.getValue()) {
            queryBuilder.append("AND i.origin_country_id = :originCountryId ");
        }
        
        if (useBandSpecificLookup) {
            queryBuilder.append("ORDER BY LENGTH(s.ndc::text) DESC, b.origin_indicator_id DESC, s.initial_number, s.final_number");
        } else {
            queryBuilder.append("ORDER BY LENGTH(s.ndc::text) DESC, s.initial_number, s.final_number");
        }

        jakarta.persistence.Query nativeQuery = entityManager.createNativeQuery(queryBuilder.toString(), Tuple.class);
        nativeQuery.setParameter("telephonyTypeId", telephonyTypeId);
        nativeQuery.setParameter("ndcCandidates", ndcCandidates);
        if (useBandSpecificLookup && prefixId != null) {
            nativeQuery.setParameter("prefixId", prefixId);
            nativeQuery.setParameter("originIndicatorId", originIndicatorId);
        } else if (prefixId != null) {
            nativeQuery.setParameter("prefixId", prefixId);
        }
        if (telephonyTypeId != TelephonyTypeEnum.INTERNATIONAL.getValue() && telephonyTypeId != TelephonyTypeEnum.SATELLITE.getValue()) {
            nativeQuery.setParameter("originCountryId", originCountryId);
        }

        List<Tuple> results = nativeQuery.getResultList();
        List<DestinationInfo> validMatches = new ArrayList<>();

        for (Tuple row : results) {
            String currentNdc = row.get("ndc_val", String.class); // Use alias
            if (phoneNumber.startsWith(currentNdc)) {
                String numberAfterNdcStr = phoneNumber.substring(currentNdc.length());
                Integer seriesInitial = row.get("initial_number", Integer.class); // Corrected field name
                Integer seriesFinal = row.get("final_number", Integer.class);     // Corrected field name

                if (numberAfterNdcStr.isEmpty() && seriesInitial != null && seriesInitial == 0 && seriesFinal != null && seriesFinal == 0) {
                     addMatch(validMatches, row, currentNdc, phoneNumber, prefixId);
                } else if (!numberAfterNdcStr.isEmpty() && numberAfterNdcStr.matches("\\d+")) {
                    long numberAfterNdc = Long.parseLong(numberAfterNdcStr);
                    long numberToCompareInSeries = numberAfterNdc;

                    if (config.seriesNumberLength > 0 && numberAfterNdcStr.length() >= config.seriesNumberLength) {
                        try { // Add try-catch for safety if substring is too short
                           numberToCompareInSeries = Long.parseLong(numberAfterNdcStr.substring(0, config.seriesNumberLength));
                        } catch (StringIndexOutOfBoundsException e) {
                            log.warn("Error taking substring for series comparison: {} from {}", config.seriesNumberLength, numberAfterNdcStr);
                            continue; // Skip this iteration
                        }
                    }
                    
                    if (seriesInitial != null && seriesFinal != null &&
                        numberToCompareInSeries >= seriesInitial && numberToCompareInSeries <= seriesFinal) {
                        addMatch(validMatches, row, currentNdc, phoneNumber, prefixId);
                    }
                }
            }
        }
        
        if (!validMatches.isEmpty()) {
            validMatches.sort(Comparator.comparing((DestinationInfo di) -> di.getNdc().length()).reversed());
            return Optional.of(validMatches.get(0));
        }
        
        return Optional.empty();
    }

    private void addMatch(List<DestinationInfo> matches, Tuple row, String ndc, String originalPhoneNumber, Long prefixId) {
        DestinationInfo di = new DestinationInfo();
        di.setIndicatorId(row.get("indicator_id", Number.class).longValue());
        Number opIdNum = row.get("operator_id", Number.class);
        di.setOperatorId(opIdNum != null ? opIdNum.longValue() : null);
        di.setNdc(ndc);
        di.setDestinationDescription(formatDestinationDescription(row.get("city_name", String.class), row.get("department_country", String.class)));
        di.setMatchedPhoneNumber(originalPhoneNumber);
        di.setPrefixIdUsed(prefixId);
        matches.add(di);
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
        String queryStr = "SELECT s.ndc FROM series s WHERE s.indicator_id = :indicatorId AND s.active = true " +
                          "GROUP BY s.ndc ORDER BY COUNT(*) DESC, s.ndc ASC LIMIT 1";
        jakarta.persistence.Query nativeQuery = entityManager.createNativeQuery(queryStr, String.class);
        nativeQuery.setParameter("indicatorId", indicatorId);
        try {
            return (String) nativeQuery.getSingleResult();
        } catch (NoResultException e) {
            return "";
        }
    }

    @Transactional(readOnly = true)
    public boolean isLocalExtended(String destinationNdc, Long localOriginIndicatorId, Long destinationIndicatorId) {
        if (localOriginIndicatorId.equals(destinationIndicatorId)) return false;

        String queryStr = "SELECT i_dest.department_country FROM indicator i_dest WHERE i_dest.id = :destinationIndicatorId AND i_dest.active = true";
        jakarta.persistence.Query destQuery = entityManager.createNativeQuery(queryStr, String.class);
        destQuery.setParameter("destinationIndicatorId", destinationIndicatorId);
        String destDeptCountry;
        try {
            destDeptCountry = (String) destQuery.getSingleResult();
        } catch (NoResultException e) {
            return false;
        }

        String localQueryStr = "SELECT i_local.department_country FROM indicator i_local WHERE i_local.id = :localOriginIndicatorId AND i_local.active = true";
        jakarta.persistence.Query localQuery = entityManager.createNativeQuery(localQueryStr, String.class);
        localQuery.setParameter("localOriginIndicatorId", localOriginIndicatorId);
        String localDeptCountry;
        try {
            localDeptCountry = (String) localQuery.getSingleResult();
        } catch (NoResultException e) {
            return false;
        }
        
        String ndcsForLocalOriginQuery = "SELECT s.ndc FROM series s WHERE s.indicator_id = :localOriginIndicatorId AND s.active = true";
        List<String> localNdcs = entityManager.createNativeQuery(ndcsForLocalOriginQuery, String.class)
                                    .setParameter("localOriginIndicatorId", localOriginIndicatorId)
                                    .getResultList();
        
        return localNdcs.contains(destinationNdc);
    }
}