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

@Service
@Log4j2
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class LookupService {

    @PersistenceContext
    private EntityManager entityManager;

    public Optional<CommunicationLocation> findCommunicationLocationById(Long id) {
        if (id == null) return Optional.empty();
        // Assuming CommunicationLocation entity has an 'active' field
        Query query = entityManager.createNativeQuery("SELECT * FROM communication_location WHERE id = :id AND active = true", CommunicationLocation.class);
        query.setParameter("id", id);
        try {
            return Optional.ofNullable((CommunicationLocation) query.getSingleResult());
        } catch (NoResultException e) {
            return Optional.empty();
        }
    }

    public InternalExtensionLimitsDto getInternalExtensionLimits(Long originCountryId, Long commLocationId) {
        int minLength = 100;
        int maxLength = 0;

        String empSql = "SELECT COALESCE(MAX(LENGTH(e.extension)), 0) AS max_len, COALESCE(MIN(LENGTH(e.extension)), 100) AS min_len " +
                        "FROM employee e " +
                        "LEFT JOIN communication_location cl ON e.communication_location_id = cl.id " +
                        "LEFT JOIN indicator i ON cl.indicator_id = i.id " +
                        "WHERE e.active = true AND (cl.id IS NULL OR cl.active = true) AND (i.id IS NULL OR i.active = true) " +
                        "  AND e.extension ~ '^[0-9]+$' AND e.extension NOT LIKE '0%' AND LENGTH(e.extension) < 8 "; // PHP: < strlen(_ACUMTOTAL_MAXEXT) - 1
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


        String rangeSql = "SELECT COALESCE(MAX(LENGTH(er.range_end::text)), 0) AS max_len, COALESCE(MIN(LENGTH(er.range_start::text)), 100) AS min_len " +
                          "FROM extension_range er " +
                          "LEFT JOIN communication_location cl ON er.comm_location_id = cl.id " +
                          "LEFT JOIN indicator i ON cl.indicator_id = i.id " +
                          "WHERE er.active = true AND (cl.id IS NULL OR cl.active = true) AND (i.id IS NULL OR i.active = true) " +
                          "  AND er.range_start::text ~ '^[0-9]+$' AND er.range_end::text ~ '^[0-9]+$' " +
                          "  AND LENGTH(er.range_start::text) < 8 AND LENGTH(er.range_end::text) < 8 " + // PHP: < strlen(_ACUMTOTAL_MAXEXT) - 1
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

        if (maxLength == 0) maxLength = 7;
        if (minLength == 100) minLength = 1;
        if (minLength > maxLength) minLength = maxLength;

        long minNumericValue = (minLength > 0 && minLength < 19) ? (long) Math.pow(10, minLength - 1) : 0L;
        long maxNumericValue = (maxLength > 0 && maxLength < 19) ? Long.parseLong("9".repeat(maxLength)) : Long.MAX_VALUE;

        String specialExtSql = "SELECT DISTINCT e.extension FROM employee e " +
                               "LEFT JOIN communication_location cl ON e.communication_location_id = cl.id " +
                               "LEFT JOIN indicator i ON cl.indicator_id = i.id " +
                               "WHERE e.active = true AND (cl.id IS NULL OR cl.active = true) AND (i.id IS NULL OR i.active = true) " +
                               "  AND (LENGTH(e.extension) >= 8 OR e.extension LIKE '0%' OR e.extension LIKE '*%' OR e.extension LIKE '#%' OR e.extension ~ '[^0-9#*+]') ";
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
        if (extension == null || extension.isEmpty() || commLocationId == null || limits == null) return Optional.empty();

        String cleanedExtension = CdrHelper.cleanPhoneNumber(extension);

        if (limits.getSpecialExtensions() != null && limits.getSpecialExtensions().contains(cleanedExtension)) {
            String sqlSpecial = "SELECT * FROM employee WHERE extension = :extension AND communication_location_id = :commLocationId AND active = true";
            Query querySpecial = entityManager.createNativeQuery(sqlSpecial, Employee.class);
            querySpecial.setParameter("extension", cleanedExtension);
            querySpecial.setParameter("commLocationId", commLocationId);
            try {
                return Optional.ofNullable((Employee) querySpecial.getSingleResult());
            } catch (NoResultException e) {
                if (!CdrHelper.isNumeric(cleanedExtension)) return Optional.empty();
            }
        }

        if (CdrHelper.isPotentialExtension(cleanedExtension, limits)) {
            String sql = "SELECT * FROM employee WHERE extension = :extension AND communication_location_id = :commLocationId AND active = true";
            Query query = entityManager.createNativeQuery(sql, Employee.class);
            query.setParameter("extension", cleanedExtension);
            query.setParameter("commLocationId", commLocationId);
            try {
                return Optional.ofNullable((Employee) query.getSingleResult());
            } catch (NoResultException e) {
                // Fall through
            }
        }

        if (CdrHelper.isNumeric(cleanedExtension)) {
            return findEmployeeByExtensionRange(cleanedExtension, commLocationId, limits);
        }

        return Optional.empty();
    }

    public Optional<Employee> findEmployeeByAuthCode(String authCode, Long commLocationId) {
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
        if (!CdrHelper.isNumeric(extension) || commLocationId == null) return Optional.empty();
        long extNumeric;
        try {
            extNumeric = Long.parseLong(extension);
        } catch (NumberFormatException e) {
            return Optional.empty();
        }

        String sql = "SELECT er.* FROM extension_range er " +
                     "WHERE er.comm_location_id = :commLocationId " +
                     "AND er.range_start <= :extNumeric AND er.range_end >= :extNumeric " +
                     "AND er.active = true " +
                     "ORDER BY (er.range_end - er.range_start) ASC, er.id";

        Query query = entityManager.createNativeQuery(sql, ExtensionRange.class);
        query.setParameter("commLocationId", commLocationId);
        query.setParameter("extNumeric", extNumeric);

        List<ExtensionRange> ranges = query.getResultList();
        if (!ranges.isEmpty()) {
            ExtensionRange bestRange = ranges.get(0);
            Employee virtualEmployee = new Employee();
            virtualEmployee.setId(null);
            virtualEmployee.setExtension(extension);
            virtualEmployee.setCommunicationLocationId(commLocationId);
            if (bestRange.getSubdivisionId() != null) {
                findSubdivisionById(bestRange.getSubdivisionId()).ifPresent(virtualEmployee::setSubdivision);
                virtualEmployee.setSubdivisionId(bestRange.getSubdivisionId());
            }
            if (bestRange.getCostCenterId() != null) {
                findCostCenterById(bestRange.getCostCenterId()).ifPresent(virtualEmployee::setCostCenter);
                virtualEmployee.setCostCenterId(bestRange.getCostCenterId());
            }
            virtualEmployee.setName((bestRange.getPrefix() != null ? bestRange.getPrefix() : "") + " " + extension + " (Range)");
            virtualEmployee.setActive(true);
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
    public List<Prefix> findMatchingPrefixes(String dialedNumber, Long originCountryId, boolean forTrunk) {
        // PHP: CargarPrefijos + buscarPrefijo
        // Query all active prefixes for the given origin country, excluding special service types.
        // Order by code length descending, then by telephony type config min descending (if available), then by telephony type ID.
        // The telephony_type_config join is complex for ordering if min/max are on TelephonyTypeConfig.
        // PHP's `TIPOTELECFG_MIN` was on `tipotelecfg`, which is `telephony_type_config`.
        // We'll simplify the order slightly if `min_value` isn't directly on `telephony_type` or `prefix`.
        String sql = "SELECT p.*, LENGTH(p.code) as code_len " +
                     "FROM prefix p " +
                     "JOIN operator o ON p.operator_id = o.id " +
                     "JOIN telephony_type tt ON p.telephone_type_id = tt.id " +
                     "LEFT JOIN telephony_type_config ttc ON tt.id = ttc.telephony_type_id AND ttc.origin_country_id = :originCountryId " + // For ordering
                     "WHERE o.origin_country_id = :originCountryId " +
                     "  AND p.active = true AND o.active = true AND tt.active = true " +
                     "  AND tt.id != :specialServiceTypeId " +
                     "ORDER BY code_len DESC, COALESCE(ttc.min_value, 0) DESC, tt.id"; // Mimic PHP order

        Query query = entityManager.createNativeQuery(sql, Prefix.class);
        query.setParameter("originCountryId", originCountryId);
        query.setParameter("specialServiceTypeId", TelephonyTypeConstants.NUMEROS_ESPECIALES);

        List<Prefix> allRetrievedPrefixes = query.getResultList();
        List<Prefix> matchingPrefixes = new ArrayList<>();

        if (!forTrunk) {
            for (Prefix p : allRetrievedPrefixes) {
                if (p.getCode() != null && !p.getCode().isEmpty() && dialedNumber.startsWith(p.getCode())) {
                    matchingPrefixes.add(p);
                    // PHP logic: for ($j = $_lista_Prefijos['max']; $j >= $_lista_Prefijos['min']; $j--) ... break;
                    // This means it takes the first set of prefixes that match the current length.
                    // Since our SQL sorts by length, we can effectively do the same by only considering
                    // prefixes of the same length as the first match found.
                    // However, PHP's `buscarPrefijo` seems to return *all* prefixes whose code is a prefix of `dialedNumber`.
                    // The selection of *which* of these to use happens in `evaluarDestino_pos`.
                    // So, for now, collect all that startWith.
                }
            }

            if (matchingPrefixes.isEmpty()) { // Fallback to local if no prefix matched
                // Find the "Local" TelephonyType
                Optional<TelephonyType> localTypeOpt = findTelephonyTypeById(TelephonyTypeConstants.LOCAL);
                if (localTypeOpt.isPresent()) {
                    TelephonyType localType = localTypeOpt.get();
                    // Check if a specific prefix entry exists for "Local" type
                    Optional<Prefix> localPrefixEntryOpt = findInternalPrefixForType(TelephonyTypeConstants.LOCAL, originCountryId);
                    if (localPrefixEntryOpt.isPresent()) {
                        matchingPrefixes.add(localPrefixEntryOpt.get());
                    } else {
                        // Create a default "Local" prefix if no explicit one is found
                        Prefix localPrefix = new Prefix();
                        localPrefix.setCode("");
                        localPrefix.setTelephonyType(localType);
                        localPrefix.setTelephoneTypeId(localType.getId());
                        findInternalOperatorByTelephonyType(TelephonyTypeConstants.LOCAL, originCountryId).ifPresent(op -> {
                            localPrefix.setOperator(op);
                            localPrefix.setOperatorId(op.getId());
                        });
                        localPrefix.setBaseValue(BigDecimal.ZERO);
                        localPrefix.setVatIncluded(false);
                        localPrefix.setVatValue(BigDecimal.ZERO);
                        localPrefix.setBandOk(true);
                        matchingPrefixes.add(localPrefix);
                    }
                }
            }
        } else { // For trunk calls, PHP logic implies using all prefixes of relevant telephony types
            matchingPrefixes.addAll(allRetrievedPrefixes);
        }
        // PHP's `krsort($arr_retornar)` on a key like "005.123" effectively sorts by prefix code string descending.
        // Our SQL already sorts by code_len DESC. If multiple prefixes of same length match, SQL order by tt.id applies.
        // This should be close enough for now.
        return matchingPrefixes;
    }

    public Optional<Prefix> findInternalPrefixForType(Long telephonyTypeId, Long originCountryId) {
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
        // PHP: CargarIndicativos
        String sql = "SELECT MIN(LENGTH(CAST(s.ndc AS TEXT))) AS min_len, MAX(LENGTH(CAST(s.ndc AS TEXT))) AS max_len, " +
                     "  MIN(LENGTH(CAST(s.initial_number AS TEXT))) as min_series_len, MAX(LENGTH(CAST(s.initial_number AS TEXT))) as max_series_len " +
                     "FROM series s JOIN indicator i ON s.indicator_id = i.id " +
                     "WHERE i.telephony_type_id = :telephonyTypeId AND i.origin_country_id IN (0, :originCountryId) " + // PHP: INDICATIVO_MPORIGEN_ID IN (0,$mporigen_id)
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
    public List<SeriesMatchDto> findIndicatorsByNumberAndType(String numberToMatch, Long telephonyTypeId, Long originCountryId, CommunicationLocation commLocation, Long prefixIdForBandOk, boolean bandOkForPrefix) {
        // PHP: buscarDestino
        String cleanedNumber = CdrHelper.cleanPhoneNumber(numberToMatch);
        if (cleanedNumber.isEmpty()) return Collections.emptyList();

        PrefixNdcLimitsDto ndcLimits = getPrefixNdcLimits(telephonyTypeId, originCountryId);
        // If no NDC limits found and it's not a local call type, no point searching series
        if (ndcLimits.getMaxNdcLength() == 0 && !Long.valueOf(TelephonyTypeConstants.LOCAL).equals(telephonyTypeId)) {
            return Collections.emptyList();
        }

        List<SeriesMatchDto> seriesMatches = new ArrayList<>();
        Long commIndicatorId = commLocation.getIndicatorId();

        // Iterate NDC lengths from max to min (PHP: for ($i = $indicamax; $i >= $indicamin; $i --))
        for (int ndcLen = ndcLimits.getMaxNdcLength(); ndcLen >= ndcLimits.getMinNdcLength(); ndcLen--) {
            if (ndcLen == 0 && !Long.valueOf(TelephonyTypeConstants.LOCAL).equals(telephonyTypeId)) continue; // Skip "no NDC" unless it's local type
            if (cleanedNumber.length() < ndcLen) continue; // Number too short for this NDC length

            String currentNdcStr = (ndcLen > 0) ? cleanedNumber.substring(0, ndcLen) : ""; // NDC part
            String remainingNumberStr = (ndcLen > 0) ? cleanedNumber.substring(ndcLen) : cleanedNumber; // Number part after NDC

            if (!CdrHelper.isNumeric(currentNdcStr) && ndcLen > 0) continue;
            if (!remainingNumberStr.isEmpty() && !CdrHelper.isNumeric(remainingNumberStr)) continue;

            Integer currentNdc = (ndcLen > 0) ? Integer.parseInt(currentNdcStr) : null;

            // PHP: $tipotele_min_tmp = $tipotele_min - $i;
            // PHP: if ($len_telefono - $i >= $tipotele_min_tmp)
            // This check ensures the remaining number part is long enough for the telephony type's minimum.
            int minLengthForTypeAfterNdc = findTelephonyTypeConfigByNumberLength(telephonyTypeId, originCountryId, 0)
                    .map(TelephonyTypeConfig::getMinValue).orElse(0);
            if (remainingNumberStr.length() < Math.max(0, minLengthForTypeAfterNdc)) {
                continue;
            }

            String sqlBase = "SELECT i.id as indicator_id, i.telephony_type_id, i.department_country, i.city_name, i.operator_id as indicator_operator_id, i.origin_country_id as indicator_origin_country_id, i.active as indicator_active, " +
                             "s.ndc as series_ndc, s.initial_number as series_initial_num, s.final_number as series_final_num ";
            String sqlFrom = "FROM indicator i JOIN series s ON i.id = s.indicator_id ";
            String sqlWhere = "WHERE i.telephony_type_id = :telephonyTypeId " +
                              "  AND i.origin_country_id IN (0, :originCountryId) " + // PHP: $filtro_origen
                              (currentNdc != null ? "  AND s.ndc = :currentNdc " : " AND (s.ndc IS NULL OR s.ndc = 0) ") +
                              "  AND i.active = true AND s.active = true ";
            String sqlOrder = "ORDER BY s.ndc DESC, "; // PHP: SERIE_NDC DESC

            if (bandOkForPrefix && prefixIdForBandOk != null) {
                sqlFrom += ", band b, band_indicator bi ";
                sqlWhere += " AND b.prefix_id = :prefixIdForBandOk " +
                            " AND bi.indicator_id = i.id AND bi.band_id = b.id " +
                            " AND (b.origin_indicator_id IS NULL OR b.origin_indicator_id = 0 OR b.origin_indicator_id = :commIndicatorId) ";
                sqlOrder += " CASE WHEN b.origin_indicator_id IS NULL OR b.origin_indicator_id = 0 THEN 1 ELSE 0 END DESC, "; // PHP: BANDA_INDICAORIGEN_ID DESC
            } else if (prefixIdForBandOk != null) {
                 sqlWhere += " AND (i.operator_id IS NULL OR i.operator_id = 0 OR i.operator_id IN (SELECT p_op.operator_id FROM prefix p_op WHERE p_op.id = :prefixIdForBandOk)) ";
            }
            sqlOrder += " (s.final_number - s.initial_number) ASC, i.id"; // PHP: SERIE_INICIAL, SERIE_FINAL

            String finalSql = sqlBase + sqlFrom + sqlWhere + sqlOrder;
            Query query = entityManager.createNativeQuery(finalSql, Tuple.class);

            query.setParameter("telephonyTypeId", telephonyTypeId);
            query.setParameter("originCountryId", originCountryId);
            if (currentNdc != null) query.setParameter("currentNdc", currentNdc);
            if (commIndicatorId != null) query.setParameter("commIndicatorId", commIndicatorId); // Used in bandOkForPrefix
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
                // Already sorted by SQL to mimic PHP's preference for smallest range
                seriesMatches.add(currentNdcMatches.get(0));
                if (ndcLen > 0) break; // PHP breaks on first NDC length that yields matches
            }
        }

        // PHP: if ($arreglo['INDICATIVO_ID'] <= 0 && $arreglo_aprox['INDICATIVO_ID'] > 0)
        // This means if no exact series match, but it's a local call, assume local indicator.
        if (seriesMatches.isEmpty() && Long.valueOf(TelephonyTypeConstants.LOCAL).equals(telephonyTypeId)) {
             if (commLocation.getIndicator() != null) {
                 seriesMatches.add(SeriesMatchDto.builder()
                    .indicator(commLocation.getIndicator())
                    .ndc(findLocalAreaCodeForIndicator(commLocation.getIndicatorId())) // Get the NDC for this local indicator
                    .destinationDescription(buildDestinationDescription(commLocation.getIndicator(), commLocation.getIndicator()))
                    .isApproximate(true)
                    .build());
             }
        }
        // PHP doesn't re-sort after finding matches for different NDC lengths. It takes the first one.
        // Our loop from maxNdcLen to minNdcLen and breaking ensures similar behavior.

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
        // PHP: $_indicativos_locales[$indicativo_id] = $row['SERIE_NDC']; (takes first from ORDER BY N DESC, SERIE_NDC)
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
        // PHP: $localext = in_array($indicativo, $_indicativos_extendida[$indicativolocal_id]);
        // $_indicativos_extendida[$indicativolocal_id] contains NDCs for the originIndicator.
        // $indicativo is the NDC of the destinationIndicator.
        String originNdc = findLocalAreaCodeForIndicator(originIndicator.getId());
        String destNdc = findLocalAreaCodeForIndicator(destinationIndicator.getId());

        return !originNdc.isEmpty() && originNdc.equals(destNdc);
    }

    public Optional<BigDecimal> findVatForTelephonyOperator(Long telephonyTypeId, Long operatorId, Long originCountryId) {
        if (telephonyTypeId == null || operatorId == null || originCountryId == null) {
             // Try to find a default VAT for the telephony type if operator is missing (e.g. for internal types)
            if (telephonyTypeId != null && originCountryId != null && operatorId == null) {
                String sqlDefault = "SELECT p.vat_value FROM prefix p " +
                                    "JOIN operator o ON p.operator_id = o.id " +
                                    "WHERE p.telephone_type_id = :telephonyTypeId " +
                                    "AND o.origin_country_id = :originCountryId " +
                                    "AND p.active = true AND o.active = true " +
                                    "ORDER BY p.id LIMIT 1"; // Get any VAT for this TT in this country
                Query queryDefault = entityManager.createNativeQuery(sqlDefault);
                queryDefault.setParameter("telephonyTypeId", telephonyTypeId);
                queryDefault.setParameter("originCountryId", originCountryId);
                try {
                    return Optional.ofNullable((BigDecimal) queryDefault.getSingleResult());
                } catch (NoResultException e) {
                    return Optional.empty();
                }
            }
            return Optional.empty();
        }

        String sql = "SELECT p.vat_value FROM prefix p " +
                     "JOIN operator o ON p.operator_id = o.id " +
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
        // PHP: VALORESPECIAL_...
        String dayOfWeekField = callTime.getDayOfWeek().name().toLowerCase() + "_enabled";

        String sql = "SELECT srv.* FROM special_rate_value srv " +
                     "LEFT JOIN prefix p ON srv.telephony_type_id = p.telephone_type_id AND srv.operator_id = p.operator_id " + // For PREFIJO_IVA
                     "WHERE srv.active = true " +
                     "  AND (srv.valid_from IS NULL OR srv.valid_from <= :callTime) " +
                     "  AND (srv.valid_to IS NULL OR srv.valid_to >= :callTime) " +
                     "  AND srv." + dayOfWeekField + " = true ";

        // PHP logic for matching: specific match OR NULL/0 (global)
        if (telephonyTypeId != null) sql += " AND (srv.telephony_type_id IS NULL OR srv.telephony_type_id = 0 OR srv.telephony_type_id = :telephonyTypeId) ";
        if (operatorId != null) sql += " AND (srv.operator_id IS NULL OR srv.operator_id = 0 OR srv.operator_id = :operatorId) ";
        if (bandId != null) sql += " AND (srv.band_id IS NULL OR srv.band_id = 0 OR srv.band_id = :bandId) ";
        if (originIndicatorId != null) sql += " AND (srv.origin_indicator_id IS NULL OR srv.origin_indicator_id = 0 OR srv.origin_indicator_id = :originIndicatorId) ";

        // PHP order: specific origin first, then specific TT, then specific Op, then specific Band
        sql += "ORDER BY CASE WHEN srv.origin_indicator_id IS NULL OR srv.origin_indicator_id = 0 THEN 1 ELSE 0 END ASC, srv.origin_indicator_id DESC, " +
               "CASE WHEN srv.telephony_type_id IS NULL OR srv.telephony_type_id = 0 THEN 1 ELSE 0 END ASC, srv.telephony_type_id DESC, " +
               "CASE WHEN srv.operator_id IS NULL OR srv.operator_id = 0 THEN 1 ELSE 0 END ASC, srv.operator_id DESC, " +
               "CASE WHEN srv.band_id IS NULL OR srv.band_id = 0 THEN 1 ELSE 0 END ASC, srv.band_id DESC, " +
               "srv.id"; // Added srv.id for deterministic tie-breaking

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
        // PHP: PBXESPECIAL_...
        String sql = "SELECT psr.* FROM pbx_special_rule psr " +
                     "WHERE psr.active = true AND (psr.comm_location_id IS NULL OR psr.comm_location_id = :commLocationId) " +
                     "ORDER BY CASE WHEN psr.comm_location_id IS NULL THEN 1 ELSE 0 END ASC, psr.comm_location_id DESC, " +
                     "LENGTH(psr.search_pattern) DESC, psr.id";
        Query query = entityManager.createNativeQuery(sql, PbxSpecialRule.class);
        query.setParameter("commLocationId", commLocationId == null ? 0L : commLocationId);
        return query.getResultList();
    }

    public Optional<SpecialService> findSpecialService(String phoneNumber, Long indicatorId, Long originCountryId) {
        // PHP: SERVESPECIAL_...
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
        // PHP: BANDA_...
        String sql = "SELECT b.* FROM band b " +
                     "WHERE b.active = true AND b.prefix_id = :prefixId ";
        if (originIndicatorId != null) {
            sql += "AND (b.origin_indicator_id IS NULL OR b.origin_indicator_id = 0 OR b.origin_indicator_id = :originIndicatorId) ";
        }
        // PHP: ORDER BY BANDA_INDICAORIGEN_ID DESC
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
        // PHP: REGLATRONCAL_...
        String sql = "SELECT tr.* FROM trunk_rule tr " +
                     "WHERE tr.active = true " +
                     "  AND (tr.trunk_id IS NULL OR tr.trunk_id = 0 OR tr.trunk_id = :trunkId) " + // Match specific trunk or global (0/NULL)
                     "  AND tr.telephony_type_id = :telephonyTypeId " +
                     "  AND (tr.origin_indicator_id IS NULL OR tr.origin_indicator_id = 0 OR tr.origin_indicator_id = :originIndicatorId) " +
                     "  AND (tr.indicator_ids = '' OR tr.indicator_ids IS NULL OR tr.indicator_ids = :indicatorIdToMatch OR " +
                     "       tr.indicator_ids LIKE :indicatorIdToMatch || ',%' OR " +
                     "       tr.indicator_ids LIKE '%,' || :indicatorIdToMatch OR " +
                     "       tr.indicator_ids LIKE '%,' || :indicatorIdToMatch || ',%') " +
                     // PHP Order: specific trunk first, then specific origin_indicator, then most specific indicator_ids match
                     "ORDER BY CASE WHEN tr.trunk_id IS NULL OR tr.trunk_id = 0 THEN 1 ELSE 0 END ASC, tr.trunk_id DESC, " +
                     "CASE WHEN tr.origin_indicator_id IS NULL OR tr.origin_indicator_id = 0 THEN 1 ELSE 0 END ASC, tr.origin_indicator_id DESC, " +
                     "LENGTH(tr.indicator_ids) DESC, tr.id"; // Longer indicator_ids string means more specific

        Query query = entityManager.createNativeQuery(sql, TrunkRule.class);
        query.setParameter("trunkId", trunkId == null ? 0L : trunkId);
        query.setParameter("telephonyTypeId", telephonyTypeId);
        query.setParameter("originIndicatorId", originIndicatorId == null ? 0L : originIndicatorId);
        query.setParameter("indicatorIdToMatch", indicatorIdToMatch == null ? "" : indicatorIdToMatch);

        return query.getResultList();
    }

    public Optional<TrunkRate> findTrunkRate(Long trunkId, Long operatorId, Long telephonyTypeId) {
        // PHP: TARIFATRONCAL_...
        String sql = "SELECT tr.* FROM trunk_rate tr " +
                     "WHERE tr.active = true " + // Assuming TrunkRate might have an active field, though not in provided entity. If not, remove.
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
        // PHP: TIPOTELECFG_...
        if (telephonyTypeId == null || originCountryId == null ) {
            return Optional.empty();
        }
        // Find a config where the numberLength falls within min_value and max_value
        String sql = "SELECT ttc.* FROM telephony_type_config ttc " +
                     "WHERE ttc.telephony_type_id = :telephonyTypeId " +
                     "  AND ttc.origin_country_id = :originCountryId " +
                     "  AND ttc.min_value <= :numberLength AND ttc.max_value >= :numberLength " +
                     "LIMIT 1"; // Assuming only one config should match for a given length
        Query query = entityManager.createNativeQuery(sql, TelephonyTypeConfig.class);
        query.setParameter("telephonyTypeId", telephonyTypeId);
        query.setParameter("originCountryId", originCountryId);
        query.setParameter("numberLength", numberLength);
        try {
            return Optional.ofNullable((TelephonyTypeConfig) query.getSingleResult());
        } catch (NoResultException e) {
            // If no exact match for length, try to get a default config for the type/country (e.g., where min/max are 0 or cover all)
            // This part of PHP logic (CargarPrefijos -> TIPOTELECFG_MIN/MAX) is complex if no direct length match.
            // For now, we only return if a length-specific config is found.
            // PHP uses these min/max to determine the "effective" length of a number for a given type.
            return Optional.empty();
        }
    }

    private String buildDestinationDescription(Indicator matchedIndicator, Indicator originCommIndicator) {
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
}