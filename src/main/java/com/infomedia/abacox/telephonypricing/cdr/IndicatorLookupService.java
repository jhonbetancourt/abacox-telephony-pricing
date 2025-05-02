// FILE: lookup/IndicatorLookupService.java
package com.infomedia.abacox.telephonypricing.cdr;

import com.infomedia.abacox.telephonypricing.entity.Indicator;
import jakarta.persistence.EntityManager;
import jakarta.persistence.NoResultException;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.Query;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
// import org.springframework.cache.annotation.Cacheable; // Consider caching
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Service
@Log4j2
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class IndicatorLookupService {

    @PersistenceContext
    private EntityManager entityManager;

    /**
     * Finds the best matching indicator and series for a given number, telephony type, and origin.
     * Replicates the logic of PHP's buscarDestino function.
     *
     * @param numberWithoutPrefix The phone number part after any operator prefix has been removed.
     * @param telephonyTypeId     The determined telephony type ID.
     * @param originCountryId     The origin country ID.
     * @param originIndicatorId   The indicator ID of the call origin (used for band origin filtering).
     * @param bandOk              Whether the associated prefix allows band lookup.
     * @param prefixId            The ID of the matched prefix (used for band lookup).
     * @return Optional containing a map with indicator and series details if found.
     */
    public Optional<Map<String, Object>> findIndicatorAndSeries(String numberWithoutPrefix, Long telephonyTypeId, Long originCountryId, Long originIndicatorId, boolean bandOk, Long prefixId) {
        if (telephonyTypeId == null || originCountryId == null || !StringUtils.hasText(numberWithoutPrefix)) {
            log.trace("findIndicatorAndSeries - Invalid input: num={}, ttId={}, ocId={}", numberWithoutPrefix, telephonyTypeId, originCountryId);
            return Optional.empty();
        }
        log.debug("Finding indicator/series for number: {}, telephonyTypeId: {}, originCountryId: {}, originIndicatorId: {}, bandOk: {}, prefixId: {}",
                numberWithoutPrefix, telephonyTypeId, originCountryId, originIndicatorId, bandOk, prefixId);

        Map<String, Integer> ndcLengths = findNdcMinMaxLength(telephonyTypeId, originCountryId);
        int minNdcLength = ndcLengths.getOrDefault("min", 0);
        int maxNdcLength = ndcLengths.getOrDefault("max", 0);

        boolean checkLocalFallback = (minNdcLength == 0 && maxNdcLength == 0 && telephonyTypeId.equals(CdrProcessingConfig.TIPOTELE_LOCAL));
        if (maxNdcLength == 0 && !checkLocalFallback) {
            log.trace("No NDC length range found for telephony type {}, cannot find indicator.", telephonyTypeId);
            return Optional.empty();
        }
        if (checkLocalFallback) {
            minNdcLength = 0; // Force NDC length 0 for local lookup
            maxNdcLength = 0;
            log.trace("Treating as LOCAL type lookup (NDC length 0)");
        }

        Map<String, Object> bestMatch = null;
        long smallestSeriesRange = Long.MAX_VALUE;

        // Iterate through possible NDC lengths, from longest to shortest (PHP logic)
        for (int ndcLength = maxNdcLength; ndcLength >= minNdcLength; ndcLength--) {
            String ndcStr = "";
            String subscriberNumberStr = numberWithoutPrefix;

            if (ndcLength > 0 && numberWithoutPrefix.length() >= ndcLength) {
                ndcStr = numberWithoutPrefix.substring(0, ndcLength);
                subscriberNumberStr = numberWithoutPrefix.substring(ndcLength);
            } else if (ndcLength > 0) {
                // Number is shorter than the current NDC length being checked
                continue;
            }

            // Validate parts are numeric before querying
            if (ndcStr.matches("\\d*") && subscriberNumberStr.matches("\\d+")) {
                Integer ndc = ndcStr.isEmpty() ? null : Integer.parseInt(ndcStr); // Use null for NDC 0/empty
                long subscriberNumber = Long.parseLong(subscriberNumberStr);

                // --- Build the complex query matching PHP's buscarDestino ---
                StringBuilder sqlBuilder = new StringBuilder();
                sqlBuilder.append("SELECT ");
                sqlBuilder.append(" i.id as indicator_id, i.department_country, i.city_name, i.operator_id, ");
                sqlBuilder.append(" s.ndc as series_ndc, s.initial_number as series_initial, s.final_number as series_final, ");
                sqlBuilder.append(" s.company as series_company "); // Fields needed by formatDestinationName

                String localTables = "";
                String localCondition = "";
                String orderByBandOrigin = "";

                // Add band conditions if applicable (PHP: $bandas_ok > 0 && $prefijo_id > 0)
                if (bandOk && prefixId != null && prefixId > 0) {
                    localTables = ", band b, band_indicator bi ";
                    localCondition = " AND b.prefix_id = :prefixId " +
                                     " AND bi.indicator_id = i.id AND bi.band_id = b.id " +
                                     " AND b.origin_indicator_id IN (0, :originIndicatorId) ";
                    orderByBandOrigin = " b.origin_indicator_id DESC, "; // Prioritize specific origin band
                    log.trace("Adding band conditions for prefixId {}, originIndicatorId {}", prefixId, originIndicatorId);
                } else if (prefixId != null && prefixId > 0) {
                    // PHP: Add operator condition based on prefix if bands are not used
                    localCondition = " AND (i.operator_id = 0 OR i.operator_id IN (SELECT p.operator_id FROM prefix p WHERE p.id = :prefixId)) ";
                    log.trace("Adding operator condition based on prefixId {}", prefixId);
                }

                sqlBuilder.append("FROM series s ");
                sqlBuilder.append("JOIN indicator i ON s.indicator_id = i.id ");
                sqlBuilder.append(localTables); // Add band tables if needed
                sqlBuilder.append("WHERE i.active = true AND s.active = true ");
                sqlBuilder.append("  AND i.telephony_type_id = :telephonyTypeId ");

                // Handle NDC condition (null or specific value)
                if (ndc != null) {
                    sqlBuilder.append("  AND s.ndc = :ndc ");
                } else {
                    sqlBuilder.append("  AND (s.ndc = 0 OR s.ndc IS NULL) "); // Match NDC 0 or NULL
                }

                // Handle origin country filtering (PHP logic)
                if (!CdrProcessingConfig.getInternalIpCallTypeIds().contains(telephonyTypeId) && // Exclude internal IP types
                    telephonyTypeId != CdrProcessingConfig.TIPOTELE_INTERNACIONAL &&
                    telephonyTypeId != CdrProcessingConfig.TIPOTELE_SATELITAL) {
                    sqlBuilder.append("  AND i.origin_country_id IN (0, :originCountryId) ");
                }

                // Series range condition
                sqlBuilder.append("  AND s.initial_number <= :subscriberNum AND s.final_number >= :subscriberNum ");

                // Add band/operator conditions
                sqlBuilder.append(localCondition);

                // Order by (PHP logic: NDC DESC, Band Origin DESC, Series Range ASC)
                sqlBuilder.append("ORDER BY ");
                if (ndcLength > 0) {
                    sqlBuilder.append(" LENGTH(CAST(s.ndc AS TEXT)) DESC, "); // Prioritize longer NDC matches implicitly via loop order
                }
                sqlBuilder.append(orderByBandOrigin); // Prioritize specific band origin
                sqlBuilder.append(" (s.final_number - s.initial_number) ASC "); // Prioritize smallest range

                // We fetch all matches for this NDC length and then pick the best one in Java
                // sqlBuilder.append("LIMIT 1"); // Remove LIMIT 1

                Query query = entityManager.createNativeQuery(sqlBuilder.toString());
                query.setParameter("telephonyTypeId", telephonyTypeId);
                if (ndc != null) {
                    query.setParameter("ndc", ndc);
                }
                query.setParameter("originCountryId", originCountryId);
                query.setParameter("subscriberNum", subscriberNumber);
                if (localCondition.contains(":prefixId")) { // Add prefixId only if needed
                    query.setParameter("prefixId", prefixId);
                }
                 if (localCondition.contains(":originIndicatorId")) { // Add originIndicatorId only if needed
                    query.setParameter("originIndicatorId", originIndicatorId != null ? originIndicatorId : 0L);
                }


                try {
                    List<Object[]> results = query.getResultList();
                    if (!results.isEmpty()) {
                        // PHP logic takes the *first* result based on its complex ORDER BY.
                        // Since we replicated the ORDER BY, we take the first result here as well.
                        Object[] result = results.get(0);
                        Map<String, Object> currentMatch = new HashMap<>();
                        Long indicatorId = (result[0] instanceof Number) ? ((Number) result[0]).longValue() : null;
                        Integer seriesNdc = (result[4] instanceof Number) ? ((Number) result[4]).intValue() : null;
                        Integer seriesInitial = (result[5] instanceof Number) ? ((Number) result[5]).intValue() : null;
                        Integer seriesFinal = (result[6] instanceof Number) ? ((Number) result[6]).intValue() : null;

                        currentMatch.put("indicator_id", (indicatorId != null && indicatorId > 0) ? indicatorId : null);
                        currentMatch.put("department_country", result[1]);
                        currentMatch.put("city_name", result[2]);
                        currentMatch.put("operator_id", result[3]);
                        currentMatch.put("series_ndc", seriesNdc);
                        currentMatch.put("series_initial", seriesInitial);
                        currentMatch.put("series_final", seriesFinal);
                        currentMatch.put("series_company", result[7]);

                        // Calculate the range size for comparison
                        long currentRange = (seriesFinal != null && seriesInitial != null) ? (long)seriesFinal - seriesInitial : Long.MAX_VALUE;

                        // PHP implicitly prefers the result from the longest NDC length loop iteration.
                        // If multiple results have the same NDC length, it prefers the smallest series range.
                        if (bestMatch == null || currentRange < smallestSeriesRange) {
                            bestMatch = currentMatch;
                            smallestSeriesRange = currentRange;
                            log.trace("Found potential best match for number {} (NDC: {}): ID={}, RangeSize={}", numberWithoutPrefix, ndcStr, bestMatch.get("indicator_id"), smallestSeriesRange);
                        }
                        // Since we iterate NDC length from max to min, the first match found for a given length
                        // with the smallest range is the best candidate *for that length*.
                        // We continue the loop to check shorter NDC lengths, but only update bestMatch
                        // if a subsequent match has an even *smaller* series range (unlikely given the ORDER BY).
                        // This effectively prioritizes the longest NDC match with the tightest series fit.
                    }
                } catch (Exception e) {
                    log.error("Error finding indicator/series for number: {}, ndc: {}: {}", numberWithoutPrefix, ndcStr, e.getMessage(), e);
                    // Continue to next NDC length
                }
            } else {
                log.trace("Skipping NDC check: NDC '{}' or Subscriber '{}' is not numeric.", ndcStr, subscriberNumberStr);
            }
        } // End NDC length loop

        if (bestMatch != null) {
            log.debug("Final best match found for number {}: Indicator ID {}", numberWithoutPrefix, bestMatch.get("indicator_id"));
            return Optional.of(bestMatch);
        } else {
            log.trace("No indicator/series found for number: {}", numberWithoutPrefix);
            return Optional.empty(); // No indicator found after checking all possible NDC lengths
        }
    }


    // --- Other methods remain the same ---
    public Map<String, Integer> findNdcMinMaxLength(Long telephonyTypeId, Long originCountryId) {
        Map<String, Integer> lengths = new HashMap<>();
        lengths.put("min", 0); lengths.put("max", 0);
        if (telephonyTypeId == null || originCountryId == null) return lengths;

        log.debug("Finding min/max NDC length for telephonyTypeId: {}, originCountryId: {}", telephonyTypeId, originCountryId);
        StringBuilder sqlBuilder = new StringBuilder();
        // Use COALESCE with 0 to handle cases where MIN/MAX might return NULL if no rows match
        sqlBuilder.append("SELECT COALESCE(MIN(LENGTH(CAST(s.ndc AS TEXT))), 0) as min_len, ");
        sqlBuilder.append("       COALESCE(MAX(LENGTH(CAST(s.ndc AS TEXT))), 0) as max_len ");
        sqlBuilder.append("FROM series s ");
        sqlBuilder.append("JOIN indicator i ON s.indicator_id = i.id ");
        sqlBuilder.append("WHERE i.active = true AND s.active = true ");
        sqlBuilder.append("  AND i.telephony_type_id = :telephonyTypeId ");
        sqlBuilder.append("  AND i.origin_country_id IN (0, :originCountryId) ");
        sqlBuilder.append("  AND s.ndc IS NOT NULL AND s.ndc > 0 "); // Ensure ndc is positive for length calculation

        Query query = entityManager.createNativeQuery(sqlBuilder.toString());
        query.setParameter("telephonyTypeId", telephonyTypeId);
        query.setParameter("originCountryId", originCountryId);

        try {
            Object[] result = (Object[]) query.getSingleResult();
            // Safely cast and handle potential nulls from COALESCE if no rows were found
            lengths.put("min", result[0] != null ? ((Number) result[0]).intValue() : 0);
            lengths.put("max", result[1] != null ? ((Number) result[1]).intValue() : 0);
        } catch (Exception e) {
            // Log as warning, defaults (0,0) will be used
            log.warn("Could not determine NDC lengths for telephony type {}: {}", telephonyTypeId, e.getMessage());
        }
        log.trace("NDC lengths for type {}: min={}, max={}", telephonyTypeId, lengths.get("min"), lengths.get("max"));
        return lengths;
    }

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

    public Optional<Integer> findLocalNdcForIndicator(Long indicatorId) {
        if (indicatorId == null || indicatorId <= 0) return Optional.empty();
        log.debug("Finding local NDC for indicatorId: {}", indicatorId);
        String sql = "SELECT s.ndc FROM series s " +
                "WHERE s.indicator_id = :indicatorId " +
                "  AND s.ndc IS NOT NULL AND s.ndc > 0 " + // Only consider positive NDCs
                "GROUP BY s.ndc " +
                "ORDER BY COUNT(*) DESC, s.ndc ASC " + // Prioritize most frequent, then lowest value
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

    public boolean isLocalExtended(Integer destinationNdc, Long originIndicatorId) {
        if (destinationNdc == null || originIndicatorId == null || originIndicatorId <= 0) {
            return false;
        }
        log.debug("Checking if NDC {} is local extended for origin indicator {}", destinationNdc, originIndicatorId);
        // PHP logic checks if the destination NDC exists for the origin indicator ID
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