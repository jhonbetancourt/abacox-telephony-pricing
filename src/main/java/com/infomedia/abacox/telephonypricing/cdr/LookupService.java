// FILE: com/infomedia/abacox/telephonypricing/cdr/LookupService.java
package com.infomedia.abacox.telephonypricing.cdr;

import com.infomedia.abacox.telephonypricing.entity.*;
import jakarta.persistence.*;
import lombok.extern.log4j.Log4j2;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;

@Service
@Log4j2
@Transactional(readOnly = true)
public class LookupService {

    @PersistenceContext
    private EntityManager entityManager;

    public Optional<CommunicationLocation> findCommunicationLocationById(Long id) {
        if (id == null) return Optional.empty();
        Query query = entityManager.createNativeQuery("SELECT * FROM communication_location WHERE id = :id AND active = true", CommunicationLocation.class);
        query.setParameter("id", id);
        try {
            return Optional.ofNullable((CommunicationLocation) query.getSingleResult());
        } catch (NoResultException e) {
            return Optional.empty();
        }
    }

    public InternalExtensionLimitsDto getInternalExtensionLimits(Long originCountryId, Long commLocationId) {
        // ... (Implementation from previous response, seems okay) ...
        // For brevity, assuming it's the same as before.
        // Make sure it queries DB each time as per "no caching" rule.

        int minLength = 100; // Default high to be overridden by smaller found lengths
        int maxLength = 0;   // Default low to be overridden by larger found lengths
        
        // Query for employee extensions
        String empSql = "SELECT COALESCE(MAX(LENGTH(e.extension)), 0) AS max_len, COALESCE(MIN(LENGTH(e.extension)), 100) AS min_len " +
                        "FROM employee e " +
                        "LEFT JOIN communication_location cl ON e.communication_location_id = cl.id " +
                        "LEFT JOIN indicator i ON cl.indicator_id = i.id " +
                        "WHERE e.active = true AND (cl.id IS NULL OR cl.active = true) AND (i.id IS NULL OR i.active = true) " +
                        "  AND e.extension ~ '^[0-9]+$' AND e.extension NOT LIKE '0%' AND LENGTH(e.extension) < 8 ";
        if (originCountryId != null) empSql += " AND (i.id IS NULL OR i.origin_country_id = :originCountryId) ";
        if (commLocationId != null) empSql += " AND (e.communication_location_id IS NULL OR e.communication_location_id = :commLocationId) ";
        
        Query empQuery = entityManager.createNativeQuery(empSql);
        if (originCountryId != null) empQuery.setParameter("originCountryId", originCountryId);
        if (commLocationId != null) empQuery.setParameter("commLocationId", commLocationId);

        try {
            Object[] empResult = (Object[]) empQuery.getSingleResult();
            if (empResult[0] != null) maxLength = Math.max(maxLength, ((Number) empResult[0]).intValue());
            if (empResult[1] != null) minLength = Math.min(minLength, ((Number) empResult[1]).intValue());
        } catch (NoResultException e) {
            // No results, defaults will be used or values from range query
        }


        // Query for extension ranges
        String rangeSql = "SELECT COALESCE(MAX(LENGTH(er.range_end::text)), 0) AS max_len, COALESCE(MIN(LENGTH(er.range_start::text)), 100) AS min_len " +
                          "FROM extension_range er " +
                          "LEFT JOIN communication_location cl ON er.comm_location_id = cl.id " +
                          "LEFT JOIN indicator i ON cl.indicator_id = i.id " +
                          "WHERE er.active = true AND (cl.id IS NULL OR cl.active = true) AND (i.id IS NULL OR i.active = true) " +
                          "  AND er.range_start::text ~ '^[0-9]+$' AND er.range_end::text ~ '^[0-9]+$' " +
                          "  AND LENGTH(er.range_start::text) < 8 AND LENGTH(er.range_end::text) < 8 " +
                          "  AND er.range_end >= er.range_start ";
        if (originCountryId != null) rangeSql += " AND (i.id IS NULL OR i.origin_country_id = :originCountryId) ";
        if (commLocationId != null) rangeSql += " AND (er.comm_location_id IS NULL OR er.comm_location_id = :commLocationId) ";

        Query rangeQuery = entityManager.createNativeQuery(rangeSql);
        if (originCountryId != null) rangeQuery.setParameter("originCountryId", originCountryId);
        if (commLocationId != null) rangeQuery.setParameter("commLocationId", commLocationId);
        
        try {
            Object[] rangeResult = (Object[]) rangeQuery.getSingleResult();
            if (rangeResult[0] != null) maxLength = Math.max(maxLength, ((Number) rangeResult[0]).intValue());
            if (rangeResult[1] != null) minLength = Math.min(minLength, ((Number) rangeResult[1]).intValue());
        } catch (NoResultException e) {
             // No results, defaults will be used or values from employee query
        }
        
        // Final adjustments for min/max length
        if (maxLength == 0) maxLength = 7; // Default max if nothing found
        if (minLength == 100) minLength = 1; // Default min if nothing found
        if (minLength > maxLength) minLength = maxLength; // Ensure min is not greater than max

        // Calculate numeric min/max based on determined lengths
        long minNumericValue = (minLength > 0 && minLength < 19) ? (long) Math.pow(10, minLength - 1) : 0L;
        long maxNumericValue = (maxLength > 0 && maxLength < 19) ? Long.parseLong("9".repeat(maxLength)) : Long.MAX_VALUE;

        // Query for special extensions (non-numeric, very long, or starting with 0, #, *)
        String specialExtSql = "SELECT DISTINCT e.extension FROM employee e " +
                               "LEFT JOIN communication_location cl ON e.communication_location_id = cl.id " +
                               "LEFT JOIN indicator i ON cl.indicator_id = i.id " +
                               "WHERE e.active = true AND (cl.id IS NULL OR cl.active = true) AND (i.id IS NULL OR i.active = true) " +
                               "  AND (LENGTH(e.extension) >= 8 OR e.extension LIKE '0%' OR e.extension LIKE '*%' OR e.extension LIKE '#%' OR e.extension ~ '[^0-9#*+]') "; // Allow #*+ in special
        if (originCountryId != null) specialExtSql += " AND (i.id IS NULL OR i.origin_country_id = :originCountryId) ";
        if (commLocationId != null) specialExtSql += " AND (e.communication_location_id IS NULL OR e.communication_location_id = :commLocationId) ";
        
        Query specialExtQuery = entityManager.createNativeQuery(specialExtSql);
        if (originCountryId != null) specialExtQuery.setParameter("originCountryId", originCountryId);
        if (commLocationId != null) specialExtQuery.setParameter("commLocationId", commLocationId);
        
        @SuppressWarnings("unchecked")
        List<String> specialExtensions = specialExtQuery.getResultList();

        InternalExtensionLimitsDto currentInternalLimits = new InternalExtensionLimitsDto(minLength, maxLength, minNumericValue, maxNumericValue, specialExtensions);
        log.debug("Calculated InternalExtensionLimits for originCountryId={}, commLocationId={}: {}", originCountryId, commLocationId, currentInternalLimits);
        return currentInternalLimits;
    }

