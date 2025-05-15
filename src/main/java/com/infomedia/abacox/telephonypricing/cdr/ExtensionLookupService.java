package com.infomedia.abacox.telephonypricing.cdr;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.Query;
import lombok.extern.log4j.Log4j2;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.Map;

@Service
@Log4j2
@Transactional(readOnly = true)
public class ExtensionLookupService {

    @PersistenceContext
    private EntityManager entityManager;
    // CdrProcessingConfig might be needed if DEFAULT_MIN/MAX_EXT_LENGTH are moved there from static constants
    // For now, assuming CdrProcessingConfig provides these values via methods if needed.
    // private final CdrProcessingConfig configService; // Add if CdrProcessingConfig methods are used

    // Assuming CdrProcessingConfig provides these values, if not, they need to be defined or passed.
    // For this refactoring, I'll assume they are available via configService or are static in CdrProcessingConfig
    // public ExtensionLookupService(EntityManager entityManager, CdrProcessingConfig configService) {
    public ExtensionLookupService(EntityManager entityManager) { // Simplified if configService not directly used for constants here
        this.entityManager = entityManager;
        // this.configService = configService;
    }

    public Map<String, Integer> findExtensionMinMaxLength(Long commLocationId) {
        log.debug("Finding min/max extension length for commLocationId: {}", commLocationId);
        Map<String, Integer> lengths = new HashMap<>();
        lengths.put("min", Integer.MAX_VALUE);
        lengths.put("max", 0);

        int maxPossibleLength = String.valueOf(CdrProcessingConfig.MAX_POSSIBLE_EXTENSION_VALUE).length();

        StringBuilder sqlEmployee = new StringBuilder();
        sqlEmployee.append("SELECT COALESCE(MIN(LENGTH(e.extension)), NULL) AS min_len, COALESCE(MAX(LENGTH(e.extension)), NULL) AS max_len ");
        sqlEmployee.append("FROM employee e ");
        sqlEmployee.append("WHERE e.active = true ");
        sqlEmployee.append("  AND e.extension ~ '^[0-9]+$' ");
        sqlEmployee.append("  AND e.extension NOT LIKE '0%' ");
        sqlEmployee.append("  AND LENGTH(e.extension) > 0 AND LENGTH(e.extension) < :maxExtPossibleLength ");
        if (commLocationId != null && commLocationId > 0) {
            sqlEmployee.append(" AND e.communication_location_id = :commLocationId ");
        }

        Query queryEmp = entityManager.createNativeQuery(sqlEmployee.toString());
        queryEmp.setParameter("maxExtPossibleLength", maxPossibleLength);
        if (commLocationId != null && commLocationId > 0) {
            queryEmp.setParameter("commLocationId", commLocationId);
        }

        try {
            Object[] resultEmp = (Object[]) queryEmp.getSingleResult();
            Integer minEmp = resultEmp[0] != null ? ((Number) resultEmp[0]).intValue() : null;
            Integer maxEmp = resultEmp[1] != null ? ((Number) resultEmp[1]).intValue() : null;

            if (minEmp != null && minEmp < lengths.get("min")) lengths.put("min", minEmp);
            if (maxEmp != null && maxEmp > lengths.get("max")) lengths.put("max", maxEmp);
            log.trace("Employee ext lengths for loc {}: min={}, max={}", commLocationId, minEmp, maxEmp);
        } catch (Exception e) { log.warn("Could not determine extension lengths from employees for loc {}: {}", commLocationId, e.getMessage()); }

        StringBuilder sqlRange = new StringBuilder();
        sqlRange.append("SELECT COALESCE(MIN(LENGTH(CAST(er.range_start AS TEXT))), NULL) AS min_len, COALESCE(MAX(LENGTH(CAST(er.range_end AS TEXT))), NULL) AS max_len ");
        sqlRange.append("FROM extension_range er ");
        sqlRange.append("WHERE er.active = true ");
        sqlRange.append("  AND CAST(er.range_start AS TEXT) ~ '^[0-9]+$' AND CAST(er.range_end AS TEXT) ~ '^[0-9]+$' ");
        sqlRange.append("  AND LENGTH(CAST(er.range_start AS TEXT)) > 0 AND LENGTH(CAST(er.range_start AS TEXT)) < :maxExtPossibleLength ");
        sqlRange.append("  AND LENGTH(CAST(er.range_end AS TEXT)) > 0 AND LENGTH(CAST(er.range_end AS TEXT)) < :maxExtPossibleLength ");
        sqlRange.append("  AND er.range_end >= er.range_start ");
        if (commLocationId != null && commLocationId > 0) {
            sqlRange.append(" AND er.comm_location_id = :commLocationId ");
        }

        Query queryRange = entityManager.createNativeQuery(sqlRange.toString());
        queryRange.setParameter("maxExtPossibleLength", maxPossibleLength);
        if (commLocationId != null && commLocationId > 0) {
            queryRange.setParameter("commLocationId", commLocationId);
        }

        try {
            Object[] resultRange = (Object[]) queryRange.getSingleResult();
            Integer minRange = resultRange[0] != null ? ((Number) resultRange[0]).intValue() : null;
            Integer maxRange = resultRange[1] != null ? ((Number) resultRange[1]).intValue() : null;

            if (minRange != null && minRange < lengths.get("min")) lengths.put("min", minRange);
            if (maxRange != null && maxRange > lengths.get("max")) lengths.put("max", maxRange);
            log.trace("Range ext lengths for loc {}: min={}, max={}", commLocationId, minRange, maxRange);
        } catch (Exception e) { log.warn("Could not determine extension lengths from ranges for loc {}: {}", commLocationId, e.getMessage()); }

        if (lengths.get("min") == Integer.MAX_VALUE) lengths.put("min", CdrProcessingConfig.DEFAULT_MIN_EXT_LENGTH);
        if (lengths.get("max") == 0) lengths.put("max", CdrProcessingConfig.DEFAULT_MAX_EXT_LENGTH);
        if (lengths.get("min") > lengths.get("max")) {
            log.warn("Calculated min length ({}) > max length ({}) for loc {}, adjusting min to max.", lengths.get("min"), lengths.get("max"), commLocationId);
            lengths.put("min", lengths.get("max"));
        }

        log.debug("Final determined extension lengths for loc {}: min={}, max={}", commLocationId, lengths.get("min"), lengths.get("max"));
        return lengths;
    }
}