// FILE: lookup/ConfigLookupService.java
package com.infomedia.abacox.telephonypricing.cdr;

import com.infomedia.abacox.telephonypricing.cdr.CdrProcessingConfig;
import com.infomedia.abacox.telephonypricing.entity.CommunicationLocation;
import com.infomedia.abacox.telephonypricing.entity.Indicator;
import com.infomedia.abacox.telephonypricing.entity.OriginCountry;
import com.infomedia.abacox.telephonypricing.entity.TelephonyTypeConfig; // Import added
import jakarta.persistence.EntityManager;
import jakarta.persistence.NoResultException;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.Query;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
// import org.springframework.cache.annotation.Cacheable; // Cache potentially useful here
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collections; // Import added
import java.util.HashMap;
import java.util.List; // Import added
import java.util.Map;
import java.util.Optional;

@Service
@Log4j2
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ConfigLookupService {

    @PersistenceContext
    private EntityManager entityManager;

    public Optional<String> findPbxPrefixByCommLocationId(Long commLocationId) {
        if (commLocationId == null) return Optional.empty();
        log.debug("Finding PBX prefix for commLocationId: {}", commLocationId);
        String sql = "SELECT pbx_prefix FROM communication_location WHERE id = :id";
        Query query = entityManager.createNativeQuery(sql, String.class);
        query.setParameter("id", commLocationId);
        try {
            return Optional.ofNullable((String) query.getSingleResult());
        } catch (NoResultException e) {
            log.trace("No CommunicationLocation found for ID: {}", commLocationId);
            return Optional.empty();
        } catch (Exception e) {
            log.error("Error finding PBX prefix for commLocationId: {}", commLocationId, e);
            return Optional.empty();
        }
    }

    public Map<String, Integer> findTelephonyTypeMinMaxConfig(Long telephonyTypeId, Long originCountryId) {
        Map<String, Integer> config = new HashMap<>();
        config.put("min", 0); config.put("max", 0);
        if (telephonyTypeId == null || originCountryId == null) return config;

        log.debug("Finding min/max config for telephonyTypeId: {}, originCountryId: {}", telephonyTypeId, originCountryId);
        String sql = "SELECT min_value, max_value FROM telephony_type_config " +
                "WHERE telephony_type_id = :telephonyTypeId AND origin_country_id = :originCountryId " +
                "LIMIT 1";
        Query query = entityManager.createNativeQuery(sql);
        query.setParameter("telephonyTypeId", telephonyTypeId);
        query.setParameter("originCountryId", originCountryId);
        try {
            Object[] result = (Object[]) query.getSingleResult();
            config.put("min", result[0] != null ? ((Number) result[0]).intValue() : 0);
            config.put("max", result[1] != null ? ((Number) result[1]).intValue() : 0);
        } catch (NoResultException e) {
            log.trace("No TelephonyTypeConfig found for type {}, country {}. Using defaults.", telephonyTypeId, originCountryId);
        } catch (Exception e) {
            log.error("Error finding min/max config for telephonyTypeId: {}, originCountryId: {}", telephonyTypeId, originCountryId, e);
        }
        return config;
    }

    public List<TelephonyTypeConfig> findAllTelephonyTypeConfigsByCountry(Long originCountryId) {
        if (originCountryId == null) {
            log.warn("findAllTelephonyTypeConfigsByCountry called with null originCountryId.");
            return Collections.emptyList();
        }
        log.debug("Finding all TelephonyTypeConfigs for originCountryId: {}", originCountryId);
        String sql = "SELECT ttc.* FROM telephony_type_config ttc " +
                     "JOIN telephony_type tt ON ttc.telephony_type_id = tt.id " + // Ensure linked type is active
                     "WHERE ttc.origin_country_id = :originCountryId AND tt.active = true";
        Query query = entityManager.createNativeQuery(sql, TelephonyTypeConfig.class);
        query.setParameter("originCountryId", originCountryId);
        try {
            return query.getResultList();
        } catch (Exception e) {
            log.error("Error finding all TelephonyTypeConfigs for originCountryId: {}", originCountryId, e);
            return Collections.emptyList();
        }
    }


    public Map<String, Integer> findExtensionMinMaxLength(Long commLocationId) {
        log.debug("Finding min/max extension length for commLocationId: {}", commLocationId);
        Map<String, Integer> lengths = new HashMap<>();
        lengths.put("min", Integer.MAX_VALUE);
        lengths.put("max", 0);

        int maxPossibleLength = String.valueOf(CdrProcessingConfig.MAX_POSSIBLE_EXTENSION_VALUE).length();

        // Query 1: Based on Employee extensions
        StringBuilder sqlEmployee = new StringBuilder();
        sqlEmployee.append("SELECT COALESCE(MIN(LENGTH(e.extension)), NULL) AS min_len, COALESCE(MAX(LENGTH(e.extension)), NULL) AS max_len ");
        sqlEmployee.append("FROM employee e ");
        sqlEmployee.append("WHERE e.active = true ");
        sqlEmployee.append("  AND e.extension ~ '^[0-9#*]+$' "); // Allow # and *
        sqlEmployee.append("  AND e.extension NOT LIKE '0%' "); // Exclude numbers starting with 0
        sqlEmployee.append("  AND LENGTH(e.extension) < :maxExtPossibleLength ");
        if (commLocationId != null) {
            sqlEmployee.append(" AND e.communication_location_id = :commLocationId ");
        }

        Query queryEmp = entityManager.createNativeQuery(sqlEmployee.toString());
        queryEmp.setParameter("maxExtPossibleLength", maxPossibleLength);
        if (commLocationId != null) {
            queryEmp.setParameter("commLocationId", commLocationId);
        }

        try {
            Object[] resultEmp = (Object[]) queryEmp.getSingleResult();
            Integer minEmp = resultEmp[0] != null ? ((Number) resultEmp[0]).intValue() : null;
            Integer maxEmp = resultEmp[1] != null ? ((Number) resultEmp[1]).intValue() : null;

            if (minEmp != null && minEmp < lengths.get("min")) lengths.put("min", minEmp);
            if (maxEmp != null && maxEmp > lengths.get("max")) lengths.put("max", maxEmp);
            log.trace("Employee ext lengths: min={}, max={}", minEmp, maxEmp);
        } catch (Exception e) { log.warn("Could not determine extension lengths from employees: {}", e.getMessage()); }

        // Query 2: Based on ExtensionRange
        StringBuilder sqlRange = new StringBuilder();
        sqlRange.append("SELECT COALESCE(MIN(LENGTH(CAST(er.range_start AS TEXT))), NULL) AS min_len, COALESCE(MAX(LENGTH(CAST(er.range_end AS TEXT))), NULL) AS max_len ");
        sqlRange.append("FROM extension_range er ");
        sqlRange.append("WHERE er.active = true ");
        // Ensure start/end are numeric before casting/length check
        sqlRange.append("  AND CAST(er.range_start AS TEXT) ~ '^[0-9]+$' AND CAST(er.range_end AS TEXT) ~ '^[0-9]+$' ");
        sqlRange.append("  AND LENGTH(CAST(er.range_start AS TEXT)) < :maxExtPossibleLength ");
        sqlRange.append("  AND LENGTH(CAST(er.range_end AS TEXT)) < :maxExtPossibleLength ");
        sqlRange.append("  AND er.range_end >= er.range_start "); // Basic range validity
        if (commLocationId != null) {
            sqlRange.append(" AND er.comm_location_id = :commLocationId ");
        }

        Query queryRange = entityManager.createNativeQuery(sqlRange.toString());
        queryRange.setParameter("maxExtPossibleLength", maxPossibleLength);
        if (commLocationId != null) {
            queryRange.setParameter("commLocationId", commLocationId);
        }

        try {
            Object[] resultRange = (Object[]) queryRange.getSingleResult();
            Integer minRange = resultRange[0] != null ? ((Number) resultRange[0]).intValue() : null;
            Integer maxRange = resultRange[1] != null ? ((Number) resultRange[1]).intValue() : null;

            if (minRange != null && minRange < lengths.get("min")) lengths.put("min", minRange);
            if (maxRange != null && maxRange > lengths.get("max")) lengths.put("max", maxRange);
            log.trace("Range ext lengths: min={}, max={}", minRange, maxRange);
        } catch (Exception e) { log.warn("Could not determine extension lengths from ranges: {}", e.getMessage()); }

        // Final adjustments
        if (lengths.get("min") == Integer.MAX_VALUE) lengths.put("min", CdrProcessingConfig.DEFAULT_MIN_EXT_LENGTH);
        if (lengths.get("max") == 0) lengths.put("max", CdrProcessingConfig.DEFAULT_MAX_EXT_LENGTH);
        if (lengths.get("min") > lengths.get("max")) {
            log.warn("Calculated min length ({}) > max length ({}), adjusting min to max.", lengths.get("min"), lengths.get("max"));
            lengths.put("min", lengths.get("max"));
        }

        log.debug("Final determined extension lengths: min={}, max={}", lengths.get("min"), lengths.get("max"));
        return lengths;
    }

    public Optional<OriginCountry> findOriginCountryById(Long id) {
        if (id == null || id <= 0) return Optional.empty();
        String sql = "SELECT oc.* FROM origin_country oc WHERE oc.id = :id AND oc.active = true";
        Query query = entityManager.createNativeQuery(sql, OriginCountry.class);
        query.setParameter("id", id);
        try { return Optional.of((OriginCountry) query.getSingleResult()); }
        catch (NoResultException e) { return Optional.empty(); }
    }

    // Helper to get OriginCountryId from CommLocation -> Indicator
    public Long getOriginCountryIdFromCommLocation(CommunicationLocation commLocation) {
        if (commLocation == null) return null;
        Indicator indicator = commLocation.getIndicator(); // Check linked entity first
        if (indicator != null && indicator.getOriginCountryId() != null) {
            return indicator.getOriginCountryId();
        }
        // If not linked or indicator has no country, try looking up indicator by ID
        if (commLocation.getIndicatorId() != null) {
            String sql = "SELECT i.origin_country_id FROM indicator i WHERE i.id = :indicatorId AND i.active = true";
            Query query = entityManager.createNativeQuery(sql);
            query.setParameter("indicatorId", commLocation.getIndicatorId());
            try {
                Object result = query.getSingleResult();
                if (result instanceof Number) {
                    return ((Number) result).longValue();
                }
            } catch (NoResultException e) {
                log.warn("Indicator {} linked to CommLocation {} not found or has no OriginCountryId.", commLocation.getIndicatorId(), commLocation.getId());
            } catch (Exception e) {
                 log.error("Error fetching origin country ID for indicator {}: {}", commLocation.getIndicatorId(), e.getMessage());
            }
        } else {
            log.warn("CommLocation {} has no IndicatorId.", commLocation.getId());
        }
        return null; // Default if not found
    }
}