    public Optional<Employee> findEmployeeByExtension(String extension, Long commLocationId, InternalExtensionLimitsDto limits) {
        // ... (Implementation from previous response, seems okay) ...
        // For brevity, assuming it's the same as before.
        if (extension == null || extension.isEmpty() || commLocationId == null || limits == null) return Optional.empty();
        
        String cleanedExtension = CdrHelper.cleanPhoneNumber(extension); // Basic cleaning for lookup

        // Prioritize special extensions if they match
        if (limits.getSpecialExtensions() != null && limits.getSpecialExtensions().contains(cleanedExtension)) {
            // Perform a direct lookup for this specific "special" extension
            String sqlSpecial = "SELECT * FROM employee WHERE extension = :extension AND communication_location_id = :commLocationId AND active = true";
            Query querySpecial = entityManager.createNativeQuery(sqlSpecial, Employee.class);
            querySpecial.setParameter("extension", cleanedExtension);
            querySpecial.setParameter("commLocationId", commLocationId);
            try {
                return Optional.ofNullable((Employee) querySpecial.getSingleResult());
            } catch (NoResultException e) {
                // Fall through to range check if it's numeric, or fail if not.
                if (!CdrHelper.isNumeric(cleanedExtension)) return Optional.empty();
            }
        }
        
        // Check if it's a "potential" standard extension based on numeric value and length
        if (CdrHelper.isPotentialExtension(cleanedExtension, limits)) {
            String sql = "SELECT * FROM employee WHERE extension = :extension AND communication_location_id = :commLocationId AND active = true";
            Query query = entityManager.createNativeQuery(sql, Employee.class);
            query.setParameter("extension", cleanedExtension);
            query.setParameter("commLocationId", commLocationId);
            try {
                return Optional.ofNullable((Employee) query.getSingleResult());
            } catch (NoResultException e) {
                // Not found as a direct extension, fall through to range check (if numeric)
            }
        }
        
        // If numeric and not found directly (or not a standard extension but numeric), check ranges
        if (CdrHelper.isNumeric(cleanedExtension)) {
            return findEmployeeByExtensionRange(cleanedExtension, commLocationId, limits);
        }

        return Optional.empty(); // Not found
    }

    public Optional<Employee> findEmployeeByAuthCode(String authCode, Long commLocationId) {
        // ... (Implementation from previous response, seems okay) ...
        // For brevity, assuming it's the same as before.
        if (authCode == null || authCode.isEmpty() || commLocationId == null) return Optional.empty();
        String sql = "SELECT * FROM employee WHERE auth_code = :authCode AND communication_location_id = :commLocationId AND active = true";
        Query query = entityManager.createNativeQuery(sql, Employee.class);
        query.setParameter("authCode", authCode);
        query.setParameter("commLocationId", commLocationId);
        try {
            return Optional.ofNullable((Employee) query.getSingleResult());
        } catch (NoResultException e) {
            return Optional.empty();
        }
    }
    
