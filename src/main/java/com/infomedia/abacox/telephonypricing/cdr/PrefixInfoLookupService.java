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

    /**
     * Checks if the given dialed number falls within the series range.
     * Uses integer comparison since the series fields are now integer types.
     *
     * @param fullDialedNumberWithNdc The full number being dialed (after operator prefix, potentially including an NDC).
     * @param ndcFromDb               The NDC associated with the series from the database. Can be 0 for local.
     * @param seriesInitialFromDb     The initial number of the series from the database.
     * @param seriesFinalFromDb       The final number of the series from the database.
     * @return true if the full dialed number falls within the series range.
     */
    private boolean isNumberInSeriesRange(String fullDialedNumberWithNdc, Integer ndcFromDb,
                                        Integer seriesInitialFromDb, Integer seriesFinalFromDb) {

        if (!StringUtils.hasText(fullDialedNumberWithNdc) || ndcFromDb == null ||
                seriesInitialFromDb == null || seriesFinalFromDb == null) {
            log.info("isNumberInSeriesRange - Invalid input: num={}, ndc={}, init={}, final={}",
                    fullDialedNumberWithNdc, ndcFromDb, seriesInitialFromDb, seriesFinalFromDb);
            return false;
        }

        if (!fullDialedNumberWithNdc.matches("\\d+")) {
            log.info("isNumberInSeriesRange - Dialed number '{}' is not purely numeric.", fullDialedNumberWithNdc);
            return false;
        }

        String dialedSubscriberPartStr;
        String ndcStr = ndcFromDb.toString();
        
        if (ndcFromDb == 0) {
            dialedSubscriberPartStr = fullDialedNumberWithNdc;
        } else {
            if (!fullDialedNumberWithNdc.startsWith(ndcStr)) {
                log.info("isNumberInSeriesRange - Full number '{}' does not start with DB NDC '{}'. This is unexpected if ndcFromDb was derived correctly.", 
                        fullDialedNumberWithNdc, ndcStr);
                return false;
            }
            dialedSubscriberPartStr = fullDialedNumberWithNdc.substring(ndcStr.length());
        }

        if (!dialedSubscriberPartStr.matches("\\d+")) {
            log.info("isNumberInSeriesRange - Subscriber part '{}' is not numeric.", dialedSubscriberPartStr);
            return false;
        }

        // Convert to numbers for comparison
        try {
            long dialedSubscriberNum = Long.parseLong(dialedSubscriberPartStr);
            
            // Check if the subscriber number is within the range
            boolean match = (dialedSubscriberNum >= seriesInitialFromDb && dialedSubscriberNum <= seriesFinalFromDb);
            
            log.info("Series range comparison: DialedSubscriber={}, DB_NDC={}, DB_Initial={}, DB_Final={}. Match: {}",
                    dialedSubscriberNum, ndcFromDb, seriesInitialFromDb, seriesFinalFromDb, match);
            return match;
        } catch (NumberFormatException e) {
            log.info("Numeric conversion error during series comparison. Dialed subscriber: '{}'. Error: {}",
                    dialedSubscriberPartStr, e.getMessage());
            return false;
        }
    }

    public Optional<Map<String, Object>> findIndicatorByNumber(
            String numberToLookup, // This is the number *after* operator prefix removal, if any
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
                effectiveNumberForLookup = originNdcOpt.get() + numberToLookup; // Prepend NDC
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

        if (maxNdcLength == 0 && !effectiveTelephonyTypeId.equals(CdrProcessingConfig.TIPOTELE_LOCAL)) {
            log.info("No NDC length range found for effective telephony type {}, cannot find indicator.", effectiveTelephonyTypeId);
            return Optional.empty();
        }

        Map<String, Integer> typeMinMaxConfig = configService.getTelephonyTypeMinMax(effectiveTelephonyTypeId, originCountryId);
        int typeMinDigits = typeMinMaxConfig.getOrDefault("min", 0);

        Map<String, Object> approximateMatchResult = null;

        for (int currentNdcLength = maxNdcLength; currentNdcLength >= minNdcLength; currentNdcLength--) {
            Integer ndcToMatchInDb;
            String subscriberPartForSeriesLookup;

            if (currentNdcLength == 0 && effectiveTelephonyTypeId.equals(CdrProcessingConfig.TIPOTELE_LOCAL)) {
                // For local calls where NDC length might be 0, it means series.ndc = 0
                ndcToMatchInDb = 0;
                subscriberPartForSeriesLookup = effectiveNumberForLookup;
            } else if (currentNdcLength > 0) {
                if (effectiveNumberForLookup.length() < currentNdcLength) continue;
                try {
                    ndcToMatchInDb = Integer.parseInt(effectiveNumberForLookup.substring(0, currentNdcLength));
                    subscriberPartForSeriesLookup = effectiveNumberForLookup.substring(currentNdcLength);
                } catch (NumberFormatException e) {
                    log.info("Could not parse NDC part as integer: {}", effectiveNumberForLookup.substring(0, currentNdcLength));
                    continue;
                }
            } else {
                // Skip if currentNdcLength is 0 but not for a TIPOTELE_LOCAL context
                continue;
            }

            if (!subscriberPartForSeriesLookup.matches("\\d*")) {
                log.info("Skipping NDC lookup: subscriber part '{}' is not valid.", subscriberPartForSeriesLookup);
                continue;
            }

            int minSubscriberLengthForThisTypeAndNdc = Math.max(0, typeMinDigits - currentNdcLength);
            if (subscriberPartForSeriesLookup.length() < minSubscriberLengthForThisTypeAndNdc) {
                log.info("Subscriber part '{}' (from num '{}') too short for NDC '{}' (min subscriber length {})",
                        subscriberPartForSeriesLookup, effectiveNumberForLookup, ndcToMatchInDb, minSubscriberLengthForThisTypeAndNdc);
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

            if (!effectiveTelephonyTypeId.equals(CdrProcessingConfig.TIPOTELE_INTERNACIONAL) && !effectiveTelephonyTypeId.equals(CdrProcessingConfig.TIPOTELE_SATELITAL)) {
                sqlBuilder.append(" AND i.origin_country_id = :originCountryId ");
            } else {
                sqlBuilder.append(" AND i.origin_country_id IN (0, :originCountryId) ");
            }

            if (!isPrefixBandOk && currentPrefixId != null) {
                sqlBuilder.append(" AND (i.operator_id = 0 OR i.operator_id IS NULL OR i.operator_id IN (SELECT p_sub.operator_id FROM prefix p_sub WHERE p_sub.id = :currentPrefixId)) ");
            }

            sqlBuilder.append("ORDER BY ");
            if (isPrefixBandOk) {
                sqlBuilder.append("  b.origin_indicator_id DESC NULLS LAST, ");
            }
            sqlBuilder.append("  CASE WHEN s.ndc = -1 THEN 1 ELSE 0 END ASC, ");
            sqlBuilder.append("  (s.final_number - s.initial_number) ASC, ");
            sqlBuilder.append("  s.id ASC ");

            Query query = entityManager.createNativeQuery(sqlBuilder.toString());
            query.setParameter("effectiveTelephonyTypeId", effectiveTelephonyTypeId);
            query.setParameter("ndcParam", ndcToMatchInDb);
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

                Integer dbNdc = (Integer) seriesData.get("series_ndc");
                Integer dbInitial = (Integer) seriesData.get("series_initial");
                Integer dbFinal = (Integer) seriesData.get("series_final");

                if (dbNdc != null && dbNdc == -1) {
                    if (approximateMatchResult == null) { // Store first approximate match
                        approximateMatchResult = new HashMap<>(seriesData);
                        approximateMatchResult.put("is_approximate", true);
                        log.info("Stored approximate match (NDC -1): {}", approximateMatchResult);
                    }
                    continue; // Continue to see if a non-approximate match is found for this NDC length
                }

                if (isNumberInSeriesRange(effectiveNumberForLookup, dbNdc, dbInitial, dbFinal)) {
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
        // Ensure NDC=0 (for local) results in length 0, other NDCs use their actual length.
        sqlBuilder.append("SELECT COALESCE(MIN(CASE WHEN s.ndc = 0 THEN 0 ELSE LENGTH(CAST(s.ndc AS VARCHAR)) END), 0) as min_len, ");
        sqlBuilder.append("       COALESCE(MAX(CASE WHEN s.ndc = 0 THEN 0 ELSE LENGTH(CAST(s.ndc AS VARCHAR)) END), 0) as max_len ");
        sqlBuilder.append("FROM series s ");
        sqlBuilder.append("JOIN indicator i ON s.indicator_id = i.id ");
        sqlBuilder.append("WHERE i.active = true AND s.active = true ");
        sqlBuilder.append("  AND i.telephony_type_id = :telephonyTypeId ");
        if (telephonyTypeId.equals(CdrProcessingConfig.TIPOTELE_INTERNACIONAL) || telephonyTypeId.equals(CdrProcessingConfig.TIPOTELE_SATELITAL)) {
            sqlBuilder.append("  AND i.origin_country_id IN (0, :originCountryId) ");
        } else {
            sqlBuilder.append("  AND i.origin_country_id = :originCountryId ");
        }
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
                "  AND s.ndc IS NOT NULL AND s.ndc != 0 AND s.ndc > 0 " +
                "GROUP BY s.ndc " +
                "ORDER BY COUNT(*) DESC, s.ndc ASC " +
                "LIMIT 1";
        Query query = entityManager.createNativeQuery(sql);
        query.setParameter("indicatorId", indicatorId);
        try {
            Integer ndc = (Integer) query.getSingleResult();
            log.info("Found local NDC {} for indicator {}", ndc, indicatorId);
            return Optional.of(ndc);
        } catch (NoResultException e) {
            log.info("No positive numeric NDC found for indicatorId: {}", indicatorId);
            return Optional.empty();
        } catch (Exception e) {
            log.info("Error finding local NDC for indicatorId: {}", indicatorId, e);
            return Optional.empty();
        }
    }

    public boolean isLocalExtended(Integer destinationNdc, Long originIndicatorId) {
        if (destinationNdc == null || originIndicatorId == null || originIndicatorId <= 0) {
            return false;
        }
        log.info("Checking if NDC {} is local extended for origin indicator {}", destinationNdc, originIndicatorId);
        String sql = "SELECT COUNT(s.id) FROM series s WHERE s.indicator_id = :originIndicatorId AND s.ndc = :destinationNdc AND s.active = true";
        Query query = entityManager.createNativeQuery(sql, Long.class);
        query.setParameter("originIndicatorId", originIndicatorId);
        query.setParameter("destinationNdc", destinationNdc);
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

    public Optional<Map<String, String>> findNationalSeriesDetailsByNdcAndSubscriber(String ndcStr, long subscriberNumber, Long originCountryId) {
        if (!StringUtils.hasText(ndcStr) || originCountryId == null) {
            log.info("findNationalSeriesDetails - Invalid input: ndc or originCountryId is null/empty.");
            return Optional.empty();
        }
        
        Integer ndc;
        try {
            ndc = Integer.parseInt(ndcStr);
        } catch (NumberFormatException e) {
            log.info("findNationalSeriesDetails - Could not parse NDC as integer: {}", ndcStr);
            return Optional.empty();
        }
        
        log.info("Finding national series details for NDC: {}, Subscriber: {}, OriginCountryId: {}", ndc, subscriberNumber, originCountryId);

        StringBuilder sqlBuilder = new StringBuilder();
        sqlBuilder.append("SELECT i.department_country, i.city_name, s.company ");
        sqlBuilder.append("FROM series s ");
        sqlBuilder.append("JOIN indicator i ON s.indicator_id = i.id ");
        sqlBuilder.append("WHERE i.telephony_type_id = :nationalType ");
        sqlBuilder.append("  AND s.ndc = :ndc ");
        sqlBuilder.append("  AND s.initial_number <= :subscriberNumber AND s.final_number >= :subscriberNumber ");
        sqlBuilder.append("  AND (i.operator_id = 0 OR i.operator_id IS NULL OR i.operator_id IN (SELECT p_sub.operator_id FROM prefix p_sub WHERE p_sub.id = :nationalRefPrefixId)) ");
        sqlBuilder.append("  AND i.origin_country_id IN (0, :originCountryId) ");
        sqlBuilder.append("  AND s.active = true AND i.active = true ");
        sqlBuilder.append("ORDER BY i.origin_country_id DESC, (s.final_number - s.initial_number) ASC ");
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