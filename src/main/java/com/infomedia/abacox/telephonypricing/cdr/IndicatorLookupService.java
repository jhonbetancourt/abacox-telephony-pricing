// FILE: lookup/IndicatorLookupService.java
package com.infomedia.abacox.telephonypricing.cdr;

import com.infomedia.abacox.telephonypricing.entity.Indicator;
import jakarta.persistence.EntityManager;
import jakarta.persistence.NoResultException;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.Query;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

@Service
@Log4j2
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class IndicatorLookupService {

    @PersistenceContext
    private EntityManager entityManager;

    @Cacheable(value = "indicatorLookup", key = "{#numberWithoutPrefix, #telephonyTypeId, #originCountryId}")
    public Optional<Map<String, Object>> findIndicatorByNumber(String numberWithoutPrefix, Long telephonyTypeId, Long originCountryId) {
        if (telephonyTypeId == null || originCountryId == null || !StringUtils.hasText(numberWithoutPrefix)) {
            log.trace("findIndicatorByNumber - Invalid input: num={}, ttId={}, ocId={}", numberWithoutPrefix, telephonyTypeId, originCountryId);
            return Optional.empty();
        }
        log.debug("Finding indicator for number: {}, telephonyTypeId: {}, originCountryId: {}", numberWithoutPrefix, telephonyTypeId, originCountryId);

        Map<String, Integer> ndcLengths = findNdcMinMaxLength(telephonyTypeId, originCountryId);
        int minNdcLength = ndcLengths.getOrDefault("min", 0);
        int maxNdcLength = ndcLengths.getOrDefault("max", 0);

        boolean checkLocalFallback = (minNdcLength == 0 && maxNdcLength == 0 && telephonyTypeId.equals(CdrProcessingConfig.TIPOTELE_LOCAL));
        if (maxNdcLength == 0 && !checkLocalFallback) {
            log.trace("No NDC length range found for telephony type {}, cannot find indicator.", telephonyTypeId);
            return Optional.empty();
        }

        if(checkLocalFallback) {
            minNdcLength = 0;
            maxNdcLength = 0;
            log.trace("Treating as LOCAL type lookup (NDC length 0)");
        }

        for (int ndcLength = maxNdcLength; ndcLength >= minNdcLength; ndcLength--) {
            String ndcStr = "";
            String subscriberNumberStr = numberWithoutPrefix;

            if (ndcLength > 0 && numberWithoutPrefix.length() >= ndcLength) {
                ndcStr = numberWithoutPrefix.substring(0, ndcLength);
                subscriberNumberStr = numberWithoutPrefix.substring(ndcLength);
            } else if (ndcLength > 0) {
                continue;
            }

            if (ndcStr.matches("\\d*") && subscriberNumberStr.matches("\\d+")) {
                Integer ndc = ndcStr.isEmpty() ? null : Integer.parseInt(ndcStr);
                long subscriberNumber = Long.parseLong(subscriberNumberStr);

                StringBuilder sqlBuilder = new StringBuilder();
                sqlBuilder.append("SELECT ");
                sqlBuilder.append(" i.id as indicator_id, i.department_country, i.city_name, i.operator_id, ");
                sqlBuilder.append(" s.ndc as series_ndc, s.initial_number as series_initial, s.final_number as series_final, ");
                sqlBuilder.append(" s.company as series_company ");
                sqlBuilder.append("FROM series s ");
                sqlBuilder.append("JOIN indicator i ON s.indicator_id = i.id ");
                sqlBuilder.append("WHERE i.active = true AND s.active = true ");
                sqlBuilder.append("  AND i.telephony_type_id = :telephonyTypeId ");
                if (ndc != null) {
                    sqlBuilder.append("  AND s.ndc = :ndc ");
                } else {
                    sqlBuilder.append("  AND (s.ndc = 0 OR s.ndc IS NULL) ");
                }
                sqlBuilder.append("  AND i.origin_country_id IN (0, :originCountryId) ");
                sqlBuilder.append("  AND s.initial_number <= :subscriberNum AND s.final_number >= :subscriberNum ");
                sqlBuilder.append("ORDER BY i.origin_country_id DESC, ");
                if (ndcLength > 0) {
                    sqlBuilder.append("     LENGTH(CAST(s.ndc AS TEXT)) DESC, ");
                }
                sqlBuilder.append("         (s.final_number - s.initial_number) ASC ");
                sqlBuilder.append("LIMIT 1");

                Query query = entityManager.createNativeQuery(sqlBuilder.toString());
                query.setParameter("telephonyTypeId", telephonyTypeId);
                if (ndc != null) {
                    query.setParameter("ndc", ndc);
                }
                query.setParameter("originCountryId", originCountryId);
                query.setParameter("subscriberNum", subscriberNumber);

                try {
                    Object[] result = (Object[]) query.getSingleResult();
                    Map<String, Object> map = new HashMap<>();
                    Long indicatorId = (result[0] instanceof Number) ? ((Number) result[0]).longValue() : null;

                    map.put("indicator_id", (indicatorId != null && indicatorId > 0) ? indicatorId : null);
                    map.put("department_country", result[1]);
                    map.put("city_name", result[2]);
                    map.put("operator_id", result[3]);
                    map.put("series_ndc", result[4]);
                    map.put("series_initial", result[5]);
                    map.put("series_final", result[6]);
                    map.put("series_company", result[7]);
                    log.trace("Found indicator info for number {} (NDC: {}): ID={}", numberWithoutPrefix, ndcStr, map.get("indicator_id"));
                    return Optional.of(map);
                } catch (NoResultException e) {
                    log.trace("No indicator found for NDC '{}', subscriber '{}'", ndcStr, subscriberNumberStr);
                } catch (Exception e) {
                    log.error("Error finding indicator for number: {}, ndc: {}: {}", numberWithoutPrefix, ndcStr, e.getMessage(), e);
                }
            } else {
                log.trace("Skipping NDC check: NDC '{}' or Subscriber '{}' is not numeric.", ndcStr, subscriberNumberStr);
            }
        } // End NDC length loop

        log.trace("No indicator found for number: {}", numberWithoutPrefix);
        return Optional.empty(); // No indicator found after checking all possible NDC lengths
    }

    @Cacheable(value = "ndcMinMaxLength", key = "{#telephonyTypeId, #originCountryId}")
    public Map<String, Integer> findNdcMinMaxLength(Long telephonyTypeId, Long originCountryId) {
        Map<String, Integer> lengths = new HashMap<>();
        lengths.put("min", 0); lengths.put("max", 0);
        if (telephonyTypeId == null || originCountryId == null) return lengths;

        log.debug("Finding min/max NDC length for telephonyTypeId: {}, originCountryId: {}", telephonyTypeId, originCountryId);
        StringBuilder sqlBuilder = new StringBuilder();
        sqlBuilder.append("SELECT COALESCE(MIN(LENGTH(CAST(s.ndc AS TEXT))), 0) as min_len, ");
        sqlBuilder.append("       COALESCE(MAX(LENGTH(CAST(s.ndc AS TEXT))), 0) as max_len ");
        sqlBuilder.append("FROM series s ");
        sqlBuilder.append("JOIN indicator i ON s.indicator_id = i.id ");
        sqlBuilder.append("WHERE i.active = true AND s.active = true ");
        sqlBuilder.append("  AND i.telephony_type_id = :telephonyTypeId ");
        sqlBuilder.append("  AND i.origin_country_id IN (0, :originCountryId) ");
        sqlBuilder.append("  AND s.ndc > 0 ");

        Query query = entityManager.createNativeQuery(sqlBuilder.toString());
        query.setParameter("telephonyTypeId", telephonyTypeId);
        query.setParameter("originCountryId", originCountryId);

        try {
            Object[] result = (Object[]) query.getSingleResult();
            lengths.put("min", result[0] != null ? ((Number) result[0]).intValue() : 0);
            lengths.put("max", result[1] != null ? ((Number) result[1]).intValue() : 0);
        } catch (Exception e) {
            log.warn("Could not determine NDC lengths for telephony type {}: {}", telephonyTypeId, e.getMessage());
        }
        log.trace("NDC lengths for type {}: min={}, max={}", telephonyTypeId, lengths.get("min"), lengths.get("max"));
        return lengths;
    }

    @Cacheable(value = "seriesInfoForNationalLookup", key = "{#ndc, #subscriberNumber}")
    public Optional<Map<String, Object>> findSeriesInfoForNationalLookup(int ndc, long subscriberNumber) {
        log.debug("Finding series info for national lookup: NDC={}, Subscriber={}", ndc, subscriberNumber);
        String sql = "SELECT s.company as series_company " +
                     "FROM series s " +
                     "WHERE s.active = true " +
                     "  AND s.ndc = :ndc " +
                     "  AND s.initial_number <= :subscriberNum AND s.final_number >= :subscriberNum " +
                     "LIMIT 1";
        Query query = entityManager.createNativeQuery(sql);
        query.setParameter("ndc", ndc);
        query.setParameter("subscriberNum", subscriberNumber);
        try {
            Object result = query.getSingleResult();
            Map<String, Object> map = new HashMap<>();
            map.put("series_company", result);
            return Optional.of(map);
        } catch (NoResultException e) {
            log.trace("No series found for national lookup: NDC={}, Subscriber={}", ndc, subscriberNumber);
            return Optional.empty();
        } catch (Exception e) {
            log.error("Error finding series info for national lookup: NDC={}, Subscriber={}: {}", ndc, subscriberNumber, e.getMessage(), e);
            return Optional.empty();
        }
    }

    @Cacheable(value = "localNdc", key = "#indicatorId")
    public Optional<Integer> findLocalNdcForIndicator(Long indicatorId) {
        if (indicatorId == null || indicatorId <= 0) return Optional.empty();
        log.debug("Finding local NDC for indicatorId: {}", indicatorId);
        String sql = "SELECT s.ndc FROM series s " +
                "WHERE s.indicator_id = :indicatorId " +
                "  AND s.ndc IS NOT NULL AND s.ndc > 0 " +
                "GROUP BY s.ndc " +
                "ORDER BY COUNT(*) DESC, s.ndc ASC " +
                "LIMIT 1";
        Query query = entityManager.createNativeQuery(sql, Integer.class);
        query.setParameter("indicatorId", indicatorId);
        try {
            Integer ndc = (Integer) query.getSingleResult();
            log.trace("Found local NDC {} for indicator {}", ndc, indicatorId);
            return Optional.ofNullable(ndc);
        } catch (NoResultException e) {
            log.warn("No positive NDC found for indicatorId: {}", indicatorId);
            return Optional.empty();
        } catch (Exception e) {
            log.error("Error finding local NDC for indicatorId: {}", indicatorId, e);
            return Optional.empty();
        }
    }

    @Cacheable(value = "isLocalExtended", key = "{#destinationNdc, #originIndicatorId}")
    public boolean isLocalExtended(Integer destinationNdc, Long originIndicatorId) {
        if (destinationNdc == null || originIndicatorId == null || originIndicatorId <= 0) {
            return false;
        }
        log.debug("Checking if NDC {} is local extended for origin indicator {}", destinationNdc, originIndicatorId);
        String sql = "SELECT COUNT(s.id) FROM series s WHERE s.indicator_id = :originIndicatorId AND s.ndc = :destinationNdc";
        Query query = entityManager.createNativeQuery(sql, Long.class);
        query.setParameter("originIndicatorId", originIndicatorId);
        query.setParameter("destinationNdc", destinationNdc);
        try {
            Long count = (Long) query.getSingleResult();
            boolean isExtended = count != null && count > 0;
            log.trace("Is NDC {} local extended for origin indicator {}: {}", destinationNdc, originIndicatorId, isExtended);
            return isExtended;
        } catch (Exception e) {
            log.error("Error checking local extended status for NDC {}, origin indicator {}: {}", destinationNdc, originIndicatorId, e);
            return false;
        }
    }

    @Cacheable("indicatorById")
    public Optional<Indicator> findIndicatorById(Long id) {
        if (id == null || id <= 0) {
             log.trace("findIndicatorById requested for invalid ID: {}", id);
             return Optional.empty();
        }
        String sql = "SELECT i.* FROM indicator i WHERE i.id = :id AND i.active = true";
        Query query = entityManager.createNativeQuery(sql, Indicator.class);
        query.setParameter("id", id);
        try { return Optional.of((Indicator) query.getSingleResult()); }
        catch (NoResultException e) { return Optional.empty(); }
    }
}