    @SuppressWarnings("unchecked")
    public Optional<Employee> findEmployeeByExtensionRange(String extension, Long commLocationId, InternalExtensionLimitsDto limits) {
        // ... (Implementation from previous response, seems okay) ...
        // For brevity, assuming it's the same as before.
        if (!CdrHelper.isNumeric(extension) || commLocationId == null) return Optional.empty();
        long extNumeric;
        try {
            extNumeric = Long.parseLong(extension);
        } catch (NumberFormatException e) {
            return Optional.empty(); // Should not happen if isNumeric passed
        }

        String sql = "SELECT er.* FROM extension_range er " +
                     "WHERE er.comm_location_id = :commLocationId " +
                     "AND er.range_start <= :extNumeric AND er.range_end >= :extNumeric " +
                     "AND er.active = true " +
                     "ORDER BY (er.range_end - er.range_start) ASC, er.id"; // Smallest range first

        Query query = entityManager.createNativeQuery(sql, ExtensionRange.class);
        query.setParameter("commLocationId", commLocationId);
        query.setParameter("extNumeric", extNumeric);

        List<ExtensionRange> ranges = query.getResultList();
        if (!ranges.isEmpty()) {
            ExtensionRange bestRange = ranges.get(0); 
            // Create a "virtual" employee based on the range
            Employee virtualEmployee = new Employee(); 
            virtualEmployee.setId(null); // Not a persisted entity
            virtualEmployee.setExtension(extension);
            virtualEmployee.setCommunicationLocationId(commLocationId);
            // Eagerly fetch related entities for the virtual employee if IDs are present
            if (bestRange.getSubdivisionId() != null) {
                findSubdivisionById(bestRange.getSubdivisionId()).ifPresent(virtualEmployee::setSubdivision);
                virtualEmployee.setSubdivisionId(bestRange.getSubdivisionId());
            }
            if (bestRange.getCostCenterId() != null) {
                findCostCenterById(bestRange.getCostCenterId()).ifPresent(virtualEmployee::setCostCenter);
                virtualEmployee.setCostCenterId(bestRange.getCostCenterId());
            }
            virtualEmployee.setName((bestRange.getPrefix() != null ? bestRange.getPrefix() : "") + " " + extension + " (Range)");
            virtualEmployee.setActive(true); // Assumed active as it matched an active range
            return Optional.of(virtualEmployee);
        }
        return Optional.empty();
    }


    public Optional<Indicator> findIndicatorById(Long id) {
        // ... (Implementation from previous response, seems okay) ...
        // For brevity, assuming it's the same as before.
        if (id == null) return Optional.empty();
        Query query = entityManager.createNativeQuery("SELECT * FROM indicator WHERE id = :id AND active = true", Indicator.class);
        query.setParameter("id", id);
        try {
            return Optional.ofNullable((Indicator) query.getSingleResult());
        } catch (NoResultException e) {
            return Optional.empty();
        }
    }

    public Optional<Operator> findOperatorById(Long id) {
        // ... (Implementation from previous response, seems okay) ...
        // For brevity, assuming it's the same as before.
        if (id == null) return Optional.empty();
        Query query = entityManager.createNativeQuery("SELECT * FROM operator WHERE id = :id AND active = true", Operator.class);
        query.setParameter("id", id);
        try {
            return Optional.ofNullable((Operator) query.getSingleResult());
        } catch (NoResultException e) {
            return Optional.empty();
        }
    }
    
    public Optional<Operator> findInternalOperatorByTelephonyType(Long telephonyTypeId, Long originCountryId) {
        // ... (Implementation from previous response, seems okay) ...
        // For brevity, assuming it's the same as before.
        if (telephonyTypeId == null || originCountryId == null) return Optional.empty();
        String sql = "SELECT o.* FROM operator o JOIN prefix p ON p.operator_id = o.id " +
                     "WHERE p.telephone_type_id = :telephonyTypeId AND o.origin_country_id = :originCountryId AND o.active = true AND p.active = true " +
                     "LIMIT 1"; // PHP logic implies taking one if multiple exist
        Query query = entityManager.createNativeQuery(sql, Operator.class);
        query.setParameter("telephonyTypeId", telephonyTypeId);
        query.setParameter("originCountryId", originCountryId);
        try {
            return Optional.ofNullable((Operator) query.getSingleResult());
        } catch (NoResultException e) {
            return Optional.empty();
        }
    }


    public Optional<TelephonyType> findTelephonyTypeById(Long id) {
        // ... (Implementation from previous response, seems okay) ...
        // For brevity, assuming it's the same as before.
        if (id == null) return Optional.empty();
        Query query = entityManager.createNativeQuery("SELECT * FROM telephony_type WHERE id = :id AND active = true", TelephonyType.class);
        query.setParameter("id", id);
        try {
            return Optional.ofNullable((TelephonyType) query.getSingleResult());
        } catch (NoResultException e) {
            return Optional.empty();
        }
    }
    
    @SuppressWarnings("unchecked")
    public List<Prefix> findMatchingPrefixes(String dialedNumber, Long originCountryId, boolean forTrunk) {
        // PHP's CargarPrefijos + buscarPrefijo
        // 1. Fetch all relevant prefixes (ordered by length descending)
        String sql = "SELECT p.*, LENGTH(p.code) as code_len " + // ttcfg fields removed as they are on TelephonyTypeConfig
                     "FROM prefix p " +
                     "JOIN operator o ON p.operator_id = o.id " +
                     "JOIN telephony_type tt ON p.telephone_type_id = tt.id " +
                     "WHERE o.origin_country_id = :originCountryId " +
                     "  AND p.active = true AND o.active = true AND tt.active = true " +
                     "  AND tt.id != :specialServiceTypeId " +
                     "ORDER BY code_len DESC, tt.id"; // PHP order: $campo_len DESC, TIPOTELECFG_MIN DESC, TIPOTELE_ID

        Query query = entityManager.createNativeQuery(sql, Prefix.class);
        query.setParameter("originCountryId", originCountryId);
        query.setParameter("specialServiceTypeId", TelephonyTypeConstants.NUMEROS_ESPECIALES);
        
        List<Prefix> allRetrievedPrefixes = query.getResultList();
        List<Prefix> matchingPrefixes = new ArrayList<>();

        if (!forTrunk) {
            // Iterate through prefixes from longest to shortest code
            for (Prefix p : allRetrievedPrefixes) { // Already sorted by code_len DESC
                if (p.getCode() != null && !p.getCode().isEmpty() && dialedNumber.startsWith(p.getCode())) {
                    matchingPrefixes.add(p);
                }
            }
            // If multiple prefixes of the same length match, PHP takes the one that appeared first based on its complex sort.
            // Here, we'd need to ensure the SQL sort matches PHP's tie-breaking if it's critical.
            // For now, if matches are found, we use them. If not, fallback to local.

            if (matchingPrefixes.isEmpty()) { // Fallback to local if no prefix matched
                Optional<TelephonyType> localTypeOpt = findTelephonyTypeById(TelephonyTypeConstants.LOCAL);
                if (localTypeOpt.isPresent()) {
                    TelephonyType localType = localTypeOpt.get();
                    Prefix localPrefix = new Prefix();
                    localPrefix.setCode(""); 
                    localPrefix.setTelephonyType(localType);
                    localPrefix.setTelephoneTypeId(localType.getId());
                    findInternalOperatorByTelephonyType(TelephonyTypeConstants.LOCAL, originCountryId).ifPresent(op -> {
                        localPrefix.setOperator(op);
                        localPrefix.setOperatorId(op.getId());
                    });
                    // Base values for local are typically 0 or defined in its own prefix entry
                    // If no explicit prefix entry for local, these are defaults.
                    localPrefix.setBaseValue(BigDecimal.ZERO); 
                    localPrefix.setVatIncluded(false);
                    localPrefix.setVatValue(BigDecimal.ZERO); 
                    localPrefix.setBandOk(true); 
                    matchingPrefixes.add(localPrefix);
                }
            }
        } else { // For trunk calls, PHP logic implies using all prefixes of relevant telephony types
                 // The calling service (EnrichmentService) will filter these based on trunk rules.
            matchingPrefixes.addAll(allRetrievedPrefixes);
        }
        return matchingPrefixes;
    }

