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
    private final CdrProcessingConfig configService;

    public LookupService(EntityManager entityManager, CdrProcessingConfig configService) {
        this.entityManager = entityManager;
        this.configService = configService;
    }

    // --- Employee Lookups ---
    
    public Optional<Employee> findEmployeeByExtensionOrAuthCode(String extension, String authCode, Long commLocationId) {
        log.debug("Looking up employee by extension: '{}', authCode: '{}', commLocationId: {}", extension, authCode, commLocationId);
        StringBuilder sqlBuilder = new StringBuilder("SELECT e.* FROM employee e WHERE e.active = true ");
        Map<String, Object> params = new HashMap<>();

        if (commLocationId != null && commLocationId > 0) {
            sqlBuilder.append(" AND e.communication_location_id = :commLocationId");
            params.put("commLocationId", commLocationId);
        } else {
            log.trace("CommLocationId is null or 0 for employee lookup. Searching without location constraint if not found locally.");
        }

        boolean hasAuthCode = StringUtils.hasText(authCode) && !configService.getIgnoredAuthCodes().contains(authCode);
        boolean hasExtension = StringUtils.hasText(extension);

        String specificCondition = "";
        if (hasAuthCode) {
            specificCondition = " AND e.auth_code = :authCode ";
            params.put("authCode", authCode);
        } else if (hasExtension) {
            specificCondition = " AND e.extension = :extension ";
            params.put("extension", extension);
        } else {
            log.trace("No valid identifier (extension or non-ignored authCode) provided for employee lookup.");
            return Optional.empty();
        }
        
        sqlBuilder.append(specificCondition);
        sqlBuilder.append(" ORDER BY e.id DESC LIMIT 1");

        Query query = entityManager.createNativeQuery(sqlBuilder.toString(), Employee.class);
        params.forEach(query::setParameter);

        try {
            Employee employee = (Employee) query.getSingleResult();
            log.trace("Found employee: {}", employee.getId());
            return Optional.of(employee);
        } catch (NoResultException e) {
            log.trace("Employee not found for ext: '{}', code: '{}', loc: {}", extension, authCode, commLocationId);
            return Optional.empty();
        } catch (Exception ex) {
            log.error("Error finding employee for ext: '{}', code: '{}', loc: {}: {}", extension, authCode, commLocationId, ex.getMessage(), ex);
            return Optional.empty();
        }
    }

    
    public Optional<Map<String, Object>> findRangeAssignment(String extension, Long commLocationId, LocalDateTime callTime) {
        if (!StringUtils.hasText(extension) || commLocationId == null || callTime == null) return Optional.empty();
        log.debug("Finding range assignment for ext: {}, commLocationId: {}, callTime: {}", extension, commLocationId, callTime);

        long extNumeric;
        try {
            extNumeric = Long.parseLong(extension.replaceAll("[^0-9]", ""));
        } catch (NumberFormatException e) {
            log.warn("Extension {} is not numeric, cannot perform range assignment.", extension);
            return Optional.empty();
        }

        String sql = "SELECT er.subdivision_id, er.cost_center_id, er.prefix as range_prefix " +
                     "FROM extension_range er " +
                     "WHERE er.active = true " +
                     "  AND er.comm_location_id = :commLocationId " +
                     "  AND er.range_start <= :extNumeric " +
                     "  AND er.range_end >= :extNumeric " +
                     "ORDER BY (er.range_end - er.range_start) ASC, er.id DESC " +
                     "LIMIT 1";
        Query query = entityManager.createNativeQuery(sql);
        query.setParameter("commLocationId", commLocationId);
        query.setParameter("extNumeric", extNumeric);

        try {
            Object[] result = (Object[]) query.getSingleResult();
            Map<String, Object> map = new HashMap<>();
            map.put("subdivision_id", result[0]);
            map.put("cost_center_id", result[1]);
            map.put("range_prefix", result[2]);
            log.trace("Found range assignment for extension {}: {}", extension, map);
            return Optional.of(map);
        } catch (NoResultException e) {
            log.trace("No active range assignment found for extension {}", extension);
            return Optional.empty();
        } catch (Exception e) {
            log.error("Error finding range assignment for extension {}: {}", extension, e.getMessage(), e);
            return Optional.empty();
        }
    }

    
    public Optional<Employee> findEmployeeById(Long id) {
        if (id == null || id <= 0) return Optional.empty();
        String sql = "SELECT e.* FROM employee e WHERE e.id = :id AND e.active = true";
        Query query = entityManager.createNativeQuery(sql, Employee.class);
        query.setParameter("id", id);
        try { return Optional.of((Employee) query.getSingleResult()); }
        catch (NoResultException e) { return Optional.empty(); }
    }

    // --- Prefix & Related Lookups ---

    
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
        sqlBuilder.append("LEFT JOIN telephony_type_config ttc ON ttc.telephony_type_id = tt.id AND ttc.origin_country_id = :originCountryId AND ttc.active = true ");
        sqlBuilder.append("WHERE p.active = true AND tt.active = true AND op.active = true ");
        sqlBuilder.append("  AND :number LIKE p.code || '%' ");
        sqlBuilder.append("  AND op.origin_country_id = :originCountryId ");
        sqlBuilder.append("  AND tt.id != :specialCallsType ");
        sqlBuilder.append("ORDER BY LENGTH(p.code) DESC, COALESCE(ttc.min_value, 0) DESC, tt.id ASC");


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
            map.put("telephony_type_min", row[11] != null ? ((Number)row[11]).intValue() : 0);
            map.put("telephony_type_max", row[12] != null ? ((Number)row[12]).intValue() : 0);
            mappedResults.add(map);
        }
        log.trace("Found {} prefixes for number lookup", mappedResults.size());
        return mappedResults;
    }

    
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
    
    public Optional<Prefix> findPrefixById(Long prefixId) {
        if (prefixId == null || prefixId <= 0) return Optional.empty();
        String sql = "SELECT p.* FROM prefix p WHERE p.id = :id AND p.active = true";
        Query query = entityManager.createNativeQuery(sql, Prefix.class);
        query.setParameter("id", prefixId);
        try {
            return Optional.of((Prefix) query.getSingleResult());
        } catch (NoResultException e) {
            log.warn("No active prefix found for ID: {}", prefixId);
            return Optional.empty();
        }
    }

    
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

    
    public Optional<Map<String, Object>> findIndicatorByNumber(String numberWithoutPrefix, Long telephonyTypeId, Long originCountryId,
                                                               boolean isPrefixBandOk, Long currentPrefixId, Long originCommLocationIndicatorId) {
        if (telephonyTypeId == null || originCountryId == null || !StringUtils.hasText(numberWithoutPrefix) || (isPrefixBandOk && currentPrefixId == null)) {
            log.trace("findIndicatorByNumber - Invalid input: num={}, ttId={}, ocId={}, bandOk={}, prefixId={}",
                    numberWithoutPrefix, telephonyTypeId, originCountryId, isPrefixBandOk, currentPrefixId);
            return Optional.empty();
        }
        log.debug("Finding indicator for num: {}, ttId: {}, ocId: {}, bandOk: {}, prefixId: {}, originCommLocIndId: {}",
                numberWithoutPrefix, telephonyTypeId, originCountryId, isPrefixBandOk, currentPrefixId, originCommLocationIndicatorId);

        Map<String, Integer> ndcLengths = findNdcMinMaxLength(telephonyTypeId, originCountryId);
        int minNdcLength = ndcLengths.getOrDefault("min", 0);
        int maxNdcLength = ndcLengths.getOrDefault("max", 0);

        boolean checkLocalFallback = (minNdcLength == 0 && maxNdcLength == 0 && telephonyTypeId.equals(CdrProcessingConfig.TIPOTELE_LOCAL));
        if (maxNdcLength == 0 && !checkLocalFallback) {
            log.trace("No NDC length range found for telephony type {}, cannot find indicator.", telephonyTypeId);
            return Optional.empty();
        }
        if (checkLocalFallback) {
            minNdcLength = 0; maxNdcLength = 0;
            log.trace("Treating as LOCAL type lookup (effective NDC length 0)");
        }

        Map<String, Integer> typeMinMaxConfig = configService.getTelephonyTypeMinMax(telephonyTypeId, originCountryId);
        int typeMinDigits = typeMinMaxConfig.getOrDefault("min", 0);


        for (int ndcLength = maxNdcLength; ndcLength >= minNdcLength; ndcLength--) {
            String ndcStr = "";
            String subscriberNumberStr = numberWithoutPrefix;

            if (ndcLength > 0 && numberWithoutPrefix.length() >= ndcLength) {
                ndcStr = numberWithoutPrefix.substring(0, ndcLength);
                subscriberNumberStr = numberWithoutPrefix.substring(ndcLength);
            } else if (ndcLength > 0) {
                continue;
            }

            int minSubscriberLength = Math.max(0, typeMinDigits - ndcLength);
            if (subscriberNumberStr.length() < minSubscriberLength) {
                log.trace("Subscriber part {} too short for NDC {} (min subscriber length {})", subscriberNumberStr, ndcStr, minSubscriberLength);
                continue;
            }


            if (ndcStr.matches("\\d*") && subscriberNumberStr.matches("\\d+")) {
                Integer ndc = ndcStr.isEmpty() ? null : Integer.parseInt(ndcStr);
                long subscriberNumber = Long.parseLong(subscriberNumberStr);

                StringBuilder sqlBuilder = new StringBuilder();
                sqlBuilder.append("SELECT ");
                sqlBuilder.append(" i.id as indicator_id, i.department_country, i.city_name, i.operator_id as indicator_operator_id, ");
                sqlBuilder.append(" s.ndc as series_ndc, s.initial_number as series_initial, s.final_number as series_final, ");
                sqlBuilder.append(" s.company as series_company ");
                sqlBuilder.append("FROM series s ");
                sqlBuilder.append("JOIN indicator i ON s.indicator_id = i.id ");

                if (isPrefixBandOk) {
                    sqlBuilder.append("JOIN band b ON b.prefix_id = :currentPrefixId AND b.active = true ");
                    sqlBuilder.append("JOIN band_indicator bi ON bi.band_id = b.id AND bi.indicator_id = i.id ");
                    sqlBuilder.append("WHERE b.origin_indicator_id IN (0, :originCommLocationIndicatorId) ");
                } else {
                    sqlBuilder.append("WHERE 1=1 ");
                }

                sqlBuilder.append("  AND i.active = true AND s.active = true ");
                sqlBuilder.append("  AND i.telephony_type_id = :telephonyTypeId ");
                if (ndc != null) {
                    sqlBuilder.append("  AND s.ndc = :ndc ");
                } else {
                    sqlBuilder.append("  AND (s.ndc = 0 OR s.ndc IS NULL) ");
                }
                if (telephonyTypeId != CdrProcessingConfig.TIPOTELE_INTERNACIONAL && telephonyTypeId != CdrProcessingConfig.TIPOTELE_SATELITAL) {
                    sqlBuilder.append(" AND i.origin_country_id = :originCountryId ");
                } else {
                    sqlBuilder.append(" AND i.origin_country_id IN (0, :originCountryId) ");
                }

                if (!isPrefixBandOk && currentPrefixId != null) {
                     sqlBuilder.append(" AND (i.operator_id = 0 OR i.operator_id IS NULL OR i.operator_id IN (SELECT p_sub.operator_id FROM prefix p_sub WHERE p_sub.id = :currentPrefixId)) ");
                }

                sqlBuilder.append("  AND s.initial_number <= :subscriberNum AND s.final_number >= :subscriberNum ");
                sqlBuilder.append("ORDER BY i.origin_country_id DESC, ");
                if (isPrefixBandOk) {
                    sqlBuilder.append(" b.origin_indicator_id DESC, ");
                }
                if (ndcLength > 0) {
                    sqlBuilder.append(" LENGTH(CAST(s.ndc AS TEXT)) DESC, ");
                }
                sqlBuilder.append(" (s.final_number - s.initial_number) ASC ");
                sqlBuilder.append("LIMIT 1");

                Query query = entityManager.createNativeQuery(sqlBuilder.toString());
                query.setParameter("telephonyTypeId", telephonyTypeId);
                if (ndc != null) {
                    query.setParameter("ndc", ndc);
                }
                query.setParameter("originCountryId", originCountryId);
                query.setParameter("subscriberNum", subscriberNumber);
                if (isPrefixBandOk) {
                    query.setParameter("currentPrefixId", currentPrefixId);
                    query.setParameter("originCommLocationIndicatorId", originCommLocationIndicatorId != null ? originCommLocationIndicatorId : 0L);
                } else if (currentPrefixId != null) {
                    query.setParameter("currentPrefixId", currentPrefixId);
                }


                try {
                    Object[] result = (Object[]) query.getSingleResult();
                    Map<String, Object> map = new HashMap<>();
                    Long indicatorIdVal = (result[0] instanceof Number) ? ((Number) result[0]).longValue() : null;
                    map.put("indicator_id", (indicatorIdVal != null && indicatorIdVal > 0) ? indicatorIdVal : null);
                    map.put("department_country", result[1]);
                    map.put("city_name", result[2]);
                    map.put("indicator_operator_id", result[3]);
                    map.put("series_ndc", result[4]);
                    map.put("series_initial", result[5]);
                    map.put("series_final", result[6]);
                    map.put("series_company", result[7]);
                    log.trace("Found indicator info for num {} (NDC: {}): ID={}", numberWithoutPrefix, ndcStr, map.get("indicator_id"));
                    return Optional.of(map);
                } catch (NoResultException e) {
                    log.trace("No indicator found for NDC '{}', subscriber '{}'", ndcStr, subscriberNumberStr);
                } catch (Exception e) {
                    log.error("Error finding indicator for num: {}, ndc: {}: {}", numberWithoutPrefix, ndcStr, e.getMessage(), e);
                }
            } else {
                log.trace("Skipping NDC check: NDC '{}' or Subscriber '{}' is not numeric.", ndcStr, subscriberNumberStr);
            }
        }
        log.trace("No indicator found for number: {}", numberWithoutPrefix);
        return Optional.empty();
    }


    
    public Map<String, Integer> findNdcMinMaxLength(Long telephonyTypeId, Long originCountryId) {
        Map<String, Integer> lengths = new HashMap<>();
        lengths.put("min", 0); lengths.put("max", 0);
        if (telephonyTypeId == null || originCountryId == null) return lengths;

        log.debug("Finding min/max NDC length for telephonyTypeId: {}, originCountryId: {}", telephonyTypeId, originCountryId);
        StringBuilder sqlBuilder = new StringBuilder();
        sqlBuilder.append("SELECT COALESCE(MIN(CASE WHEN s.ndc = 0 THEN 0 ELSE LENGTH(CAST(s.ndc AS TEXT)) END), 0) as min_len, ");
        sqlBuilder.append("       COALESCE(MAX(CASE WHEN s.ndc = 0 THEN 0 ELSE LENGTH(CAST(s.ndc AS TEXT)) END), 0) as max_len ");
        sqlBuilder.append("FROM series s ");
        sqlBuilder.append("JOIN indicator i ON s.indicator_id = i.id ");
        sqlBuilder.append("WHERE i.active = true AND s.active = true ");
        sqlBuilder.append("  AND i.telephony_type_id = :telephonyTypeId ");
        sqlBuilder.append("  AND i.origin_country_id IN (0, :originCountryId) ");
        sqlBuilder.append("  AND s.ndc IS NOT NULL ");

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

    
    public Optional<String> findCompanyForNationalSeries(int ndc, long subscriberNumber, Long originCountryId) {
        if (originCountryId == null) return Optional.empty();
        log.debug("Finding company for national series: NDC={}, Subscriber={}, OriginCountryId={}", ndc, subscriberNumber, originCountryId);

        Long nationalTelephonyTypeId = CdrProcessingConfig.TIPOTELE_NACIONAL;

        StringBuilder sqlBuilder = new StringBuilder();
        sqlBuilder.append("SELECT s.company as series_company ");
        sqlBuilder.append("FROM series s ");
        sqlBuilder.append("JOIN indicator i ON s.indicator_id = i.id ");
        sqlBuilder.append("WHERE i.active = true AND s.active = true ");
        sqlBuilder.append("  AND i.telephony_type_id = :nationalTelephonyTypeId ");
        sqlBuilder.append("  AND s.ndc = :ndc ");
        sqlBuilder.append("  AND s.initial_number <= :subscriberNum AND s.final_number >= :subscriberNum ");
        sqlBuilder.append("  AND i.origin_country_id IN (0, :originCountryId) ");

        sqlBuilder.append("ORDER BY i.origin_country_id DESC, LENGTH(CAST(s.ndc AS TEXT)) DESC, (s.final_number - s.initial_number) ASC ");
        sqlBuilder.append("LIMIT 1");

        Query query = entityManager.createNativeQuery(sqlBuilder.toString(), String.class);
        query.setParameter("nationalTelephonyTypeId", nationalTelephonyTypeId);
        query.setParameter("ndc", ndc);
        query.setParameter("subscriberNum", subscriberNumber);
        query.setParameter("originCountryId", originCountryId);

        try {
            String company = (String) query.getSingleResult();
            return Optional.ofNullable(company);
        } catch (NoResultException e) {
            log.trace("No series company found for national lookup: NDC={}, Subscriber={}, OriginCountryId={}", ndc, subscriberNumber, originCountryId);
            return Optional.empty();
        } catch (Exception e) {
            log.error("Error finding series company for national lookup: NDC={}, Subscriber={}, OriginCountryId={}: {}", ndc, subscriberNumber, originCountryId, e.getMessage(), e);
            return Optional.empty();
        }
    }


    
    public Optional<Map<String, Object>> findBaseRateForPrefix(Long prefixId) {
        if (prefixId == null) return Optional.empty();
        log.debug("Finding base rate for prefixId: {}", prefixId);
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
            log.warn("No active prefix found for ID: {}", prefixId);
            return Optional.empty();
        } catch (Exception e) {
            log.error("Error finding base rate for prefixId: {}", prefixId, e);
            return Optional.empty();
        }
    }

    
    public Optional<Map<String, Object>> findBandByPrefixAndIndicator(Long prefixId, Long indicatorId, Long originIndicatorId) {
        if (prefixId == null) {
            log.trace("findBandByPrefixAndIndicator - Invalid input: prefixId is null");
            return Optional.empty();
        }
        Long effectiveOriginIndicatorId = originIndicatorId != null ? originIndicatorId : 0L;
        Long effectiveIndicatorId = indicatorId;

        log.debug("Finding band for prefixId: {}, effectiveIndicatorId: {}, effectiveOriginIndicatorId: {}", prefixId, effectiveIndicatorId, effectiveOriginIndicatorId);

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
            log.trace("Found band {} for prefix {}, indicator {}", map.get("band_id"), prefixId, effectiveIndicatorId);
            return Optional.of(map);
        } catch (NoResultException e) {
            log.trace("No matching band found for prefix {}, indicator {}", prefixId, effectiveIndicatorId);
            return Optional.empty();
        } catch (Exception e) {
            log.error("Error finding band for prefixId: {}, indicatorId: {}, originIndicatorId: {}", prefixId, effectiveIndicatorId, effectiveOriginIndicatorId, e);
            return Optional.empty();
        }
    }

    // --- Trunk Lookups ---

    
    public Optional<Trunk> findTrunkByCode(String trunkCode, Long commLocationId) {
        if (!StringUtils.hasText(trunkCode) || commLocationId == null) {
            log.trace("findTrunkByCode - Invalid input: trunkCode={}, commLocationId={}", trunkCode, commLocationId);
            return Optional.empty();
        }
        log.debug("Finding trunk by code: '{}', commLocationId: {}", trunkCode, commLocationId);
        String sql = "SELECT t.* FROM trunk t " +
                "WHERE t.active = true " +
                "  AND UPPER(t.name) = UPPER(:trunkCode) " +
                "  AND (t.comm_location_id = :commLocationId OR t.comm_location_id = 0 OR t.comm_location_id IS NULL) " +
                "ORDER BY CASE WHEN t.comm_location_id IS NOT NULL AND t.comm_location_id != 0 THEN 0 ELSE 1 END ASC, t.id DESC " +
                "LIMIT 1";
        Query query = entityManager.createNativeQuery(sql, Trunk.class);
        query.setParameter("trunkCode", trunkCode);
        query.setParameter("commLocationId", commLocationId);
        try {
            Trunk trunk = (Trunk) query.getSingleResult();
            return Optional.of(trunk);
        } catch (NoResultException e) {
            log.trace("No active trunk found for code '{}' at location {} or globally", trunkCode, commLocationId);
            return Optional.empty();
        } catch (Exception e) {
            log.error("Error finding trunk for code: '{}', loc: {}", trunkCode, commLocationId, e);
            return Optional.empty();
        }
    }

    
    public Optional<TrunkRate> findTrunkRate(Long trunkId, Long operatorId, Long telephonyTypeId) {
        if (trunkId == null || operatorId == null || telephonyTypeId == null) {
            log.trace("findTrunkRate - Invalid input: trunkId={}, opId={}, ttId={}", trunkId, operatorId, telephonyTypeId);
            return Optional.empty();
        }
        log.debug("Finding trunk rate for trunkId: {}, operatorId: {}, telephonyTypeId: {}", trunkId, operatorId, telephonyTypeId);
        String sql = "SELECT tr.* FROM trunk_rate tr " +
                "WHERE tr.active = true " +
                "  AND tr.trunk_id = :trunkId " +
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
            log.trace("No active TrunkRate found for trunkId: {}, opId: {}, ttId: {}", trunkId, operatorId, telephonyTypeId);
            return Optional.empty();
        } catch (Exception e) {
            log.error("Error finding trunk rate for trunkId: {}, opId: {}, ttId: {}", trunkId, operatorId, telephonyTypeId, e);
            return Optional.empty();
        }
    }


    
    public Optional<TrunkRule> findTrunkRule(String trunkName, Long telephonyTypeId, Long indicatorId, Long originCommLocationIndicatorId) {
        if (telephonyTypeId == null) {
            log.trace("findTrunkRule - Invalid input: ttId is null");
            return Optional.empty();
        }
        Long effectiveOriginIndicatorId = originCommLocationIndicatorId != null ? originCommLocationIndicatorId : 0L;
        String indicatorIdStr = (indicatorId != null && indicatorId > 0) ? String.valueOf(indicatorId) : null;

        log.debug("Finding trunk rule for trunkName: '{}', ttId: {}, indIdStr: {}, effectiveOriginIndId: {}",
                trunkName, telephonyTypeId, indicatorIdStr, effectiveOriginIndicatorId);

        StringBuilder sqlBuilder = new StringBuilder();
        sqlBuilder.append("SELECT tr.* ");
        sqlBuilder.append("FROM trunk_rule tr ");
        sqlBuilder.append("LEFT JOIN trunk t ON tr.trunk_id = t.id AND t.active = true ");
        sqlBuilder.append("WHERE tr.active = true ");
        sqlBuilder.append("  AND (tr.trunk_id = 0 OR tr.trunk_id IS NULL OR (t.name = :trunkName)) ");
        sqlBuilder.append("  AND tr.telephony_type_id = :telephonyTypeId ");
        sqlBuilder.append("  AND (tr.origin_indicator_id = 0 OR tr.origin_indicator_id IS NULL OR tr.origin_indicator_id = :originIndicatorId) ");

        if (indicatorIdStr != null) {
            sqlBuilder.append("  AND (tr.indicator_ids = '' OR tr.indicator_ids IS NULL OR tr.indicator_ids = :indicatorIdStr OR tr.indicator_ids LIKE :indicatorIdStrLikeStart OR tr.indicator_ids LIKE :indicatorIdStrLikeEnd OR tr.indicator_ids LIKE :indicatorIdStrLikeMiddle) ");
        } else {
            sqlBuilder.append("  AND (tr.indicator_ids = '' OR tr.indicator_ids IS NULL) ");
        }

        sqlBuilder.append("ORDER BY CASE WHEN tr.trunk_id IS NOT NULL AND tr.trunk_id != 0 THEN 0 ELSE 1 END ASC, ");
        sqlBuilder.append("         CASE WHEN tr.origin_indicator_id IS NOT NULL AND tr.origin_indicator_id != 0 THEN 0 ELSE 1 END ASC, ");
        if (indicatorIdStr != null) {
            sqlBuilder.append("     CASE WHEN tr.indicator_ids = :indicatorIdStr THEN 0 ");
            sqlBuilder.append("          WHEN tr.indicator_ids LIKE :indicatorIdStrLikeStart THEN 1 ");
            sqlBuilder.append("          WHEN tr.indicator_ids LIKE :indicatorIdStrLikeMiddle THEN 2 ");
            sqlBuilder.append("          WHEN tr.indicator_ids LIKE :indicatorIdStrLikeEnd THEN 3 ");
            sqlBuilder.append("          WHEN tr.indicator_ids = '' OR tr.indicator_ids IS NULL THEN 5 ");
            sqlBuilder.append("          ELSE 4 ");
            sqlBuilder.append("     END ASC, ");
        } else {
             sqlBuilder.append("     CASE WHEN tr.indicator_ids = '' OR tr.indicator_ids IS NULL THEN 0 ELSE 1 END ASC, ");
        }
        sqlBuilder.append("     LENGTH(tr.indicator_ids) DESC ");
        sqlBuilder.append("LIMIT 1");

        Query query = entityManager.createNativeQuery(sqlBuilder.toString(), TrunkRule.class);
        query.setParameter("trunkName", trunkName != null ? trunkName : "");
        query.setParameter("telephonyTypeId", telephonyTypeId);
        query.setParameter("originIndicatorId", effectiveOriginIndicatorId);
        if (indicatorIdStr != null) {
            query.setParameter("indicatorIdStr", indicatorIdStr);
            query.setParameter("indicatorIdStrLikeStart", indicatorIdStr + ",%");
            query.setParameter("indicatorIdStrLikeEnd", "%," + indicatorIdStr);
            query.setParameter("indicatorIdStrLikeMiddle", "%," + indicatorIdStr + ",%");
        }

        try {
            TrunkRule rule = (TrunkRule) query.getSingleResult();
            return Optional.of(rule);
        } catch (NoResultException e) {
            log.trace("No matching TrunkRule found for trunk: '{}', ttId: {}, indId: {}, originIndId: {}", trunkName, telephonyTypeId, indicatorIdStr, effectiveOriginIndicatorId);
            return Optional.empty();
        } catch (Exception e) {
            log.error("Error finding trunk rule for trunk: '{}', ttId: {}, indId: {}, originIndId: {}", trunkName, telephonyTypeId, indicatorIdStr, effectiveOriginIndicatorId, e);
            return Optional.empty();
        }
    }

    // --- Special Rules & Rates ---

    
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

    
    public List<PbxSpecialRule> findPbxSpecialRuleCandidates(Long commLocationId, int direction) {
        if (commLocationId == null) return Collections.emptyList();
        log.debug("Finding PBX special rule candidates for commLocationId: {}, direction: {}", commLocationId, direction);
        String sql = "SELECT p.* FROM pbx_special_rule p " +
                "WHERE p.active = true " +
                "  AND (p.comm_location_id = :commLocationId OR p.comm_location_id IS NULL OR p.comm_location_id = 0) " +
                "  AND p.direction IN (0, :direction) " +
                "ORDER BY CASE WHEN p.comm_location_id IS NOT NULL AND p.comm_location_id != 0 THEN 0 ELSE 1 END ASC, LENGTH(p.search_pattern) DESC";
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

    
    public List<Map<String, Object>> findSpecialRateValues(Long telephonyTypeId, Long operatorId, Long bandId, Long originCommLocationIndicatorId, LocalDateTime callDateTime) {
        if (callDateTime == null) return Collections.emptyList();
        Long effectiveOriginIndicatorId = originCommLocationIndicatorId != null ? originCommLocationIndicatorId : 0L;
        Long effectiveTelephonyTypeId = telephonyTypeId != null ? telephonyTypeId : 0L;
        Long effectiveOperatorId = operatorId != null ? operatorId : 0L;
        Long effectiveBandId = bandId != null ? bandId : 0L;

        log.debug("Finding special rate values for ttId: {}, opId: {}, bandId: {}, effectiveOriginIndId: {}, dateTime: {}",
                effectiveTelephonyTypeId, effectiveOperatorId, effectiveBandId, effectiveOriginIndicatorId, callDateTime);

        int dayOfWeek = callDateTime.getDayOfWeek().getValue();
        boolean isHoliday = false; // Holiday logic omitted

        String dayColumn;
        switch (dayOfWeek) {
            case 1: dayColumn = "srv.monday_enabled"; break;
            case 2: dayColumn = "srv.tuesday_enabled"; break;
            case 3: dayColumn = "srv.wednesday_enabled"; break;
            case 4: dayColumn = "srv.thursday_enabled"; break;
            case 5: dayColumn = "srv.friday_enabled"; break;
            case 6: dayColumn = "srv.saturday_enabled"; break;
            case 7: dayColumn = "srv.sunday_enabled"; break;
            default:
                log.error("Invalid day of week: {}", dayOfWeek);
                return Collections.emptyList();
        }

        StringBuilder sqlBuilder = new StringBuilder();
        sqlBuilder.append("SELECT srv.id, srv.name, srv.rate_value, srv.value_type, ");
        sqlBuilder.append("       srv.includes_vat, srv.hours_specification, ");
        sqlBuilder.append("       COALESCE(p.vat_value, 0) as prefix_vat_value ");
        sqlBuilder.append("FROM special_rate_value srv ");
        sqlBuilder.append("LEFT JOIN prefix p ON srv.telephony_type_id = p.telephone_type_id AND srv.operator_id = p.operator_id AND p.active = true ");
        sqlBuilder.append("WHERE srv.active = true ");
        sqlBuilder.append("  AND (srv.telephony_type_id = :telephonyTypeId OR srv.telephony_type_id IS NULL OR srv.telephony_type_id = 0) ");
        sqlBuilder.append("  AND (srv.operator_id = :operatorId OR srv.operator_id IS NULL OR srv.operator_id = 0) ");
        sqlBuilder.append("  AND (srv.band_id = :bandId OR srv.band_id IS NULL OR srv.band_id = 0) ");
        sqlBuilder.append("  AND (srv.origin_indicator_id = :originIndicatorId OR srv.origin_indicator_id IS NULL OR srv.origin_indicator_id = 0) ");
        sqlBuilder.append("  AND (srv.valid_from IS NULL OR srv.valid_from <= :callDateTime) ");
        sqlBuilder.append("  AND (srv.valid_to IS NULL OR srv.valid_to >= :callDateTime) ");
        sqlBuilder.append("  AND (").append(dayColumn).append(" = true ");
        if (isHoliday) {
            sqlBuilder.append("OR srv.holiday_enabled = true");
        }
        sqlBuilder.append(") ");
        sqlBuilder.append("ORDER BY CASE WHEN srv.origin_indicator_id IS NOT NULL AND srv.origin_indicator_id != 0 THEN 0 ELSE 1 END ASC, ");
        sqlBuilder.append("         CASE WHEN srv.telephony_type_id IS NOT NULL AND srv.telephony_type_id != 0 THEN 0 ELSE 1 END ASC, ");
        sqlBuilder.append("         CASE WHEN srv.operator_id IS NOT NULL AND srv.operator_id != 0 THEN 0 ELSE 1 END ASC, ");
        sqlBuilder.append("         CASE WHEN srv.band_id IS NOT NULL AND srv.band_id != 0 THEN 0 ELSE 1 END ASC");


        Query query = entityManager.createNativeQuery(sqlBuilder.toString());
        query.setParameter("telephonyTypeId", effectiveTelephonyTypeId);
        query.setParameter("operatorId", effectiveOperatorId);
        query.setParameter("bandId", effectiveBandId);
        query.setParameter("originIndicatorId", effectiveOriginIndicatorId);
        query.setParameter("callDateTime", callDateTime);

        try {
            List<Object[]> results = query.getResultList();
            List<Map<String, Object>> mappedResults = new ArrayList<>();
            for (Object[] row : results) {
                Map<String, Object> map = new HashMap<>();
                map.put("id", row[0]);
                map.put("name", row[1]);
                map.put("rate_value", row[2]);
                map.put("value_type", row[3]);
                map.put("includes_vat", row[4]);
                map.put("hours_specification", row[5]);
                map.put("prefix_vat_value", row[6]);
                mappedResults.add(map);
            }
            log.trace("Found {} special rate candidates", mappedResults.size());
            return mappedResults;
        } catch (Exception e) {
            log.error("Error finding special rate values for ttId: {}, opId: {}, bandId: {}, originIndId: {}, dateTime: {}",
                    effectiveTelephonyTypeId, effectiveOperatorId, effectiveBandId, effectiveOriginIndicatorId, callDateTime, e);
            return Collections.emptyList();
        }
    }

    
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
                "  AND (ss.indicator_id = :indicatorId OR ss.indicator_id = 0 OR ss.indicator_id IS NULL) " +
                "  AND ss.origin_country_id = :originCountryId " +
                "ORDER BY CASE WHEN ss.indicator_id IS NOT NULL AND ss.indicator_id != 0 THEN 0 ELSE 1 END ASC, ss.id DESC " +
                "LIMIT 1";
        Query query = entityManager.createNativeQuery(sql, SpecialService.class);
        query.setParameter("phoneNumber", phoneNumber);
        query.setParameter("indicatorId", effectiveIndicatorId);
        query.setParameter("originCountryId", originCountryId);
        try {
            SpecialService service = (SpecialService) query.getSingleResult();
            return Optional.of(service);
        } catch (NoResultException e) {
            log.trace("No active special service found for number: {}, indId: {}, originId: {}", phoneNumber, effectiveIndicatorId, originCountryId);
            return Optional.empty();
        } catch (Exception e) {
            log.error("Error finding special service for number: {}, indId: {}, originId: {}", phoneNumber, effectiveIndicatorId, originCountryId, e);
            return Optional.empty();
        }
    }

    // --- Other Lookups ---

    
    public Optional<String> findPbxPrefixByCommLocationId(Long commLocationId) {
        if (commLocationId == null) return Optional.empty();
        log.debug("Finding PBX prefix for commLocationId: {}", commLocationId);
        String sql = "SELECT pbx_prefix FROM communication_location WHERE id = :id AND active = true";
        Query query = entityManager.createNativeQuery(sql, String.class);
        query.setParameter("id", commLocationId);
        try {
            return Optional.ofNullable((String) query.getSingleResult());
        } catch (NoResultException e) {
            log.trace("No active CommunicationLocation found for ID: {}", commLocationId);
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
                "WHERE telephony_type_id = :telephonyTypeId AND origin_country_id = :originCountryId AND active = true " +
                "LIMIT 1";
        Query query = entityManager.createNativeQuery(sql);
        query.setParameter("telephonyTypeId", telephonyTypeId);
        query.setParameter("originCountryId", originCountryId);
        try {
            Object[] result = (Object[]) query.getSingleResult();
            config.put("min", result[0] != null ? ((Number) result[0]).intValue() : 0);
            config.put("max", result[1] != null ? ((Number) result[1]).intValue() : 0);
        } catch (NoResultException e) {
            log.trace("No active TelephonyTypeConfig found for type {}, country {}. Using defaults 0,0.", telephonyTypeId, originCountryId);
        } catch (Exception e) {
            log.error("Error finding min/max config for telephonyTypeId: {}, originCountryId: {}", telephonyTypeId, originCountryId, e);
        }
        return config;
    }

    
    public Optional<Operator> findOperatorByTelephonyTypeAndOrigin(Long telephonyTypeId, Long originCountryId) {
        if (telephonyTypeId == null || originCountryId == null) return Optional.empty();
        log.debug("Finding operator for telephonyTypeId: {}, originCountryId: {}", telephonyTypeId, originCountryId);
        String sql = "SELECT op.* FROM operator op " +
                "JOIN prefix p ON p.operator_id = op.id " +
                "WHERE p.telephone_type_id = :telephonyTypeId " +
                "  AND op.origin_country_id = :originCountryId " +
                "  AND op.active = true " +
                "  AND p.active = true " +
                "ORDER BY op.id ASC " +
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

    
    public Optional<Map<String, Object>> findInternalTariff(Long telephonyTypeId) {
        if (telephonyTypeId == null) return Optional.empty();
        log.debug("Finding internal tariff for telephonyTypeId: {}", telephonyTypeId);
        String sql = "SELECT p.base_value, p.vat_included, p.vat_value, tt.name as telephony_type_name " +
                "FROM prefix p " +
                "JOIN telephony_type tt ON p.telephone_type_id = tt.id " +
                "WHERE p.telephone_type_id = :telephonyTypeId " +
                "  AND p.active = true AND tt.active = true " +
                "ORDER BY p.id ASC " +
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

    
    public boolean isLocalExtended(Integer destinationNdc, Long originIndicatorId) {
        if (destinationNdc == null || originIndicatorId == null || originIndicatorId <= 0) {
            return false;
        }
        log.debug("Checking if NDC {} is local extended for origin indicator {}", destinationNdc, originIndicatorId);
        String sql = "SELECT COUNT(s.id) FROM series s WHERE s.indicator_id = :originIndicatorId AND s.ndc = :destinationNdc AND s.active = true";
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

    public boolean isPrefixUniqueToOperator(String prefixCode, Long telephonyTypeId, Long originCountryId) {
        if (!StringUtils.hasText(prefixCode) || telephonyTypeId == null || originCountryId == null) {
            return false;
        }
        log.debug("Checking if prefix {} is unique for type {} in country {}", prefixCode, telephonyTypeId, originCountryId);
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
            log.trace("Prefix {} unique check for type {} in country {}: {}", prefixCode, telephonyTypeId, originCountryId, isUnique);
            return isUnique;
        } catch (Exception e) {
            log.error("Error checking prefix uniqueness for code {}, type {}, country {}: {}", prefixCode, telephonyTypeId, originCountryId, e.getMessage(), e);
            return false;
        }
    }


    // --- Simple Find By ID methods ---

    
    public Optional<CommunicationLocation> findCommunicationLocationById(Long id) {
        if (id == null || id <=0) return Optional.empty();
        String sql = "SELECT cl.* FROM communication_location cl WHERE cl.id = :id AND cl.active = true";
        Query query = entityManager.createNativeQuery(sql, CommunicationLocation.class);
        query.setParameter("id", id);
        try { return Optional.of((CommunicationLocation) query.getSingleResult()); }
        catch (NoResultException e) { return Optional.empty(); }
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

    
    public Optional<Operator> findOperatorById(Long id) {
        if (id == null || id <= 0) return Optional.empty();
        String sql = "SELECT o.* FROM operator o WHERE o.id = :id AND o.active = true";
        Query query = entityManager.createNativeQuery(sql, Operator.class);
        query.setParameter("id", id);
        try { return Optional.of((Operator) query.getSingleResult()); }
        catch (NoResultException e) { return Optional.empty(); }
    }

    
    public Optional<TelephonyType> findTelephonyTypeById(Long id) {
        if (id == null || id <= 0) return Optional.empty();
        String sql = "SELECT tt.* FROM telephony_type tt WHERE tt.id = :id AND tt.active = true";
        Query query = entityManager.createNativeQuery(sql, TelephonyType.class);
        query.setParameter("id", id);
        try { return Optional.of((TelephonyType) query.getSingleResult()); }
        catch (NoResultException e) { return Optional.empty(); }
    }

    
    public Optional<OriginCountry> findOriginCountryById(Long id) {
        if (id == null || id <= 0) return Optional.empty();
        String sql = "SELECT oc.* FROM origin_country oc WHERE oc.id = :id AND oc.active = true";
        Query query = entityManager.createNativeQuery(sql, OriginCountry.class);
        query.setParameter("id", id);
        try { return Optional.of((OriginCountry) query.getSingleResult()); }
        catch (NoResultException e) { return Optional.empty(); }
    }

    
    public Optional<Subdivision> findSubdivisionById(Long id) {
        if (id == null || id <= 0) return Optional.empty();
        String sql = "SELECT s.* FROM subdivision s WHERE s.id = :id AND s.active = true";
        Query query = entityManager.createNativeQuery(sql, Subdivision.class);
        query.setParameter("id", id);
        try { return Optional.of((Subdivision) query.getSingleResult()); }
        catch (NoResultException e) { return Optional.empty(); }
    }

    
    public Optional<CostCenter> findCostCenterById(Long id) {
        if (id == null || id <= 0) return Optional.empty();
        String sql = "SELECT cc.* FROM cost_center cc WHERE cc.id = :id AND cc.active = true";
        Query query = entityManager.createNativeQuery(sql, CostCenter.class);
        query.setParameter("id", id);
        try { return Optional.of((CostCenter) query.getSingleResult()); }
        catch (NoResultException e) { return Optional.empty(); }
    }
}