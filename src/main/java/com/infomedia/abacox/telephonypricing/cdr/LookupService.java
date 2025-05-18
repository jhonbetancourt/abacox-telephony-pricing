
package com.infomedia.abacox.telephonypricing.cdr;

import com.infomedia.abacox.telephonypricing.entity.*;
import jakarta.persistence.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;


@Service
@Log4j2
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class LookupService {

    @PersistenceContext
    private EntityManager entityManager;
    
    private CdrConfigService cdrConfigService;

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
        int minLength = 100; // Start with a high min
        int maxLength = 0;   // Start with a low max

        // Query for employees
        String empSql = "SELECT COALESCE(MAX(LENGTH(e.extension)), 0) AS max_len, COALESCE(MIN(LENGTH(e.extension)), 100) AS min_len " +
                        "FROM employee e " +
                        "LEFT JOIN communication_location cl ON e.communication_location_id = cl.id " +
                        "LEFT JOIN indicator i ON cl.indicator_id = i.id " +
                        "WHERE e.active = true AND (cl.id IS NULL OR cl.active = true) AND (i.id IS NULL OR i.active = true) " +
                        "  AND e.extension ~ '^[0-9]+$' AND e.extension NOT LIKE '0%' AND LENGTH(e.extension) < :maxExtNumLength ";
        if (originCountryId != null) empSql += " AND (i.id IS NULL OR i.origin_country_id = :originCountryId) ";
        if (commLocationId != null) empSql += " AND (e.communication_location_id IS NULL OR e.communication_location_id = :commLocationId) ";

        Query empQuery = entityManager.createNativeQuery(empSql);
        empQuery.setParameter("maxExtNumLength", String.valueOf(CdrConfigService.MAX_EXTENSION_NUMERIC_LENGTH_FOR_LIMITS).length() -1 );
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
                          "  AND LENGTH(er.range_start::text) < :maxExtNumLength AND LENGTH(er.range_end::text) < :maxExtNumLength " +
                          "  AND er.range_end >= er.range_start ";
        if (originCountryId != null) rangeSql += " AND (i.id IS NULL OR i.origin_country_id = :originCountryId) ";
        if (commLocationId != null) rangeSql += " AND (er.comm_location_id IS NULL OR er.comm_location_id = :commLocationId) ";

        Query rangeQuery = entityManager.createNativeQuery(rangeSql);
        rangeQuery.setParameter("maxExtNumLength", String.valueOf(CdrConfigService.MAX_EXTENSION_NUMERIC_LENGTH_FOR_LIMITS).length() -1 );
        if (originCountryId != null) rangeQuery.setParameter("originCountryId", originCountryId);
        if (commLocationId != null) rangeQuery.setParameter("commLocationId", commLocationId);

        try {
            Object[] rangeResult = (Object[]) rangeQuery.getSingleResult();
            if (rangeResult[0] != null) maxLength = Math.max(maxLength, ((Number) rangeResult[0]).intValue());
            if (rangeResult[1] != null) minLength = Math.min(minLength, ((Number) rangeResult[1]).intValue());
        } catch (NoResultException e) {
             // No results, defaults will be used or values from employee query
        }

        if (maxLength == 0) maxLength = String.valueOf(CdrConfigService.MAX_EXTENSION_NUMERIC_LENGTH_FOR_LIMITS).length() - 2; // Default if nothing found
        if (minLength == 100) minLength = 1; // Default if nothing found
        if (minLength > maxLength) minLength = maxLength; // Ensure min <= max

        long minNumericValue = (minLength > 0 && minLength < 19) ? (long) Math.pow(10, minLength - 1) : 0L;
        long maxNumericValue = (maxLength > 0 && maxLength < 19) ? Long.parseLong("9".repeat(maxLength)) : CdrConfigService.MAX_EXTENSION_NUMERIC_LENGTH_FOR_LIMITS -1;


        String specialExtSql = "SELECT DISTINCT e.extension FROM employee e " +
                               "LEFT JOIN communication_location cl ON e.communication_location_id = cl.id " +
                               "LEFT JOIN indicator i ON cl.indicator_id = i.id " +
                               "WHERE e.active = true AND (cl.id IS NULL OR cl.active = true) AND (i.id IS NULL OR i.active = true) " +
                               "  AND (LENGTH(e.extension) >= :maxExtStrLength " +
                               "       OR e.extension LIKE '0%' OR e.extension LIKE '*%' OR e.extension LIKE '#%' OR e.extension ~ '[^0-9#*+]') ";
        if (originCountryId != null) specialExtSql += " AND (i.id IS NULL OR i.origin_country_id = :originCountryId) ";
        if (commLocationId != null) specialExtSql += " AND (e.communication_location_id IS NULL OR e.communication_location_id = :commLocationId) ";

        Query specialExtQuery = entityManager.createNativeQuery(specialExtSql);
        specialExtQuery.setParameter("maxExtStrLength", String.valueOf(CdrConfigService.MAX_EXTENSION_NUMERIC_LENGTH_FOR_LIMITS).length() -1 );
        if (originCountryId != null) specialExtQuery.setParameter("originCountryId", originCountryId);
        if (commLocationId != null) specialExtQuery.setParameter("commLocationId", commLocationId);

        @SuppressWarnings("unchecked")
        List<String> specialExtensions = specialExtQuery.getResultList();

        InternalExtensionLimitsDto currentInternalLimits = new InternalExtensionLimitsDto(minLength, maxLength, minNumericValue, maxNumericValue, specialExtensions);
        log.debug("Calculated InternalExtensionLimits for originCountryId={}, commLocationId={}: {}", originCountryId, commLocationId, currentInternalLimits);
        return currentInternalLimits;
    }

    public Optional<Employee> findEmployeeByExtension(String extension, Long commLocationId, InternalExtensionLimitsDto limits) {
        if (extension == null || extension.isEmpty() || limits == null) return Optional.empty();
        String cleanedExtension = CdrHelper.cleanPhoneNumber(extension);
        if (cleanedExtension.isEmpty()) return Optional.empty();

        // Prefer specific commLocation match if provided
        if (commLocationId != null) {
            String sql = "SELECT * FROM employee WHERE extension = :extension AND communication_location_id = :commLocationId AND active = true";
            Query query = entityManager.createNativeQuery(sql, Employee.class);
            query.setParameter("extension", cleanedExtension);
            query.setParameter("commLocationId", commLocationId);
            try {
                return Optional.ofNullable((Employee) query.getSingleResult());
            } catch (NoResultException e) {
                // Fall through to range or global search if enabled
            }
        }
        
        // If global extensions are enabled or no specific commLocationId was given for the search
        if (commLocationId == null || cdrConfigService.isGlobalExtensionsEnabled(
            findCommunicationLocationById(commLocationId).map(cl -> cl.getPlantType() != null ? cl.getPlantType().getId() : null).orElse(null))
        ) {
            String sqlGlobal = "SELECT * FROM employee WHERE extension = :extension AND active = true ORDER BY communication_location_id NULLS LAST LIMIT 1"; // Prefer non-null comm_location
            Query queryGlobal = entityManager.createNativeQuery(sqlGlobal, Employee.class);
            queryGlobal.setParameter("extension", cleanedExtension);
            try {
                Employee globalEmp = (Employee) queryGlobal.getSingleResult();
                if (globalEmp != null) return Optional.of(globalEmp);
            } catch (NoResultException e) {
                // Fall through
            }
        }

        // Fallback to range lookup, respecting commLocationId if provided
        if (CdrHelper.isNumeric(cleanedExtension)) {
             return findEmployeeByExtensionRange(cleanedExtension, commLocationId, limits);
        }

        return Optional.empty();
    }

    public Optional<Employee> findEmployeeByAuthCode(String authCode, Long commLocationId) {
        if (authCode == null || authCode.isEmpty()) return Optional.empty();
        
        String sql;
        Query query;

        if (commLocationId != null) {
            sql = "SELECT * FROM employee WHERE auth_code = :authCode AND communication_location_id = :commLocationId AND active = true";
            query = entityManager.createNativeQuery(sql, Employee.class);
            query.setParameter("authCode", authCode);
            query.setParameter("commLocationId", commLocationId);
        } else { // Global auth code lookup if commLocationId is null
             sql = "SELECT * FROM employee WHERE auth_code = :authCode AND active = true ORDER BY communication_location_id NULLS LAST LIMIT 1";
            query = entityManager.createNativeQuery(sql, Employee.class);
            query.setParameter("authCode", authCode);
        }
        
        try {
            return Optional.ofNullable((Employee) query.getSingleResult());
        } catch (NoResultException e) {
            return Optional.empty();
        }
    }

    @SuppressWarnings("unchecked")
    public Optional<Employee> findEmployeeByExtensionRange(String extension, Long commLocationId, InternalExtensionLimitsDto limits) {
        if (!CdrHelper.isNumeric(extension)) return Optional.empty();
        long extNumeric;
        try {
            extNumeric = Long.parseLong(extension);
        } catch (NumberFormatException e) {
            return Optional.empty();
        }

        String sql = "SELECT er.* FROM extension_range er ";
        List<String> conditions = new ArrayList<>();
        conditions.add("er.active = true");
        conditions.add("er.range_start <= :extNumeric AND er.range_end >= :extNumeric");

        if (commLocationId != null) {
            conditions.add("er.comm_location_id = :commLocationId");
        }
        
        sql += "WHERE " + String.join(" AND ", conditions);
        sql += " ORDER BY (er.range_end - er.range_start) ASC, er.id"; // Prefer tighter range

        Query query = entityManager.createNativeQuery(sql, ExtensionRange.class);
        query.setParameter("extNumeric", extNumeric);
        if (commLocationId != null) {
            query.setParameter("commLocationId", commLocationId);
        }

        List<ExtensionRange> ranges = query.getResultList();
        if (!ranges.isEmpty()) {
            ExtensionRange bestRange = ranges.get(0);
            Employee virtualEmployee = new Employee();
            virtualEmployee.setId(null); // Not a real DB employee record
            virtualEmployee.setExtension(extension);
            virtualEmployee.setCommunicationLocationId(bestRange.getCommLocationId());
            if (bestRange.getSubdivisionId() != null) {
                findSubdivisionById(bestRange.getSubdivisionId()).ifPresent(virtualEmployee::setSubdivision);
                virtualEmployee.setSubdivisionId(bestRange.getSubdivisionId());
            }
            if (bestRange.getCostCenterId() != null) {
                findCostCenterById(bestRange.getCostCenterId()).ifPresent(virtualEmployee::setCostCenter);
                virtualEmployee.setCostCenterId(bestRange.getCostCenterId());
            }
            virtualEmployee.setName((bestRange.getPrefix() != null ? bestRange.getPrefix() : "") + " " + extension + " (Range)");
            virtualEmployee.setActive(true); // Considered active as it matched an active range
            return Optional.of(virtualEmployee);
        }
        return Optional.empty();
    }


    public Optional<Indicator> findIndicatorById(Long id) {
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
        if (telephonyTypeId == null || originCountryId == null) return Optional.empty();
        String sql = "SELECT o.* FROM operator o JOIN prefix p ON p.operator_id = o.id " +
                     "WHERE p.telephone_type_id = :telephonyTypeId AND o.origin_country_id = :originCountryId AND o.active = true AND p.active = true " +
                     "LIMIT 1";
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
    public List<Prefix> findMatchingPrefixes(String dialedNumber, Long originCountryId, boolean forTrunkContext) {
        String sql = "SELECT p.*, LENGTH(p.code) as code_len " +
                     "FROM prefix p " +
                     "JOIN operator o ON p.operator_id = o.id " +
                     "JOIN telephony_type tt ON p.telephone_type_id = tt.id " +
                     "LEFT JOIN telephony_type_config ttc ON tt.id = ttc.telephony_type_id AND ttc.origin_country_id = :originCountryId " +
                     "WHERE o.origin_country_id = :originCountryId " +
                     "  AND p.active = true AND o.active = true AND tt.active = true " +
                     "  AND tt.id != :specialServiceTypeId " +
                     "ORDER BY code_len DESC, COALESCE(ttc.min_value, 0) DESC, tt.id";

        Query query = entityManager.createNativeQuery(sql, Prefix.class);
        query.setParameter("originCountryId", originCountryId);
        query.setParameter("specialServiceTypeId", TelephonyTypeConstants.NUMEROS_ESPECIALES);

        List<Prefix> allRetrievedPrefixes = query.getResultList();
        List<Prefix> matchingPrefixes = new ArrayList<>();

        if (!forTrunkContext) {
            for (Prefix p : allRetrievedPrefixes) {
                if (p.getCode() != null && !p.getCode().isEmpty() && dialedNumber.startsWith(p.getCode())) {
                    matchingPrefixes.add(p);
                }
            }
            if (matchingPrefixes.isEmpty()) { // Fallback to local if no prefix matched
                findInternalPrefixForType(TelephonyTypeConstants.LOCAL, originCountryId)
                    .ifPresent(matchingPrefixes::add);
            }
        } else { // For trunk calls, PHP logic implies using all prefixes of relevant telephony types
            matchingPrefixes.addAll(allRetrievedPrefixes);
        }
        return matchingPrefixes;
    }

    public Optional<Prefix> findInternalPrefixForType(Long telephonyTypeId, Long originCountryId) {
         String sql = "SELECT p.* FROM prefix p " +
                     "JOIN operator o ON p.operator_id = o.id " +
                     "JOIN telephony_type tt ON p.telephone_type_id = tt.id " +
                     "WHERE p.telephone_type_id = :telephonyTypeId " +
                     "AND o.origin_country_id = :originCountryId " +
                     "AND p.active = true AND o.active = true AND tt.active = true " +
                     "LIMIT 1";
        Query query = entityManager.createNativeQuery(sql, Prefix.class);
        query.setParameter("telephonyTypeId", telephonyTypeId);
        query.setParameter("originCountryId", originCountryId);
        try {
            Prefix prefix = (Prefix) query.getSingleResult();
            // Eagerly fetch related entities if needed, or ensure they are fetched by JPA
            if (prefix.getOperatorId() != null) prefix.setOperator(findOperatorById(prefix.getOperatorId()).orElse(null));
            if (prefix.getTelephoneTypeId() != null) prefix.setTelephonyType(findTelephonyTypeById(prefix.getTelephoneTypeId()).orElse(null));
            return Optional.of(prefix);
        } catch (NoResultException e) {
            // If no specific prefix, create a default "Local" or "Internal" representation if applicable
            if (TelephonyTypeConstants.LOCAL == telephonyTypeId ||
                TelephonyTypeConstants.INTERNA == telephonyTypeId ||
                TelephonyTypeConstants.INTERNA_LOCAL == telephonyTypeId ||
                TelephonyTypeConstants.INTERNA_NACIONAL == telephonyTypeId ||
                TelephonyTypeConstants.INTERNA_INTERNACIONAL == telephonyTypeId) {

                return findTelephonyTypeById(telephonyTypeId).map(tt -> {
                    Prefix defaultPrefix = new Prefix();
                    defaultPrefix.setCode(""); // No actual code for these defaults
                    defaultPrefix.setTelephonyType(tt);
                    defaultPrefix.setTelephoneTypeId(tt.getId());
                    findInternalOperatorByTelephonyType(tt.getId(), originCountryId).ifPresent(op -> {
                        defaultPrefix.setOperator(op);
                        defaultPrefix.setOperatorId(op.getId());
                    });
                    defaultPrefix.setBaseValue(BigDecimal.ZERO);
                    defaultPrefix.setVatIncluded(false);
                    defaultPrefix.setVatValue(BigDecimal.ZERO);
                    defaultPrefix.setBandOk(false); // Typically bands don't apply to internal defaults
                    return defaultPrefix;
                });
            }
            return Optional.empty();
        }
    }

    @SuppressWarnings("unchecked")
    public List<Prefix> findInternalPrefixesForType(Long telephonyTypeId, Long originCountryId) {
        String sql = "SELECT p.* FROM prefix p " +
                     "JOIN operator o ON p.operator_id = o.id " +
                     "JOIN telephony_type tt ON p.telephone_type_id = tt.id " +
                     "WHERE p.telephone_type_id = :telephonyTypeId " +
                     "AND o.origin_country_id = :originCountryId " +
                     "AND p.active = true AND o.active = true AND tt.active = true " +
                     "ORDER BY LENGTH(p.code) DESC"; // Longer prefixes first for matching
        Query query = entityManager.createNativeQuery(sql, Prefix.class);
        query.setParameter("telephonyTypeId", telephonyTypeId);
        query.setParameter("originCountryId", originCountryId);
        return query.getResultList();
    }


    public Optional<Trunk> findTrunkByNameAndCommLocation(String trunkName, Long commLocationId) {
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
        String sql = "SELECT COALESCE(MIN(LENGTH(CAST(s.ndc AS TEXT))), 0) AS min_len, COALESCE(MAX(LENGTH(CAST(s.ndc AS TEXT))), 0) AS max_len, " +
                     "  COALESCE(MIN(LENGTH(CAST(s.initial_number AS TEXT))), 0) as min_series_len, COALESCE(MAX(LENGTH(CAST(s.initial_number AS TEXT))), 0) as max_series_len " +
                     "FROM series s JOIN indicator i ON s.indicator_id = i.id " +
                     "WHERE i.telephony_type_id = :telephonyTypeId AND i.origin_country_id IN (0, :originCountryId) " +
                     "AND i.active = true AND s.active = true AND s.ndc IS NOT NULL AND s.ndc > 0 AND s.initial_number >= 0";
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
    public List<SeriesMatchDto> findIndicatorsByNumberAndType(String numberToMatch, Long telephonyTypeId, Long originCountryId, CommunicationLocation commLocation, Long prefixIdForBandOk, boolean bandOkForPrefix, boolean isIncomingMode) {
        String cleanedNumber = CdrHelper.cleanPhoneNumber(numberToMatch);
        if (cleanedNumber.isEmpty()) return Collections.emptyList();

        PrefixNdcLimitsDto ndcLimits = getPrefixNdcLimits(telephonyTypeId, originCountryId);
        if (ndcLimits.getMaxNdcLength() == 0 && !Long.valueOf(TelephonyTypeConstants.LOCAL).equals(telephonyTypeId)) {
            return Collections.emptyList();
        }

        List<SeriesMatchDto> seriesMatches = new ArrayList<>();
        Long commIndicatorId = commLocation.getIndicatorId();
        TelephonyTypeConfig ttConfig = findTelephonyTypeConfigForLengthDetermination(telephonyTypeId, originCountryId).orElse(null);
        int minLengthForType = (ttConfig != null) ? ttConfig.getMinValue() : 0;

        for (int ndcLen = ndcLimits.getMaxNdcLength(); ndcLen >= ndcLimits.getMinNdcLength(); ndcLen--) {
            if (ndcLen == 0 && !Long.valueOf(TelephonyTypeConstants.LOCAL).equals(telephonyTypeId)) continue;
            if (cleanedNumber.length() < ndcLen) continue;

            String currentNdcStr = (ndcLen > 0) ? cleanedNumber.substring(0, ndcLen) : "";
            String remainingNumberStr = (ndcLen > 0) ? cleanedNumber.substring(ndcLen) : cleanedNumber;

            if (!CdrHelper.isNumeric(currentNdcStr) && ndcLen > 0) continue;
            if (!remainingNumberStr.isEmpty() && !CdrHelper.isNumeric(remainingNumberStr)) continue;

            Integer currentNdc = (ndcLen > 0) ? Integer.parseInt(currentNdcStr) : null;

            // PHP: $tipotele_min_tmp = $tipotele_min - $i (where $i is ndcLen)
            // This check ensures the remaining number part is long enough for the telephony type's minimum *after* NDC.
            // If incomingMode, we are checking the full number against type's min length.
            int effectiveMinLengthForType = isIncomingMode ? minLengthForType : Math.max(0, minLengthForType - ndcLen);
            if (remainingNumberStr.length() < effectiveMinLengthForType) {
                continue;
            }

            String sqlBase = "SELECT i.id as indicator_id, i.telephony_type_id, i.department_country, i.city_name, i.operator_id as indicator_operator_id, i.origin_country_id as indicator_origin_country_id, i.active as indicator_active, " +
                             "s.ndc as series_ndc, s.initial_number as series_initial_num, s.final_number as series_final_num ";
            String sqlFrom = "FROM indicator i JOIN series s ON i.id = s.indicator_id ";
            String sqlWhere = "WHERE i.telephony_type_id = :telephonyTypeId " +
                              "  AND i.origin_country_id IN (0, :originCountryId) " +
                              (currentNdc != null ? "  AND s.ndc = :currentNdc " : " AND (s.ndc IS NULL OR s.ndc = 0) ") +
                              "  AND i.active = true AND s.active = true ";
            String sqlOrder = "ORDER BY s.ndc DESC, ";

            if (bandOkForPrefix && prefixIdForBandOk != null) {
                sqlFrom += ", band b, band_indicator bi ";
                sqlWhere += " AND b.prefix_id = :prefixIdForBandOk " +
                            " AND bi.indicator_id = i.id AND bi.band_id = b.id " +
                            " AND (b.origin_indicator_id IS NULL OR b.origin_indicator_id = 0 OR b.origin_indicator_id = :commIndicatorId) ";
                sqlOrder += " CASE WHEN b.origin_indicator_id IS NULL OR b.origin_indicator_id = 0 THEN 1 ELSE 0 END ASC, b.origin_indicator_id DESC, ";
            } else if (prefixIdForBandOk != null) {
                 sqlWhere += " AND (i.operator_id IS NULL OR i.operator_id = 0 OR i.operator_id IN (SELECT p_op.operator_id FROM prefix p_op WHERE p_op.id = :prefixIdForBandOk)) ";
            }
            sqlOrder += " (s.final_number - s.initial_number) ASC, i.id";

            String finalSql = sqlBase + sqlFrom + sqlWhere + sqlOrder;
            Query query = entityManager.createNativeQuery(finalSql, Tuple.class);

            query.setParameter("telephonyTypeId", telephonyTypeId);
            query.setParameter("originCountryId", originCountryId);
            if (currentNdc != null) query.setParameter("currentNdc", currentNdc);
            if (commIndicatorId != null) query.setParameter("commIndicatorId", commIndicatorId);
            if (prefixIdForBandOk != null) query.setParameter("prefixIdForBandOk", prefixIdForBandOk);

            List<jakarta.persistence.Tuple> results = query.getResultList();
            List<SeriesMatchDto> currentNdcMatches = new ArrayList<>();

            for (jakarta.persistence.Tuple row : results) {
                Indicator indicator = mapTupleToIndicator(row);
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
                seriesMatches.add(currentNdcMatches.get(0));
                if (ndcLen > 0) break;
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
        return seriesMatches;
    }

    private Indicator mapTupleToIndicator(jakarta.persistence.Tuple tuple) {
        Indicator ind = new Indicator();
        ind.setId(tuple.get("indicator_id", Long.class));
        ind.setTelephonyTypeId(tuple.get("telephony_type_id", Long.class));
        ind.setDepartmentCountry(tuple.get("department_country", String.class));
        ind.setCityName(tuple.get("city_name", String.class));
        ind.setOperatorId(tuple.get("indicator_operator_id", Long.class));
        ind.setOriginCountryId(tuple.get("indicator_origin_country_id", Long.class));
        ind.setActive(tuple.get("indicator_active", Boolean.class));
        return ind;
    }

    public String findLocalAreaCodeForIndicator(Long indicatorId) {
        if (indicatorId == null) return "";
        String sql = "SELECT CAST(s.ndc AS TEXT) FROM series s WHERE s.indicator_id = :indicatorId AND s.active = true " +
                     "GROUP BY s.ndc ORDER BY COUNT(*) DESC, s.ndc LIMIT 1";
        Query query = entityManager.createNativeQuery(sql, String.class);
        query.setParameter("indicatorId", indicatorId);
        try {
            Object result = query.getSingleResult();
            return result != null ? result.toString() : "";
        } catch (NoResultException e) {
            return "";
        }
    }

    public boolean isLocalExtended(Indicator originIndicator, Indicator destinationIndicator) {
        if (originIndicator == null || destinationIndicator == null ||
            Objects.equals(originIndicator.getId(), destinationIndicator.getId())) {
            return false;
        }
        // PHP logic: if same department_country, it's local extended.
        // However, the PHP code in `BuscarLocalExtendida` actually compares NDCs of the origin indicator.
        // Let's stick to the NDC comparison as it was more specific.
        String originNdc = findLocalAreaCodeForIndicator(originIndicator.getId());
        String destNdc = findLocalAreaCodeForIndicator(destinationIndicator.getId());

        // If both indicators have the same primary NDC, they are considered "local extended" relative to each other.
        return !originNdc.isEmpty() && originNdc.equals(destNdc);
    }

    public Optional<BigDecimal> findVatForTelephonyOperator(Long telephonyTypeId, Long operatorId, Long originCountryId) {
        if (telephonyTypeId == null || originCountryId == null) {
            return Optional.empty();
        }

        String sql;
        Query query;

        if (operatorId != null) {
            sql = "SELECT p.vat_value FROM prefix p " +
                  "JOIN operator o ON p.operator_id = o.id " +
                  "WHERE p.telephone_type_id = :telephonyTypeId " +
                  "  AND p.operator_id = :operatorId " +
                  "  AND o.origin_country_id = :originCountryId " +
                  "  AND p.active = true AND o.active = true " +
                  "LIMIT 1";
            query = entityManager.createNativeQuery(sql);
            query.setParameter("operatorId", operatorId);
        } else {
            // Try to find a default VAT for the telephony type if operator is missing
            sql = "SELECT p.vat_value FROM prefix p " +
                  "JOIN operator o ON p.operator_id = o.id " +
                  "WHERE p.telephone_type_id = :telephonyTypeId " +
                  "  AND o.origin_country_id = :originCountryId " +
                  "  AND p.active = true AND o.active = true " +
                  "ORDER BY p.id LIMIT 1"; // Get any VAT for this TT in this country
            query = entityManager.createNativeQuery(sql);
        }
        
        query.setParameter("telephonyTypeId", telephonyTypeId);
        query.setParameter("originCountryId", originCountryId);
        
        try {
            return Optional.ofNullable((BigDecimal) query.getSingleResult());
        } catch (NoResultException e) {
            return Optional.empty();
        }
    }


    @SuppressWarnings("unchecked")
    public List<SpecialRateValue> findSpecialRateValues(LocalDateTime callTime, Long telephonyTypeId, Long operatorId, Long bandId, Long originIndicatorId) {
        String dayOfWeekField = callTime.getDayOfWeek().name().toLowerCase() + "_enabled"; // e.g., monday_enabled

        String sql = "SELECT srv.* FROM special_rate_value srv " +
                     "WHERE srv.active = true " +
                     "  AND (srv.valid_from IS NULL OR srv.valid_from <= :callTime) " +
                     "  AND (srv.valid_to IS NULL OR srv.valid_to >= :callTime) " +
                     "  AND srv." + dayOfWeekField + " = true ";

        if (telephonyTypeId != null) sql += " AND (srv.telephony_type_id IS NULL OR srv.telephony_type_id = 0 OR srv.telephony_type_id = :telephonyTypeId) ";
        if (operatorId != null) sql += " AND (srv.operator_id IS NULL OR srv.operator_id = 0 OR srv.operator_id = :operatorId) ";
        if (bandId != null) sql += " AND (srv.band_id IS NULL OR srv.band_id = 0 OR srv.band_id = :bandId) ";
        if (originIndicatorId != null) sql += " AND (srv.origin_indicator_id IS NULL OR srv.origin_indicator_id = 0 OR srv.origin_indicator_id = :originIndicatorId) ";

        sql += "ORDER BY CASE WHEN srv.origin_indicator_id IS NULL OR srv.origin_indicator_id = 0 THEN 1 ELSE 0 END ASC, srv.origin_indicator_id DESC, " +
               "CASE WHEN srv.telephony_type_id IS NULL OR srv.telephony_type_id = 0 THEN 1 ELSE 0 END ASC, srv.telephony_type_id DESC, " +
               "CASE WHEN srv.operator_id IS NULL OR srv.operator_id = 0 THEN 1 ELSE 0 END ASC, srv.operator_id DESC, " +
               "CASE WHEN srv.band_id IS NULL OR srv.band_id = 0 THEN 1 ELSE 0 END ASC, srv.band_id DESC, " +
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
        String sql = "SELECT psr.* FROM pbx_special_rule psr " +
                     "WHERE psr.active = true AND (psr.comm_location_id IS NULL OR psr.comm_location_id = 0 OR psr.comm_location_id = :commLocationId) " + // PHP: PBXESPECIAL_COMUBICACION_ID (0 means global)
                     "ORDER BY CASE WHEN psr.comm_location_id IS NULL OR psr.comm_location_id = 0 THEN 1 ELSE 0 END ASC, psr.comm_location_id DESC, " +
                     "LENGTH(psr.search_pattern) DESC, psr.id"; // PHP: ORDER BY PBXESPECIAL_COMUBICACION_ID, PBXESPECIAL_BUSCAR DESC
        Query query = entityManager.createNativeQuery(sql, PbxSpecialRule.class);
        query.setParameter("commLocationId", commLocationId == null ? 0L : commLocationId);
        return query.getResultList();
    }

    public Optional<SpecialService> findSpecialService(String phoneNumber, Long indicatorId, Long originCountryId) {
        String sql = "SELECT ss.* FROM special_service ss " +
                     "WHERE ss.active = true AND ss.phone_number = :phoneNumber " +
                     "  AND (ss.indicator_id IS NULL OR ss.indicator_id = 0 OR ss.indicator_id = :indicatorId) " +
                     "  AND ss.origin_country_id = :originCountryId " +
                     "ORDER BY CASE WHEN ss.indicator_id IS NULL OR ss.indicator_id = 0 THEN 1 ELSE 0 END ASC, ss.indicator_id DESC, ss.id LIMIT 1";
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
        String sql = "SELECT b.* FROM band b " +
                     "WHERE b.active = true AND b.prefix_id = :prefixId ";
        if (originIndicatorId != null) {
            sql += "AND (b.origin_indicator_id IS NULL OR b.origin_indicator_id = 0 OR b.origin_indicator_id = :originIndicatorId) ";
        }
        sql += "ORDER BY CASE WHEN b.origin_indicator_id IS NULL OR b.origin_indicator_id = 0 THEN 1 ELSE 0 END ASC, b.origin_indicator_id DESC, b.id";

        Query query = entityManager.createNativeQuery(sql, Band.class);
        query.setParameter("prefixId", prefixId);
        if (originIndicatorId != null) {
            query.setParameter("originIndicatorId", originIndicatorId);
        }
        return query.getResultList();
    }

    @SuppressWarnings("unchecked")
    public List<TrunkRule> findTrunkRules(Long trunkId, Long telephonyTypeId, String indicatorIdToMatch, Long originIndicatorId) {
        String sql = "SELECT tr.* FROM trunk_rule tr " +
                     "WHERE tr.active = true " +
                     "  AND (tr.trunk_id IS NULL OR tr.trunk_id = 0 OR tr.trunk_id = :trunkId) " +
                     "  AND tr.telephony_type_id = :telephonyTypeId " +
                     "  AND (tr.origin_indicator_id IS NULL OR tr.origin_indicator_id = 0 OR tr.origin_indicator_id = :originIndicatorId) " +
                     "  AND (tr.indicator_ids = '' OR tr.indicator_ids IS NULL OR tr.indicator_ids = :indicatorIdToMatch OR " +
                     "       tr.indicator_ids LIKE :indicatorIdToMatch || ',%' OR " +
                     "       tr.indicator_ids LIKE '%,' || :indicatorIdToMatch OR " +
                     "       tr.indicator_ids LIKE '%,' || :indicatorIdToMatch || ',%') " +
                     "ORDER BY CASE WHEN tr.trunk_id IS NULL OR tr.trunk_id = 0 THEN 1 ELSE 0 END ASC, tr.trunk_id DESC, " +
                     "CASE WHEN tr.origin_indicator_id IS NULL OR tr.origin_indicator_id = 0 THEN 1 ELSE 0 END ASC, tr.origin_indicator_id DESC, " +
                     "LENGTH(tr.indicator_ids) DESC, tr.id";

        Query query = entityManager.createNativeQuery(sql, TrunkRule.class);
        query.setParameter("trunkId", trunkId == null ? 0L : trunkId);
        query.setParameter("telephonyTypeId", telephonyTypeId);
        query.setParameter("originIndicatorId", originIndicatorId == null ? 0L : originIndicatorId);
        query.setParameter("indicatorIdToMatch", indicatorIdToMatch == null ? "" : indicatorIdToMatch);

        return query.getResultList();
    }

    public Optional<TrunkRate> findTrunkRate(Long trunkId, Long operatorId, Long telephonyTypeId) {
        String sql = "SELECT tr.* FROM trunk_rate tr " +
                     "WHERE tr.trunk_id = :trunkId " +
                     "  AND tr.operator_id = :operatorId " +
                     "  AND tr.telephony_type_id = :telephonyTypeId " +
                     // "  AND tr.active = true " + // TrunkRate entity does not have 'active'
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
        if (id == null) return Optional.empty();
        Query query = entityManager.createNativeQuery("SELECT * FROM call_category WHERE id = :id AND active = true", CallCategory.class);
        query.setParameter("id", id);
        try {
            return Optional.ofNullable((CallCategory) query.getSingleResult());
        } catch (NoResultException e) {
            return Optional.empty();
        }
    }

    public Optional<TelephonyTypeConfig> findTelephonyTypeConfigForLengthDetermination(Long telephonyTypeId, Long originCountryId) {
        // PHP's CargarPrefijos uses TIPOTELECFG_MIN/MAX. This query tries to get a representative config.
        // If multiple configs exist, it's ambiguous. PHP seems to use the one from the join in CargarPrefijos,
        // which might not be strictly defined if multiple ttc records exist for a tt.
        // We'll pick one, perhaps one with min_value > 0 if available, or any.
        if (telephonyTypeId == null || originCountryId == null ) {
            return Optional.empty();
        }
        String sql = "SELECT ttc.* FROM telephony_type_config ttc " +
                     "WHERE ttc.telephony_type_id = :telephonyTypeId " +
                     "  AND ttc.origin_country_id = :originCountryId " +
                     "ORDER BY ttc.min_value DESC, ttc.id LIMIT 1"; // Prefer specific ranges over generic ones
        Query query = entityManager.createNativeQuery(sql, TelephonyTypeConfig.class);
        query.setParameter("telephonyTypeId", telephonyTypeId);
        query.setParameter("originCountryId", originCountryId);
        try {
            return Optional.ofNullable((TelephonyTypeConfig) query.getSingleResult());
        } catch (NoResultException e) {
            return Optional.empty();
        }
    }

    private String buildDestinationDescription(Indicator matchedIndicator, Indicator originCommIndicator) {
        if (matchedIndicator == null) return "Unknown";
        String city = matchedIndicator.getCityName();
        String deptCountry = matchedIndicator.getDepartmentCountry();
        if (city != null && !city.isEmpty()) {
            if (deptCountry != null && !deptCountry.isEmpty() && !city.equalsIgnoreCase(deptCountry)) {
                if (originCommIndicator != null && !Objects.equals(originCommIndicator.getId(), matchedIndicator.getId()) &&
                    isLocalExtended(originCommIndicator, matchedIndicator)) {
                    return city + " (" + deptCountry + " - Local Ext.)";
                }
                return city + " (" + deptCountry + ")";
            }
            return city;
        }
        return deptCountry != null ? deptCountry : "N/A";
    }

    public Optional<Subdivision> findSubdivisionForEmployee(Employee employee) {
        if (employee == null || employee.getSubdivisionId() == null) return Optional.empty();
        return findSubdivisionById(employee.getSubdivisionId());
    }
    
    public Optional<Indicator> findIndicatorForCommunicationLocation(CommunicationLocation commLocation) {
        if (commLocation == null || commLocation.getIndicatorId() == null) return Optional.empty();
        return findIndicatorById(commLocation.getIndicatorId());
    }
}