    public Optional<Prefix> findInternalPrefixForType(Long telephonyTypeId, Long originCountryId) {
        // ... (Implementation from previous response, seems okay) ...
        // For brevity, assuming it's the same as before.
         String sql = "SELECT p.* FROM prefix p " +
                     "JOIN operator o ON p.operator_id = o.id " +
                     "WHERE p.telephone_type_id = :telephonyTypeId " +
                     "AND o.origin_country_id = :originCountryId " +
                     "AND p.active = true AND o.active = true " +
                     "LIMIT 1";
        Query query = entityManager.createNativeQuery(sql, Prefix.class);
        query.setParameter("telephonyTypeId", telephonyTypeId);
        query.setParameter("originCountryId", originCountryId);
        try {
            return Optional.ofNullable((Prefix) query.getSingleResult());
        } catch (NoResultException e) {
            return Optional.empty();
        }
    }

    public Optional<Trunk> findTrunkByNameAndCommLocation(String trunkName, Long commLocationId) {
        // ... (Implementation from previous response, seems okay) ...
        // For brevity, assuming it's the same as before.
        if (trunkName == null || trunkName.isEmpty() || commLocationId == null) return Optional.empty();
        String sql = "SELECT * FROM trunk WHERE name = :trunkName AND comm_location_id = :commLocationId AND active = true";
        Query query = entityManager.createNativeQuery(sql, Trunk.class);
        query.setParameter("trunkName", trunkName);
        query.setParameter("commLocationId", commLocationId);
        try {
            return Optional.ofNullable((Trunk) query.getSingleResult());
        } catch (NoResultException e) {
            return Optional.empty();
        }
    }
    
    public Optional<CostCenter> findCostCenterById(Long id) {
        // ... (Implementation from previous response, seems okay) ...
        // For brevity, assuming it's the same as before.
        if (id == null) return Optional.empty();
        Query query = entityManager.createNativeQuery("SELECT * FROM cost_center WHERE id = :id AND active = true", CostCenter.class);
        query.setParameter("id", id);
        try {
            return Optional.ofNullable((CostCenter) query.getSingleResult());
        } catch (NoResultException e) {
            return Optional.empty();
        }
    }

    public Optional<Subdivision> findSubdivisionById(Long id) {
        // ... (Implementation from previous response, seems okay) ...
        // For brevity, assuming it's the same as before.
        if (id == null) return Optional.empty();
        Query query = entityManager.createNativeQuery("SELECT * FROM subdivision WHERE id = :id AND active = true", Subdivision.class);
        query.setParameter("id", id);
        try {
            return Optional.ofNullable((Subdivision) query.getSingleResult());
        } catch (NoResultException e) {
            return Optional.empty();
        }
    }
    
    public PrefixNdcLimitsDto getPrefixNdcLimits(Long telephonyTypeId, Long originCountryId) {
        // ... (Implementation from previous response, seems okay) ...
        // For brevity, assuming it's the same as before.
        String sql = "SELECT MIN(LENGTH(CAST(s.ndc AS TEXT))) AS min_len, MAX(LENGTH(CAST(s.ndc AS TEXT))) AS max_len, " +
                     "  MIN(LENGTH(CAST(s.initial_number AS TEXT))) as min_series_len, MAX(LENGTH(CAST(s.initial_number AS TEXT))) as max_series_len " +
                     "FROM series s JOIN indicator i ON s.indicator_id = i.id " +
                     "WHERE i.telephony_type_id = :telephonyTypeId AND i.origin_country_id = :originCountryId " +
                     "AND i.active = true AND s.active = true AND s.ndc IS NOT NULL AND s.ndc > 0 AND s.initial_number >= 0"; // Ensure ndc > 0
        Query query = entityManager.createNativeQuery(sql);
        query.setParameter("telephonyTypeId", telephonyTypeId);
        query.setParameter("originCountryId", originCountryId);
        
        Object[] result = null;
        try {
            result = (Object[]) query.getSingleResult();
        } catch (NoResultException e) {
            // No series found
        }

        int minNdcLen = (result != null && result[0] != null) ? ((Number)result[0]).intValue() : 0;
        int maxNdcLen = (result != null && result[1] != null) ? ((Number)result[1]).intValue() : 0;
        int minSeriesLen = (result != null && result[2] != null) ? ((Number)result[2]).intValue() : 0;
        int maxSeriesLen = (result != null && result[3] != null) ? ((Number)result[3]).intValue() : 0;
        
        int seriesNumLen = (minSeriesLen == maxSeriesLen && minSeriesLen > 0) ? minSeriesLen : 0;

        return PrefixNdcLimitsDto.builder()
            .minNdcLength(minNdcLen)
            .maxNdcLength(maxNdcLen)
            .seriesNumberLength(seriesNumLen)
            .build();
    }

