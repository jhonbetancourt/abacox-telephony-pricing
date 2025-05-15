// FILE: com/infomedia/abacox/telephonypricing/cdr/PrefixInfoLookupService.java
package com.infomedia.abacox.telephonypricing.cdr;

import com.infomedia.abacox.telephonypricing.entity.CommunicationLocation;
import com.infomedia.abacox.telephonypricing.entity.Operator;
import com.infomedia.abacox.telephonypricing.entity.Prefix;
import com.infomedia.abacox.telephonypricing.entity.TelephonyType;
import jakarta.persistence.EntityManager;
import jakarta.persistence.NoResultException;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.Query;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.util.*;
import java.util.stream.Collectors;

@Service
@Log4j2
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class PrefixInfoLookupService {

    @PersistenceContext
    private final EntityManager entityManager;
    private final CdrProcessingConfig configService;

    /**
     * Finds matching prefixes based on the processed number and call context.
     * This version is refined to more closely match the PHP `buscarPrefijo` logic,
     * especially in how candidate prefixes are ordered and how local prefixes are handled.
     *
     * @param processedNumber                      The number to match against, potentially preprocessed.
     * @param originCountryId                      The origin country ID for filtering.
     * @param isTrunkCall                          True if the call is via a trunk, affecting prefix selection logic.
     * @param allowedTelephonyTypeIdsForTrunk      If isTrunkCall, this set contains TelephonyType IDs allowed by the trunk.
     * @param forcedTelephonyTypeIdFromPreprocessing Optional TelephonyType ID determined during preprocessing.
     * @param commLocation                         The communication location, used for local fallback context.
     * @return A list of maps, each representing a matching prefix and its related details, ordered according to PHP logic.
     */
    public List<Map<String, Object>> findMatchingPrefixes(
            String processedNumber,
            Long originCountryId,
            boolean isTrunkCall,
            Set<Long> allowedTelephonyTypeIdsForTrunk,
            Long forcedTelephonyTypeIdFromPreprocessing,
            CommunicationLocation commLocation
    ) {
        if (originCountryId == null || !StringUtils.hasText(processedNumber)) {
            log.warn("findMatchingPrefixes - Invalid input: processedNumber or originCountryId is null/empty.");
            return Collections.emptyList();
        }

        String numberForLog = processedNumber.length() > 6 ? processedNumber.substring(0, 6) + "..." : processedNumber;
        log.debug("Finding matching prefixes for number: '{}', originCountryId: {}, isTrunkCall: {}, allowedTrunkTypes: {}, forcedType: {}",
                numberForLog, originCountryId, isTrunkCall, allowedTelephonyTypeIdsForTrunk, forcedTelephonyTypeIdFromPreprocessing);

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
        sqlBuilder.append("  AND op.origin_country_id = :originCountryId ");
        sqlBuilder.append("  AND tt.id != :specialCallsType "); // Exclude TIPOTELE_ESPECIALES

        Map<String, Object> queryParams = new HashMap<>();
        queryParams.put("originCountryId", originCountryId);
        queryParams.put("specialCallsType", CdrProcessingConfig.TIPOTELE_ESPECIALES);

        if (isTrunkCall && allowedTelephonyTypeIdsForTrunk != null && !allowedTelephonyTypeIdsForTrunk.isEmpty()) {
            sqlBuilder.append("  AND p.telephone_type_id IN (:allowedTelephonyTypeIdsForTrunk) ");
            queryParams.put("allowedTelephonyTypeIdsForTrunk", allowedTelephonyTypeIdsForTrunk);
            log.trace("Trunk call: Filtering prefixes by allowed TelephonyType IDs: {}", allowedTelephonyTypeIdsForTrunk);
        } else {
            // Non-trunk call: match by prefix code against the start of the number
            sqlBuilder.append("  AND :number LIKE p.code || '%' ");
            queryParams.put("number", processedNumber);
            log.trace("Non-trunk call or no specific trunk types: Matching prefixes by code against number: '{}'", numberForLog);
        }

        if (forcedTelephonyTypeIdFromPreprocessing != null) {
            sqlBuilder.append("  AND p.telephone_type_id = :forcedTelephonyTypeId ");
            queryParams.put("forcedTelephonyTypeId", forcedTelephonyTypeIdFromPreprocessing);
            log.trace("Further filtering prefixes by forced TelephonyType ID: {}", forcedTelephonyTypeIdFromPreprocessing);
        }

        // ORDER BY to match PHP's CargarPrefijos and krsort logic on sprintf key
        sqlBuilder.append("ORDER BY LENGTH(p.code) DESC, p.code DESC, COALESCE(ttc.min_value, 0) DESC, tt.id ASC, p.id ASC");

        Query query = entityManager.createNativeQuery(sqlBuilder.toString());
        queryParams.forEach(query::setParameter);

        List<Object[]> results = query.getResultList();
        List<Map<String, Object>> initialMappedResults = new ArrayList<>();
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
            initialMappedResults.add(map);
        }

        log.trace("Found {} candidate prefixes from SQL for number lookup (isTrunk: {})", initialMappedResults.size(), isTrunkCall);

        List<Map<String, Object>> finalResults = new ArrayList<>(initialMappedResults);

        if (!isTrunkCall && !finalResults.isEmpty()) {
            String longestMatchingPrefixCode = (String) finalResults.get(0).get("prefix_code");
            // Handle null prefix code case, treat as empty string for comparison
            final String effectiveLongestCode = (longestMatchingPrefixCode == null) ? "" : longestMatchingPrefixCode;

            finalResults = finalResults.stream()
                    .filter(map -> {
                        String currentCode = (String) map.get("prefix_code");
                        return (currentCode == null ? "" : currentCode).equals(effectiveLongestCode);
                    })
                    .collect(Collectors.toList());
            log.debug("Non-trunk call: Filtered to {} prefixes matching the longest code '{}'", finalResults.size(), effectiveLongestCode);
        }


        // Explicit Local Prefix Addition (for non-trunk calls), mimicking PHP's logic
        if (!isTrunkCall) {
            // Check if a prefix with TIPOTELE_LOCAL was already included in the filtered results
            boolean localPrefixFoundInFilteredResults = finalResults.stream()
                    .anyMatch(map -> Long.valueOf(CdrProcessingConfig.TIPOTELE_LOCAL).equals(map.get("telephony_type_id")));

            if (!localPrefixFoundInFilteredResults) {
                log.debug("TIPOTELE_LOCAL not found in filtered results for non-trunk call, attempting explicit add for number: {}", numberForLog);

                Optional<Operator> localInternalOperatorOpt = configService.getOperatorInternal(CdrProcessingConfig.TIPOTELE_LOCAL, originCountryId);
                if (localInternalOperatorOpt.isPresent()) {
                    Long localOperatorId = localInternalOperatorOpt.get().getId();
                    Optional<Prefix> localPrefixOpt = findPrefixByTypeOperatorOrigin(CdrProcessingConfig.TIPOTELE_LOCAL, localOperatorId, originCountryId);

                    if (localPrefixOpt.isPresent()) {
                        Prefix localPrefix = localPrefixOpt.get();
                        Map<String, Integer> localTypeConfig = configService.getTelephonyTypeMinMax(CdrProcessingConfig.TIPOTELE_LOCAL, originCountryId);
                        int minLengthForLocal = localTypeConfig.getOrDefault("min", 0);

                        // PHP: if ($lnumero >= $arreglo_local['tipotele_min'])
                        if (processedNumber.length() >= minLengthForLocal) {
                            Map<String, Object> localPrefixMap = new HashMap<>();
                            localPrefixMap.put("prefix_id", localPrefix.getId());
                            localPrefixMap.put("prefix_code", localPrefix.getCode()); // Might be empty for local
                            localPrefixMap.put("prefix_base_value", localPrefix.getBaseValue());
                            localPrefixMap.put("prefix_vat_included", localPrefix.isVatIncluded());
                            localPrefixMap.put("prefix_vat_value", localPrefix.getVatValue());
                            localPrefixMap.put("prefix_band_ok", localPrefix.isBandOk());
                            localPrefixMap.put("telephony_type_id", CdrProcessingConfig.TIPOTELE_LOCAL);
                            localPrefixMap.put("telephony_type_name", configService.getTelephonyTypeById(CdrProcessingConfig.TIPOTELE_LOCAL).map(TelephonyType::getName).orElse("Local"));
                            localPrefixMap.put("telephony_type_uses_trunks", configService.getTelephonyTypeById(CdrProcessingConfig.TIPOTELE_LOCAL).map(TelephonyType::isUsesTrunks).orElse(false));
                            localPrefixMap.put("operator_id", localOperatorId);
                            localPrefixMap.put("operator_name", localInternalOperatorOpt.get().getName());
                            localPrefixMap.put("telephony_type_min", minLengthForLocal);
                            localPrefixMap.put("telephony_type_max", localTypeConfig.getOrDefault("max", 0));

                            finalResults.add(localPrefixMap); // Add to end of results
                            log.info("Explicitly added TIPOTELE_LOCAL prefix to candidates for number: {}", numberForLog);
                        } else {
                            log.debug("Explicit local prefix add: number {} length {} is less than min length {} for local type.", numberForLog, processedNumber.length(), minLengthForLocal);
                        }
                    } else {
                        log.warn("Explicit local prefix add: Could not find prefix definition for TIPOTELE_LOCAL ({}), Operator {}, Country {}.", CdrProcessingConfig.TIPOTELE_LOCAL, localOperatorId, originCountryId);
                    }
                } else {
                    log.warn("Explicit local prefix add: Could not find internal operator for TIPOTELE_LOCAL ({}) in Country {}.", CdrProcessingConfig.TIPOTELE_LOCAL, originCountryId);
                }
            } else {
                log.debug("TIPOTELE_LOCAL was already found in filtered results. No explicit add needed.");
            }
        }
        return finalResults;
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
        String numberForLog = number.length() > 6 ? number.substring(0, 6) + "..." : number;
        log.debug("Finding internal prefix match for number starting with: {}, originCountryId: {}", numberForLog, originCountryId);

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
            log.trace("Found internal prefix match for number {}: Type={}, Op={}", numberForLog, result[0], result[1]);
            return Optional.of(map);
        } catch (NoResultException e) {
            log.trace("No internal prefix match found for number {}", numberForLog);
            return Optional.empty();
        } catch (Exception e) {
            log.error("Error finding internal prefix match for number {}: {}", numberForLog, e.getMessage(), e);
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
            minNdcLength = 0; maxNdcLength = 0; // For local, NDC can be effectively '0' or empty string
            log.trace("Treating as LOCAL type lookup (effective NDC length 0 for series.ndc='0')");
        }

        Map<String, Integer> typeMinMaxConfig = configService.getTelephonyTypeMinMax(telephonyTypeId, originCountryId);
        int typeMinDigits = typeMinMaxConfig.getOrDefault("min", 0);


        for (int ndcLength = maxNdcLength; ndcLength >= minNdcLength; ndcLength--) {
            String ndcStr = "";
            String subscriberNumberStr = numberWithoutPrefix;

            if (ndcLength > 0 && numberWithoutPrefix.length() >= ndcLength) {
                ndcStr = numberWithoutPrefix.substring(0, ndcLength);
                subscriberNumberStr = numberWithoutPrefix.substring(ndcLength);
            } else if (ndcLength > 0) { // Not enough digits for this NDC length
                continue;
            }
            // If ndcLength is 0 (local fallback), ndcStr remains empty, subscriberNumberStr is the full number.

            int minSubscriberLength = Math.max(0, typeMinDigits - ndcLength);
            if (subscriberNumberStr.length() < minSubscriberLength) {
                log.trace("Subscriber part {} (from num {}) too short for NDC {} (min subscriber length {})", subscriberNumberStr, numberWithoutPrefix, ndcStr, minSubscriberLength);
                continue;
            }

            // Ensure ndcStr (if not empty) and subscriberNumberStr are numeric.
            // ndcStr can be empty for local calls (NDC='0'). subscriberNumberStr must be numeric.
            if ((!ndcStr.isEmpty() && !ndcStr.matches("\\d*")) || !subscriberNumberStr.matches("\\d+")) {
                log.trace("Skipping NDC check: NDC '{}' or Subscriber '{}' is not numeric.", ndcStr, subscriberNumberStr);
                continue;
            }

            String ndcParam = ndcStr.isEmpty() ? "0" : ndcStr; // Use "0" for empty NDC (local)
            long subscriberNumber = Long.parseLong(subscriberNumberStr);

            StringBuilder sqlBuilder = new StringBuilder();
            sqlBuilder.append("SELECT ");
            sqlBuilder.append(" i.id as indicator_id, i.department_country, i.city_name, i.operator_id as indicator_operator_id, ");
            sqlBuilder.append(" s.ndc as series_ndc, s.initial_number as series_initial, s.final_number as series_final, ");
            sqlBuilder.append(" s.company as series_company ");
            sqlBuilder.append("FROM series s ");
            sqlBuilder.append("JOIN indicator i ON s.indicator_id = i.id ");

            String bandJoinAndWhere = "";
            if (isPrefixBandOk) {
                sqlBuilder.append("JOIN band b ON b.prefix_id = :currentPrefixId AND b.active = true ");
                sqlBuilder.append("JOIN band_indicator bi ON bi.band_id = b.id AND bi.indicator_id = i.id ");
                bandJoinAndWhere = " AND b.origin_indicator_id IN (0, :originCommLocationIndicatorId) ";
            }

            sqlBuilder.append("WHERE i.active = true AND s.active = true ");
            sqlBuilder.append(bandJoinAndWhere); // Add band conditions here
            sqlBuilder.append("  AND i.telephony_type_id = :telephonyTypeId ");
            sqlBuilder.append("  AND s.ndc = :ndc ");

            if (telephonyTypeId != CdrProcessingConfig.TIPOTELE_INTERNACIONAL && telephonyTypeId != CdrProcessingConfig.TIPOTELE_SATELITAL) {
                sqlBuilder.append(" AND i.origin_country_id = :originCountryId ");
            } else {
                // For international/satellite, allow global indicators (origin_country_id = 0) or specific match
                sqlBuilder.append(" AND i.origin_country_id IN (0, :originCountryId) ");
            }

            if (!isPrefixBandOk && currentPrefixId != null) {
                // If not using bands, ensure indicator's operator is compatible with prefix's operator
                // (or indicator has no specific operator)
                sqlBuilder.append(" AND (i.operator_id = 0 OR i.operator_id IS NULL OR i.operator_id IN (SELECT p_sub.operator_id FROM prefix p_sub WHERE p_sub.id = :currentPrefixId)) ");
            }

            sqlBuilder.append("  AND CAST(s.initial_number AS BIGINT) <= :subscriberNum AND CAST(s.final_number AS BIGINT) >= :subscriberNum ");

            // Revised ORDER BY
            sqlBuilder.append("ORDER BY ");
            sqlBuilder.append("  CASE WHEN s.ndc ~ '^[0-9]+$' AND CAST(s.ndc AS INTEGER) >= 0 THEN 0 ELSE 1 END ASC, "); // Prioritize non-negative NDCs
            sqlBuilder.append("  i.origin_country_id DESC, "); // Specific country over global for INT/SAT
            if (isPrefixBandOk) {
                sqlBuilder.append(" b.origin_indicator_id DESC, "); // Specific origin band over global band
            }
            if (ndcLength > 0) { // Only consider NDC length if it's not a local (NDC=0) check
                sqlBuilder.append(" LENGTH(s.ndc) DESC, ");
            }
            sqlBuilder.append("  (CAST(s.final_number AS BIGINT) - CAST(s.initial_number AS BIGINT)) ASC "); // Smallest range first
            sqlBuilder.append("LIMIT 1");


            Query query = entityManager.createNativeQuery(sqlBuilder.toString());
            query.setParameter("telephonyTypeId", telephonyTypeId);
            query.setParameter("ndc", ndcParam);
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
        // Ensure NDC='0' (for local) results in length 0, other NDCs use their actual length.
        sqlBuilder.append("SELECT COALESCE(MIN(CASE WHEN s.ndc = '0' THEN 0 ELSE LENGTH(s.ndc) END), 0) as min_len, ");
        sqlBuilder.append("       COALESCE(MAX(CASE WHEN s.ndc = '0' THEN 0 ELSE LENGTH(s.ndc) END), 0) as max_len ");
        sqlBuilder.append("FROM series s ");
        sqlBuilder.append("JOIN indicator i ON s.indicator_id = i.id ");
        sqlBuilder.append("WHERE i.active = true AND s.active = true ");
        sqlBuilder.append("  AND i.telephony_type_id = :telephonyTypeId ");
        if (telephonyTypeId.equals(CdrProcessingConfig.TIPOTELE_INTERNACIONAL) || telephonyTypeId.equals(CdrProcessingConfig.TIPOTELE_SATELITAL)) {
            sqlBuilder.append("  AND i.origin_country_id IN (0, :originCountryId) ");
        } else {
            sqlBuilder.append("  AND i.origin_country_id = :originCountryId ");
        }
        sqlBuilder.append("  AND s.ndc IS NOT NULL "); // Ensure ndc is not null for LENGTH function

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
        sqlBuilder.append("  AND CAST(s.initial_number AS BIGINT) <= :subscriberNum AND CAST(s.final_number AS BIGINT) >= :subscriberNum ");
        sqlBuilder.append("  AND i.origin_country_id IN (0, :originCountryId) "); // National can be global or country-specific

        sqlBuilder.append("ORDER BY i.origin_country_id DESC, LENGTH(s.ndc) DESC, (CAST(s.final_number AS BIGINT) - CAST(s.initial_number AS BIGINT)) ASC ");
        sqlBuilder.append("LIMIT 1");

        Query query = entityManager.createNativeQuery(sqlBuilder.toString(), String.class);
        query.setParameter("nationalTelephonyTypeId", nationalTelephonyTypeId);
        query.setParameter("ndc", String.valueOf(ndc));
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
        Long effectiveIndicatorId = indicatorId; // Can be null or 0 for bands not specific to a destination indicator

        log.debug("Finding band for prefixId: {}, effectiveIndicatorId: {}, effectiveOriginIndicatorId: {}", prefixId, effectiveIndicatorId, effectiveOriginIndicatorId);

        StringBuilder sqlBuilder = new StringBuilder();
        sqlBuilder.append("SELECT b.id as band_id, b.value as band_value, b.vat_included as band_vat_included, b.name as band_name ");
        sqlBuilder.append("FROM band b ");
        if (effectiveIndicatorId != null && effectiveIndicatorId > 0) {
            // Join with band_indicator only if a specific destination indicator is being checked
            sqlBuilder.append("JOIN band_indicator bi ON b.id = bi.band_id AND bi.indicator_id = :indicatorId ");
        }
        sqlBuilder.append("WHERE b.active = true ");
        sqlBuilder.append("  AND b.prefix_id = :prefixId ");
        if (effectiveIndicatorId == null || effectiveIndicatorId <= 0) {
            // If no specific indicator (or global indicator 0), look for bands not linked to any specific indicator via band_indicator.
            // These are bands applicable to the prefix generally, or for a specific origin, but not a specific destination.
            sqlBuilder.append(" AND NOT EXISTS (SELECT 1 FROM band_indicator bi_check WHERE bi_check.band_id = b.id) ");
        }
        sqlBuilder.append("  AND b.origin_indicator_id IN (0, :originIndicatorId) "); // Match specific origin or global (0)
        sqlBuilder.append("ORDER BY b.origin_indicator_id DESC "); // Prioritize specific origin over global
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
            log.trace("Found band {} for prefix {}, effective indicator {}", map.get("band_id"), prefixId, effectiveIndicatorId);
            return Optional.of(map);
        } catch (NoResultException e) {
            log.trace("No matching band found for prefix {}, effective indicator {}", prefixId, effectiveIndicatorId);
            return Optional.empty();
        } catch (Exception e) {
            log.error("Error finding band for prefixId: {}, indicatorId: {}, originIndicatorId: {}", prefixId, effectiveIndicatorId, effectiveOriginIndicatorId, e);
            return Optional.empty();
        }
    }

    public Optional<Integer> findLocalNdcForIndicator(Long indicatorId) {
        if (indicatorId == null || indicatorId <= 0) return Optional.empty();
        log.debug("Finding local NDC for indicatorId: {}", indicatorId);
        // Ensure NDC is treated as a number for ordering and filtering, but selected as string for flexibility
        // The PHP logic `ORDER BY N DESC, SERIE_NDC` on a VARCHAR SERIE_NDC would sort '10' before '2'.
        // To achieve numeric sort, we cast.
        String sql = "SELECT s.ndc FROM series s " +
                "WHERE s.indicator_id = :indicatorId " +
                "  AND s.ndc IS NOT NULL AND s.ndc != '0' AND s.ndc ~ '^[1-9][0-9]*$' " + // Ensure ndc is a positive number string
                "GROUP BY s.ndc " +
                "ORDER BY COUNT(*) DESC, CAST(s.ndc AS INTEGER) ASC " +
                "LIMIT 1";
        Query query = entityManager.createNativeQuery(sql, String.class); // Expecting String NDC
        query.setParameter("indicatorId", indicatorId);
        try {
            String ndcStr = (String) query.getSingleResult();
            Integer ndc = Integer.parseInt(ndcStr); // Convert to Integer after fetching
            log.trace("Found local NDC {} for indicator {}", ndc, indicatorId);
            return Optional.of(ndc);
        } catch (NoResultException e) {
            log.warn("No positive numeric NDC found for indicatorId: {}", indicatorId);
            return Optional.empty();
        } catch (NumberFormatException nfe) {
            log.error("NDC value fetched for indicatorId {} is not a valid integer: {}", indicatorId, nfe.getMessage());
            return Optional.empty();
        }
        catch (Exception e) {
            log.error("Error finding local NDC for indicatorId: {}", indicatorId, e);
            return Optional.empty();
        }
    }

    public boolean isLocalExtended(Integer destinationNdc, Long originIndicatorId) {
        if (destinationNdc == null || originIndicatorId == null || originIndicatorId <= 0) {
            return false;
        }
        log.debug("Checking if NDC {} is local extended for origin indicator {}", destinationNdc, originIndicatorId);
        String sql = "SELECT COUNT(s.id) FROM series s WHERE s.indicator_id = :originIndicatorId AND s.ndc = :destinationNdcStr AND s.active = true";
        Query query = entityManager.createNativeQuery(sql, Long.class);
        query.setParameter("originIndicatorId", originIndicatorId);
        query.setParameter("destinationNdcStr", String.valueOf(destinationNdc));
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

    /**
     * Finds national series details for a given NDC and subscriber number.
     * This query mirrors the one used in PHP's `_esCelular_fijo` for 10-digit numbers starting with "60".
     *
     * @param ndc              The National Destination Code (single digit string).
     * @param subscriberNumber The subscriber part of the number (long).
     * @param originCountryId  The origin country ID.
     * @return Optional map containing "department_country", "city_name", "company" if a match is found.
     */
    public Optional<Map<String, String>> findNationalSeriesDetailsByNdcAndSubscriber(String ndc, long subscriberNumber, Long originCountryId) {
        if (!StringUtils.hasText(ndc) || originCountryId == null) {
            log.warn("findNationalSeriesDetails - Invalid input: ndc or originCountryId is null/empty.");
            return Optional.empty();
        }
        log.debug("Finding national series details for NDC: {}, Subscriber: {}, OriginCountryId: {}", ndc, subscriberNumber, originCountryId);

        StringBuilder sqlBuilder = new StringBuilder();
        sqlBuilder.append("SELECT i.department_country, i.city_name, s.company ");
        sqlBuilder.append("FROM series s ");
        sqlBuilder.append("JOIN indicator i ON s.indicator_id = i.id ");
        sqlBuilder.append("WHERE i.telephony_type_id = :nationalType "); // TIPOTELE_NACIONAL
        sqlBuilder.append("  AND s.ndc = :ndc ");
        sqlBuilder.append("  AND CAST(s.initial_number AS BIGINT) <= :subscriberNumber AND CAST(s.final_number AS BIGINT) >= :subscriberNumber ");
        // The specific prefix ID (7000012L) implies a reference to a specific operator or prefix configuration for national calls.
        // This should ideally be more dynamic or configurable if it's not a fixed system-wide constant.
        sqlBuilder.append("  AND (i.operator_id = 0 OR i.operator_id IS NULL OR i.operator_id IN (SELECT p_sub.operator_id FROM prefix p_sub WHERE p_sub.id = :nationalRefPrefixId)) ");
        sqlBuilder.append("  AND i.origin_country_id IN (0, :originCountryId) "); // Colombia or global
        sqlBuilder.append("  AND s.active = true AND i.active = true ");
        sqlBuilder.append("ORDER BY i.origin_country_id DESC, LENGTH(s.ndc) DESC, (CAST(s.final_number AS BIGINT) - CAST(s.initial_number AS BIGINT)) ASC ");
        sqlBuilder.append("LIMIT 1");

        Query query = entityManager.createNativeQuery(sqlBuilder.toString());
        query.setParameter("nationalType", CdrProcessingConfig.TIPOTELE_NACIONAL);
        query.setParameter("ndc", ndc);
        query.setParameter("subscriberNumber", subscriberNumber);
        query.setParameter("nationalRefPrefixId", CdrProcessingConfig.NATIONAL_REFERENCE_PREFIX_ID);
        query.setParameter("originCountryId", originCountryId);

        try {
            Object[] result = (Object[]) query.getSingleResult();
            Map<String, String> details = new HashMap<>();
            details.put("department_country", (String) result[0]);
            details.put("city_name", (String) result[1]);
            details.put("company", (String) result[2]);
            log.trace("Found national series details for NDC {}: {}", ndc, details);
            return Optional.of(details);
        } catch (NoResultException e) {
            log.trace("No national series details found for NDC: {}, Subscriber: {}", ndc, subscriberNumber);
            return Optional.empty();
        } catch (Exception e) {
            log.error("Error finding national series details for NDC: {}, Subscriber: {}: {}", ndc, subscriberNumber, e.getMessage(), e);
            return Optional.empty();
        }
    }
}