package com.infomedia.abacox.telephonypricing.cdr;

import com.infomedia.abacox.telephonypricing.entity.*;
import jakarta.persistence.EntityManager;
import jakarta.persistence.NoResultException;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.Query;
import lombok.extern.log4j.Log4j2;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;


import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

@Service
@Log4j2
@Transactional(readOnly = true)
public class LookupService {

    @PersistenceContext
    private EntityManager entityManager;

    // --- Employee Lookups ---
    @Cacheable(value = "employeeLookup", key = "{#extension, #authCode, #commLocationId}")
    public Optional<Employee> findEmployeeByExtensionOrAuthCode(String extension, String authCode, Long commLocationId) {
        log.debug("Looking up employee by extension: '{}', authCode: '{}', commLocationId: {}", extension, authCode, commLocationId);
        StringBuilder sqlBuilder = new StringBuilder("SELECT e.* FROM employee e WHERE e.active = true ");
        Map<String, Object> params = new HashMap<>();

        // Communication location is crucial context
        if (commLocationId != null) {
            sqlBuilder.append(" AND e.communication_location_id = :commLocationId");
            params.put("commLocationId", commLocationId);
        } else {
            log.warn("CommLocationId is null during employee lookup. Results may be incorrect if extensions/codes are not unique across locations.");
            // Depending on requirements, might return empty or proceed without location filter
            // Proceeding without filter for now, matching potential ambiguity in PHP if context wasn't passed.
        }

        // Prioritize authCode if provided (as per PHP logic flow)
        if (authCode != null && !authCode.isEmpty()) {
            sqlBuilder.append(" AND e.auth_code = :authCode");
            params.put("authCode", authCode);
        } else if (extension != null && !extension.isEmpty()){
            sqlBuilder.append(" AND e.extension = :extension");
            params.put("extension", extension);
        } else {
            log.trace("No valid identifier (extension or authCode) provided for employee lookup.");
            return Optional.empty(); // No identifier to search by
        }
        sqlBuilder.append(" LIMIT 1"); // Assume unique combination or take the first match

        Query query = entityManager.createNativeQuery(sqlBuilder.toString(), Employee.class);
        params.forEach(query::setParameter);

        try {
            Employee employee = (Employee) query.getSingleResult();
            log.trace("Found employee: {}", employee.getId());
            return Optional.of(employee);
        } catch (NoResultException e) {
            log.trace("Employee not found for ext: '{}', code: '{}', loc: {}", extension, authCode, commLocationId);
            return Optional.empty();
        } catch (Exception e) {
            log.error("Error finding employee for ext: '{}', code: '{}', loc: {}", extension, authCode, commLocationId, e);
            return Optional.empty();
        }
    }

    @Cacheable(value = "employeeById", key = "#id")
    public Optional<Employee> findEmployeeById(Long id) {
        if (id == null || id <= 0) return Optional.empty();
        String sql = "SELECT e.* FROM employee e WHERE e.id = :id AND e.active = true";
        Query query = entityManager.createNativeQuery(sql, Employee.class);
        query.setParameter("id", id);
        try { return Optional.of((Employee) query.getSingleResult()); }
        catch (NoResultException e) { return Optional.empty(); }
    }

    // --- Prefix & Related Lookups ---