    @SuppressWarnings("unchecked")
    public List<SeriesMatchDto> findIndicatorsByNumberAndType(String numberToMatch, Long telephonyTypeId, Long originCountryId, CommunicationLocation commLocation, Long prefixIdForBandOk, boolean bandOkForPrefix) {
        String cleanedNumber = CdrHelper.cleanPhoneNumber(numberToMatch);
        if (cleanedNumber.isEmpty()) return Collections.emptyList();

        PrefixNdcLimitsDto ndcLimits = getPrefixNdcLimits(telephonyTypeId, originCountryId);
        if (ndcLimits.getMaxNdcLength() == 0 && !Long.valueOf(TelephonyTypeConstants.LOCAL).equals(telephonyTypeId)) {
            return Collections.emptyList();
        }

        List<SeriesMatchDto> seriesMatches = new ArrayList<>();

        for (int ndcLen = ndcLimits.getMaxNdcLength(); ndcLen >= ndcLimits.getMinNdcLength(); ndcLen--) {
            if (ndcLen == 0 && !Long.valueOf(TelephonyTypeConstants.LOCAL).equals(telephonyTypeId)) continue;
            if (cleanedNumber.length() < ndcLen) continue;

            String currentNdcStr = (ndcLen > 0) ? cleanedNumber.substring(0, ndcLen) : "";
            String remainingNumberStr = (ndcLen > 0) ? cleanedNumber.substring(ndcLen) : cleanedNumber;

            if (!CdrHelper.isNumeric(currentNdcStr) && ndcLen > 0) continue;
            if (!remainingNumberStr.isEmpty() && !CdrHelper.isNumeric(remainingNumberStr)) continue;
            
            Integer currentNdc = (ndcLen > 0) ? Integer.parseInt(currentNdcStr) : null; // Use null for "no NDC" for local

            String sqlBase = "SELECT i.*, s.ndc as series_ndc, s.initial_number as series_initial_num, s.final_number as series_final_num, " +
                             "i_orig.department_country as origin_dept_country, i_orig.city_name as origin_city_name ";
            String sqlFrom = "FROM indicator i JOIN series s ON i.id = s.indicator_id " +
                             "LEFT JOIN indicator i_orig ON i.origin_country_id = i_orig.origin_country_id AND i_orig.id = :originCommIndicatorId ";
            String sqlWhere = "WHERE i.telephony_type_id = :telephonyTypeId " +
                              "  AND i.origin_country_id = :originCountryId " +
                              (currentNdc != null ? "  AND s.ndc = :currentNdc " : " AND (s.ndc IS NULL OR s.ndc = 0) ") + // Handle local "no NDC"
                              "  AND i.active = true AND s.active = true ";
            String sqlOrder = "ORDER BY s.ndc DESC, (s.final_number - s.initial_number) ASC, i.id";

            // PHP's $local_cond for band-based filtering
            if (bandOkForPrefix && prefixIdForBandOk != null) {
                sqlFrom += ", band b, band_indicator bi ";
                sqlWhere += " AND b.prefix_id = :prefixIdForBandOk " +
                            " AND bi.indicator_id = i.id AND bi.band_id = b.id " +
                            " AND (b.origin_indicator_id IS NULL OR b.origin_indicator_id = 0 OR b.origin_indicator_id = :commIndicatorId) ";
                sqlOrder = "ORDER BY CASE WHEN b.origin_indicator_id IS NULL OR b.origin_indicator_id = 0 THEN 1 ELSE 0 END DESC, " + sqlOrder;
            } else if (prefixIdForBandOk != null) { // Not bandOK, but prefix exists (PHP: $prefijo_id > 0)
                 sqlWhere += " AND (i.operator_id IS NULL OR i.operator_id = 0 OR i.operator_id IN (SELECT p_op.operator_id FROM prefix p_op WHERE p_op.id = :prefixIdForBandOk)) ";
            }
            
            String finalSql = sqlBase + sqlFrom + sqlWhere + sqlOrder;
            Query query = entityManager.createNativeQuery(finalSql, Tuple.class); // Using Tuple for custom result set

            query.setParameter("telephonyTypeId", telephonyTypeId);
            query.setParameter("originCountryId", originCountryId);
            if (currentNdc != null) query.setParameter("currentNdc", currentNdc);
            query.setParameter("originCommIndicatorId", commLocation.getIndicatorId());
            if (bandOkForPrefix && prefixIdForBandOk != null) {
                query.setParameter("prefixIdForBandOk", prefixIdForBandOk);
                query.setParameter("commIndicatorId", commLocation.getIndicatorId());
            } else if (prefixIdForBandOk != null) {
                 query.setParameter("prefixIdForBandOk", prefixIdForBandOk);
            }

            @SuppressWarnings("unchecked")
            List<jakarta.persistence.Tuple> results = query.getResultList();
            List<SeriesMatchDto> currentNdcMatches = new ArrayList<>();

            for (jakarta.persistence.Tuple row : results) {
                Indicator indicator = mapTupleToIndicator(row); // You'll need a helper to map Tuple to Indicator
                Integer seriesInitialNum = row.get("series_initial_num", Integer.class);
                Integer seriesFinalNum = row.get("series_final_num", Integer.class);
                
                PaddedSeriesDto padded = CdrHelper.rellenaSerie(cleanedNumber, currentNdcStr, String.valueOf(seriesInitialNum), String.valueOf(seriesFinalNum));

                if (cleanedNumber.compareTo(padded.getPaddedInitialNumber()) >= 0 &&
                    cleanedNumber.compareTo(padded.getPaddedFinalNumber()) <= 0) {
                    
                    currentNdcMatches.add(SeriesMatchDto.builder()
                        .indicator(indicator)
                        .ndc(currentNdcStr)
                        .destinationDescription(buildDestinationDescription(indicator, commLocation.getIndicator()))
                        .build());
                }
            }
            
            if (!currentNdcMatches.isEmpty()) {
                // PHP sorts by (final-initial) ASC. We already did that in SQL.
                // Then it takes the first one.
                seriesMatches.add(currentNdcMatches.get(0)); 
                if (ndcLen > 0) break; // PHP breaks on first NDC length that yields matches
            }
        }
        
        if (seriesMatches.isEmpty() && Long.valueOf(TelephonyTypeConstants.LOCAL).equals(telephonyTypeId)) {
             if (commLocation.getIndicator() != null) {
                 seriesMatches.add(SeriesMatchDto.builder()
                    .indicator(commLocation.getIndicator())
                    .ndc(findLocalAreaCodeForIndicator(commLocation.getIndicatorId()))
                    .destinationDescription(buildDestinationDescription(commLocation.getIndicator(), commLocation.getIndicator()))
                    .isApproximate(true)
                    .build());
             }
        }
        // Final sort if multiple NDC lengths yielded results (though PHP breaks early)
        seriesMatches.sort(Comparator.comparing((SeriesMatchDto sm) -> sm.getNdc() != null ? sm.getNdc().length() : 0).reversed());
        
        return seriesMatches;
    }

