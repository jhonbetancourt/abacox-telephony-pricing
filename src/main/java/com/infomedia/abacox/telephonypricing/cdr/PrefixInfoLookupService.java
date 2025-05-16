// FILE: com/infomedia/abacox/telephonypricing/cdr/PrefixInfoLookupService.java
package com.infomedia.abacox.telephonypricing.cdr;

import com.infomedia.abacox.telephonypricing.entity.CommunicationLocation;
import com.infomedia.abacox.telephonypricing.entity.Operator;
import com.infomedia.abacox.telephonypricing.entity.Prefix;
import com.infomedia.abacox.telephonypricing.entity.TelephonyType;
import jakarta.persistence.EntityManager;
import jakarta.persistence.NoResultException;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.Query;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.util.*;
import java.util.stream.Collectors;

@Service
@Log4j2
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class PrefixInfoLookupService {

    @PersistenceContext
    private final EntityManager entityManager;
    private final CdrProcessingConfig configService;

    private String padLeft(String input, int targetLength, char padChar) {
        if (input == null) input = "";
        int currentLength = input.length();
        if (currentLength >= targetLength) return input;
        StringBuilder sb = new StringBuilder(targetLength);
        for (int i = 0; i < targetLength - currentLength; i++) sb.append(padChar);
        sb.append(input);
        return sb.toString();
    }

    private String padRight(String input, int targetLength, char padChar) {
        if (input == null) input = "";
        int currentLength = input.length();
        if (currentLength == targetLength) return input;
        if (currentLength > targetLength) return input.substring(0, targetLength);
        StringBuilder sb = new StringBuilder(input);
        for (int i = 0; i < targetLength - currentLength; i++) sb.append(padChar);
        return sb.toString();
    }

    private boolean adjustAndCompareSeries(String fullDialedNumberWithNdcStr, String ndcFromDbStr,
                                           String seriesInitialFromDbStr, String seriesFinalFromDbStr) {
        if (!StringUtils.hasText(fullDialedNumberWithNdcStr) || !StringUtils.hasText(ndcFromDbStr) ||
                !StringUtils.hasText(seriesInitialFromDbStr) || !StringUtils.hasText(seriesFinalFromDbStr)) {
            log.info("adjustAndCompareSeries - Invalid string input: num={}, ndc={}, init={}, final={}",
                    fullDialedNumberWithNdcStr, ndcFromDbStr, seriesInitialFromDbStr, seriesFinalFromDbStr);
            return false;
        }
        if (!seriesInitialFromDbStr.matches("\\d+") || !seriesFinalFromDbStr.matches("\\d+")) {
             log.info("adjustAndCompareSeries - DB series initial or final is not purely numeric: init={}, final={}", seriesInitialFromDbStr, seriesFinalFromDbStr);
            return false;
        }

        String dialedSubscriberPartStr;
        if (ndcFromDbStr.equals("0")) {
            dialedSubscriberPartStr = fullDialedNumberWithNdcStr;
            if (!fullDialedNumberWithNdcStr.matches("\\d+")) {
                log.info("adjustAndCompareSeries - Local number (NDC='0') '{}' is not purely numeric.", fullDialedNumberWithNdcStr);
                return false;
            }
        } else {
            if (!fullDialedNumberWithNdcStr.startsWith(ndcFromDbStr)) {
                log.info("adjustAndCompareSeries - Full number '{}' does not start with DB NDC '{}'. This is unexpected if ndcFromDbStr was derived correctly.", fullDialedNumberWithNdcStr, ndcFromDbStr);
                return false;
            }
            dialedSubscriberPartStr = fullDialedNumberWithNdcStr.substring(ndcFromDbStr.length());
            if (!dialedSubscriberPartStr.matches("\\d*")) { // Allow empty subscriber part if NDC matches full number
                log.info("adjustAndCompareSeries - Subscriber part '{}' (from num '{}', ndc '{}') is not numeric.",
                        dialedSubscriberPartStr, fullDialedNumberWithNdcStr, ndcFromDbStr);
                return false;
            }
        }

        String currentSeriesInitial = seriesInitialFromDbStr;
        String currentSeriesFinal = seriesFinalFromDbStr;
        int lenInitial = currentSeriesInitial.length();
        int lenFinal = currentSeriesFinal.length();

        if (lenInitial < lenFinal) {
            currentSeriesInitial = padLeft(currentSeriesInitial, lenFinal, '0');
        } else if (lenFinal < lenInitial) {
            currentSeriesFinal = padRight(currentSeriesFinal, lenInitial, '9');
        }

        int dialedSubscriberLength = dialedSubscriberPartStr.length();
        String finalComparableInitialSubPart = padRight(currentSeriesInitial, dialedSubscriberLength, '0');
        String finalComparableFinalSubPart = padRight(currentSeriesFinal, dialedSubscriberLength, '9');

        String comparisonInitialStr = ndcFromDbStr.equals("0") ? finalComparableInitialSubPart : ndcFromDbStr + finalComparableInitialSubPart;
        String comparisonFinalStr = ndcFromDbStr.equals("0") ? finalComparableFinalSubPart : ndcFromDbStr + finalComparableFinalSubPart;

        try {
            long dialedNumVal = Long.parseLong(fullDialedNumberWithNdcStr);
            long compInitialVal = Long.parseLong(comparisonInitialStr);
            long compFinalVal = Long.parseLong(comparisonFinalStr);
            boolean match = (dialedNumVal >= compInitialVal && dialedNumVal <= compFinalVal);
            log.info("Adjusted series comparison: DialedNum={}, DB_NDC={}, DB_Initial={}, DB_Final={}. AdjustedInitialSub={}, AdjustedFinalSub={}. FullCompInitial={}, FullCompFinal={}. Match: {}",
                    fullDialedNumberWithNdcStr, ndcFromDbStr, seriesInitialFromDbStr, seriesFinalFromDbStr,
                    finalComparableInitialSubPart, finalComparableFinalSubPart,
                    comparisonInitialStr, comparisonFinalStr, match);
            return match;
        } catch (NumberFormatException e) {
            log.info("Numeric conversion error during series comparison. Dialed: '{}', CompInitial: '{}', CompFinal: '{}'. Error: {}",
                    fullDialedNumberWithNdcStr, comparisonInitialStr, comparisonFinalStr, e.getMessage());
            return false;
        }
    }


    public Optional<Map<String, Object>> findIndicatorByNumber(
            String numberToLookup,
            Long telephonyTypeId,
            Long originCountryId,
            boolean isPrefixBandOk,
            Long currentPrefixId,
            Long originCommLocationIndicatorId
    ) {
        if (telephonyTypeId == null || originCountryId == null || !StringUtils.hasText(numberToLookup)) {
            log.info("findIndicatorByNumber - Invalid input: num={}, ttId={}, ocId={}", numberToLookup, telephonyTypeId, originCountryId);
            return Optional.empty();
        }

        String effectiveNumberForLookup = numberToLookup;
        Long effectiveTelephonyTypeId = telephonyTypeId;

        if (telephonyTypeId.equals(CdrProcessingConfig.TIPOTELE_LOCAL)) {
            Optional<Integer> originNdcOpt = findLocalNdcForIndicator(originCommLocationIndicatorId);
            if (originNdcOpt.isPresent()) {
                effectiveNumberForLookup = originNdcOpt.get() + numberToLookup;
                effectiveTelephonyTypeId = CdrProcessingConfig.TIPOTELE_NACIONAL;
                log.info("Local call lookup: transformed num {} to {} for type NATIONAL", numberToLookup, effectiveNumberForLookup);
            } else {
                log.info("Could not get origin NDC for local call lookup from indicator {}, proceeding with original number and type.", originCommLocationIndicatorId);
            }
        }

        log.info("Finding indicator for effectiveNum: {}, effectiveTtId: {}, ocId: {}, bandOk: {}, prefixId: {}, originCommLocIndId: {}",
                effectiveNumberForLookup, effectiveTelephonyTypeId, originCountryId, isPrefixBandOk, currentPrefixId, originCommLocationIndicatorId);

        Map<String, Integer> ndcLengths = findNdcMinMaxLength(effectiveTelephonyTypeId, originCountryId);
        int minNdcLength = ndcLengths.getOrDefault("min", 0);
        int maxNdcLength = ndcLengths.getOrDefault("max", 0);

        if (maxNdcLength == 0 && !effectiveTelephonyTypeId.equals(CdrProcessingConfig.TIPOTELE_LOCAL) && !(minNdcLength == 0 && effectiveTelephonyTypeId.equals(CdrProcessingConfig.TIPOTELE_CELULAR) && effectiveNumberForLookup.length() == 10) ) {
             // Allow cellular to proceed if NDC length is 0 but number is 10 digits (implying NDC is part of the 10 digits)
            log.info("No NDC length range found for effective telephony type {} (or invalid for type), cannot find indicator.", effectiveTelephonyTypeId);
            return Optional.empty();
        }


        Map<String, Integer> typeMinMaxConfig = configService.getTelephonyTypeMinMax(effectiveTelephonyTypeId, originCountryId);
        int typeMinDigits = typeMinMaxConfig.getOrDefault("min", 0);

        Map<String, Object> approximateMatchResult = null;

        for (int currentNdcLength = maxNdcLength; currentNdcLength >= minNdcLength; currentNdcLength--) {
            String ndcStrToMatchInDb;
            String subscriberPartForSeriesLookup;

            if (currentNdcLength == 0 && (effectiveTelephonyTypeId.equals(CdrProcessingConfig.TIPOTELE_LOCAL) || effectiveTelephonyTypeId.equals(CdrProcessingConfig.TIPOTELE_CELULAR))) {
                ndcStrToMatchInDb = "0";
                subscriberPartForSeriesLookup = effectiveNumberForLookup;
            } else if (currentNdcLength > 0) {
                if (effectiveNumberForLookup.length() < currentNdcLength) continue;
                ndcStrToMatchInDb = effectiveNumberForLookup.substring(0, currentNdcLength);
                subscriberPartForSeriesLookup = effectiveNumberForLookup.substring(currentNdcLength);
            } else {
                continue;
            }

            if (!ndcStrToMatchInDb.matches("\\d+") || !subscriberPartForSeriesLookup.matches("\\d*")) {
                log.info("Skipping NDC lookup: NDC to match in DB '{}' or subscriber part '{}' is not valid.", ndcStrToMatchInDb, subscriberPartForSeriesLookup);
                continue;
            }

            int minSubscriberLengthForThisTypeAndNdc = Math.max(0, typeMinDigits - currentNdcLength);
            if (subscriberPartForSeriesLookup.length() < minSubscriberLengthForThisTypeAndNdc) {
                log.info("Subscriber part '{}' (from num '{}') too short for NDC '{}' (min subscriber length {})",
                        subscriberPartForSeriesLookup, effectiveNumberForLookup, ndcStrToMatchInDb, minSubscriberLengthForThisTypeAndNdc);
                continue;
            }

            StringBuilder sqlBuilder = new StringBuilder();
            sqlBuilder.append("SELECT i.id as indicator_id, i.department_country, i.city_name, i.operator_id as indicator_operator_id, ");
            sqlBuilder.append("s.ndc as series_ndc, s.initial_number as series_initial, s.final_number as series_final, s.company as series_company ");
            if (isPrefixBandOk) {
                sqlBuilder.append(", b.origin_indicator_id as band_origin_indicator_id ");
            }
            sqlBuilder.append("FROM series s JOIN indicator i ON s.indicator_id = i.id ");

            if (isPrefixBandOk) {
                sqlBuilder.append("JOIN band b ON b.prefix_id = :currentPrefixId AND b.active = true ");
                sqlBuilder.append("JOIN band_indicator bi ON bi.band_id = b.id AND bi.indicator_id = i.id ");
            }
            sqlBuilder.append("WHERE i.active = true AND s.active = true ");
            if (isPrefixBandOk) {
                 sqlBuilder.append(" AND b.origin_indicator_id IN (0, :originCommLocationIndicatorId) ");
            }
            sqlBuilder.append("  AND i.telephony_type_id = :effectiveTelephonyTypeId AND s.ndc = :ndcParam ");

            // Origin country filter:
            // For international/satellite, allow global (0) or specific country.
            // For others, allow global (0) or specific country (aligning with PHP's $filtro_origen).
            sqlBuilder.append(" AND i.origin_country_id IN (0, :originCountryId) ");


            // Operator filter on indicator:
            // If a prefix is known (currentPrefixId != null) and bands are NOT used for this prefix,
            // then the indicator's operator should match the prefix's operator or be global (0/null).
            // If no prefix is known (currentPrefixId == null, e.g., incoming classification),
            // then no operator filter should be applied to the indicator at this stage.
            if (!isPrefixBandOk && currentPrefixId != null) {
                sqlBuilder.append(" AND (i.operator_id = 0 OR i.operator_id IS NULL OR i.operator_id IN (SELECT p_sub.operator_id FROM prefix p_sub WHERE p_sub.id = :currentPrefixId)) ");
            }
            // If isPrefixBandOk is true, the join to band_indicator implicitly handles operator via prefix_id on band.
            // If currentPrefixId is null (incoming), no operator filter on indicator.

            sqlBuilder.append("ORDER BY ");
            if (isPrefixBandOk) {
                sqlBuilder.append("  b.origin_indicator_id DESC NULLS LAST, ");
            }
            sqlBuilder.append("  CASE WHEN s.ndc = '-1' THEN 1 ELSE 0 END ASC, "); // Prioritize non-approximate matches
            sqlBuilder.append("  (CASE WHEN s.initial_number ~ E'^\\\\d+$' AND s.final_number ~ E'^\\\\d+$' THEN CAST(s.final_number AS BIGINT) - CAST(s.initial_number AS BIGINT) ELSE NULL END) ASC NULLS LAST, ");
            sqlBuilder.append("  s.id ASC ");

            Query query = entityManager.createNativeQuery(sqlBuilder.toString());
            query.setParameter("effectiveTelephonyTypeId", effectiveTelephonyTypeId);
            query.setParameter("ndcParam", ndcStrToMatchInDb);
            query.setParameter("originCountryId", originCountryId);

            if (isPrefixBandOk) {
                query.setParameter("currentPrefixId", currentPrefixId);
                query.setParameter("originCommLocationIndicatorId", originCommLocationIndicatorId != null ? originCommLocationIndicatorId : 0L);
            } else if (currentPrefixId != null) {
                query.setParameter("currentPrefixId", currentPrefixId);
            }

            List<Object[]> seriesCandidates = query.getResultList();

            for (Object[] row : seriesCandidates) {
                Map<String, Object> seriesData = new HashMap<>();
                int colIdx = 0;
                seriesData.put("indicator_id", row[colIdx++]);
                seriesData.put("department_country", row[colIdx++]);
                seriesData.put("city_name", row[colIdx++]);
                seriesData.put("indicator_operator_id", row[colIdx++]);
                seriesData.put("series_ndc", row[colIdx++]);
                seriesData.put("series_initial", row[colIdx++]);
                seriesData.put("series_final", row[colIdx++]);
                seriesData.put("series_company", row[colIdx++]);
                if (isPrefixBandOk) {
                    seriesData.put("band_origin_indicator_id", row[colIdx++]);
                }

                String dbNdc = (String) seriesData.get("series_ndc");
                String dbInitial = (String) seriesData.get("series_initial");
                String dbFinal = (String) seriesData.get("series_final");

                if ("-1".equals(dbNdc)) {
                    if (approximateMatchResult == null) {
                        approximateMatchResult = new HashMap<>(seriesData);
                        approximateMatchResult.put("is_approximate", true);
                        log.info("Stored approximate match (NDC -1): {}", approximateMatchResult);
                    }
                    continue;
                }

                if (adjustAndCompareSeries(effectiveNumberForLookup, dbNdc, dbInitial, dbFinal)) {
                    log.info("Found exact indicator match for num {} (NDC: {}): ID={}", effectiveNumberForLookup, dbNdc, seriesData.get("indicator_id"));
                    return Optional.of(seriesData);
                }
            }
        }

        if (approximateMatchResult != null) {
            log.info("Using approximate match (NDC -1) for num {}: ID={}", effectiveNumberForLookup, approximateMatchResult.get("indicator_id"));
            return Optional.of(approximateMatchResult);
        }

        log.info("No indicator found for number: {}", effectiveNumberForLookup);
        return Optional.empty();
    }

    public Map<String, Integer> findNdcMinMaxLength(Long telephonyTypeId, Long originCountryId) {
        Map<String, Integer> lengths = new HashMap<>();
        lengths.put("min", 0); lengths.put("max", 0);
        if (telephonyTypeId == null || originCountryId == null) return lengths;

        log.info("Finding min/max NDC length for telephonyTypeId: {}, originCountryId: {}", telephonyTypeId, originCountryId);
        StringBuilder sqlBuilder = new StringBuilder();
        sqlBuilder.append("SELECT COALESCE(MIN(CASE WHEN s.ndc = '0' THEN 0 ELSE LENGTH(s.ndc) END), 0) as min_len, ");
        sqlBuilder.append("       COALESCE(MAX(CASE WHEN s.ndc = '0' THEN 0 ELSE LENGTH(s.ndc) END), 0) as max_len ");
        sqlBuilder.append("FROM series s ");
        sqlBuilder.append("JOIN indicator i ON s.indicator_id = i.id ");
        sqlBuilder.append("WHERE i.active = true AND s.active = true ");
        sqlBuilder.append("  AND i.telephony_type_id = :telephonyTypeId ");
        // Align with PHP's $filtro_origen
        sqlBuilder.append(" AND i.origin_country_id IN (0, :originCountryId) ");
        sqlBuilder.append("  AND s.ndc IS NOT NULL ");

        Query query = entityManager.createNativeQuery(sqlBuilder.toString());
        query.setParameter("telephonyTypeId", telephonyTypeId);
        query.setParameter("originCountryId", originCountryId);

        try {
            Object[] result = (Object[]) query.getSingleResult();
            lengths.put("min", result[0] != null ? ((Number) result[0]).intValue() : 0);
            lengths.put("max", result[1] != null ? ((Number) result[1]).intValue() : 0);
        } catch (Exception e) {
            log.info("Could not determine NDC lengths for telephony type {}: {}", telephonyTypeId, e.getMessage());
        }
        log.info("NDC lengths for type {}: min={}, max={}", telephonyTypeId, lengths.get("min"), lengths.get("max"));
        return lengths;
    }

    public Optional<Map<String, Object>> findBaseRateForPrefix(Long prefixId) {
        if (prefixId == null) return Optional.empty();
        log.info("Finding base rate for prefixId: {}", prefixId);
        String sql = "SELECT p.base_value, p.vat_included, p.vat_value, p.band_ok, p.telephone_type_id " +
                "FROM prefix p " +
                "WHERE p.id = :prefixId AND p.active = true";
        Query query = entityManager.createNativeQuery(sql);
        query.setParameter("prefixId", prefixId);
        try {
            Object[] result = (Object[]) query.getSingleResult();
            Map<String, Object> map = new HashMap<>();
            map.put("base_value", result[0] != null ? result[0] : BigDecimal.ZERO);
            map.put("vat_included", result[1] != null ? result[1] : false);
            map.put("vat_value", result[2] != null ? result[2] : BigDecimal.ZERO);
            map.put("band_ok", result[3] != null ? result[3] : false);
            map.put("telephony_type_id", result[4]);
            return Optional.of(map);
        } catch (NoResultException e) {
            log.info("No active prefix found for ID: {}", prefixId);
            return Optional.empty();
        } catch (Exception e) {
            log.info("Error finding base rate for prefixId: {}", prefixId, e);
            return Optional.empty();
        }
    }

    public Optional<Map<String, Object>> findBandByPrefixAndIndicator(Long prefixId, Long indicatorId, Long originIndicatorId) {
        if (prefixId == null) {
            log.info("findBandByPrefixAndIndicator - Invalid input: prefixId is null");
            return Optional.empty();
        }
        Long effectiveOriginIndicatorId = originIndicatorId != null ? originIndicatorId : 0L;
        Long effectiveIndicatorId = indicatorId;

        log.info("Finding band for prefixId: {}, effectiveIndicatorId: {}, effectiveOriginIndicatorId: {}", prefixId, effectiveIndicatorId, effectiveOriginIndicatorId);

        StringBuilder sqlBuilder = new StringBuilder();
        sqlBuilder.append("SELECT b.id as band_id, b.value as band_value, b.vat_included as band_vat_included, b.name as band_name ");
        sqlBuilder.append("FROM band b ");
        if (effectiveIndicatorId != null && effectiveIndicatorId > 0) {
            sqlBuilder.append("JOIN band_indicator bi ON b.id = bi.band_id AND bi.indicator_id = :indicatorId ");
        }
        sqlBuilder.append("WHERE b.active = true ");
        sqlBuilder.append("  AND b.prefix_id = :prefixId ");
        if (effectiveIndicatorId == null || effectiveIndicatorId <= 0) {
            sqlBuilder.append(" AND NOT EXISTS (SELECT 1 FROM band_indicator bi_check WHERE bi_check.band_id = b.id) ");
        }
        sqlBuilder.append("  AND b.origin_indicator_id IN (0, :originIndicatorId) ");
        sqlBuilder.append("ORDER BY b.origin_indicator_id DESC ");
        sqlBuilder.append("LIMIT 1");

        Query query = entityManager.createNativeQuery(sqlBuilder.toString());
        query.setParameter("prefixId", prefixId);
        if (effectiveIndicatorId != null && effectiveIndicatorId > 0) {
            query.setParameter("indicatorId", effectiveIndicatorId);
        }
        query.setParameter("originIndicatorId", effectiveOriginIndicatorId);

        try {
            Object[] result = (Object[]) query.getSingleResult();
            Map<String, Object> map = new HashMap<>();
            map.put("band_id", result[0]);
            map.put("band_value", result[1] != null ? result[1] : BigDecimal.ZERO);
            map.put("band_vat_included", result[2] != null ? result[2] : false);
            map.put("band_name", result[3]);
            log.info("Found band {} for prefix {}, effective indicator {}", map.get("band_id"), prefixId, effectiveIndicatorId);
            return Optional.of(map);
        } catch (NoResultException e) {
            log.info("No matching band found for prefix {}, effective indicator {}", prefixId, effectiveIndicatorId);
            return Optional.empty();
        } catch (Exception e) {
            log.info("Error finding band for prefixId: {}, indicatorId: {}, originIndicatorId: {}", prefixId, effectiveIndicatorId, effectiveOriginIndicatorId, e);
            return Optional.empty();
        }
    }

    public Optional<Integer> findLocalNdcForIndicator(Long indicatorId) {
        if (indicatorId == null || indicatorId <= 0) return Optional.empty();
        log.info("Finding local NDC for indicatorId: {}", indicatorId);
        String sql = "SELECT s.ndc FROM series s " +
                "WHERE s.indicator_id = :indicatorId " +
                "  AND s.ndc IS NOT NULL AND s.ndc != '0' AND s.ndc ~ '^[1-9][0-9]*$' " +
                "GROUP BY s.ndc " +
                "ORDER BY COUNT(*) DESC, CAST(s.ndc AS INTEGER) ASC " +
                "LIMIT 1";
        Query query = entityManager.createNativeQuery(sql, String.class);
        query.setParameter("indicatorId", indicatorId);
        try {
            String ndcStr = (String) query.getSingleResult();
            Integer ndc = Integer.parseInt(ndcStr);
            log.info("Found local NDC {} for indicator {}", ndc, indicatorId);
            return Optional.of(ndc);
        } catch (NoResultException e) {
            log.info("No positive numeric NDC found for indicatorId: {}", indicatorId);
            return Optional.empty();
        } catch (NumberFormatException nfe) {
            log.info("NDC value fetched for indicatorId {} is not a valid integer: {}", indicatorId, nfe.getMessage());
            return Optional.empty();
        }
        catch (Exception e) {
            log.info("Error finding local NDC for indicatorId: {}", indicatorId, e);
            return Optional.empty();
        }
    }

    public boolean isLocalExtended(Integer destinationNdc, Long originIndicatorId) {
        if (destinationNdc == null || originIndicatorId == null || originIndicatorId <= 0) {
            return false;
        }
        log.info("Checking if NDC {} is local extended for origin indicator {}", destinationNdc, originIndicatorId);
        String sql = "SELECT COUNT(s.id) FROM series s WHERE s.indicator_id = :originIndicatorId AND s.ndc = :destinationNdcStr AND s.active = true";
        Query query = entityManager.createNativeQuery(sql, Long.class);
        query.setParameter("originIndicatorId", originIndicatorId);
        query.setParameter("destinationNdcStr", String.valueOf(destinationNdc));
        try {
            Long count = (Long) query.getSingleResult();
            boolean isExtended = count != null && count > 0;
            log.info("Is NDC {} local extended for origin indicator {}: {}", destinationNdc, originIndicatorId, isExtended);
            return isExtended;
        } catch (Exception e) {
            log.info("Error checking local extended status for NDC {}, origin indicator {}: {}", destinationNdc, originIndicatorId, e);
            return false;
        }
    }

    public boolean isPrefixUniqueToOperator(String prefixCode, Long telephonyTypeId, Long originCountryId) {
        if (!StringUtils.hasText(prefixCode) || telephonyTypeId == null || originCountryId == null) {
            return false;
        }
        log.info("Checking if prefix {} is unique for type {} in country {}", prefixCode, telephonyTypeId, originCountryId);
        String sql = "SELECT COUNT(DISTINCT p.operator_id) " +
                "FROM prefix p " +
                "JOIN operator op ON p.operator_id = op.id " +
                "WHERE p.active = true AND op.active = true " +
                "  AND p.code = :prefixCode " +
                "  AND p.telephone_type_id = :telephonyTypeId " +
                "  AND op.origin_country_id = :originCountryId";
        Query query = entityManager.createNativeQuery(sql, Long.class);
        query.setParameter("prefixCode", prefixCode);
        query.setParameter("telephonyTypeId", telephonyTypeId);
        query.setParameter("originCountryId", originCountryId);
        try {
            Long count = (Long) query.getSingleResult();
            boolean isUnique = count != null && count == 1;
            log.info("Prefix {} unique check for type {} in country {}: {}", prefixCode, telephonyTypeId, originCountryId, isUnique);
            return isUnique;
        } catch (Exception e) {
            log.info("Error checking prefix uniqueness for code {}, type {}, country {}: {}", prefixCode, telephonyTypeId, originCountryId, e.getMessage(), e);
            return false;
        }
    }

    public Optional<Map<String, String>> findNationalSeriesDetailsByNdcAndSubscriber(String ndc, long subscriberNumber, Long originCountryId) {
        if (!StringUtils.hasText(ndc) || originCountryId == null) {
            log.info("findNationalSeriesDetails - Invalid input: ndc or originCountryId is null/empty.");
            return Optional.empty();
        }
        log.info("Finding national series details for NDC: {}, Subscriber: {}, OriginCountryId: {}", ndc, subscriberNumber, originCountryId);

        StringBuilder sqlBuilder = new StringBuilder();
        sqlBuilder.append("SELECT i.department_country, i.city_name, s.company ");
        sqlBuilder.append("FROM series s ");
        sqlBuilder.append("JOIN indicator i ON s.indicator_id = i.id ");
        sqlBuilder.append("WHERE i.telephony_type_id = :nationalType ");
        sqlBuilder.append("  AND s.ndc = :ndc ");
        sqlBuilder.append("  AND CAST(s.initial_number AS BIGINT) <= :subscriberNumber AND CAST(s.final_number AS BIGINT) >= :subscriberNumber ");
        // Align with PHP's $filtro_origen for INDICATIVO_MPORIGEN_ID
        sqlBuilder.append("  AND i.origin_country_id IN (0, :originCountryId) ");
        sqlBuilder.append("  AND s.active = true AND i.active = true ");
        // Operator filter for national reference prefix, if applicable (PHP's $local_cond with $prefijo_id > 0)
        // For national lookup, we assume a reference prefix (like the default national operator's prefix)
        // This part is complex to exactly mirror without knowing how PHP's $prefijo_id is set for this specific call path
        // For now, let's assume a general national lookup might not strictly filter by a specific prefix's operator unless it's implied by the NDC.
        // If NATIONAL_REFERENCE_PREFIX_ID is a constant, we can use its operator.
        sqlBuilder.append("  AND (i.operator_id = 0 OR i.operator_id IS NULL OR i.operator_id IN (SELECT p_sub.operator_id FROM prefix p_sub WHERE p_sub.id = :nationalRefPrefixId)) ");

        sqlBuilder.append("ORDER BY i.origin_country_id DESC, LENGTH(s.ndc) DESC, (CAST(s.final_number AS BIGINT) - CAST(s.initial_number AS BIGINT)) ASC ");
        sqlBuilder.append("LIMIT 1");

        Query query = entityManager.createNativeQuery(sqlBuilder.toString());
        query.setParameter("nationalType", CdrProcessingConfig.TIPOTELE_NACIONAL);
        query.setParameter("ndc", ndc);
        query.setParameter("subscriberNumber", subscriberNumber);
        query.setParameter("nationalRefPrefixId", CdrProcessingConfig.NATIONAL_REFERENCE_PREFIX_ID);
        query.setParameter("originCountryId", originCountryId);

        try {
            Object[] result = (Object[]) query.getSingleResult();
            Map<String, String> details = new HashMap<>();
            details.put("department_country", (String) result[0]);
            details.put("city_name", (String) result[1]);
            details.put("company", (String) result[2]);
            log.info("Found national series details for NDC {}: {}", ndc, details);
            return Optional.of(details);
        } catch (NoResultException e) {
            log.info("No national series details found for NDC: {}, Subscriber: {}", ndc, subscriberNumber);
            return Optional.empty();
        } catch (Exception e) {
            log.info("Error finding national series details for NDC: {}, Subscriber: {}: {}", ndc, subscriberNumber, e.getMessage(), e);
            return Optional.empty();
        }
    }

    public List<Map<String, Object>> findMatchingPrefixes(
            String processedNumber,
            Long originCountryId,
            boolean isTrunkCall,
            Set<Long> allowedTelephonyTypeIdsForTrunk,
            Long forcedTelephonyTypeIdFromPreprocessing,
            CommunicationLocation commLocation
    ) {
        if (originCountryId == null || !StringUtils.hasText(processedNumber)) {
            log.info("findMatchingPrefixes - Invalid input: processedNumber or originCountryId is null/empty.");
            return Collections.emptyList();
        }

        String numberForLog = processedNumber.length() > 6 ? processedNumber.substring(0, 6) + "..." : processedNumber;
        log.info("Finding matching prefixes for number: '{}', originCountryId: {}, isTrunkCall: {}, allowedTrunkTypes: {}, forcedType: {}",
                numberForLog, originCountryId, isTrunkCall, allowedTelephonyTypeIdsForTrunk, forcedTelephonyTypeIdFromPreprocessing);

        StringBuilder sqlBuilder = new StringBuilder();
        sqlBuilder.append("SELECT ");
        sqlBuilder.append(" p.id as prefix_id, p.code as prefix_code, p.base_value as prefix_base_value, ");
        sqlBuilder.append(" p.vat_included as prefix_vat_included, p.vat_value as prefix_vat_value, p.band_ok as prefix_band_ok, ");
        sqlBuilder.append(" tt.id as telephony_type_id, tt.name as telephony_type_name, tt.uses_trunks as telephony_type_uses_trunks, ");
        sqlBuilder.append(" op.id as operator_id, op.name as operator_name, ");
        sqlBuilder.append(" COALESCE(ttc.min_value, 0) as telephony_type_min, ");
        sqlBuilder.append(" COALESCE(ttc.max_value, 0) as telephony_type_max ");
        sqlBuilder.append("FROM prefix p ");
        sqlBuilder.append("JOIN telephony_type tt ON p.telephone_type_id = tt.id ");
        sqlBuilder.append("JOIN operator op ON p.operator_id = op.id ");
        sqlBuilder.append("LEFT JOIN telephony_type_config ttc ON ttc.telephony_type_id = tt.id AND ttc.origin_country_id = :originCountryId AND ttc.active = true ");
        sqlBuilder.append("WHERE p.active = true AND tt.active = true AND op.active = true ");
        sqlBuilder.append("  AND op.origin_country_id = :originCountryId ");
        sqlBuilder.append("  AND tt.id != :specialCallsType ");

        Map<String, Object> queryParams = new HashMap<>();
        queryParams.put("originCountryId", originCountryId);
        queryParams.put("specialCallsType", CdrProcessingConfig.TIPOTELE_ESPECIALES);

        if (isTrunkCall && allowedTelephonyTypeIdsForTrunk != null && !allowedTelephonyTypeIdsForTrunk.isEmpty()) {
            sqlBuilder.append("  AND p.telephone_type_id IN (:allowedTelephonyTypeIdsForTrunk) ");
            queryParams.put("allowedTelephonyTypeIdsForTrunk", allowedTelephonyTypeIdsForTrunk);
            log.info("Trunk call: Filtering prefixes by allowed TelephonyType IDs: {}", allowedTelephonyTypeIdsForTrunk);
        } else {
            sqlBuilder.append("  AND :number LIKE p.code || '%' ");
            queryParams.put("number", processedNumber);
            log.info("Non-trunk call or no specific trunk types: Matching prefixes by code against number: '{}'", numberForLog);
        }

        if (forcedTelephonyTypeIdFromPreprocessing != null) {
            sqlBuilder.append("  AND p.telephone_type_id = :forcedTelephonyTypeId ");
            queryParams.put("forcedTelephonyTypeId", forcedTelephonyTypeIdFromPreprocessing);
            log.info("Further filtering prefixes by forced TelephonyType ID: {}", forcedTelephonyTypeIdFromPreprocessing);
        }

        sqlBuilder.append("ORDER BY LENGTH(p.code) DESC, p.code DESC, COALESCE(ttc.min_value, 0) DESC, tt.id ASC, p.id ASC");

        Query query = entityManager.createNativeQuery(sqlBuilder.toString());
        queryParams.forEach(query::setParameter);

        List<Object[]> results = query.getResultList();
        List<Map<String, Object>> initialMappedResults = new ArrayList<>();
        for (Object[] row : results) {
            Map<String, Object> map = new HashMap<>();
            map.put("prefix_id", row[0]);
            map.put("prefix_code", row[1]);
            map.put("prefix_base_value", row[2]);
            map.put("prefix_vat_included", row[3]);
            map.put("prefix_vat_value", row[4]);
            map.put("prefix_band_ok", row[5]);
            map.put("telephony_type_id", row[6]);
            map.put("telephony_type_name", row[7]);
            map.put("telephony_type_uses_trunks", row[8]);
            map.put("operator_id", row[9]);
            map.put("operator_name", row[10]);
            map.put("telephony_type_min", row[11] != null ? ((Number)row[11]).intValue() : 0);
            map.put("telephony_type_max", row[12] != null ? ((Number)row[12]).intValue() : 0);
            initialMappedResults.add(map);
        }

        log.info("Found {} candidate prefixes from SQL for number lookup (isTrunk: {})", initialMappedResults.size(), isTrunkCall);

        List<Map<String, Object>> finalResults = new ArrayList<>(initialMappedResults);

        if (!isTrunkCall && !finalResults.isEmpty()) {
            String longestMatchingPrefixCode = (String) finalResults.get(0).get("prefix_code");
            final String effectiveLongestCode = (longestMatchingPrefixCode == null) ? "" : longestMatchingPrefixCode;

            finalResults = finalResults.stream()
                    .filter(map -> {
                        String currentCode = (String) map.get("prefix_code");
                        return (currentCode == null ? "" : currentCode).equals(effectiveLongestCode);
                    })
                    .collect(Collectors.toList());
            log.info("Non-trunk call: Filtered to {} prefixes matching the longest code '{}'", finalResults.size(), effectiveLongestCode);
        }

        if (!isTrunkCall) {
            boolean localPrefixFoundInFilteredResults = finalResults.stream()
                    .anyMatch(map -> Long.valueOf(CdrProcessingConfig.TIPOTELE_LOCAL).equals(map.get("telephony_type_id")));

            if (!localPrefixFoundInFilteredResults) {
                log.info("TIPOTELE_LOCAL not found in filtered results for non-trunk call, attempting explicit add for number: {}", numberForLog);
                Optional<Operator> localInternalOperatorOpt = configService.getOperatorInternal(CdrProcessingConfig.TIPOTELE_LOCAL, originCountryId);
                if (localInternalOperatorOpt.isPresent()) {
                    Long localOperatorId = localInternalOperatorOpt.get().getId();
                    Optional<Prefix> localPrefixOpt = findPrefixByTypeOperatorOrigin(CdrProcessingConfig.TIPOTELE_LOCAL, localOperatorId, originCountryId);

                    if (localPrefixOpt.isPresent()) {
                        Prefix localPrefix = localPrefixOpt.get();
                        Map<String, Integer> localTypeConfig = configService.getTelephonyTypeMinMax(CdrProcessingConfig.TIPOTELE_LOCAL, originCountryId);
                        int minLengthForLocal = localTypeConfig.getOrDefault("min", 0);

                        if (processedNumber.length() >= minLengthForLocal) {
                            Map<String, Object> localPrefixMap = new HashMap<>();
                            localPrefixMap.put("prefix_id", localPrefix.getId());
                            localPrefixMap.put("prefix_code", localPrefix.getCode());
                            localPrefixMap.put("prefix_base_value", localPrefix.getBaseValue());
                            localPrefixMap.put("prefix_vat_included", localPrefix.isVatIncluded());
                            localPrefixMap.put("prefix_vat_value", localPrefix.getVatValue());
                            localPrefixMap.put("prefix_band_ok", localPrefix.isBandOk());
                            localPrefixMap.put("telephony_type_id", CdrProcessingConfig.TIPOTELE_LOCAL);
                            localPrefixMap.put("telephony_type_name", configService.getTelephonyTypeById(CdrProcessingConfig.TIPOTELE_LOCAL).map(TelephonyType::getName).orElse("Local"));
                            localPrefixMap.put("telephony_type_uses_trunks", configService.getTelephonyTypeById(CdrProcessingConfig.TIPOTELE_LOCAL).map(TelephonyType::isUsesTrunks).orElse(false));
                            localPrefixMap.put("operator_id", localOperatorId);
                            localPrefixMap.put("operator_name", localInternalOperatorOpt.get().getName());
                            localPrefixMap.put("telephony_type_min", minLengthForLocal);
                            localPrefixMap.put("telephony_type_max", localTypeConfig.getOrDefault("max", 0));
                            finalResults.add(localPrefixMap);
                            log.info("Explicitly added TIPOTELE_LOCAL prefix to candidates for number: {}", numberForLog);
                        } else {
                            log.info("Explicit local prefix add: number {} length {} is less than min length {} for local type.", numberForLog, processedNumber.length(), minLengthForLocal);
                        }
                    } else {
                        log.info("Explicit local prefix add: Could not find prefix definition for TIPOTELE_LOCAL ({}), Operator {}, Country {}.", CdrProcessingConfig.TIPOTELE_LOCAL, localOperatorId, originCountryId);
                    }
                } else {
                    log.info("Explicit local prefix add: Could not find internal operator for TIPOTELE_LOCAL ({}) in Country {}.", CdrProcessingConfig.TIPOTELE_LOCAL, originCountryId);
                }
            } else {
                log.info("TIPOTELE_LOCAL was already found in filtered results. No explicit add needed.");
            }
        }
        return finalResults;
    }

    public Optional<Prefix> findPrefixByTypeOperatorOrigin(Long telephonyTypeId, Long operatorId, Long originCountryId) {
        if (telephonyTypeId == null || operatorId == null || originCountryId == null) {
            log.info("findPrefixByTypeOperatorOrigin - Invalid input: ttId={}, opId={}, ocId={}", telephonyTypeId, operatorId, originCountryId);
            return Optional.empty();
        }
        log.info("Finding prefix for type {}, operator {}, origin country {}", telephonyTypeId, operatorId, originCountryId);

        StringBuilder sqlBuilder = new StringBuilder();
        sqlBuilder.append("SELECT p.* FROM prefix p ");
        sqlBuilder.append("JOIN operator op ON p.operator_id = op.id ");
        sqlBuilder.append("WHERE p.active = true AND op.active = true ");
        sqlBuilder.append("  AND p.telephone_type_id = :telephonyTypeId ");
        sqlBuilder.append("  AND p.operator_id = :operatorId ");
        sqlBuilder.append("  AND op.origin_country_id = :originCountryId ");
        sqlBuilder.append("LIMIT 1");

        Query query = entityManager.createNativeQuery(sqlBuilder.toString(), Prefix.class);
        query.setParameter("telephonyTypeId", telephonyTypeId);
        query.setParameter("operatorId", operatorId);
        query.setParameter("originCountryId", originCountryId);

        try {
            Prefix prefix = (Prefix) query.getSingleResult();
            return Optional.of(prefix);
        } catch (NoResultException e) {
            log.info("No active prefix found for type {}, operator {}, origin country {}", telephonyTypeId, operatorId, originCountryId);
            return Optional.empty();
        } catch (Exception e) {
            log.info("Error finding prefix for type {}, operator {}, origin country {}: {}", telephonyTypeId, operatorId, originCountryId, e.getMessage(), e);
            return Optional.empty();
        }
    }

    public Optional<Map<String, Object>> findInternalPrefixMatch(String number, Long originCountryId) {
        if (originCountryId == null || !StringUtils.hasText(number)) return Optional.empty();
        String numberForLog = number.length() > 6 ? number.substring(0, 6) + "..." : number;
        log.info("Finding internal prefix match for number starting with: {}, originCountryId: {}", numberForLog, originCountryId);

        Set<Long> internalTypes = CdrProcessingConfig.getInternalIpCallTypeIds();
        if (internalTypes.isEmpty()) return Optional.empty();

        StringBuilder sqlBuilder = new StringBuilder();
        sqlBuilder.append("SELECT p.telephone_type_id, p.operator_id ");
        sqlBuilder.append("FROM prefix p ");
        sqlBuilder.append("JOIN operator op ON p.operator_id = op.id ");
        sqlBuilder.append("WHERE p.active = true AND op.active = true ");
        sqlBuilder.append("  AND p.telephone_type_id IN (:internalTypes) ");
        sqlBuilder.append("  AND :number LIKE p.code || '%' ");
        sqlBuilder.append("  AND op.origin_country_id = :originCountryId ");
        sqlBuilder.append("ORDER BY LENGTH(p.code) DESC ");
        sqlBuilder.append("LIMIT 1");

        Query query = entityManager.createNativeQuery(sqlBuilder.toString());
        query.setParameter("internalTypes", internalTypes);
        query.setParameter("number", number);
        query.setParameter("originCountryId", originCountryId);

        try {
            Object[] result = (Object[]) query.getSingleResult();
            Map<String, Object> map = new HashMap<>();
            map.put("telephony_type_id", result[0]);
            map.put("operator_id", result[1]);
            log.info("Found internal prefix match for number {}: Type={}, Op={}", numberForLog, result[0], result[1]);
            return Optional.of(map);
        } catch (NoResultException e) {
            log.info("No internal prefix match found for number {}", numberForLog);
            return Optional.empty();
        } catch (Exception e) {
            log.info("Error finding internal prefix match for number {}: {}", numberForLog, e.getMessage(), e);
            return Optional.empty();
        }
    }
}