    @Cacheable(value = "prefixLookup", key = "{#number, #originCountryId}")
    public List<Map<String, Object>> findPrefixesByNumber(String number, Long originCountryId) {
        if (originCountryId == null || !StringUtils.hasText(number)) return Collections.emptyList();
        log.debug("Finding prefixes for number starting with: {}, originCountryId: {}", number.substring(0, Math.min(number.length(), 6))+"...", originCountryId); // Log prefix only

        StringBuilder sqlBuilder = new StringBuilder();
        sqlBuilder.append("SELECT ");
        sqlBuilder.append(" p.id as prefix_id, p.code as prefix_code, p.base_value as prefix_base_value, ");
        sqlBuilder.append(" p.vat_included as prefix_vat_included, p.vat_value as prefix_vat_value, p.band_ok as prefix_band_ok, ");
        sqlBuilder.append(" tt.id as telephony_type_id, tt.name as telephony_type_name, tt.uses_trunks as telephony_type_uses_trunks, ");
        sqlBuilder.append(" op.id as operator_id, op.name as operator_name, ");
        // Use COALESCE for min/max to handle cases where no config exists for the specific country
        sqlBuilder.append(" COALESCE(ttc.min_value, 0) as telephony_type_min, ");
        sqlBuilder.append(" COALESCE(ttc.max_value, 0) as telephony_type_max ");
        sqlBuilder.append("FROM prefix p ");
        sqlBuilder.append("JOIN telephony_type tt ON p.telephone_type_id = tt.id ");
        sqlBuilder.append("JOIN operator op ON p.operator_id = op.id ");
        // LEFT JOIN to TelephonyTypeConfig specific to the origin country
        sqlBuilder.append("LEFT JOIN telephony_type_config ttc ON ttc.telephony_type_id = tt.id AND ttc.origin_country_id = :originCountryId ");
        sqlBuilder.append("WHERE p.active = true AND tt.active = true AND op.active = true ");
        // Match numbers starting with the prefix code
        sqlBuilder.append("  AND :number LIKE p.code || '%' ");
        // Ensure the operator belongs to the correct origin country
        sqlBuilder.append("  AND op.origin_country_id = :originCountryId ");
        // Exclude special service prefixes (PHP: TIPOTELE_ID != _TIPOTELE_ESPECIALES)
        sqlBuilder.append("  AND tt.id != :specialCallsType ");
        // Order to prioritize longer prefixes first (more specific match)
        // Then order by min length descending (as per PHP logic, though purpose unclear)
        sqlBuilder.append("ORDER BY LENGTH(p.code) DESC, telephony_type_min DESC, tt.id");


        Query query = entityManager.createNativeQuery(sqlBuilder.toString());
        query.setParameter("number", number);
        query.setParameter("originCountryId", originCountryId);
        query.setParameter("specialCallsType", ConfigurationService.TIPOTELE_ESPECIALES);

        List<Object[]> results = query.getResultList();
        List<Map<String, Object>> mappedResults = new ArrayList<>();
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
            map.put("telephony_type_min", row[11]);
            map.put("telephony_type_max", row[12]);
            mappedResults.add(map);
        }
        log.trace("Found {} prefixes for number lookup", mappedResults.size());
        return mappedResults;
    }

    @Cacheable(value = "prefixByTypeOperatorOrigin", key = "{#telephonyTypeId, #operatorId, #originCountryId}")
    public Optional<Prefix> findPrefixByTypeOperatorOrigin(Long telephonyTypeId, Long operatorId, Long originCountryId) {
        if (telephonyTypeId == null || operatorId == null || originCountryId == null) {
            log.trace("findPrefixByTypeOperatorOrigin - Invalid input: ttId={}, opId={}, ocId={}", telephonyTypeId, operatorId, originCountryId);
            return Optional.empty();
        }
        log.debug("Finding prefix for type {}, operator {}, origin country {}", telephonyTypeId, operatorId, originCountryId);

        StringBuilder sqlBuilder = new StringBuilder();
        sqlBuilder.append("SELECT p.* FROM prefix p ");
        sqlBuilder.append("JOIN operator op ON p.operator_id = op.id ");
        sqlBuilder.append("WHERE p.active = true AND op.active = true ");
        sqlBuilder.append("  AND p.telephone_type_id = :telephonyTypeId ");
        sqlBuilder.append("  AND p.operator_id = :operatorId ");
        // Ensure the operator is associated with the correct origin country
        sqlBuilder.append("  AND op.origin_country_id = :originCountryId ");
        sqlBuilder.append("LIMIT 1"); // Assuming only one active prefix per type/op/country

        Query query = entityManager.createNativeQuery(sqlBuilder.toString(), Prefix.class);
        query.setParameter("telephonyTypeId", telephonyTypeId);
        query.setParameter("operatorId", operatorId);
        query.setParameter("originCountryId", originCountryId);

        try {
            Prefix prefix = (Prefix) query.getSingleResult();
            return Optional.of(prefix);
        } catch (NoResultException e) {
            log.warn("No active prefix found for type {}, operator {}, origin country {}", telephonyTypeId, operatorId, originCountryId);
            return Optional.empty();
        } catch (Exception e) {
            log.error("Error finding prefix for type {}, operator {}, origin country {}: {}", telephonyTypeId, operatorId, originCountryId, e.getMessage(), e);
            return Optional.empty();
        }
    }

    @Cacheable(value = "internalPrefixMatch", key = "{#number, #originCountryId}")
    public Optional<Map<String, Object>> findInternalPrefixMatch(String number, Long originCountryId) {
        if (originCountryId == null || !StringUtils.hasText(number)) return Optional.empty();
        log.debug("Finding internal prefix match for number starting with: {}, originCountryId: {}", number.substring(0, Math.min(number.length(), 6))+"...", originCountryId);

        Set<Long> internalTypes = ConfigurationService.getInternalIpCallTypeIds();
        if (internalTypes.isEmpty()) return Optional.empty();

        StringBuilder sqlBuilder = new StringBuilder();
        sqlBuilder.append("SELECT p.telephone_type_id, p.operator_id ");
        sqlBuilder.append("FROM prefix p ");
        sqlBuilder.append("JOIN operator op ON p.operator_id = op.id ");
        sqlBuilder.append("WHERE p.active = true AND op.active = true ");
        sqlBuilder.append("  AND p.telephone_type_id IN (:internalTypes) ");
        sqlBuilder.append("  AND :number LIKE p.code || '%' "); // Match numbers starting with the internal prefix
        sqlBuilder.append("  AND op.origin_country_id = :originCountryId ");
        sqlBuilder.append("ORDER BY LENGTH(p.code) DESC "); // Prioritize longer prefix matches
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
            log.trace("Found internal prefix match for number {}: Type={}, Op={}", number, result[0], result[1]);
            return Optional.of(map);
        } catch (NoResultException e) {
            log.trace("No internal prefix match found for number {}", number);
            return Optional.empty();
        } catch (Exception e) {
            log.error("Error finding internal prefix match for number {}: {}", number, e.getMessage(), e);
            return Optional.empty();
        }
    }


    @Cacheable(value = "indicatorLookup", key = "{#numberWithoutPrefix, #telephonyTypeId, #originCountryId}")
    public Optional<Map<String, Object>> findIndicatorByNumber(String numberWithoutPrefix, Long telephonyTypeId, Long originCountryId) {
        if (telephonyTypeId == null || originCountryId == null || !StringUtils.hasText(numberWithoutPrefix)) {
            log.trace("findIndicatorByNumber - Invalid input: num={}, ttId={}, ocId={}", numberWithoutPrefix, telephonyTypeId, originCountryId);
            return Optional.empty();
        }
        log.debug("Finding indicator for number: {}, telephonyTypeId: {}, originCountryId: {}", numberWithoutPrefix, telephonyTypeId, originCountryId);

        // Get min/max NDC lengths for this telephony type and country
        Map<String, Integer> ndcLengths = findNdcMinMaxLength(telephonyTypeId, originCountryId);
        int minNdcLength = ndcLengths.getOrDefault("min", 0);
        int maxNdcLength = ndcLengths.getOrDefault("max", 0);

        // Handle LOCAL type specifically if no NDC range is found (PHP logic implicitly does this)
        boolean checkLocalFallback = (maxNdcLength == 0 && telephonyTypeId == ConfigurationService.TIPOTELE_LOCAL);
        if (maxNdcLength == 0 && !checkLocalFallback) {
            log.trace("No NDC length range found for telephony type {}, cannot find indicator.", telephonyTypeId);
            return Optional.empty(); // Cannot proceed without NDC length info (unless it's LOCAL)
        }

        if(checkLocalFallback) {
            minNdcLength = 0; // No NDC prefix for local type lookup
            maxNdcLength = 0;
            log.trace("Treating as LOCAL type lookup (NDC length 0)");
        }

        // Iterate from longest possible NDC length down to shortest
        for (int ndcLength = maxNdcLength; ndcLength >= minNdcLength; ndcLength--) {
            String ndcStr = "";
            String subscriberNumberStr = numberWithoutPrefix; // Assume no NDC initially

            if (ndcLength > 0 && numberWithoutPrefix.length() >= ndcLength) {
                ndcStr = numberWithoutPrefix.substring(0, ndcLength);
                subscriberNumberStr = numberWithoutPrefix.substring(ndcLength);
            } else if (ndcLength > 0) {
                // Number is shorter than the current NDC length we're checking
                continue; // Skip to the next shorter NDC length
            }
            // If ndcLength is 0, ndcStr remains "" and subscriberNumberStr is the full number

            // Ensure parts are numeric (allow empty NDC, require numeric subscriber number)
            if (ndcStr.matches("\\d*") && subscriberNumberStr.matches("\\d+")) {
                Integer ndc = ndcStr.isEmpty() ? null : Integer.parseInt(ndcStr); // Use null for DB query if NDC is empty
                long subscriberNumber = Long.parseLong(subscriberNumberStr);

                StringBuilder sqlBuilder = new StringBuilder();
                sqlBuilder.append("SELECT ");
                sqlBuilder.append(" i.id as indicator_id, i.department_country, i.city_name, i.operator_id, ");
                sqlBuilder.append(" s.ndc as series_ndc, s.initial_number as series_initial, s.final_number as series_final, ");
                sqlBuilder.append(" s.company as series_company "); // Added series_company
                sqlBuilder.append("FROM series s ");
                sqlBuilder.append("JOIN indicator i ON s.indicator_id = i.id ");
                sqlBuilder.append("WHERE i.active = true AND s.active = true ");
                sqlBuilder.append("  AND i.telephony_type_id = :telephonyTypeId ");
                // Handle NDC matching (including null/0 case)
                if (ndc != null) {
                    sqlBuilder.append("  AND s.ndc = :ndc ");
                } else {
                    // Match series where NDC is explicitly 0 or NULL for local/non-NDC types
                    sqlBuilder.append("  AND (s.ndc = 0 OR s.ndc IS NULL) ");
                }
                // Match indicator's origin country (0 means applicable to all origins within the system)
                sqlBuilder.append("  AND i.origin_country_id IN (0, :originCountryId) ");
                // Check if the subscriber number falls within the series range
                sqlBuilder.append("  AND s.initial_number <= :subscriberNum AND s.final_number >= :subscriberNum ");
                // Order results: prioritize specific country, then longer NDC, then narrowest range
                sqlBuilder.append("ORDER BY i.origin_country_id DESC, "); // Specific country first
                sqlBuilder.append("         LENGTH(CAST(s.ndc AS TEXT)) DESC, "); // Longer NDC match first
                sqlBuilder.append("         (s.final_number - s.initial_number) ASC "); // Narrowest matching range first
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
                    map.put("indicator_id", result[0]);
                    map.put("department_country", result[1]);
                    map.put("city_name", result[2]);
                    map.put("operator_id", result[3]);
                    map.put("series_ndc", result[4]);
                    map.put("series_initial", result[5]);
                    map.put("series_final", result[6]);
                    map.put("series_company", result[7]); // Added series_company
                    log.trace("Found indicator {} for number {} (NDC: {})", map.get("indicator_id"), numberWithoutPrefix, ndcStr);
                    return Optional.of(map); // Return the first, most specific match found
                } catch (NoResultException e) {
                    // No match for this NDC length, continue loop to try shorter NDC
                    log.trace("No indicator found for NDC '{}', subscriber '{}'", ndcStr, subscriberNumberStr);
                } catch (Exception e) {
                    log.error("Error finding indicator for number: {}, ndc: {}: {}", numberWithoutPrefix, ndcStr, e.getMessage(), e);
                    // Potentially stop or continue based on error type
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
        // Calculate MIN/MAX length of the NDC column (converted to text)
        sqlBuilder.append("SELECT COALESCE(MIN(LENGTH(CAST(s.ndc AS TEXT))), 0) as min_len, ");
        sqlBuilder.append("       COALESCE(MAX(LENGTH(CAST(s.ndc AS TEXT))), 0) as max_len ");
        sqlBuilder.append("FROM series s ");
        sqlBuilder.append("JOIN indicator i ON s.indicator_id = i.id ");
        sqlBuilder.append("WHERE i.active = true AND s.active = true ");
        sqlBuilder.append("  AND i.telephony_type_id = :telephonyTypeId ");
        // Consider series linked to indicators for the specific origin or the global origin (0)
        sqlBuilder.append("  AND i.origin_country_id IN (0, :originCountryId) ");
        // Only consider series with a positive NDC value for length calculation
        sqlBuilder.append("  AND s.ndc > 0 ");

        Query query = entityManager.createNativeQuery(sqlBuilder.toString());
        query.setParameter("telephonyTypeId", telephonyTypeId);
        query.setParameter("originCountryId", originCountryId);

        try {
            Object[] result = (Object[]) query.getSingleResult();
            // Safely cast to Number and get int value
            lengths.put("min", result[0] != null ? ((Number) result[0]).intValue() : 0);
            lengths.put("max", result[1] != null ? ((Number) result[1]).intValue() : 0);
        } catch (Exception e) {
            log.warn("Could not determine NDC lengths for telephony type {}: {}", telephonyTypeId, e.getMessage());
            // Keep defaults (0, 0) on error
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
            Object result = query.getSingleResult(); // Expecting only the company name
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


    @Cacheable(value = "baseRateLookup", key = "{#prefixId}")
    public Optional<Map<String, Object>> findBaseRateForPrefix(Long prefixId) {
        if (prefixId == null) return Optional.empty();
        log.debug("Finding base rate for prefixId: {}", prefixId);
        // Select the relevant fields for base rate calculation
        String sql = "SELECT base_value, vat_included, vat_value, band_ok " +
                "FROM prefix " +
                "WHERE id = :prefixId AND active = true";
        Query query = entityManager.createNativeQuery(sql);
        query.setParameter("prefixId", prefixId);
        try {
            Object[] result = (Object[]) query.getSingleResult();
            Map<String, Object> map = new HashMap<>();
            // Provide defaults if values are null in the database
            map.put("base_value", result[0] != null ? result[0] : BigDecimal.ZERO);
            map.put("vat_included", result[1] != null ? result[1] : false);
            map.put("vat_value", result[2] != null ? result[2] : BigDecimal.ZERO); // VAT percentage
            map.put("band_ok", result[3] != null ? result[3] : false); // Flag indicating if bands should be checked
            return Optional.of(map);
        } catch (NoResultException e) {
            log.warn("No active prefix found for ID: {}", prefixId);
            return Optional.empty();
        } catch (Exception e) {
            log.error("Error finding base rate for prefixId: {}", prefixId, e);
            return Optional.empty();
        }
    }

    @Cacheable(value = "bandLookup", key = "{#prefixId, #indicatorId, #originIndicatorId}")
    public Optional<Map<String, Object>> findBandByPrefixAndIndicator(Long prefixId, Long indicatorId, Long originIndicatorId) {
        if (prefixId == null || indicatorId == null) {
            log.trace("findBandByPrefixAndIndicator - Invalid input: prefixId={}, indicatorId={}", prefixId, indicatorId);
            return Optional.empty();
        }
        // Use 0 for originIndicatorId if it's null (representing the 'all origins' case)
        Long effectiveOriginIndicatorId = originIndicatorId != null ? originIndicatorId : 0L;
        log.debug("Finding band for prefixId: {}, indicatorId: {}, effectiveOriginIndicatorId: {}", prefixId, indicatorId, effectiveOriginIndicatorId);

        StringBuilder sqlBuilder = new StringBuilder();
        sqlBuilder.append("SELECT b.id as band_id, b.value as band_value, b.vat_included as band_vat_included, b.name as band_name ");
        sqlBuilder.append("FROM band b ");
        // Join with the mapping table
        sqlBuilder.append("JOIN band_indicator bi ON b.id = bi.band_id ");
        sqlBuilder.append("WHERE b.active = true ");
        sqlBuilder.append("  AND b.prefix_id = :prefixId ");
        // Match the specific destination indicator
        sqlBuilder.append("  AND bi.indicator_id = :indicatorId ");
        // Match the origin indicator (0 means applies to all origins)
        sqlBuilder.append("  AND b.origin_indicator_id IN (0, :originIndicatorId) ");
        // Prioritize the specific origin indicator over the general one (0)
        sqlBuilder.append("ORDER BY b.origin_indicator_id DESC ");
        sqlBuilder.append("LIMIT 1"); // Get the most specific matching band

        Query query = entityManager.createNativeQuery(sqlBuilder.toString());
        query.setParameter("prefixId", prefixId);
        query.setParameter("indicatorId", indicatorId);
        query.setParameter("originIndicatorId", effectiveOriginIndicatorId);

        try {
            Object[] result = (Object[]) query.getSingleResult();
            Map<String, Object> map = new HashMap<>();
            map.put("band_id", result[0]);
            map.put("band_value", result[1] != null ? result[1] : BigDecimal.ZERO);
            map.put("band_vat_included", result[2] != null ? result[2] : false);
            map.put("band_name", result[3]);
            log.trace("Found band {} for prefix {}, indicator {}", map.get("band_id"), prefixId, indicatorId);
            return Optional.of(map);
        } catch (NoResultException e) {
            log.trace("No matching band found for prefix {}, indicator {}", prefixId, indicatorId);
            return Optional.empty();
        } catch (Exception e) {
            log.error("Error finding band for prefixId: {}, indicatorId: {}, originIndicatorId: {}", prefixId, indicatorId, effectiveOriginIndicatorId, e);
            return Optional.empty();
        }
    }


    // --- Trunk Lookups ---

    @Cacheable(value = "trunkLookup", key = "#trunkCode + '-' + #commLocationId")
    public Optional<Trunk> findTrunkByCode(String trunkCode, Long commLocationId) {
        // Ensure trunkCode is not blank and commLocationId is provided
        if (!StringUtils.hasText(trunkCode) || commLocationId == null) {
            log.trace("findTrunkByCode - Invalid input: trunkCode={}, commLocationId={}", trunkCode, commLocationId);
            return Optional.empty();
        }
        log.debug("Finding trunk by code: '{}', commLocationId: {}", trunkCode, commLocationId);
        // Query active trunks matching the name and specific communication location
        String sql = "SELECT t.* FROM trunk t " +
                "WHERE t.active = true " +
                "  AND t.name = :trunkCode " +
                "  AND t.comm_location_id = :commLocationId " +
                "LIMIT 1"; // Expecting at most one active trunk with the same name per location
        Query query = entityManager.createNativeQuery(sql, Trunk.class);
        query.setParameter("trunkCode", trunkCode);
        query.setParameter("commLocationId", commLocationId);
        try {
            Trunk trunk = (Trunk) query.getSingleResult();
            return Optional.of(trunk);
        } catch (NoResultException e) {
            log.trace("No active trunk found for code '{}' at location {}", trunkCode, commLocationId);
            return Optional.empty();
        } catch (Exception e) {
            log.error("Error finding trunk for code: '{}', loc: {}", trunkCode, commLocationId, e);
            return Optional.empty();
        }
    }

    @Cacheable(value = "trunkRateLookup", key = "{#trunkId, #operatorId, #telephonyTypeId}")
    public Optional<TrunkRate> findTrunkRate(Long trunkId, Long operatorId, Long telephonyTypeId) {
        if (trunkId == null || operatorId == null || telephonyTypeId == null) {
            log.trace("findTrunkRate - Invalid input: trunkId={}, opId={}, ttId={}", trunkId, operatorId, telephonyTypeId);
            return Optional.empty();
        }
        log.debug("Finding trunk rate for trunkId: {}, operatorId: {}, telephonyTypeId: {}", trunkId, operatorId, telephonyTypeId);
        // Find the specific rate defined for this combination
        String sql = "SELECT tr.* FROM trunk_rate tr " +
                "WHERE tr.trunk_id = :trunkId " +
                "  AND tr.operator_id = :operatorId " +
                "  AND tr.telephony_type_id = :telephonyTypeId " +
                "LIMIT 1"; // Assuming only one rate per combination
        Query query = entityManager.createNativeQuery(sql, TrunkRate.class);
        query.setParameter("trunkId", trunkId);
        query.setParameter("operatorId", operatorId);
        query.setParameter("telephonyTypeId", telephonyTypeId);
        try {
            TrunkRate rate = (TrunkRate) query.getSingleResult();
            return Optional.of(rate);
        } catch (NoResultException e) {
            log.trace("No TrunkRate found for trunkId: {}, opId: {}, ttId: {}", trunkId, operatorId, telephonyTypeId);
            return Optional.empty();
        } catch (Exception e) {
            log.error("Error finding trunk rate for trunkId: {}, opId: {}, ttId: {}", trunkId, operatorId, telephonyTypeId, e);
            return Optional.empty();
        }
    }


    @Cacheable(value = "trunkRuleLookup", key = "{#trunkCode, #telephonyTypeId, #indicatorId, #originIndicatorId}")
    public Optional<TrunkRule> findTrunkRule(String trunkCode, Long telephonyTypeId, Long indicatorId, Long originIndicatorId) {
        if (telephonyTypeId == null || indicatorId == null) {
            log.trace("findTrunkRule - Invalid input: ttId={}, indId={}", telephonyTypeId, indicatorId);
            return Optional.empty();
        }
        // Use 0 for originIndicatorId if null
        Long effectiveOriginIndicatorId = originIndicatorId != null ? originIndicatorId : 0L;
        log.debug("Finding trunk rule for trunkCode: '{}', ttId: {}, indId: {}, effectiveOriginIndId: {}", trunkCode, telephonyTypeId, indicatorId, effectiveOriginIndicatorId);
        String indicatorIdStr = String.valueOf(indicatorId);

        StringBuilder sqlBuilder = new StringBuilder();
        sqlBuilder.append("SELECT tr.* ");
        sqlBuilder.append("FROM trunk_rule tr ");
        // LEFT JOIN to potentially match specific trunk by name
        sqlBuilder.append("LEFT JOIN trunk t ON tr.trunk_id = t.id AND t.active = true ");
        sqlBuilder.append("WHERE tr.active = true ");
        // Match rules for specific trunk OR global rules (trunk_id = 0)
        sqlBuilder.append("  AND (tr.trunk_id = 0 OR (t.name = :trunkCode)) ");
        sqlBuilder.append("  AND tr.telephony_type_id = :telephonyTypeId ");
        // Match specific origin or global origin (0)
        sqlBuilder.append("  AND tr.origin_indicator_id IN (0, :originIndicatorId) ");
        // Match indicator_ids: exact match, starts with, ends with, contains, or empty/null
        sqlBuilder.append("  AND (tr.indicator_ids = '' OR tr.indicator_ids IS NULL OR tr.indicator_ids = :indicatorIdStr OR tr.indicator_ids LIKE :indicatorIdStrLikeStart OR tr.indicator_ids LIKE :indicatorIdStrLikeEnd OR tr.indicator_ids LIKE :indicatorIdStrLikeMiddle) ");
        // Order to prioritize: specific trunk over global, specific origin over global, more specific indicator_ids match over less specific or empty
        sqlBuilder.append("ORDER BY tr.trunk_id DESC NULLS LAST, "); // Specific trunk first
        sqlBuilder.append("         tr.origin_indicator_id DESC NULLS LAST, "); // Specific origin first
        sqlBuilder.append("         CASE WHEN tr.indicator_ids = :indicatorIdStr THEN 0 "); // Exact match highest priority
        sqlBuilder.append("              WHEN tr.indicator_ids LIKE :indicatorIdStrLikeStart THEN 1 ");
        sqlBuilder.append("              WHEN tr.indicator_ids LIKE :indicatorIdStrLikeMiddle THEN 2 ");
        sqlBuilder.append("              WHEN tr.indicator_ids LIKE :indicatorIdStrLikeEnd THEN 3 ");
        sqlBuilder.append("              WHEN tr.indicator_ids = '' OR tr.indicator_ids IS NULL THEN 5 "); // Empty/NULL lowest priority
        sqlBuilder.append("              ELSE 4 "); // Other LIKE matches
        sqlBuilder.append("         END, ");
        sqlBuilder.append("         LENGTH(tr.indicator_ids) DESC "); // Longer (more specific) indicator_ids list first
        sqlBuilder.append("LIMIT 1"); // Get the single most specific rule

        Query query = entityManager.createNativeQuery(sqlBuilder.toString(), TrunkRule.class);
        query.setParameter("trunkCode", trunkCode != null ? trunkCode : ""); // Use empty string if trunkCode is null
        query.setParameter("telephonyTypeId", telephonyTypeId);
        query.setParameter("originIndicatorId", effectiveOriginIndicatorId);
        query.setParameter("indicatorIdStr", indicatorIdStr);
        query.setParameter("indicatorIdStrLikeStart", indicatorIdStr + ",%");
        query.setParameter("indicatorIdStrLikeEnd", "%," + indicatorIdStr);
        query.setParameter("indicatorIdStrLikeMiddle", "%," + indicatorIdStr + ",%");

        try {
            TrunkRule rule = (TrunkRule) query.getSingleResult();
            return Optional.of(rule);
        } catch (NoResultException e) {
            log.trace("No matching TrunkRule found for trunk: '{}', ttId: {}, indId: {}, originIndId: {}", trunkCode, telephonyTypeId, indicatorId, effectiveOriginIndicatorId);
            return Optional.empty();
        } catch (Exception e) {
            log.error("Error finding trunk rule for trunk: '{}', ttId: {}, indId: {}, originIndId: {}", trunkCode, telephonyTypeId, indicatorId, effectiveOriginIndicatorId, e);
            return Optional.empty();
        }
    }

    // --- Special Rules & Rates ---

    @Cacheable(value = "pbxSpecialRuleLookup", key = "{#dialedNumber, #commLocationId, #direction}")
    public Optional<PbxSpecialRule> findPbxSpecialRule(String dialedNumber, Long commLocationId, int direction) {
        if (!StringUtils.hasText(dialedNumber) || commLocationId == null) {
            log.trace("findPbxSpecialRule - Invalid input: dialedNumber={}, commLocationId={}", dialedNumber, commLocationId);
            return Optional.empty();
        }
        log.debug("Finding PBX special rule for number: {}, commLocationId: {}, direction: {}", dialedNumber, commLocationId, direction);

        // Get candidate rules ordered by specificity
        List<PbxSpecialRule> candidates = findPbxSpecialRuleCandidates(commLocationId, direction);

        for (PbxSpecialRule rule : candidates) {
            boolean match = false;
            String searchPattern = rule.getSearchPattern();

            // 1. Check Search Pattern
            if (StringUtils.hasText(searchPattern) && dialedNumber.startsWith(searchPattern)) {
                match = true; // Initial match based on search pattern

                // 2. Check Ignore Patterns
                String ignorePatternString = rule.getIgnorePattern();
                if (match && StringUtils.hasText(ignorePatternString)) {
                    String[] ignorePatterns = ignorePatternString.split(",");
                    for (String ignore : ignorePatterns) {
                        String trimmedIgnore = ignore.trim();
                        // If the number starts with any ignore pattern, it's not a match
                        if (!trimmedIgnore.isEmpty() && dialedNumber.startsWith(trimmedIgnore)) {
                            match = false;
                            log.trace("Rule {} ignored for number {} due to ignore pattern '{}'", rule.getId(), dialedNumber, trimmedIgnore);
                            break; // Stop checking ignore patterns for this rule
                        }
                    }
                }

                // 3. Check Minimum Length
                if (match && rule.getMinLength() != null && dialedNumber.length() < rule.getMinLength()) {
                    match = false;
                    log.trace("Rule {} ignored for number {} due to minLength ({} < {})", rule.getId(), dialedNumber, dialedNumber.length(), rule.getMinLength());
                }
            } // End check for current rule

            if (match) {
                log.trace("Found matching PBX special rule {} for number {}", rule.getId(), dialedNumber);
                return Optional.of(rule); // Return the first (most specific) rule that matches
            }
        } // End loop through candidates

        log.trace("No matching PBX special rule found for number {}", dialedNumber);
        return Optional.empty(); // No rule matched
    }

    @Cacheable(value = "pbxSpecialRuleCandidates", key = "{#commLocationId, #direction}")
    public List<PbxSpecialRule> findPbxSpecialRuleCandidates(Long commLocationId, int direction) {
        if (commLocationId == null) return Collections.emptyList();
        log.debug("Finding PBX special rule candidates for commLocationId: {}, direction: {}", commLocationId, direction);
        // Query active rules matching the location (or global) and direction (0=both, 1=in, 2=out)
        // Order by specific location first, then by the length of the search pattern descending (more specific patterns first)
        String sql = "SELECT p.* FROM pbx_special_rule p " +
                "WHERE p.active = true " +
                // Match specific location OR global rules (comm_location_id IS NULL)
                "  AND (p.comm_location_id = :commLocationId OR p.comm_location_id IS NULL) " +
                // Match direction (0=both, or specific direction)
                "  AND p.direction IN (0, :direction) " +
                // Prioritize rules specific to the location, then longer search patterns
                "ORDER BY p.comm_location_id DESC NULLS LAST, LENGTH(p.search_pattern) DESC";
        Query query = entityManager.createNativeQuery(sql, PbxSpecialRule.class);
        query.setParameter("commLocationId", commLocationId);
        query.setParameter("direction", direction);
        try {
            return query.getResultList();
        } catch (Exception e) {
            log.error("Error finding PBX special rule candidates for commLocationId: {}, direction: {}", commLocationId, direction, e);
            return Collections.emptyList();
        }
    }

    @Cacheable(value = "specialRateValueLookup", key = "{#telephonyTypeId, #operatorId, #bandId, #originIndicatorId, #callDateTime}")
    public List<SpecialRateValue> findSpecialRateValues(Long telephonyTypeId, Long operatorId, Long bandId, Long originIndicatorId, LocalDateTime callDateTime) {
        // Allow nulls for broader matching, but require dateTime
        if (callDateTime == null) return Collections.emptyList();
        // Use 0 for originIndicatorId if null
        Long effectiveOriginIndicatorId = originIndicatorId != null ? originIndicatorId : 0L;
        log.debug("Finding special rate values for ttId: {}, opId: {}, bandId: {}, effectiveOriginIndId: {}, dateTime: {}",
                telephonyTypeId, operatorId, bandId, effectiveOriginIndicatorId, callDateTime);

        int dayOfWeek = callDateTime.getDayOfWeek().getValue(); // 1 (Monday) to 7 (Sunday)
        boolean isHoliday = false; // Placeholder - Implement holiday lookup if needed
        // Example: isHoliday = holidayLookupService.isHoliday(callDateTime.toLocalDate(), originCountryId);

        // Map Java DayOfWeek value to entity column names
        String dayColumn;
        switch (dayOfWeek) {
            case 1: dayColumn = "monday_enabled"; break;
            case 2: dayColumn = "tuesday_enabled"; break;
            case 3: dayColumn = "wednesday_enabled"; break;
            case 4: dayColumn = "thursday_enabled"; break;
            case 5: dayColumn = "friday_enabled"; break;
            case 6: dayColumn = "saturday_enabled"; break;
            case 7: dayColumn = "sunday_enabled"; break;
            default:
                log.error("Invalid day of week: {}", dayOfWeek);
                return Collections.emptyList(); // Should not happen
        }

        StringBuilder sqlBuilder = new StringBuilder();
        sqlBuilder.append("SELECT srv.* ");
        sqlBuilder.append("FROM special_rate_value srv ");
        sqlBuilder.append("WHERE srv.active = true ");
        // Match specific type or global (NULL)
        sqlBuilder.append("  AND (srv.telephony_type_id = :telephonyTypeId OR srv.telephony_type_id IS NULL) ");
        // Match specific operator or global (NULL)
        sqlBuilder.append("  AND (srv.operator_id = :operatorId OR srv.operator_id IS NULL) ");
        // Match specific band or global (NULL) - pass bandId even if null
        sqlBuilder.append("  AND (srv.band_id = :bandId OR srv.band_id IS NULL) ");
        // Match specific origin indicator or global (0 or NULL)
        sqlBuilder.append("  AND (srv.origin_indicator_id = :originIndicatorId OR srv.origin_indicator_id IS NULL OR srv.origin_indicator_id = 0) ");
        // Check validity date range
        sqlBuilder.append("  AND (srv.valid_from IS NULL OR srv.valid_from <= :callDateTime) ");
        sqlBuilder.append("  AND (srv.valid_to IS NULL OR srv.valid_to >= :callDateTime) ");
        // Check day of week or holiday flag
        sqlBuilder.append("  AND (srv.").append(dayColumn).append(" = true ");
        if (isHoliday) {
            sqlBuilder.append("OR srv.holiday_enabled = true");
        }
        sqlBuilder.append(") ");
        // Order by specificity: origin, type, operator, band
        sqlBuilder.append("ORDER BY srv.origin_indicator_id DESC NULLS LAST, ");
        sqlBuilder.append("         srv.telephony_type_id DESC NULLS LAST, ");
        sqlBuilder.append("         srv.operator_id DESC NULLS LAST, ");
        sqlBuilder.append("         srv.band_id DESC NULLS LAST");

        Query query = entityManager.createNativeQuery(sqlBuilder.toString(), SpecialRateValue.class);
        // Bind parameters, allowing nulls for broader matching
        query.setParameter("telephonyTypeId", telephonyTypeId);
        query.setParameter("operatorId", operatorId);
        query.setParameter("bandId", bandId); // Pass null if bandId is null
        query.setParameter("originIndicatorId", effectiveOriginIndicatorId);
        query.setParameter("callDateTime", callDateTime);

        try {
            List<SpecialRateValue> results = query.getResultList();
            log.trace("Found {} special rate candidates", results.size());
            return results;
        } catch (Exception e) {
            log.error("Error finding special rate values for ttId: {}, opId: {}, bandId: {}, originIndId: {}, dateTime: {}",
                    telephonyTypeId, operatorId, bandId, effectiveOriginIndicatorId, callDateTime, e);
            return Collections.emptyList();
        }
    }

    @Cacheable(value = "specialServiceLookup", key = "{#phoneNumber, #indicatorId, #originCountryId}")
    public Optional<SpecialService> findSpecialService(String phoneNumber, Long indicatorId, Long originCountryId) {
        if (!StringUtils.hasText(phoneNumber) || originCountryId == null) {
            log.trace("findSpecialService - Invalid input: phone={}, indId={}, ocId={}", phoneNumber, indicatorId, originCountryId);
            return Optional.empty();
        }
        // Use 0 for indicatorId if null (global match)
        Long effectiveIndicatorId = indicatorId != null ? indicatorId : 0L;
        log.debug("Finding special service for number: {}, effectiveIndicatorId: {}, originCountryId: {}", phoneNumber, effectiveIndicatorId, originCountryId);

        String sql = "SELECT ss.* FROM special_service ss " +
                "WHERE ss.active = true " +
                "  AND ss.phone_number = :phoneNumber " +
                // Match specific indicator OR global (0)
                "  AND ss.indicator_id IN (0, :indicatorId) " +
                "  AND ss.origin_country_id = :originCountryId " +
                // Prioritize the specific indicator match over the global one
                "ORDER BY ss.indicator_id DESC " +
                "LIMIT 1"; // Get the most specific match
        Query query = entityManager.createNativeQuery(sql, SpecialService.class);
        query.setParameter("phoneNumber", phoneNumber);
        query.setParameter("indicatorId", effectiveIndicatorId);
        query.setParameter("originCountryId", originCountryId);
        try {
            SpecialService service = (SpecialService) query.getSingleResult();
            return Optional.of(service);
        } catch (NoResultException e) {
            log.trace("No special service found for number: {}, indId: {}, originId: {}", phoneNumber, effectiveIndicatorId, originCountryId);
            return Optional.empty();
        } catch (Exception e) {
            log.error("Error finding special service for number: {}, indId: {}, originId: {}", phoneNumber, effectiveIndicatorId, originCountryId, e);
            return Optional.empty();
        }
    }

    // --- Other Lookups ---

    @Cacheable(value = "commLocationPrefix", key = "#commLocationId")
    public Optional<String> findPbxPrefixByCommLocationId(Long commLocationId) {
        if (commLocationId == null) return Optional.empty();
        log.debug("Finding PBX prefix for commLocationId: {}", commLocationId);
        // Select only the pbx_prefix column
        String sql = "SELECT pbx_prefix FROM communication_location WHERE id = :id";
        // Specify String.class as the result type
        Query query = entityManager.createNativeQuery(sql, String.class);
        query.setParameter("id", commLocationId);
        try {
            // Result will be a single String or null
            return Optional.ofNullable((String) query.getSingleResult());
        } catch (NoResultException e) {
            log.trace("No CommunicationLocation found for ID: {}", commLocationId);
            return Optional.empty();
        } catch (Exception e) {
            log.error("Error finding PBX prefix for commLocationId: {}", commLocationId, e);
            return Optional.empty();
        }
    }

    @Cacheable(value = "telephonyTypeConfig", key = "{#telephonyTypeId, #originCountryId}")
    public Map<String, Integer> findTelephonyTypeMinMaxConfig(Long telephonyTypeId, Long originCountryId) {
        Map<String, Integer> config = new HashMap<>();
        config.put("min", 0); config.put("max", 0); // Defaults
        if (telephonyTypeId == null || originCountryId == null) return config;

        log.debug("Finding min/max config for telephonyTypeId: {}, originCountryId: {}", telephonyTypeId, originCountryId);
        String sql = "SELECT min_value, max_value FROM telephony_type_config " +
                "WHERE telephony_type_id = :telephonyTypeId AND origin_country_id = :originCountryId " +
                "LIMIT 1"; // Assuming one config per type/country
        Query query = entityManager.createNativeQuery(sql);
        query.setParameter("telephonyTypeId", telephonyTypeId);
        query.setParameter("originCountryId", originCountryId);
        try {
            Object[] result = (Object[]) query.getSingleResult();
            // Use defaults if DB values are null
            config.put("min", result[0] != null ? ((Number) result[0]).intValue() : 0);
            config.put("max", result[1] != null ? ((Number) result[1]).intValue() : 0);
        } catch (NoResultException e) {
            log.trace("No TelephonyTypeConfig found for type {}, country {}. Using defaults.", telephonyTypeId, originCountryId);
            // Keep defaults (0, 0)
        } catch (Exception e) {
            log.error("Error finding min/max config for telephonyTypeId: {}, originCountryId: {}", telephonyTypeId, originCountryId, e);
            // Keep defaults on error
        }
        return config;
    }

    @Cacheable(value = "operatorByTelephonyType", key = "{#telephonyTypeId, #originCountryId}")
    public Optional<Operator> findOperatorByTelephonyTypeAndOrigin(Long telephonyTypeId, Long originCountryId) {
        if (telephonyTypeId == null || originCountryId == null) return Optional.empty();
        log.debug("Finding operator for telephonyTypeId: {}, originCountryId: {}", telephonyTypeId, originCountryId);
        // Find an active operator linked via an active prefix to the given type and origin country
        String sql = "SELECT op.* FROM operator op " +
                "JOIN prefix p ON p.operator_id = op.id " +
                "WHERE p.telephone_type_id = :telephonyTypeId " +
                "  AND op.origin_country_id = :originCountryId " +
                "  AND op.active = true " +
                "  AND p.active = true " +
                "LIMIT 1"; // Return the first matching operator
        Query query = entityManager.createNativeQuery(sql, Operator.class);
        query.setParameter("telephonyTypeId", telephonyTypeId);
        query.setParameter("originCountryId", originCountryId);
        try {
            Operator operator = (Operator) query.getSingleResult();
            return Optional.of(operator);
        } catch (NoResultException e) {
            log.trace("No active operator found linked to telephony type {} for origin {}", telephonyTypeId, originCountryId);
            return Optional.empty();
        } catch (Exception e) {
            log.error("Error finding operator for telephonyTypeId: {}, originCountryId: {}", telephonyTypeId, originCountryId, e);
            return Optional.empty();
        }
    }

    @Cacheable(value = "internalTariff", key = "#telephonyTypeId")
    public Optional<Map<String, Object>> findInternalTariff(Long telephonyTypeId) {
        if (telephonyTypeId == null) return Optional.empty();
        log.debug("Finding internal tariff for telephonyTypeId: {}", telephonyTypeId);
        // Find the prefix associated with the internal telephony type
        // Assumes there's one relevant prefix entry for internal types (might need refinement based on operator/country if applicable)
        String sql = "SELECT p.base_value, p.vat_included, p.vat_value, tt.name as telephony_type_name " +
                "FROM prefix p " +
                "JOIN telephony_type tt ON p.telephone_type_id = tt.id " +
                "WHERE p.telephone_type_id = :telephonyTypeId " +
                "  AND p.active = true AND tt.active = true " +
                "LIMIT 1"; // Assuming one primary prefix defines the internal rate
        Query query = entityManager.createNativeQuery(sql);
        query.setParameter("telephonyTypeId", telephonyTypeId);
        try {
            Object[] result = (Object[]) query.getSingleResult();
            Map<String, Object> map = new HashMap<>();
            // Extract rate info
            map.put("valor_minuto", result[0] != null ? result[0] : BigDecimal.ZERO);
            map.put("valor_minuto_iva", result[1] != null ? result[1] : false);
            map.put("iva", result[2] != null ? result[2] : BigDecimal.ZERO);
            map.put("tipotele_nombre", result[3]);
            // Add defaults for fields not present in prefix but needed for pricing consistency
            map.put("ensegundos", false); // Internal usually per minute
            map.put("valor_inicial", BigDecimal.ZERO); // Internal usually no initial charge
            map.put("valor_inicial_iva", false);
            return Optional.of(map);
        } catch (NoResultException e) {
            log.warn("No active prefix found defining internal tariff for telephonyTypeId: {}", telephonyTypeId);
            return Optional.empty();
        } catch (Exception e) {
            log.error("Error finding internal tariff for telephonyTypeId: {}", telephonyTypeId, e);
            return Optional.empty();
        }
    }

    @Cacheable(value = "extensionMinMaxLength", key = "{#commLocationId}")
    public Map<String, Integer> findExtensionMinMaxLength(Long commLocationId) {
        log.debug("Finding min/max extension length for commLocationId: {}", commLocationId);
        Map<String, Integer> lengths = new HashMap<>();
        // Initialize with defaults that will be overridden by smaller min or larger max
        lengths.put("min", Integer.MAX_VALUE);
        lengths.put("max", 0);

        int maxPossibleLength = String.valueOf(ConfigurationService.MAX_POSSIBLE_EXTENSION_VALUE).length();

        // Query 1: Based on Employee extensions
        StringBuilder sqlEmployee = new StringBuilder();
        sqlEmployee.append("SELECT COALESCE(MIN(LENGTH(e.extension)), NULL) AS min_len, COALESCE(MAX(LENGTH(e.extension)), NULL) AS max_len ");
        sqlEmployee.append("FROM employee e ");
        sqlEmployee.append("WHERE e.active = true ");
        // Match extensions containing only digits, #, or * (as per PHP is_numeric check adapted)
        sqlEmployee.append("  AND e.extension ~ '^[0-9#*]+$' ");
        // Exclude extensions starting with 0 (PHP logic)
        sqlEmployee.append("  AND e.extension NOT LIKE '0%' ");
        // Exclude extensions longer than the absolute max possible value's length
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
        sqlRange.append("SELECT COALESCE(MIN(LENGTH(er.range_start)), NULL) AS min_len, COALESCE(MAX(LENGTH(er.range_end)), NULL) AS max_len ");
        sqlRange.append("FROM extension_range er ");
        sqlRange.append("WHERE er.active = true ");
        // Ensure ranges are purely numeric for length calculation
        sqlRange.append("  AND er.range_start ~ '^[0-9]+$' AND er.range_end ~ '^[0-9]+$' ");
        // Exclude ranges exceeding the max possible length
        sqlRange.append("  AND LENGTH(er.range_start) < :maxExtPossibleLength ");
        sqlRange.append("  AND LENGTH(er.range_end) < :maxExtPossibleLength ");
        // Ensure range is valid
        sqlRange.append("  AND er.range_end >= er.range_start ");
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

        // Final adjustments: If no lengths found, use defaults. Ensure min <= max.
        if (lengths.get("min") == Integer.MAX_VALUE) lengths.put("min", ConfigurationService.DEFAULT_MIN_EXT_LENGTH);
        if (lengths.get("max") == 0) lengths.put("max", ConfigurationService.DEFAULT_MAX_EXT_LENGTH);
        if (lengths.get("min") > lengths.get("max")) {
            log.warn("Calculated min length ({}) > max length ({}), adjusting min to max.", lengths.get("min"), lengths.get("max"));
            lengths.put("min", lengths.get("max"));
        }

        log.debug("Final determined extension lengths: min={}, max={}", lengths.get("min"), lengths.get("max"));
        return lengths;
    }

    @Cacheable(value = "localNdc", key = "#indicatorId")
    public Optional<Integer> findLocalNdcForIndicator(Long indicatorId) {
        if (indicatorId == null || indicatorId <= 0) return Optional.empty();
        log.debug("Finding local NDC for indicatorId: {}", indicatorId);
        // Find the most frequent NDC associated with the given indicator ID
        String sql = "SELECT s.ndc FROM series s " +
                "WHERE s.indicator_id = :indicatorId " +
                "  AND s.ndc IS NOT NULL AND s.ndc > 0 " + // Only consider valid NDCs
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

    @Cacheable(value = "isLocalExtended", key = "{#destinationNdc, #originIndicatorId}")
    public boolean isLocalExtended(Integer destinationNdc, Long originIndicatorId) {
        if (destinationNdc == null || originIndicatorId == null || originIndicatorId <= 0) {
            return false; // Cannot determine without valid inputs
        }
        log.debug("Checking if NDC {} is local extended for origin indicator {}", destinationNdc, originIndicatorId);
        // Check if the destination NDC exists in the list of NDCs associated with the origin indicator
        String sql = "SELECT COUNT(s.id) FROM series s WHERE s.indicator_id = :originIndicatorId AND s.ndc = :destinationNdc";
        Query query = entityManager.createNativeQuery(sql, Long.class); // Use Long for count
        query.setParameter("originIndicatorId", originIndicatorId);
        query.setParameter("destinationNdc", destinationNdc);
        try {
            Long count = (Long) query.getSingleResult();
            boolean isExtended = count != null && count > 0;
            log.trace("Is NDC {} local extended for origin indicator {}: {}", destinationNdc, originIndicatorId, isExtended);
            return isExtended;
        } catch (Exception e) {
            log.error("Error checking local extended status for NDC {}, origin indicator {}: {}", destinationNdc, originIndicatorId, e);
            return false; // Assume not extended on error
        }
    }


    // --- Simple Find By ID methods ---
    // These assume the ID is valid and the entity should exist. Added null checks.

    @Cacheable("communicationLocationById")
    public Optional<CommunicationLocation> findCommunicationLocationById(Long id) {
        if (id == null) return Optional.empty();
        String sql = "SELECT cl.* FROM communication_location cl WHERE cl.id = :id AND cl.active = true"; // Added active check
        Query query = entityManager.createNativeQuery(sql, CommunicationLocation.class);
        query.setParameter("id", id);
        try { return Optional.of((CommunicationLocation) query.getSingleResult()); }
        catch (NoResultException e) { return Optional.empty(); }
    }

    @Cacheable("indicatorById")
    public Optional<Indicator> findIndicatorById(Long id) {
        if (id == null || id <= 0) return Optional.empty();
        String sql = "SELECT i.* FROM indicator i WHERE i.id = :id AND i.active = true"; // Added active check
        Query query = entityManager.createNativeQuery(sql, Indicator.class);
        query.setParameter("id", id);
        try { return Optional.of((Indicator) query.getSingleResult()); }
        catch (NoResultException e) { return Optional.empty(); }
    }

    @Cacheable("operatorByIdLookup")
    public Optional<Operator> findOperatorById(Long id) {
        if (id == null || id <= 0) return Optional.empty();
        String sql = "SELECT o.* FROM operator o WHERE o.id = :id AND o.active = true"; // Added active check
        Query query = entityManager.createNativeQuery(sql, Operator.class);
        query.setParameter("id", id);
        try { return Optional.of((Operator) query.getSingleResult()); }
        catch (NoResultException e) { return Optional.empty(); }
    }

    @Cacheable("telephonyTypeByIdLookup")
    public Optional<TelephonyType> findTelephonyTypeById(Long id) {
        if (id == null || id <= 0) return Optional.empty();
        String sql = "SELECT tt.* FROM telephony_type tt WHERE tt.id = :id AND tt.active = true"; // Added active check
        Query query = entityManager.createNativeQuery(sql, TelephonyType.class);
        query.setParameter("id", id);
        try { return Optional.of((TelephonyType) query.getSingleResult()); }
        catch (NoResultException e) { return Optional.empty(); }
    }

    @Cacheable("originCountryById")
    public Optional<OriginCountry> findOriginCountryById(Long id) {
        if (id == null || id <= 0) return Optional.empty();
        String sql = "SELECT oc.* FROM origin_country oc WHERE oc.id = :id AND oc.active = true"; // Added active check
        Query query = entityManager.createNativeQuery(sql, OriginCountry.class);
        query.setParameter("id", id);
        try { return Optional.of((OriginCountry) query.getSingleResult()); }
        catch (NoResultException e) { return Optional.empty(); }
    }

    @Cacheable("subdivisionById")
    public Optional<Subdivision> findSubdivisionById(Long id) {
        if (id == null || id <= 0) return Optional.empty();
        String sql = "SELECT s.* FROM subdivision s WHERE s.id = :id AND s.active = true"; // Added active check
        Query query = entityManager.createNativeQuery(sql, Subdivision.class);
        query.setParameter("id", id);
        try { return Optional.of((Subdivision) query.getSingleResult()); }
        catch (NoResultException e) { return Optional.empty(); }
    }

    @Cacheable("costCenterById")
    public Optional<CostCenter> findCostCenterById(Long id) {
        if (id == null || id <= 0) return Optional.empty();
        String sql = "SELECT cc.* FROM cost_center cc WHERE cc.id = :id AND cc.active = true"; // Added active check
        Query query = entityManager.createNativeQuery(sql, CostCenter.class);
        query.setParameter("id", id);
        try { return Optional.of((CostCenter) query.getSingleResult()); }
        catch (NoResultException e) { return Optional.empty(); }
    }
}