    private Indicator mapTupleToIndicator(jakarta.persistence.Tuple tuple) {
        // Manual mapping from Tuple to Indicator entity
        // This is needed because native queries with joins don't automatically map to the root entity if extra columns are selected.
        // Alternatively, use a constructor expression in JPQL or a @SqlResultSetMapping if this becomes too common.
        Indicator ind = new Indicator();
        ind.setId(tuple.get("id", Long.class));
        ind.setTelephonyTypeId(tuple.get("telephony_type_id", Long.class));
        ind.setDepartmentCountry(tuple.get("department_country", String.class));
        ind.setCityName(tuple.get("city_name", String.class));
        ind.setOperatorId(tuple.get("operator_id", Long.class));
        ind.setOriginCountryId(tuple.get("origin_country_id", Long.class));
        ind.setActive(tuple.get("active", Boolean.class));
        // Set other AuditedEntity fields if needed
        return ind;
    }

    private String buildDestinationDescription(Indicator matchedIndicator, Indicator originCommIndicator) {
        // ... (Implementation from previous response, seems okay) ...
        // For brevity, assuming it's the same as before.
        if (matchedIndicator == null) return "Unknown";
        String city = matchedIndicator.getCityName();
        String deptCountry = matchedIndicator.getDepartmentCountry();
        if (city != null && !city.isEmpty()) {
            if (deptCountry != null && !deptCountry.isEmpty() && !city.equalsIgnoreCase(deptCountry)) {
                if (originCommIndicator != null && Objects.equals(originCommIndicator.getDepartmentCountry(), deptCountry)) {
                    if (isLocalExtended(originCommIndicator, matchedIndicator)) { // Check if truly local extended
                        return city + " (" + deptCountry + " - Local Ext.)";
                    }
                }
                return city + " (" + deptCountry + ")";
            }
            return city;
        }
        return deptCountry != null ? deptCountry : "N/A";
    }

    public String findLocalAreaCodeForIndicator(Long indicatorId) {
        // ... (Implementation from previous response, seems okay) ...
        // For brevity, assuming it's the same as before.
        if (indicatorId == null) return "";
        String sql = "SELECT CAST(s.ndc AS TEXT) FROM series s WHERE s.indicator_id = :indicatorId AND s.active = true ORDER BY LENGTH(CAST(s.ndc AS TEXT)) DESC, s.ndc LIMIT 1";
        Query query = entityManager.createNativeQuery(sql, String.class);
        query.setParameter("indicatorId", indicatorId);
        try {
            return query.getSingleResult().toString();
        } catch (NoResultException e) {
            return "";
        }
    }
    
    public boolean isLocalExtended(Indicator originIndicator, Indicator destinationIndicator) {
        // ... (Implementation from previous response, seems okay) ...
        // For brevity, assuming it's the same as before.
        if (originIndicator == null || destinationIndicator == null || 
            Objects.equals(originIndicator.getId(), destinationIndicator.getId())) {
            return false;
        }
        String originNdc = findLocalAreaCodeForIndicator(originIndicator.getId());
        String destNdc = findLocalAreaCodeForIndicator(destinationIndicator.getId());

        return !originNdc.isEmpty() && originNdc.equals(destNdc);
    }
    
