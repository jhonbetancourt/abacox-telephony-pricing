package com.infomedia.abacox.telephonypricing.cdr;

import com.infomedia.abacox.telephonypricing.entity.CommunicationLocation;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import lombok.Getter;
import org.springframework.stereotype.Service;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class CdrConfigService {

    @PersistenceContext
    private EntityManager entityManager;

    // Cache for frequently accessed configurations to avoid repeated DB hits
    // For simplicity, this example uses a simple map. Consider a more robust caching solution for production.
    private final Map<String, Object> configCache = new ConcurrentHashMap<>();

    // Example: _LIM_INTERNAS from PHP
    // These would ideally be configurable or dynamically determined
    @Getter
    private final int minInternalExtensionLength = 1;  // Default, PHP logic uses strlen(_LIM_INTERNAS['min'])
    @Getter
    private final int maxInternalExtensionLength = 7; // Default, PHP logic uses strlen(_LIM_INTERNAS['max'])
                                                      // _ACUMTOTAL_MAXEXT seems to be 1,000,000, but length is used.

    // From PHP's _defineParam('CAPTURAS_TIEMPOCERO', $link);
    public int getMinCallDurationForProcessing() {
        // This would be a lookup from a config table or a fixed value
        return 0; // Defaulting to 0 as per PHP's CAPTURAS_TIEMPOCERO if not set
    }

    public int getMaxCallDurationCap() {
        // From PHP's _defineParam('CAPTURAS_TIEMPOMAX', $link);
        return 172800; // 2 days in seconds, default in PHP
    }


    public List<String> getPbxOutputPrefixes(CommunicationLocation commLocation) {
        if (commLocation == null || commLocation.getPbxPrefix() == null || commLocation.getPbxPrefix().isEmpty()) {
            return Collections.emptyList();
        }
        // Cache this per commLocationId if it's frequently accessed
        String cacheKey = "pbxPrefixes_" + commLocation.getId();
        return (List<String>) configCache.computeIfAbsent(cacheKey, k ->
            Arrays.asList(commLocation.getPbxPrefix().split(","))
        );
    }

    // Method to fetch _LIM_INTERNAS dynamically if needed, or specific values
    // The PHP code for ObtenerMaxMin is complex and involves DB queries.
    // For now, we'll use the hardcoded defaults above, but this is where you'd implement
    // a simplified version or a direct query if those values are in a config table.
    public Map<String, Integer> getInternalExtensionLimits(Long originCountryId, Long commLocationId) {
        // Simplified: In PHP, this queries FUNCIONARIO and RANGOEXT tables.
        // For now, returning defaults. A full implementation would query.
        // String sqlFunc = "SELECT max(length(e.extension)) AS MAX_LEN, min(length(e.extension)) AS MIN_LEN " +
        //                  "FROM employee e JOIN communication_location cl ON e.communication_location_id = cl.id " +
        //                  "JOIN indicator i ON cl.indicator_id = i.id " +
        //                  "WHERE e.active = true AND cl.active = true AND i.active = true AND i.origin_country_id = :originCountryId " +
        //                  "AND e.communication_location_id = :commLocationId AND e.extension ~ '^[0-9]+$' " + // numeric
        //                  "AND e.extension NOT LIKE '0%' AND length(e.extension) < 8"; // Example length limit
        // Query queryFunc = entityManager.createNativeQuery(sqlFunc);
        // ... set parameters, execute, process results ...
        return Map.of("min", getMinInternalExtensionLength(), "max", getMaxInternalExtensionLength());
    }

    public List<String> getIgnoredAuthCodes() {
        // From PHP's $_FUN_IGNORAR_CLAVE
        return Arrays.asList("Invalid Authorization Code", "Invalid Authorization Level");
    }

    // Add more methods to fetch other global configurations as needed
}