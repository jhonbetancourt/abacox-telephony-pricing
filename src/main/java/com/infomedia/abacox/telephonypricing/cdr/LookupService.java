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

        if (commLocationId != null) {
            sqlBuilder.append(" AND e.communication_location_id = :commLocationId");
            params.put("commLocationId", commLocationId);
        } else {
             log.warn("CommLocationId is null during employee lookup.");
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
            return Optional.of(employee);
        } catch (NoResultException e) {
            log.trace("Employee not found for ext: '{}', code: '{}', loc: {}", extension, authCode, commLocationId);
            return Optional.empty();
        } catch (Exception e) {
            log.error("Error finding employee for ext: '{}', code: '{}', loc: {}", extension, authCode, commLocationId, e);
            return Optional.empty();
        }
    }

     // --- Prefix & Related Lookups ---

    @Cacheable(value = "prefixLookup", key = "{#number, #originCountryId}")
    public List<Map<String, Object>> findPrefixesByNumber(String number, Long originCountryId) {
        if (originCountryId == null) return Collections.emptyList();
        log.debug("Finding prefixes for number: {}, originCountryId: {}", number, originCountryId);

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
        sqlBuilder.append("  AND tt.id != :specialCallsType "); // Exclude special service prefixes
        sqlBuilder.append("ORDER BY LENGTH(p.code) DESC, ttc.min_value DESC, tt.id");


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
        log.trace("Found {} prefixes for number {}", mappedResults.size(), number);
        return mappedResults;
    }

    @Cacheable(value = "prefixByTypeOperatorOrigin", key = "{#telephonyTypeId, #operatorId, #originCountryId}")
    public Optional<Prefix> findPrefixByTypeOperatorOrigin(Long telephonyTypeId, Long operatorId, Long originCountryId) {
        if (telephonyTypeId == null || operatorId == null || originCountryId == null) {
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


    @Cacheable(value = "indicatorLookup", key = "{#numberWithoutPrefix, #telephonyTypeId, #originCountryId}")
    public Optional<Map<String, Object>> findIndicatorByNumber(String numberWithoutPrefix, Long telephonyTypeId, Long originCountryId) {
         if (telephonyTypeId == null || originCountryId == null) return Optional.empty();
         log.debug("Finding indicator for number: {}, telephonyTypeId: {}, originCountryId: {}", numberWithoutPrefix, telephonyTypeId, originCountryId);

        Map<String, Integer> ndcLengths = findNdcMinMaxLength(telephonyTypeId, originCountryId);
        int minNdcLength = ndcLengths.getOrDefault("min", 0);
        int maxNdcLength = ndcLengths.getOrDefault("max", 0);

        boolean checkLocalFallback = (maxNdcLength == 0 && telephonyTypeId == ConfigurationService.TIPOTELE_LOCAL);
        if (maxNdcLength == 0 && !checkLocalFallback) {
            log.trace("No NDC length range found for telephony type {}, cannot find indicator.", telephonyTypeId);
            return Optional.empty();
        }

        if(checkLocalFallback) {
            minNdcLength = 0; // No NDC prefix for local
            maxNdcLength = 0;
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

            if (ndcStr.matches("\\d*") && subscriberNumberStr.matches("\\d+")) { // Allow empty NDC, require numeric subscriber
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
                sqlBuilder.append("         LENGTH(CAST(s.ndc AS TEXT)) DESC, ");
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
                    map.put("indicator_id", result[0]);
                    map.put("department_country", result[1]);
                    map.put("city_name", result[2]);
                    map.put("operator_id", result[3]);
                    map.put("series_ndc", result[4]);
                    map.put("series_initial", result[5]);
                    map.put("series_final", result[6]);
                    map.put("series_company", result[7]);
                    log.trace("Found indicator {} for number {}", map.get("indicator_id"), numberWithoutPrefix);
                    return Optional.of(map);
                } catch (NoResultException e) { /* Continue */ }
                catch (Exception e) { log.error("Error finding indicator for number: {}, ndc: {}", numberWithoutPrefix, ndcStr, e); }
            } else {
                 log.trace("Skipping NDC check: NDC '{}' or Subscriber '{}' is not numeric.", ndcStr, subscriberNumberStr);
            }
        }

        log.trace("No indicator found for number: {}", numberWithoutPrefix);
        return Optional.empty();
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
            lengths.put("min", ((Number) result[0]).intValue());
            lengths.put("max", ((Number) result[1]).intValue());
         } catch (Exception e) { log.warn("Could not determine NDC lengths for telephony type {}: {}", telephonyTypeId, e.getMessage()); }
         log.trace("NDC lengths for type {}: min={}, max={}", telephonyTypeId, lengths.get("min"), lengths.get("max"));
         return lengths;
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
        } catch (NoResultException e) { return Optional.empty(); }
        catch (Exception e) { log.error("Error finding base rate for prefixId: {}", prefixId, e); return Optional.empty(); }
    }

    @Cacheable(value = "bandLookup", key = "{#prefixId, #indicatorId, #originIndicatorId}")
    public Optional<Map<String, Object>> findBandByPrefixAndIndicator(Long prefixId, Long indicatorId, Long originIndicatorId) {
        if (prefixId == null || indicatorId == null) return Optional.empty();
        log.debug("Finding band for prefixId: {}, indicatorId: {}, originIndicatorId: {}", prefixId, indicatorId, originIndicatorId);
        StringBuilder sqlBuilder = new StringBuilder();
        sqlBuilder.append("SELECT b.id as band_id, b.value as band_value, b.vat_included as band_vat_included, b.name as band_name ");
        sqlBuilder.append("FROM band b ");
        sqlBuilder.append("JOIN band_indicator bi ON b.id = bi.band_id ");
        sqlBuilder.append("WHERE b.active = true ");
        sqlBuilder.append("  AND b.prefix_id = :prefixId ");
        sqlBuilder.append("  AND bi.indicator_id = :indicatorId ");
        sqlBuilder.append("  AND b.origin_indicator_id IN (0, :originIndicatorId) ");
        sqlBuilder.append("ORDER BY b.origin_indicator_id DESC ");
        sqlBuilder.append("LIMIT 1");

        Query query = entityManager.createNativeQuery(sqlBuilder.toString());
        query.setParameter("prefixId", prefixId);
        query.setParameter("indicatorId", indicatorId);
        query.setParameter("originIndicatorId", originIndicatorId != null ? originIndicatorId : 0);

        try {
            Object[] result = (Object[]) query.getSingleResult();
            Map<String, Object> map = new HashMap<>();
            map.put("band_id", result[0]);
            map.put("band_value", result[1] != null ? result[1] : BigDecimal.ZERO);
            map.put("band_vat_included", result[2] != null ? result[2] : false);
            map.put("band_name", result[3]);
            return Optional.of(map);
        } catch (NoResultException e) { return Optional.empty(); }
        catch (Exception e) { log.error("Error finding band for prefixId: {}, indicatorId: {}, originIndicatorId: {}", prefixId, indicatorId, originIndicatorId, e); return Optional.empty(); }
    }


    // --- Trunk Lookups ---

    @Cacheable(value = "trunkLookup", key = "#trunkCode + '-' + #commLocationId")
    public Optional<Trunk> findTrunkByCode(String trunkCode, Long commLocationId) {
        if (!org.springframework.util.StringUtils.hasText(trunkCode) || commLocationId == null) return Optional.empty();
        log.debug("Finding trunk by code: '{}', commLocationId: {}", trunkCode, commLocationId);
        String sql = "SELECT t.* FROM trunk t " +
                     "WHERE t.active = true " +
                     "  AND t.name = :trunkCode " +
                     "  AND t.comm_location_id = :commLocationId " +
                     "LIMIT 1";
        Query query = entityManager.createNativeQuery(sql, Trunk.class);
        query.setParameter("trunkCode", trunkCode);
        query.setParameter("commLocationId", commLocationId);
        try { return Optional.of((Trunk) query.getSingleResult()); }
        catch (NoResultException e) { return Optional.empty(); }
        catch (Exception e) { log.error("Error finding trunk for code: '{}', loc: {}", trunkCode, commLocationId, e); return Optional.empty(); }
    }

    @Cacheable(value = "trunkRateLookup", key = "{#trunkId, #operatorId, #telephonyTypeId}")
    public Optional<TrunkRate> findTrunkRate(Long trunkId, Long operatorId, Long telephonyTypeId) {
         if (trunkId == null || operatorId == null || telephonyTypeId == null) return Optional.empty();
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
         try { return Optional.of((TrunkRate) query.getSingleResult()); }
         catch (NoResultException e) { return Optional.empty(); }
         catch (Exception e) { log.error("Error finding trunk rate for trunkId: {}, opId: {}, ttId: {}", trunkId, operatorId, telephonyTypeId, e); return Optional.empty(); }
    }


    @Cacheable(value = "trunkRuleLookup", key = "{#trunkCode, #telephonyTypeId, #indicatorId, #originIndicatorId}")
    public Optional<TrunkRule> findTrunkRule(String trunkCode, Long telephonyTypeId, Long indicatorId, Long originIndicatorId) {
        if (telephonyTypeId == null || indicatorId == null) return Optional.empty();
        log.debug("Finding trunk rule for trunkCode: '{}', ttId: {}, indId: {}, originIndId: {}", trunkCode, telephonyTypeId, indicatorId, originIndicatorId);
        String indicatorIdStr = String.valueOf(indicatorId);

        StringBuilder sqlBuilder = new StringBuilder();
        sqlBuilder.append("SELECT tr.* ");
        sqlBuilder.append("FROM trunk_rule tr ");
        sqlBuilder.append("LEFT JOIN trunk t ON tr.trunk_id = t.id AND t.active = true ");
        sqlBuilder.append("WHERE tr.active = true ");
        sqlBuilder.append("  AND (tr.trunk_id = 0 OR (t.name = :trunkCode)) ");
        sqlBuilder.append("  AND tr.telephony_type_id = :telephonyTypeId ");
        sqlBuilder.append("  AND tr.origin_indicator_id IN (0, :originIndicatorId) ");
        sqlBuilder.append("  AND (tr.indicator_ids = '' OR tr.indicator_ids IS NULL OR tr.indicator_ids = :indicatorIdStr OR tr.indicator_ids LIKE :indicatorIdStrLikeStart OR tr.indicator_ids LIKE :indicatorIdStrLikeEnd OR tr.indicator_ids LIKE :indicatorIdStrLikeMiddle) ");
        sqlBuilder.append("ORDER BY tr.trunk_id DESC NULLS LAST, ");
        sqlBuilder.append("         tr.origin_indicator_id DESC NULLS LAST, ");
        sqlBuilder.append("         CASE WHEN tr.indicator_ids = :indicatorIdStr THEN 0 ");
        sqlBuilder.append("              WHEN tr.indicator_ids LIKE :indicatorIdStrLikeStart THEN 1 ");
        sqlBuilder.append("              WHEN tr.indicator_ids LIKE :indicatorIdStrLikeMiddle THEN 2 ");
        sqlBuilder.append("              WHEN tr.indicator_ids LIKE :indicatorIdStrLikeEnd THEN 3 ");
        sqlBuilder.append("              ELSE 4 ");
        sqlBuilder.append("         END, ");
        sqlBuilder.append("         LENGTH(tr.indicator_ids) DESC ");
        sqlBuilder.append("LIMIT 1");

        Query query = entityManager.createNativeQuery(sqlBuilder.toString(), TrunkRule.class);
        query.setParameter("trunkCode", trunkCode != null ? trunkCode : "");
        query.setParameter("telephonyTypeId", telephonyTypeId);
        query.setParameter("originIndicatorId", originIndicatorId != null ? originIndicatorId : 0);
        query.setParameter("indicatorIdStr", indicatorIdStr);
        query.setParameter("indicatorIdStrLikeStart", indicatorIdStr + ",%");
        query.setParameter("indicatorIdStrLikeEnd", "%," + indicatorIdStr);
        query.setParameter("indicatorIdStrLikeMiddle", "%," + indicatorIdStr + ",%");

        try { return Optional.of((TrunkRule) query.getSingleResult()); }
        catch (NoResultException e) { return Optional.empty(); }
        catch (Exception e) { log.error("Error finding trunk rule for trunk: '{}', ttId: {}, indId: {}, originIndId: {}", trunkCode, telephonyTypeId, indicatorId, originIndicatorId, e); return Optional.empty(); }
    }

    // --- Special Rules & Rates ---

    @Cacheable(value = "pbxSpecialRuleLookup", key = "{#dialedNumber, #commLocationId, #direction}")
    public Optional<PbxSpecialRule> findPbxSpecialRule(String dialedNumber, Long commLocationId, int direction) {
        if (!org.springframework.util.StringUtils.hasText(dialedNumber) || commLocationId == null) return Optional.empty();

        List<PbxSpecialRule> candidates = findPbxSpecialRuleCandidates(commLocationId, direction);

        for (PbxSpecialRule rule : candidates) {
            boolean match = false;
            String searchPattern = rule.getSearchPattern();
            if (searchPattern != null && !searchPattern.isEmpty() && dialedNumber.startsWith(searchPattern)) {
                match = true;
                String ignorePattern = rule.getIgnorePattern();
                if (org.springframework.util.StringUtils.hasText(ignorePattern)) {
                    String[] ignorePatterns = ignorePattern.split(",");
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
                     log.trace("Rule {} ignored for number {} due to minLength ({})", rule.getId(), dialedNumber, rule.getMinLength());
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
         try { return query.getResultList(); }
         catch (Exception e) { log.error("Error finding PBX special rule candidates for commLocationId: {}, direction: {}", commLocationId, direction, e); return Collections.emptyList(); }
    }

    @Cacheable(value = "specialRateValueLookup", key = "{#telephonyTypeId, #operatorId, #bandId, #originIndicatorId, #callDateTime}")
    public List<SpecialRateValue> findSpecialRateValues(Long telephonyTypeId, Long operatorId, Long bandId, Long originIndicatorId, LocalDateTime callDateTime) {
        if (telephonyTypeId == null || operatorId == null || callDateTime == null) return Collections.emptyList();
        log.debug("Finding special rate values for ttId: {}, opId: {}, bandId: {}, originIndId: {}, dateTime: {}",
                telephonyTypeId, operatorId, bandId, originIndicatorId, callDateTime);

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
            default: return Collections.emptyList();
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
        query.setParameter("bandId", bandId); // Can be null
        query.setParameter("originIndicatorId", originIndicatorId != null ? originIndicatorId : 0);
        query.setParameter("callDateTime", callDateTime);

        try {
             List<SpecialRateValue> results = query.getResultList();
             log.trace("Found {} special rate candidates", results.size());
             return results;
         } catch (Exception e) {
             log.error("Error finding special rate values for ttId: {}, opId: {}, bandId: {}, originIndId: {}, dateTime: {}",
                telephonyTypeId, operatorId, bandId, originIndicatorId, callDateTime, e);
             return Collections.emptyList();
         }
    }

    @Cacheable(value = "specialServiceLookup", key = "{#phoneNumber, #indicatorId, #originCountryId}")
    public Optional<SpecialService> findSpecialService(String phoneNumber, Long indicatorId, Long originCountryId) {
        if (!org.springframework.util.StringUtils.hasText(phoneNumber) || originCountryId == null) return Optional.empty();
        log.debug("Finding special service for number: {}, indicatorId: {}, originCountryId: {}", phoneNumber, indicatorId, originCountryId);
        String sql = "SELECT ss.* FROM special_service ss " +
                     "WHERE ss.active = true " +
                     "  AND ss.phone_number = :phoneNumber " +
                     "  AND ss.indicator_id IN (0, :indicatorId) " +
                     "  AND ss.origin_country_id = :originCountryId " +
                     "ORDER BY ss.indicator_id DESC " +
                     "LIMIT 1";
        Query query = entityManager.createNativeQuery(sql, SpecialService.class);
        query.setParameter("phoneNumber", phoneNumber);
        query.setParameter("indicatorId", indicatorId != null ? indicatorId : 0);
        query.setParameter("originCountryId", originCountryId);
        try { return Optional.of((SpecialService) query.getSingleResult()); }
        catch (NoResultException e) { return Optional.empty(); }
        catch (Exception e) { log.error("Error finding special service for number: {}, indId: {}, originId: {}", phoneNumber, indicatorId, originCountryId, e); return Optional.empty(); }
    }

    // --- Other Lookups ---

    @Cacheable(value = "commLocationPrefix", key = "#commLocationId")
    public Optional<String> findPbxPrefixByCommLocationId(Long commLocationId) {
        if (commLocationId == null) return Optional.empty();
        log.debug("Finding PBX prefix for commLocationId: {}", commLocationId);
        String sql = "SELECT pbx_prefix FROM communication_location WHERE id = :id";
        Query query = entityManager.createNativeQuery(sql, String.class);
        query.setParameter("id", commLocationId);
        try { return Optional.ofNullable((String) query.getSingleResult()); }
        catch (NoResultException e) { return Optional.empty(); }
        catch (Exception e) { log.error("Error finding PBX prefix for commLocationId: {}", commLocationId, e); return Optional.empty(); }
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
        } catch (NoResultException e) { /* Use defaults */ }
        catch (Exception e) { log.error("Error finding min/max config for telephonyTypeId: {}, originCountryId: {}", telephonyTypeId, originCountryId, e); }
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
        try { return Optional.of((Operator) query.getSingleResult()); }
        catch (NoResultException e) { return Optional.empty(); }
        catch (Exception e) { log.error("Error finding operator for telephonyTypeId: {}, originCountryId: {}", telephonyTypeId, originCountryId, e); return Optional.empty(); }
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
        } catch (NoResultException e) { return Optional.empty(); }
        catch (Exception e) { log.error("Error finding internal tariff for telephonyTypeId: {}", telephonyTypeId, e); return Optional.empty(); }
     }

    @Cacheable(value = "extensionMinMaxLength", key = "{#commLocationId}")
    public Map<String, Integer> findExtensionMinMaxLength(Long commLocationId) {
        log.debug("Finding min/max extension length for commLocationId: {}", commLocationId);
        Map<String, Integer> lengths = new HashMap<>();
        lengths.put("min", 100); lengths.put("max", 0);

        int maxPossibleLength = String.valueOf(ConfigurationService.MAX_POSSIBLE_EXTENSION_VALUE).length();

        // Query 1: Based on Employee extensions
        StringBuilder sqlEmployee = new StringBuilder();
        sqlEmployee.append("SELECT COALESCE(MIN(LENGTH(e.extension)), 100) AS min_len, COALESCE(MAX(LENGTH(e.extension)), 0) AS max_len ");
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
            int minEmp = ((Number) resultEmp[0]).intValue();
            int maxEmp = ((Number) resultEmp[1]).intValue();
            if (minEmp < lengths.get("min")) lengths.put("min", minEmp);
            if (maxEmp > lengths.get("max")) lengths.put("max", maxEmp);
            log.trace("Employee ext lengths: min={}, max={}", minEmp, maxEmp);
        } catch (Exception e) { log.warn("Could not determine extension lengths from employees: {}", e.getMessage()); }

        // Query 2: Based on ExtensionRange
        StringBuilder sqlRange = new StringBuilder();
        sqlRange.append("SELECT COALESCE(MIN(LENGTH(er.range_start)), 100) AS min_len, COALESCE(MAX(LENGTH(er.range_end)), 0) AS max_len ");
        sqlRange.append("FROM extension_range er ");
        sqlRange.append("WHERE er.active = true ");
        sqlRange.append("  AND er.range_start ~ '^[0-9]+$' AND er.range_end ~ '^[0-9]+$' ");
        sqlRange.append("  AND LENGTH(er.range_start) < :maxExtPossibleLength ");
        sqlRange.append("  AND LENGTH(er.range_end) < :maxExtPossibleLength ");
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
            int minRange = ((Number) resultRange[0]).intValue();
            int maxRange = ((Number) resultRange[1]).intValue();
            if (minRange < lengths.get("min")) lengths.put("min", minRange);
            if (maxRange > lengths.get("max")) lengths.put("max", maxRange);
             log.trace("Range ext lengths: min={}, max={}", minRange, maxRange);
        } catch (Exception e) { log.warn("Could not determine extension lengths from ranges: {}", e.getMessage()); }

        // Final adjustments
        if (lengths.get("min") == 100) lengths.put("min", 0);
        if (lengths.get("max") == 0) lengths.put("max", maxPossibleLength -1);
        if (lengths.get("min") > lengths.get("max")) { lengths.put("min", lengths.get("max")); }

        log.debug("Final determined extension lengths: min={}, max={}", lengths.get("min"), lengths.get("max"));
        return lengths;
    }

    @Cacheable(value = "localNdc", key = "#indicatorId")
    public Optional<Integer> findLocalNdcForIndicator(Long indicatorId) {
        if (indicatorId == null || indicatorId <= 0) return Optional.empty();
        log.debug("Finding local NDC for indicatorId: {}", indicatorId);
        String sql = "SELECT s.ndc FROM series s " +
                     "WHERE s.indicator_id = :indicatorId " +
                     "GROUP BY s.ndc " +
                     "ORDER BY COUNT(*) DESC, s.ndc ASC " +
                     "LIMIT 1";
        Query query = entityManager.createNativeQuery(sql, Integer.class);
        query.setParameter("indicatorId", indicatorId);
        try {
            return Optional.ofNullable((Integer) query.getSingleResult());
        } catch (NoResultException e) { log.warn("No NDC found for indicatorId: {}", indicatorId); return Optional.empty(); }
        catch (Exception e) { log.error("Error finding local NDC for indicatorId: {}", indicatorId, e); return Optional.empty(); }
    }

    @Cacheable(value = "isLocalExtended", key = "{#destinationNdc, #originIndicatorId}")
    public boolean isLocalExtended(Integer destinationNdc, Long originIndicatorId) {
        if (destinationNdc == null || originIndicatorId == null || originIndicatorId <= 0) {
            return false;
        }
        log.debug("Checking if NDC {} is local extended for origin indicator {}", destinationNdc, originIndicatorId);
        String sql = "SELECT DISTINCT s.ndc FROM series s WHERE s.indicator_id = :originIndicatorId";
        Query query = entityManager.createNativeQuery(sql, Integer.class);
        query.setParameter("originIndicatorId", originIndicatorId);
        try {
            List<Integer> originNdcs = query.getResultList();
            boolean isExtended = originNdcs.contains(destinationNdc);
            log.trace("NDCs for origin {}: {}. Destination NDC {} is extended: {}", originIndicatorId, originNdcs, destinationNdc, isExtended);
            return isExtended;
        } catch (Exception e) { log.error("Error checking local extended status for NDC {}, origin indicator {}: {}", destinationNdc, originIndicatorId, e); return false; }
    }


    // --- Simple Find By ID methods ---
    @Cacheable("communicationLocationById")
    public Optional<CommunicationLocation> findCommunicationLocationById(Long id) {
        if (id == null) return Optional.empty();
        String sql = "SELECT cl.* FROM communication_location cl WHERE cl.id = :id";
        Query query = entityManager.createNativeQuery(sql, CommunicationLocation.class);
        query.setParameter("id", id);
        try { return Optional.of((CommunicationLocation) query.getSingleResult()); }
        catch (NoResultException e) { return Optional.empty(); }
    }

    @Cacheable("indicatorById")
    public Optional<Indicator> findIndicatorById(Long id) {
        if (id == null || id <= 0) return Optional.empty();
        String sql = "SELECT i.* FROM indicator i WHERE i.id = :id";
        Query query = entityManager.createNativeQuery(sql, Indicator.class);
        query.setParameter("id", id);
        try { return Optional.of((Indicator) query.getSingleResult()); }
        catch (NoResultException e) { return Optional.empty(); }
    }

     @Cacheable("operatorByIdLookup")
     public Optional<Operator> findOperatorById(Long id) {
        if (id == null || id <= 0) return Optional.empty();
        String sql = "SELECT o.* FROM operator o WHERE o.id = :id";
        Query query = entityManager.createNativeQuery(sql, Operator.class);
        query.setParameter("id", id);
        try { return Optional.of((Operator) query.getSingleResult()); }
        catch (NoResultException e) { return Optional.empty(); }
     }

     @Cacheable("telephonyTypeByIdLookup")
     public Optional<TelephonyType> findTelephonyTypeById(Long id) {
        if (id == null || id <= 0) return Optional.empty();
        String sql = "SELECT tt.* FROM telephony_type tt WHERE tt.id = :id";
        Query query = entityManager.createNativeQuery(sql, TelephonyType.class);
        query.setParameter("id", id);
        try { return Optional.of((TelephonyType) query.getSingleResult()); }
        catch (NoResultException e) { return Optional.empty(); }
     }

     @Cacheable("originCountryById")
     public Optional<OriginCountry> findOriginCountryById(Long id) {
        if (id == null || id <= 0) return Optional.empty();
        String sql = "SELECT oc.* FROM origin_country oc WHERE oc.id = :id";
        Query query = entityManager.createNativeQuery(sql, OriginCountry.class);
        query.setParameter("id", id);
        try { return Optional.of((OriginCountry) query.getSingleResult()); }
        catch (NoResultException e) { return Optional.empty(); }
     }

    @Cacheable("subdivisionById")
    public Optional<Subdivision> findSubdivisionById(Long id) {
        if (id == null || id <= 0) return Optional.empty();
        String sql = "SELECT s.* FROM subdivision s WHERE s.id = :id";
        Query query = entityManager.createNativeQuery(sql, Subdivision.class);
        query.setParameter("id", id);
        try { return Optional.of((Subdivision) query.getSingleResult()); }
        catch (NoResultException e) { return Optional.empty(); }
    }

    @Cacheable("costCenterById")
    public Optional<CostCenter> findCostCenterById(Long id) {
        if (id == null || id <= 0) return Optional.empty();
        String sql = "SELECT cc.* FROM cost_center cc WHERE cc.id = :id";
        Query query = entityManager.createNativeQuery(sql, CostCenter.class);
        query.setParameter("id", id);
        try { return Optional.of((CostCenter) query.getSingleResult()); }
        catch (NoResultException e) { return Optional.empty(); }
    }
}