    public Optional<BigDecimal> findVatForTelephonyOperator(Long telephonyTypeId, Long operatorId, Long originCountryId) {
        // ... (Implementation from previous response, seems okay) ...
        // For brevity, assuming it's the same as before.
        String sql = "SELECT p.vat_value FROM prefix p " +
                     "JOIN operator o ON p.operator_id = o.id " + // Join to operator to check originCountryId
                     "WHERE p.telephone_type_id = :telephonyTypeId " +
                     "  AND p.operator_id = :operatorId " +
                     "  AND o.origin_country_id = :originCountryId " +
                     "  AND p.active = true AND o.active = true " +
                     "LIMIT 1";
        Query query = entityManager.createNativeQuery(sql);
        query.setParameter("telephonyTypeId", telephonyTypeId);
        query.setParameter("operatorId", operatorId);
        query.setParameter("originCountryId", originCountryId);
        try {
            return Optional.ofNullable((BigDecimal) query.getSingleResult());
        } catch (NoResultException e) {
            return Optional.empty();
        }
    }


    @SuppressWarnings("unchecked")
    public List<SpecialRateValue> findSpecialRateValues(LocalDateTime callTime, Long telephonyTypeId, Long operatorId, Long bandId, Long originIndicatorId) {
        // ... (Implementation from previous response, seems okay) ...
        // For brevity, assuming it's the same as before.
        String dayOfWeekField = callTime.getDayOfWeek().name().toLowerCase() + "_enabled"; 

        String sql = "SELECT srv.* FROM special_rate_value srv " +
                     "WHERE srv.active = true " +
                     "  AND (srv.valid_from IS NULL OR srv.valid_from <= :callTime) " +
                     "  AND (srv.valid_to IS NULL OR srv.valid_to >= :callTime) " +
                     "  AND srv." + dayOfWeekField + " = true "; 
        
        if (telephonyTypeId != null) sql += " AND (srv.telephony_type_id IS NULL OR srv.telephony_type_id = 0 OR srv.telephony_type_id = :telephonyTypeId) ";
        if (operatorId != null) sql += " AND (srv.operator_id IS NULL OR srv.operator_id = 0 OR srv.operator_id = :operatorId) ";
        if (bandId != null) sql += " AND (srv.band_id IS NULL OR srv.band_id = 0 OR srv.band_id = :bandId) ";
        if (originIndicatorId != null) sql += " AND (srv.origin_indicator_id IS NULL OR srv.origin_indicator_id = 0 OR srv.origin_indicator_id = :originIndicatorId) ";
        
        sql += "ORDER BY CASE WHEN srv.origin_indicator_id IS NULL OR srv.origin_indicator_id = 0 THEN 1 ELSE 0 END, srv.origin_indicator_id DESC, " +
               "CASE WHEN srv.telephony_type_id IS NULL OR srv.telephony_type_id = 0 THEN 1 ELSE 0 END, srv.telephony_type_id DESC, " +
               "CASE WHEN srv.operator_id IS NULL OR srv.operator_id = 0 THEN 1 ELSE 0 END, srv.operator_id DESC, " +
               "CASE WHEN srv.band_id IS NULL OR srv.band_id = 0 THEN 1 ELSE 0 END, srv.band_id DESC, " +
               "srv.id";


        Query query = entityManager.createNativeQuery(sql, SpecialRateValue.class);
        query.setParameter("callTime", callTime);
        if (telephonyTypeId != null) query.setParameter("telephonyTypeId", telephonyTypeId);
        if (operatorId != null) query.setParameter("operatorId", operatorId);
        if (bandId != null) query.setParameter("bandId", bandId);
        if (originIndicatorId != null) query.setParameter("originIndicatorId", originIndicatorId);
        
        return query.getResultList();
    }

    @SuppressWarnings("unchecked")
    public List<PbxSpecialRule> findPbxSpecialRules(Long commLocationId) {
        // ... (Implementation from previous response, seems okay) ...
        // For brevity, assuming it's the same as before.
        String sql = "SELECT psr.* FROM pbx_special_rule psr " +
                     "WHERE psr.active = true AND (psr.comm_location_id IS NULL OR psr.comm_location_id = :commLocationId) " + // Rules for specific plant or global (NULL)
                     "ORDER BY CASE WHEN psr.comm_location_id IS NULL THEN 1 ELSE 0 END, psr.comm_location_id DESC, " + // Specific plant rules first
                     "LENGTH(psr.search_pattern) DESC, psr.id"; // Longer (more specific) search patterns first
        Query query = entityManager.createNativeQuery(sql, PbxSpecialRule.class);
        query.setParameter("commLocationId", commLocationId == null ? 0L : commLocationId); // Use 0 or a distinct value for global rules if comm_location_id is not nullable and 0 means global
        return query.getResultList();
    }

    public Optional<SpecialService> findSpecialService(String phoneNumber, Long indicatorId, Long originCountryId) {
        // ... (Implementation from previous response, seems okay) ...
        // For brevity, assuming it's the same as before.
        String sql = "SELECT ss.* FROM special_service ss " +
                     "WHERE ss.active = true AND ss.phone_number = :phoneNumber " +
                     "  AND (ss.indicator_id IS NULL OR ss.indicator_id = 0 OR ss.indicator_id = :indicatorId) " + 
                     "  AND ss.origin_country_id = :originCountryId " +
                     "ORDER BY CASE WHEN ss.indicator_id IS NULL OR ss.indicator_id = 0 THEN 1 ELSE 0 END, ss.indicator_id DESC, ss.id LIMIT 1"; 
        Query query = entityManager.createNativeQuery(sql, SpecialService.class);
        query.setParameter("phoneNumber", phoneNumber);
        query.setParameter("indicatorId", indicatorId == null ? 0L : indicatorId);
        query.setParameter("originCountryId", originCountryId);
        try {
            return Optional.ofNullable((SpecialService) query.getSingleResult());
        } catch (NoResultException e) {
            return Optional.empty();
        }
    }
    
