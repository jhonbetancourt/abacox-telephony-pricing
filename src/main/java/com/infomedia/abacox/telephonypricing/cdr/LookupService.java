package com.infomedia.abacox.telephonypricing.cdr;

import com.infomedia.abacox.telephonypricing.entity.*;
import jakarta.persistence.EntityManager;
import jakarta.persistence.NoResultException;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.Query;
import lombok.extern.log4j.Log4j2;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

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

        int minLength = 100; // Default high to find true min
        int maxLength = 0;   // Default low to find true max

        // Query 1: Employee extensions
        String empSql = "SELECT MAX(LENGTH(e.extension)) AS max_len, MIN(LENGTH(e.extension)) AS min_len " +
                        "FROM employee e " +
                        "JOIN communication_location cl ON e.communication_location_id = cl.id " +
                        "JOIN indicator i ON cl.indicator_id = i.id " +
                        "WHERE e.active = true AND cl.active = true AND i.active = true " +
                        "  AND e.extension ~ '^[0-9]+$' AND e.extension NOT LIKE '0%' AND LENGTH(e.extension) < 8 ";
        if (originCountryId != null) empSql += " AND i.origin_country_id = :originCountryId ";
        if (commLocationId != null) empSql += " AND e.communication_location_id = :commLocationId ";
        
        Query empQuery = entityManager.createNativeQuery(empSql);
        if (originCountryId != null) empQuery.setParameter("originCountryId", originCountryId);
        if (commLocationId != null) empQuery.setParameter("commLocationId", commLocationId);

        try {
            Object[] empResult = (Object[]) empQuery.getSingleResult();
            if (empResult[0] != null) maxLength = Math.max(maxLength, ((Number) empResult[0]).intValue());
            if (empResult[1] != null) minLength = Math.min(minLength, ((Number) empResult[1]).intValue());
        } catch (NoResultException e) {
            // No employees match, use defaults or proceed to ranges
        }

        // Query 2: Extension ranges
        String rangeSql = "SELECT MAX(LENGTH(er.range_end)) AS max_len, MIN(LENGTH(er.range_start)) AS min_len " +
                          "FROM extension_range er " +
                          "JOIN communication_location cl ON er.comm_location_id = cl.id " +
                          "JOIN indicator i ON cl.indicator_id = i.id " +
                          "WHERE er.active = true AND cl.active = true AND i.active = true " +
                          "  AND er.range_start ~ '^[0-9]+$' AND er.range_end ~ '^[0-9]+$' " +
                          "  AND LENGTH(er.range_start) < 8 AND LENGTH(er.range_end) < 8 " +
                          "  AND er.range_end >= er.range_start ";
        if (originCountryId != null) rangeSql += " AND i.origin_country_id = :originCountryId ";
        if (commLocationId != null) rangeSql += " AND er.comm_location_id = :commLocationId ";

        Query rangeQuery = entityManager.createNativeQuery(rangeSql);
        if (originCountryId != null) rangeQuery.setParameter("originCountryId", originCountryId);
        if (commLocationId != null) rangeQuery.setParameter("commLocationId", commLocationId);
        
        try {
            Object[] rangeResult = (Object[]) rangeQuery.getSingleResult();
            if (rangeResult[0] != null) maxLength = Math.max(maxLength, ((Number) rangeResult[0]).intValue());
            if (rangeResult[1] != null) minLength = Math.min(minLength, ((Number) rangeResult[1]).intValue());
        } catch (NoResultException e) {
            // No ranges match
        }
        
        if (maxLength == 0) maxLength = 7; // Default if no data found
        if (minLength == 100) minLength = 1; // Default if no data found
        if (minLength > maxLength) minLength = maxLength; // Ensure min <= max

        long minNumericValue = (minLength > 0) ? (long) Math.pow(10, minLength - 1) : 0L;
        long maxNumericValue = (maxLength > 0) ? Long.parseLong("9".repeat(maxLength)) : 0L;


        // Query 3: Special extensions (alphanumeric, very long, starting with 0, #, *)
        String specialExtSql = "SELECT DISTINCT e.extension FROM employee e " +
                               "JOIN communication_location cl ON e.communication_location_id = cl.id " +
                               "JOIN indicator i ON cl.indicator_id = i.id " +
                               "WHERE e.active = true AND cl.active = true AND i.active = true " +
                               "  AND (LENGTH(e.extension) >= 8 OR e.extension LIKE '0%' OR e.extension LIKE '*%' OR e.extension LIKE '#%' OR e.extension ~ '[^0-9]') ";
        if (originCountryId != null) specialExtSql += " AND i.origin_country_id = :originCountryId ";
        if (commLocationId != null) specialExtSql += " AND e.communication_location_id = :commLocationId ";
        
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
        
        // First, check if it's a special extension
        if (limits.getSpecialExtensions() != null && limits.getSpecialExtensions().contains(CdrHelper.cleanPhoneNumber(extension))) {
             // If it's special, direct lookup is needed as it might not be purely numeric or within standard range
        } else if (!CdrHelper.isPotentialExtension(extension, limits)) {
            // Not a special one, and not matching numeric/length criteria for standard extensions
            // Try range lookup only if it's numeric, as ranges are numeric
            if (CdrHelper.isNumeric(extension)) {
                 return findEmployeeByExtensionRange(extension, commLocationId, limits);
            }
            return Optional.empty();
        }

        String sql = "SELECT * FROM employee WHERE extension = :extension AND communication_location_id = :commLocationId AND active = true";
        Query query = entityManager.createNativeQuery(sql, Employee.class);
        query.setParameter("extension", extension);
        query.setParameter("commLocationId", commLocationId);
        try {
            return Optional.ofNullable((Employee) query.getSingleResult());
        } catch (NoResultException e) {
            if (CdrHelper.isNumeric(extension)) { // Only try range if numeric and direct match failed
                return findEmployeeByExtensionRange(extension, commLocationId, limits);
            }
            return Optional.empty();
        }
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
            return Optional.empty(); // Not a valid number for range check
        }

        String sql = "SELECT er.* FROM extension_range er " +
                     "WHERE er.comm_location_id = :commLocationId " +
                     "AND er.range_start <= :extNumeric AND er.range_end >= :extNumeric " +
                     "AND er.active = true " +
                     "ORDER BY (er.range_end - er.range_start) ASC"; 

        Query query = entityManager.createNativeQuery(sql, ExtensionRange.class);
        query.setParameter("commLocationId", commLocationId);
        query.setParameter("extNumeric", extNumeric);

        List<ExtensionRange> ranges = query.getResultList();
        if (!ranges.isEmpty()) {
            ExtensionRange bestRange = ranges.get(0); 
            Employee virtualEmployee = new Employee();
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
            virtualEmployee.setName("Range: " + bestRange.getPrefix() + bestRange.getRangeStart() + "-" + bestRange.getRangeEnd());
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
        String sql = "SELECT p.* FROM prefix p " +
                     "JOIN operator o ON p.operator_id = o.id " +
                     "JOIN telephony_type tt ON p.telephone_type_id = tt.id " +
                     "WHERE :dialedNumber LIKE p.code || '%' " + 
                     "AND o.origin_country_id = :originCountryId " +
                     "AND p.active = true AND o.active = true AND tt.active = true " +
                     "ORDER BY LENGTH(p.code) DESC"; 

        Query query = entityManager.createNativeQuery(sql, Prefix.class);
        query.setParameter("dialedNumber", dialedNumber);
        query.setParameter("originCountryId", originCountryId);
        
        List<Prefix> prefixes = query.getResultList();

        if (!forTrunk && prefixes.isEmpty()) {
            // PHP fallback: if not for trunk and no prefix found, try local type.
            // This means creating a "dummy" prefix representing a local call.
            findTelephonyTypeById(TelephonyTypeConstants.LOCAL).ifPresent(localType -> {
                Prefix localPrefix = new Prefix();
                localPrefix.setCode(""); // No actual prefix code for local
                localPrefix.setTelephonyType(localType);
                localPrefix.setTelephoneTypeId(localType.getId());
                // Set other defaults as per PHP logic if needed (e.g., default operator for local)
                findInternalOperatorByTelephonyType(TelephonyTypeConstants.LOCAL, originCountryId).ifPresent(op -> {
                    localPrefix.setOperator(op);
                    localPrefix.setOperatorId(op.getId());
                });
                localPrefix.setBaseValue(BigDecimal.ZERO); // Default, actual pricing from bands/special rates
                localPrefix.setVatIncluded(false);
                localPrefix.setVatValue(BigDecimal.ZERO);
                localPrefix.setBandOk(true); // Assume bands can apply for local
                prefixes.add(localPrefix);
            });
        }
        return prefixes;
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
    
    @SuppressWarnings("unchecked")
    public List<Series> findSeriesByNdcAndNumber(Integer ndc, String numberWithoutNdc, Long indicatorId) {
        if (ndc == null || numberWithoutNdc == null || !CdrHelper.isNumeric(numberWithoutNdc)) return Collections.emptyList();
        long numPart;
        try {
            numPart = Long.parseLong(numberWithoutNdc);
        } catch (NumberFormatException e) {
            return Collections.emptyList();
        }


        String sql = "SELECT s.* FROM series s " +
                     "WHERE s.ndc = :ndc AND s.initial_number <= :numPart AND s.final_number >= :numPart ";
        if (indicatorId != null) {
            sql += "AND s.indicator_id = :indicatorId ";
        }
        sql += "AND s.active = true ORDER BY (s.final_number - s.initial_number) ASC, s.id"; 

        Query query = entityManager.createNativeQuery(sql, Series.class);
        query.setParameter("ndc", ndc);
        query.setParameter("numPart", numPart);
        if (indicatorId != null) {
            query.setParameter("indicatorId", indicatorId);
        }
        return query.getResultList();
    }

    @SuppressWarnings("unchecked")
    public List<Indicator> findIndicatorsByNumberAndType(String number, Long telephonyTypeId, Long originCountryId, CommunicationLocation commLocation) {
        // PHP: buscarDestino logic for finding indicator based on number parts (NDC)
        // This needs to iterate through possible NDC lengths.
        
        // First, get NDC length ranges for this telephony type from TelephonyTypeConfig
        // The PHP code gets min/max NDC string lengths from the SERIE table itself (CargarIndicativos)
        // Let's replicate that part of CargarIndicativos here for NDC lengths.
        String ndcLengthSql = "SELECT MIN(LENGTH(CAST(s.ndc AS TEXT))) AS min_len, MAX(LENGTH(CAST(s.ndc AS TEXT))) AS max_len " +
                              "FROM series s JOIN indicator i ON s.indicator_id = i.id " +
                              "WHERE i.telephony_type_id = :telephonyTypeId AND i.origin_country_id = :originCountryId " +
                              "AND i.active = true AND s.active = true AND s.ndc IS NOT NULL AND s.ndc > 0"; // NDC must be positive
        Query ndcLengthQuery = entityManager.createNativeQuery(ndcLengthSql);
        ndcLengthQuery.setParameter("telephonyTypeId", telephonyTypeId);
        ndcLengthQuery.setParameter("originCountryId", originCountryId);
        
        Object[] lengthResult = null;
        try {
            lengthResult = (Object[]) ndcLengthQuery.getSingleResult();
        } catch (NoResultException e) {
            // No series found for this type/country, or no NDCs defined
        }

        int minNdcLen = (lengthResult != null && lengthResult[0] != null) ? ((Number)lengthResult[0]).intValue() : 0;
        int maxNdcLen = (lengthResult != null && lengthResult[1] != null) ? ((Number)lengthResult[1]).intValue() : 0;

        if (maxNdcLen == 0) { // No NDCs defined or found, or all are 0/empty
             if (Long.valueOf(TelephonyTypeConstants.LOCAL).equals(telephonyTypeId)) {
                // For local calls, if no specific indicator series match, use the commLocation's indicator.
                return commLocation.getIndicator() != null ? List.of(commLocation.getIndicator()) : Collections.emptyList();
            }
            return Collections.emptyList();
        }
        
        List<Indicator> foundIndicators = new ArrayList<>();
        String cleanedNumber = CdrHelper.cleanPhoneNumber(number);

        for (int len = maxNdcLen; len >= minNdcLen; len--) {
            if (len == 0) continue; // Skip 0-length NDCs if minNdcLen was 0
            if (cleanedNumber.length() < len) continue;

            String ndcStr = cleanedNumber.substring(0, len);
            String remainingNumber = cleanedNumber.substring(len);
            
            if (!CdrHelper.isNumeric(ndcStr) || (remainingNumber.length() > 0 && !CdrHelper.isNumeric(remainingNumber))) {
                continue;
            }
            Integer ndc = Integer.parseInt(ndcStr);

            // Query series for this NDC and remaining number part
            String seriesSql = "SELECT i.* FROM indicator i " +
                               "JOIN series s ON i.id = s.indicator_id " +
                               "WHERE i.telephony_type_id = :telephonyTypeId " +
                               "  AND i.origin_country_id = :originCountryId " +
                               "  AND s.ndc = :ndc ";
            if (!remainingNumber.isEmpty()) {
                 seriesSql += " AND s.initial_number <= :remainingNum AND s.final_number >= :remainingNum ";
            } else { // If remainingNumber is empty, it means the NDC itself is the full number part to match.
                 seriesSql += " AND s.initial_number <= 0 AND s.final_number >= 0 "; // Or specific logic for NDC-only match
            }
            seriesSql += "AND i.active = true AND s.active = true " +
                         "ORDER BY (s.final_number - s.initial_number) ASC, i.id"; // Prefer most specific series

            Query seriesQuery = entityManager.createNativeQuery(seriesSql, Indicator.class);
            seriesQuery.setParameter("telephonyTypeId", telephonyTypeId);
            seriesQuery.setParameter("originCountryId", originCountryId);
            seriesQuery.setParameter("ndc", ndc);
            if (!remainingNumber.isEmpty()) {
                seriesQuery.setParameter("remainingNum", Long.parseLong(remainingNumber));
            }
            
            List<Indicator> currentIndicators = seriesQuery.getResultList();
            if (!currentIndicators.isEmpty()) {
                foundIndicators.addAll(currentIndicators);
                // PHP logic might stop at the first NDC length that yields results.
                // For now, we collect all and let EnrichmentService decide, or sort by specificity.
                // If we want to mimic PHP's "first match by longest NDC wins", we can return here.
                // However, PHP's `buscarDestino` seems to collect and then pick the "best" based on series specificity.
                // The ORDER BY in the seriesSql already prefers more specific series.
                // Let's return the first non-empty list found, as longest NDC is tried first.
                return foundIndicators;
            }
        }
        
        if (foundIndicators.isEmpty() && Long.valueOf(TelephonyTypeConstants.LOCAL).equals(telephonyTypeId)) {
             return commLocation.getIndicator() != null ? List.of(commLocation.getIndicator()) : Collections.emptyList();
        }
        return foundIndicators;
    }
    
    @SuppressWarnings("unchecked")
    public List<SpecialRateValue> findSpecialRateValues(LocalDateTime callTime, Long telephonyTypeId, Long operatorId, Long bandId, Long originIndicatorId) {
        String dayOfWeekField = callTime.getDayOfWeek().name().toLowerCase() + "_enabled"; 

        String sql = "SELECT srv.* FROM special_rate_value srv " +
                     "WHERE srv.active = true " +
                     "  AND (srv.valid_from IS NULL OR srv.valid_from <= :callTime) " +
                     "  AND (srv.valid_to IS NULL OR srv.valid_to >= :callTime) " +
                     "  AND srv." + dayOfWeekField + " = true "; 
        
        if (telephonyTypeId != null) sql += " AND (srv.telephony_type_id IS NULL OR srv.telephony_type_id = :telephonyTypeId) ";
        if (operatorId != null) sql += " AND (srv.operator_id IS NULL OR srv.operator_id = :operatorId) ";
        if (bandId != null) sql += " AND (srv.band_id IS NULL OR srv.band_id = :bandId) ";
        if (originIndicatorId != null) sql += " AND (srv.origin_indicator_id IS NULL OR srv.origin_indicator_id = :originIndicatorId) ";
        
        sql += "ORDER BY srv.origin_indicator_id DESC NULLS LAST, srv.telephony_type_id DESC NULLS LAST, srv.operator_id DESC NULLS LAST, srv.band_id DESC NULLS LAST, srv.id";


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
        if (commLocationId == null) return Collections.emptyList();
        String sql = "SELECT psr.* FROM pbx_special_rule psr " +
                     "WHERE psr.active = true AND (psr.comm_location_id IS NULL OR psr.comm_location_id = :commLocationId) " +
                     "ORDER BY psr.comm_location_id DESC NULLS LAST, LENGTH(psr.search_pattern) DESC, psr.id"; 
        Query query = entityManager.createNativeQuery(sql, PbxSpecialRule.class);
        query.setParameter("commLocationId", commLocationId);
        return query.getResultList();
    }

    public Optional<SpecialService> findSpecialService(String phoneNumber, Long indicatorId, Long originCountryId) {
        String sql = "SELECT ss.* FROM special_service ss " +
                     "WHERE ss.active = true AND ss.phone_number = :phoneNumber " +
                     "  AND (ss.indicator_id IS NULL OR ss.indicator_id = :indicatorId) " + 
                     "  AND ss.origin_country_id = :originCountryId " +
                     "ORDER BY ss.indicator_id DESC NULLS LAST LIMIT 1"; 
        Query query = entityManager.createNativeQuery(sql, SpecialService.class);
        query.setParameter("phoneNumber", phoneNumber);
        query.setParameter("indicatorId", indicatorId);
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
            sql += "AND (b.origin_indicator_id IS NULL OR b.origin_indicator_id = :originIndicatorId) ";
        }
        sql += "ORDER BY b.origin_indicator_id DESC NULLS LAST, b.id"; 

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
                     "  AND (tr.trunk_id IS NULL OR tr.trunk_id = :trunkId) " +
                     "  AND tr.telephony_type_id = :telephonyTypeId " +
                     "  AND (tr.origin_indicator_id IS NULL OR tr.origin_indicator_id = :originIndicatorId) " +
                     "  AND (tr.indicator_ids = '' OR tr.indicator_ids = :indicatorIdToMatch OR " +
                     "       tr.indicator_ids LIKE :indicatorIdToMatch || ',%' OR " +
                     "       tr.indicator_ids LIKE '%,' || :indicatorIdToMatch OR " +
                     "       tr.indicator_ids LIKE '%,' || :indicatorIdToMatch || ',%') " +
                     "ORDER BY tr.trunk_id DESC NULLS LAST, LENGTH(tr.indicator_ids) DESC, tr.origin_indicator_id DESC NULLS LAST, tr.id";

        Query query = entityManager.createNativeQuery(sql, TrunkRule.class);
        query.setParameter("trunkId", trunkId);
        query.setParameter("telephonyTypeId", telephonyTypeId);
        query.setParameter("originIndicatorId", originIndicatorId);
        query.setParameter("indicatorIdToMatch", indicatorIdToMatch); 
        
        return query.getResultList();
    }

    public Optional<TrunkRate> findTrunkRate(Long trunkId, Long operatorId, Long telephonyTypeId) {
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