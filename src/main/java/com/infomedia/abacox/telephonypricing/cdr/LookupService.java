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

        if (commLocationId != null) {
            sqlBuilder.append(" AND e.communication_location_id = :commLocationId");
            params.put("commLocationId", commLocationId);
        } else {
            log.warn("CommLocationId is null during employee lookup. Results may be incorrect if extensions/codes are not unique across locations.");
        }

        if (authCode != null && !authCode.isEmpty()) {
            sqlBuilder.append(" AND e.auth_code = :authCode");
            params.put("authCode", authCode);
        } else if (extension != null && !extension.isEmpty()){
            sqlBuilder.append(" AND e.extension = :extension");
            params.put("extension", extension);
        } else {
            log.trace("No valid identifier (extension or authCode) provided for employee lookup.");
            return Optional.empty();
        }
        sqlBuilder.append(" LIMIT 1");

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
        log.debug("Finding prefixes for number starting with: {}, originCountryId: {}", number.substring(0, Math.min(number.length(), 6))+"...", originCountryId);

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
        sqlBuilder.append("LEFT JOIN telephony_type_config ttc ON ttc.telephony_type_id = tt.id AND ttc.origin_country_id = :originCountryId ");
        sqlBuilder.append("WHERE p.active = true AND tt.active = true AND op.active = true ");
        sqlBuilder.append("  AND :number LIKE p.code || '%' ");
        sqlBuilder.append("  AND op.origin_country_id = :originCountryId ");
        sqlBuilder.append("  AND tt.id != :specialCallsType ");
        sqlBuilder.append("ORDER BY LENGTH(p.code) DESC, telephony_type_min DESC, tt.id");

        Query query = entityManager.createNativeQuery(sqlBuilder.toString());
        query.setParameter("number", number);
        query.setParameter("originCountryId", originCountryId);
        query.setParameter("specialCallsType", CdrProcessingConfig.TIPOTELE_ESPECIALES);

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

                    // *** SOLUTION 2: Return null if ID is 0 ***
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


    @Cacheable(value = "baseRateLookup", key = "{#prefixId}")
    public Optional<Map<String, Object>> findBaseRateForPrefix(Long prefixId) {
        if (prefixId == null) return Optional.empty();
        log.debug("Finding base rate for prefixId: {}", prefixId);
        String sql = "SELECT base_value, vat_included, vat_value, band_ok " +
                "FROM prefix " +
                "WHERE id = :prefixId AND active = true";
        Query query = entityManager.createNativeQuery(sql);
        query.setParameter("prefixId", prefixId);
        try {
            Object[] result = (Object[]) query.getSingleResult();
            Map<String, Object> map = new HashMap<>();
            map.put("base_value", result[0] != null ? result[0] : BigDecimal.ZERO);
            map.put("vat_included", result[1] != null ? result[1] : false);
            map.put("vat_value", result[2] != null ? result[2] : BigDecimal.ZERO);
            map.put("band_ok", result[3] != null ? result[3] : false);
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
        // *** Allow null indicatorId for lookup ***
        if (prefixId == null) {
            log.trace("findBandByPrefixAndIndicator - Invalid input: prefixId is null");
            return Optional.empty();
        }
        // Use 0 for originIndicatorId if it's null
        Long effectiveOriginIndicatorId = originIndicatorId != null ? originIndicatorId : 0L;
        log.debug("Finding band for prefixId: {}, indicatorId: {}, effectiveOriginIndicatorId: {}", prefixId, indicatorId, effectiveOriginIndicatorId);

        StringBuilder sqlBuilder = new StringBuilder();
        sqlBuilder.append("SELECT b.id as band_id, b.value as band_value, b.vat_included as band_vat_included, b.name as band_name ");
        sqlBuilder.append("FROM band b ");
        sqlBuilder.append("JOIN band_indicator bi ON b.id = bi.band_id ");
        sqlBuilder.append("WHERE b.active = true ");
        sqlBuilder.append("  AND b.prefix_id = :prefixId ");
        // *** Handle null indicatorId in the query ***
        if (indicatorId != null) {
             sqlBuilder.append("  AND bi.indicator_id = :indicatorId ");
        } else {
             // If indicatorId is null, we might want bands that apply when no specific indicator is matched.
             // This depends on business logic. A common approach is to look for bands linked to a 'null' or '0' indicator.
             // Assuming bands are always linked to a specific indicator for now. If null indicator means "no band applies", this query is fine.
             // If null indicator should match a specific 'default' band, adjust the WHERE clause.
             // For now, if indicatorId is null, this query won't find a match in band_indicator.
             log.trace("IndicatorId is null, band lookup will likely find no match unless bands are linked to null/0 indicators.");
             // To explicitly match bands linked to a 0 indicator when input indicatorId is null:
             // sqlBuilder.append(" AND bi.indicator_id = 0 ");
             // For now, let the query run as is; it will likely return empty if indicatorId is null.
             // Or, if null indicator means no band applies, return empty immediately:
             // return Optional.empty();
        }
        sqlBuilder.append("  AND b.origin_indicator_id IN (0, :originIndicatorId) ");
        sqlBuilder.append("ORDER BY b.origin_indicator_id DESC ");
        sqlBuilder.append("LIMIT 1");

        Query query = entityManager.createNativeQuery(sqlBuilder.toString());
        query.setParameter("prefixId", prefixId);
        if (indicatorId != null) { // Only bind if not null
             query.setParameter("indicatorId", indicatorId);
        }
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
        if (!StringUtils.hasText(trunkCode) || commLocationId == null) {
            log.trace("findTrunkByCode - Invalid input: trunkCode={}, commLocationId={}", trunkCode, commLocationId);
            return Optional.empty();
        }
        log.debug("Finding trunk by code: '{}', commLocationId: {}", trunkCode, commLocationId);
        String sql = "SELECT t.* FROM trunk t " +
                "WHERE t.active = true " +
                "  AND t.name = :trunkCode " +
                "  AND t.comm_location_id = :commLocationId " +
                "LIMIT 1";
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
        String sql = "SELECT tr.* FROM trunk_rate tr " +
                "WHERE tr.trunk_id = :trunkId " +
                "  AND tr.operator_id = :operatorId " +
                "  AND tr.telephony_type_id = :telephonyTypeId " +
                "LIMIT 1";
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
        // *** Allow null indicatorId ***
        if (telephonyTypeId == null) {
            log.trace("findTrunkRule - Invalid input: ttId is null");
            return Optional.empty();
        }
        Long effectiveOriginIndicatorId = originIndicatorId != null ? originIndicatorId : 0L;
        log.debug("Finding trunk rule for trunkCode: '{}', ttId: {}, indId: {}, effectiveOriginIndId: {}", trunkCode, telephonyTypeId, indicatorId, effectiveOriginIndicatorId);
        String indicatorIdStr = indicatorId != null ? String.valueOf(indicatorId) : null; // Handle null indicatorId

        StringBuilder sqlBuilder = new StringBuilder();
        sqlBuilder.append("SELECT tr.* ");
        sqlBuilder.append("FROM trunk_rule tr ");
        sqlBuilder.append("LEFT JOIN trunk t ON tr.trunk_id = t.id AND t.active = true ");
        sqlBuilder.append("WHERE tr.active = true ");
        sqlBuilder.append("  AND (tr.trunk_id IS NULL OR tr.trunk_id = 0 OR (t.name = :trunkCode)) ");
        sqlBuilder.append("  AND tr.telephony_type_id = :telephonyTypeId ");
        sqlBuilder.append("  AND (tr.origin_indicator_id IS NULL OR tr.origin_indicator_id = 0 OR tr.origin_indicator_id = :originIndicatorId) ");

        // *** Adjust indicator_ids matching for null indicatorId ***
        if (indicatorIdStr != null) {
            sqlBuilder.append("  AND (tr.indicator_ids = '' OR tr.indicator_ids IS NULL OR tr.indicator_ids = :indicatorIdStr OR tr.indicator_ids LIKE :indicatorIdStrLikeStart OR tr.indicator_ids LIKE :indicatorIdStrLikeEnd OR tr.indicator_ids LIKE :indicatorIdStrLikeMiddle) ");
        } else {
            // If indicatorId is null, only match rules where indicator_ids is also empty or null
            sqlBuilder.append("  AND (tr.indicator_ids = '' OR tr.indicator_ids IS NULL) ");
        }

        sqlBuilder.append("ORDER BY tr.trunk_id DESC NULLS LAST, ");
        sqlBuilder.append("         tr.origin_indicator_id DESC NULLS LAST, ");
        // Adjust CASE statement for null indicatorIdStr
        if (indicatorIdStr != null) {
            sqlBuilder.append("         CASE WHEN tr.indicator_ids = :indicatorIdStr THEN 0 ");
            sqlBuilder.append("              WHEN tr.indicator_ids LIKE :indicatorIdStrLikeStart THEN 1 ");
            sqlBuilder.append("              WHEN tr.indicator_ids LIKE :indicatorIdStrLikeMiddle THEN 2 ");
            sqlBuilder.append("              WHEN tr.indicator_ids LIKE :indicatorIdStrLikeEnd THEN 3 ");
            sqlBuilder.append("              WHEN tr.indicator_ids = '' OR tr.indicator_ids IS NULL THEN 5 ");
            sqlBuilder.append("              ELSE 4 ");
            sqlBuilder.append("         END, ");
        } else {
             // If indicatorIdStr is null, prioritize rules with empty/null indicator_ids
             sqlBuilder.append("         CASE WHEN tr.indicator_ids = '' OR tr.indicator_ids IS NULL THEN 0 ELSE 1 END, ");
        }
        sqlBuilder.append("         LENGTH(tr.indicator_ids) DESC ");
        sqlBuilder.append("LIMIT 1");

        Query query = entityManager.createNativeQuery(sqlBuilder.toString(), TrunkRule.class);
        query.setParameter("trunkCode", trunkCode != null ? trunkCode : "");
        query.setParameter("telephonyTypeId", telephonyTypeId);
        query.setParameter("originIndicatorId", effectiveOriginIndicatorId);
        if (indicatorIdStr != null) { // Bind only if not null
            query.setParameter("indicatorIdStr", indicatorIdStr);
            query.setParameter("indicatorIdStrLikeStart", indicatorIdStr + ",%");
            query.setParameter("indicatorIdStrLikeEnd", "%," + indicatorIdStr);
            query.setParameter("indicatorIdStrLikeMiddle", "%," + indicatorIdStr + ",%");
        }

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

        List<PbxSpecialRule> candidates = findPbxSpecialRuleCandidates(commLocationId, direction);

        for (PbxSpecialRule rule : candidates) {
            boolean match = false;
            String searchPattern = rule.getSearchPattern();

            if (StringUtils.hasText(searchPattern) && dialedNumber.startsWith(searchPattern)) {
                match = true;
                String ignorePatternString = rule.getIgnorePattern();
                if (match && StringUtils.hasText(ignorePatternString)) {
                    String[] ignorePatterns = ignorePatternString.split(",");
                    for (String ignore : ignorePatterns) {
                        String trimmedIgnore = ignore.trim();
                        if (!trimmedIgnore.isEmpty() && dialedNumber.startsWith(trimmedIgnore)) {
                            match = false;
                            log.trace("Rule {} ignored for number {} due to ignore pattern '{}'", rule.getId(), dialedNumber, trimmedIgnore);
                            break;
                        }
                    }
                }
                if (match && rule.getMinLength() != null && dialedNumber.length() < rule.getMinLength()) {
                    match = false;
                    log.trace("Rule {} ignored for number {} due to minLength ({} < {})", rule.getId(), dialedNumber, dialedNumber.length(), rule.getMinLength());
                }
            }

            if (match) {
                log.trace("Found matching PBX special rule {} for number {}", rule.getId(), dialedNumber);
                return Optional.of(rule);
            }
        }

        log.trace("No matching PBX special rule found for number {}", dialedNumber);
        return Optional.empty();
    }

    @Cacheable(value = "pbxSpecialRuleCandidates", key = "{#commLocationId, #direction}")
    public List<PbxSpecialRule> findPbxSpecialRuleCandidates(Long commLocationId, int direction) {
        if (commLocationId == null) return Collections.emptyList();
        log.debug("Finding PBX special rule candidates for commLocationId: {}, direction: {}", commLocationId, direction);
        String sql = "SELECT p.* FROM pbx_special_rule p " +
                "WHERE p.active = true " +
                "  AND (p.comm_location_id = :commLocationId OR p.comm_location_id IS NULL) " +
                "  AND p.direction IN (0, :direction) " +
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
        if (callDateTime == null) return Collections.emptyList();
        Long effectiveOriginIndicatorId = originIndicatorId != null ? originIndicatorId : 0L;
        log.debug("Finding special rate values for ttId: {}, opId: {}, bandId: {}, effectiveOriginIndId: {}, dateTime: {}",
                telephonyTypeId, operatorId, bandId, effectiveOriginIndicatorId, callDateTime);

        int dayOfWeek = callDateTime.getDayOfWeek().getValue();
        boolean isHoliday = false; // Placeholder

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
                return Collections.emptyList();
        }

        StringBuilder sqlBuilder = new StringBuilder();
        sqlBuilder.append("SELECT srv.* ");
        sqlBuilder.append("FROM special_rate_value srv ");
        sqlBuilder.append("WHERE srv.active = true ");
        sqlBuilder.append("  AND (srv.telephony_type_id = :telephonyTypeId OR srv.telephony_type_id IS NULL) ");
        sqlBuilder.append("  AND (srv.operator_id = :operatorId OR srv.operator_id IS NULL) ");
        sqlBuilder.append("  AND (srv.band_id = :bandId OR srv.band_id IS NULL) ");
        sqlBuilder.append("  AND (srv.origin_indicator_id = :originIndicatorId OR srv.origin_indicator_id IS NULL OR srv.origin_indicator_id = 0) ");
        sqlBuilder.append("  AND (srv.valid_from IS NULL OR srv.valid_from <= :callDateTime) ");
        sqlBuilder.append("  AND (srv.valid_to IS NULL OR srv.valid_to >= :callDateTime) ");
        sqlBuilder.append("  AND (srv.").append(dayColumn).append(" = true ");
        if (isHoliday) {
            sqlBuilder.append("OR srv.holiday_enabled = true");
        }
        sqlBuilder.append(") ");
        sqlBuilder.append("ORDER BY srv.origin_indicator_id DESC NULLS LAST, ");
        sqlBuilder.append("         srv.telephony_type_id DESC NULLS LAST, ");
        sqlBuilder.append("         srv.operator_id DESC NULLS LAST, ");
        sqlBuilder.append("         srv.band_id DESC NULLS LAST");

        Query query = entityManager.createNativeQuery(sqlBuilder.toString(), SpecialRateValue.class);
        query.setParameter("telephonyTypeId", telephonyTypeId);
        query.setParameter("operatorId", operatorId);
        query.setParameter("bandId", bandId);
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
        Long effectiveIndicatorId = indicatorId != null ? indicatorId : 0L;
        log.debug("Finding special service for number: {}, effectiveIndicatorId: {}, originCountryId: {}", phoneNumber, effectiveIndicatorId, originCountryId);

        String sql = "SELECT ss.* FROM special_service ss " +
                "WHERE ss.active = true " +
                "  AND ss.phone_number = :phoneNumber " +
                "  AND ss.indicator_id IN (0, :indicatorId) " +
                "  AND ss.origin_country_id = :originCountryId " +
                "ORDER BY ss.indicator_id DESC " +
                "LIMIT 1";
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

    @Cacheable(value = "telephonyTypeConfig", key = "{#telephonyTypeId, #originCountryId}")
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

    @Cacheable(value = "operatorByTelephonyType", key = "{#telephonyTypeId, #originCountryId}")
    public Optional<Operator> findOperatorByTelephonyTypeAndOrigin(Long telephonyTypeId, Long originCountryId) {
        if (telephonyTypeId == null || originCountryId == null) return Optional.empty();
        log.debug("Finding operator for telephonyTypeId: {}, originCountryId: {}", telephonyTypeId, originCountryId);
        String sql = "SELECT op.* FROM operator op " +
                "JOIN prefix p ON p.operator_id = op.id " +
                "WHERE p.telephone_type_id = :telephonyTypeId " +
                "  AND op.origin_country_id = :originCountryId " +
                "  AND op.active = true " +
                "  AND p.active = true " +
                "LIMIT 1";
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
        String sql = "SELECT p.base_value, p.vat_included, p.vat_value, tt.name as telephony_type_name " +
                "FROM prefix p " +
                "JOIN telephony_type tt ON p.telephone_type_id = tt.id " +
                "WHERE p.telephone_type_id = :telephonyTypeId " +
                "  AND p.active = true AND tt.active = true " +
                "LIMIT 1";
        Query query = entityManager.createNativeQuery(sql);
        query.setParameter("telephonyTypeId", telephonyTypeId);
        try {
            Object[] result = (Object[]) query.getSingleResult();
            Map<String, Object> map = new HashMap<>();
            map.put("valor_minuto", result[0] != null ? result[0] : BigDecimal.ZERO);
            map.put("valor_minuto_iva", result[1] != null ? result[1] : false);
            map.put("iva", result[2] != null ? result[2] : BigDecimal.ZERO);
            map.put("tipotele_nombre", result[3]);
            map.put("ensegundos", false);
            map.put("valor_inicial", BigDecimal.ZERO);
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
        lengths.put("min", Integer.MAX_VALUE);
        lengths.put("max", 0);

        int maxPossibleLength = String.valueOf(CdrProcessingConfig.MAX_POSSIBLE_EXTENSION_VALUE).length();

        // Query 1: Based on Employee extensions
        StringBuilder sqlEmployee = new StringBuilder();
        sqlEmployee.append("SELECT COALESCE(MIN(LENGTH(e.extension)), NULL) AS min_len, COALESCE(MAX(LENGTH(e.extension)), NULL) AS max_len ");
        sqlEmployee.append("FROM employee e ");
        sqlEmployee.append("WHERE e.active = true ");
        sqlEmployee.append("  AND e.extension ~ '^[0-9#*]+$' ");
        sqlEmployee.append("  AND e.extension NOT LIKE '0%' ");
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
        sqlRange.append("  AND CAST(er.range_start AS TEXT) ~ '^[0-9]+$' AND CAST(er.range_end AS TEXT) ~ '^[0-9]+$' ");
        sqlRange.append("  AND LENGTH(CAST(er.range_start AS TEXT)) < :maxExtPossibleLength ");
        sqlRange.append("  AND LENGTH(CAST(er.range_end AS TEXT)) < :maxExtPossibleLength ");
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


    // --- Simple Find By ID methods ---

    @Cacheable("communicationLocationById")
    public Optional<CommunicationLocation> findCommunicationLocationById(Long id) {
        if (id == null) return Optional.empty();
        String sql = "SELECT cl.* FROM communication_location cl WHERE cl.id = :id AND cl.active = true";
        Query query = entityManager.createNativeQuery(sql, CommunicationLocation.class);
        query.setParameter("id", id);
        try { return Optional.of((CommunicationLocation) query.getSingleResult()); }
        catch (NoResultException e) { return Optional.empty(); }
    }

    @Cacheable("indicatorById")
    public Optional<Indicator> findIndicatorById(Long id) {
        // *** Allow lookup for ID 0 (or handle it if needed), but generally 0 is not valid ***
        // For now, return empty if ID is null or 0, as 0 caused FK violation.
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

    @Cacheable("operatorByIdLookup")
    public Optional<Operator> findOperatorById(Long id) {
        if (id == null || id <= 0) return Optional.empty();
        String sql = "SELECT o.* FROM operator o WHERE o.id = :id AND o.active = true";
        Query query = entityManager.createNativeQuery(sql, Operator.class);
        query.setParameter("id", id);
        try { return Optional.of((Operator) query.getSingleResult()); }
        catch (NoResultException e) { return Optional.empty(); }
    }

    @Cacheable("telephonyTypeByIdLookup")
    public Optional<TelephonyType> findTelephonyTypeById(Long id) {
        if (id == null || id <= 0) return Optional.empty();
        String sql = "SELECT tt.* FROM telephony_type tt WHERE tt.id = :id AND tt.active = true";
        Query query = entityManager.createNativeQuery(sql, TelephonyType.class);
        query.setParameter("id", id);
        try { return Optional.of((TelephonyType) query.getSingleResult()); }
        catch (NoResultException e) { return Optional.empty(); }
    }

    @Cacheable("originCountryById")
    public Optional<OriginCountry> findOriginCountryById(Long id) {
        if (id == null || id <= 0) return Optional.empty();
        String sql = "SELECT oc.* FROM origin_country oc WHERE oc.id = :id AND oc.active = true";
        Query query = entityManager.createNativeQuery(sql, OriginCountry.class);
        query.setParameter("id", id);
        try { return Optional.of((OriginCountry) query.getSingleResult()); }
        catch (NoResultException e) { return Optional.empty(); }
    }

    @Cacheable("subdivisionById")
    public Optional<Subdivision> findSubdivisionById(Long id) {
        if (id == null || id <= 0) return Optional.empty();
        String sql = "SELECT s.* FROM subdivision s WHERE s.id = :id AND s.active = true";
        Query query = entityManager.createNativeQuery(sql, Subdivision.class);
        query.setParameter("id", id);
        try { return Optional.of((Subdivision) query.getSingleResult()); }
        catch (NoResultException e) { return Optional.empty(); }
    }

    @Cacheable("costCenterById")
    public Optional<CostCenter> findCostCenterById(Long id) {
        if (id == null || id <= 0) return Optional.empty();
        String sql = "SELECT cc.* FROM cost_center cc WHERE cc.id = :id AND cc.active = true";
        Query query = entityManager.createNativeQuery(sql, CostCenter.class);
        query.setParameter("id", id);
        try { return Optional.of((CostCenter) query.getSingleResult()); }
        catch (NoResultException e) { return Optional.empty(); }
    }
}