    @SuppressWarnings("unchecked")
    public List<Band> findBandsForPrefixAndIndicator(Long prefixId, Long originIndicatorId) {
        // ... (Implementation from previous response, seems okay) ...
        // For brevity, assuming it's the same as before.
        String sql = "SELECT b.* FROM band b " +
                     "WHERE b.active = true AND b.prefix_id = :prefixId ";
        if (originIndicatorId != null) {
            sql += "AND (b.origin_indicator_id IS NULL OR b.origin_indicator_id = 0 OR b.origin_indicator_id = :originIndicatorId) ";
        }
        sql += "ORDER BY CASE WHEN b.origin_indicator_id IS NULL OR b.origin_indicator_id = 0 THEN 1 ELSE 0 END, b.origin_indicator_id DESC, b.id"; 

        Query query = entityManager.createNativeQuery(sql, Band.class);
        query.setParameter("prefixId", prefixId);
        if (originIndicatorId != null) {
            query.setParameter("originIndicatorId", originIndicatorId);
        }
        return query.getResultList();
    }

    @SuppressWarnings("unchecked")
    public List<TrunkRule> findTrunkRules(Long trunkId, Long telephonyTypeId, String indicatorIdToMatch, Long originIndicatorId) {
        // ... (Implementation from previous response, seems okay) ...
        // For brevity, assuming it's the same as before.
        String sql = "SELECT tr.* FROM trunk_rule tr " +
                     "WHERE tr.active = true " +
                     "  AND (tr.trunk_id IS NULL OR tr.trunk_id = 0 OR tr.trunk_id = :trunkId) " +
                     "  AND tr.telephony_type_id = :telephonyTypeId " +
                     "  AND (tr.origin_indicator_id IS NULL OR tr.origin_indicator_id = 0 OR tr.origin_indicator_id = :originIndicatorId) " +
                     "  AND (tr.indicator_ids = '' OR tr.indicator_ids = :indicatorIdToMatch OR " +
                     "       tr.indicator_ids LIKE :indicatorIdToMatch || ',%' OR " + 
                     "       tr.indicator_ids LIKE '%,' || :indicatorIdToMatch OR " +   
                     "       tr.indicator_ids LIKE '%,' || :indicatorIdToMatch || ',%') " + 
                     "ORDER BY CASE WHEN tr.trunk_id IS NULL OR tr.trunk_id = 0 THEN 1 ELSE 0 END, tr.trunk_id DESC, " +
                     "CASE WHEN tr.origin_indicator_id IS NULL OR tr.origin_indicator_id = 0 THEN 1 ELSE 0 END, tr.origin_indicator_id DESC, " +
                     "LENGTH(tr.indicator_ids) DESC, tr.id";

        Query query = entityManager.createNativeQuery(sql, TrunkRule.class);
        query.setParameter("trunkId", trunkId == null ? 0L : trunkId);
        query.setParameter("telephonyTypeId", telephonyTypeId);
        query.setParameter("originIndicatorId", originIndicatorId == null ? 0L : originIndicatorId);
        query.setParameter("indicatorIdToMatch", indicatorIdToMatch == null ? "" : indicatorIdToMatch); 
        
        return query.getResultList();
    }

    public Optional<TrunkRate> findTrunkRate(Long trunkId, Long operatorId, Long telephonyTypeId) {
        // ... (Implementation from previous response, seems okay) ...
        // For brevity, assuming it's the same as before.
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
            return Optional.ofNullable((TrunkRate) query.getSingleResult());
        } catch (NoResultException e) {
            return Optional.empty();
        }
    }

    public Optional<CallCategory> findCallCategoryById(Long id) {
        // ... (Implementation from previous response, seems okay) ...
        // For brevity, assuming it's the same as before.
        if (id == null) return Optional.empty();
        Query query = entityManager.createNativeQuery("SELECT * FROM call_category WHERE id = :id AND active = true", CallCategory.class);
        query.setParameter("id", id);
        try {
            return Optional.ofNullable((CallCategory) query.getSingleResult());
        } catch (NoResultException e) {
            return Optional.empty();
        }
    }

    public Optional<TelephonyTypeConfig> findTelephonyTypeConfigByNumberLength(Long telephonyTypeId, Long originCountryId, int numberLength) {
        // ... (Implementation from previous response, seems okay) ...
        // For brevity, assuming it's the same as before.
        if (telephonyTypeId == null || originCountryId == null ) {
            return Optional.empty();
        }
        String sql = "SELECT ttc.* FROM telephony_type_config ttc " +
                     "WHERE ttc.telephony_type_id = :telephonyTypeId " +
                     "  AND ttc.origin_country_id = :originCountryId " +
                     "  AND ttc.min_value <= :numberLength AND ttc.max_value >= :numberLength " +
                     "LIMIT 1"; 
        Query query = entityManager.createNativeQuery(sql, TelephonyTypeConfig.class);
        query.setParameter("telephonyTypeId", telephonyTypeId);
        query.setParameter("originCountryId", originCountryId);
        query.setParameter("numberLength", numberLength);
        try {
            return Optional.ofNullable((TelephonyTypeConfig) query.getSingleResult());
        } catch (NoResultException e) {
            return Optional.empty();
        }
    }
}