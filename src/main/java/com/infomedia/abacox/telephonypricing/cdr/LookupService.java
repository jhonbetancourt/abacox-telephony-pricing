package com.infomedia.abacox.telephonypricing.cdr;

import com.infomedia.abacox.telephonypricing.entity.*;
import jakarta.persistence.EntityManager;
import jakarta.persistence.NoResultException;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.Query;
import lombok.extern.log4j.Log4j2;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional; // Important for read-only methods

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

@Service
@Log4j2
@Transactional(readOnly = true) // Default to read-only for lookup methods
public class LookupService {

    @PersistenceContext
    private EntityManager entityManager;

    public Optional<CommunicationLocation> findCommunicationLocationById(Long id) {
        if (id == null) return Optional.empty();
        // Assuming CommunicationLocationRepository is injected or use EntityManager
        Query query = entityManager.createNativeQuery("SELECT * FROM communication_location WHERE id = :id AND active = true", CommunicationLocation.class);
        query.setParameter("id", id);
        try {
            return Optional.ofNullable((CommunicationLocation) query.getSingleResult());
        } catch (NoResultException e) {
            return Optional.empty();
        }
    }

    public Optional<Employee> findEmployeeByExtension(String extension, Long commLocationId) {
        if (extension == null || extension.isEmpty() || commLocationId == null) return Optional.empty();
        String sql = "SELECT * FROM employee WHERE extension = :extension AND communication_location_id = :commLocationId AND active = true";
        Query query = entityManager.createNativeQuery(sql, Employee.class);
        query.setParameter("extension", extension);
        query.setParameter("commLocationId", commLocationId);
        try {
            return Optional.ofNullable((Employee) query.getSingleResult());
        } catch (NoResultException e) {
            // PHP logic also checks RANGOEXT (ExtensionRange)
            return findEmployeeByExtensionRange(extension, commLocationId);
        }
    }

    public Optional<Employee> findEmployeeByAuthCode(String authCode, Long commLocationId) {
        if (authCode == null || authCode.isEmpty() || commLocationId == null) return Optional.empty();
        // PHP logic for auth code might be global or per commLocation. Assuming per commLocation for now.
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
    public Optional<Employee> findEmployeeByExtensionRange(String extension, Long commLocationId) {
        if (!CdrHelper.isNumeric(extension) || commLocationId == null) return Optional.empty();
        long extNumeric = Long.parseLong(extension);

        String sql = "SELECT er.* FROM extension_range er " +
                     "WHERE er.comm_location_id = :commLocationId " +
                     "AND er.range_start <= :extNumeric AND er.range_end >= :extNumeric " +
                     "AND er.active = true " +
                     "ORDER BY (er.range_end - er.range_start) ASC"; // Prefer more specific ranges

        Query query = entityManager.createNativeQuery(sql, ExtensionRange.class);
        query.setParameter("commLocationId", commLocationId);
        query.setParameter("extNumeric", extNumeric);

        List<ExtensionRange> ranges = query.getResultList();
        if (!ranges.isEmpty()) {
            ExtensionRange bestRange = ranges.get(0); // Smallest valid range
            // Create a "virtual" employee based on the range's cost center and subdivision
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
            // This employee is not persisted, just used for enrichment.
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
        // PHP: operador_interno logic
        String sql = "SELECT o.* FROM operator o JOIN prefix p ON p.operator_id = o.id " +
                     "WHERE p.telephone_type_id = :telephonyTypeId AND o.origin_country_id = :originCountryId AND o.active = true AND p.active = true " +
                     "LIMIT 1"; // Assuming one internal operator per type/country
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
        // PHP: CargarPrefijos then buscarPrefijo
        // This is complex. It tries to match the longest prefix first.
        // For simplicity, let's assume we try to match prefixes from longest to shortest.
        // A more optimized way would be a single query if possible, or fewer queries.

        String sql = "SELECT p.* FROM prefix p " +
                     "JOIN operator o ON p.operator_id = o.id " +
                     "JOIN telephony_type tt ON p.telephone_type_id = tt.id " +
                     "WHERE :dialedNumber LIKE p.code || '%' " + // Starts with prefix code
                     "AND o.origin_country_id = :originCountryId " +
                     "AND p.active = true AND o.active = true AND tt.active = true " +
                     "ORDER BY LENGTH(p.code) DESC"; // Match longest prefix first

        Query query = entityManager.createNativeQuery(sql, Prefix.class);
        query.setParameter("dialedNumber", dialedNumber);
        query.setParameter("originCountryId", originCountryId);
        
        List<Prefix> prefixes = query.getResultList();

        if (!forTrunk && prefixes.isEmpty()) { // If not for trunk and no prefix found, try local
            findTelephonyTypeById(TelephonyTypeConstants.LOCAL).ifPresent(localType -> {
                // Create a "dummy" prefix for local calls if no explicit prefix matches
                // This emulates PHP's behavior of falling back to local if no prefix matches
                // and it's not a trunk-based lookup.
                Prefix localPrefix = new Prefix();
                localPrefix.setCode(""); // No actual prefix code for local
                localPrefix.setTelephonyType(localType);
                localPrefix.setTelephoneTypeId(localType.getId());
                // Set other defaults if necessary
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
        long numPart = Long.parseLong(numberWithoutNdc);

        String sql = "SELECT s.* FROM series s " +
                     "WHERE s.ndc = :ndc AND s.initial_number <= :numPart AND s.final_number >= :numPart ";
        if (indicatorId != null) {
            sql += "AND s.indicator_id = :indicatorId ";
        }
        sql += "AND s.active = true ORDER BY (s.final_number - s.initial_number) ASC"; // Prefer more specific series

        Query query = entityManager.createNativeQuery(sql, Series.class);
        query.setParameter("ndc", ndc);
        query.setParameter("numPart", numPart);
        if (indicatorId != null) {
            query.setParameter("indicatorId", indicatorId);
        }
        return query.getResultList();
    }

    @SuppressWarnings("unchecked")
    public List<Indicator> findIndicatorsByNumberAndType(String number, Long telephonyTypeId, Long originCountryId) {
        // PHP: buscarDestino logic for finding indicator based on number parts (NDC)
        // This is highly complex. A simplified version:
        // Try to match the longest possible NDC (National Destination Code) part of the number.
        // This requires knowing typical NDC lengths for the given telephonyType and originCountry.
        // For now, this is a placeholder for that complex logic.
        // A real implementation would iterate, trying different lengths for NDC.

        // Example: If number is "1234567" and NDCs can be 1, 2, or 3 digits:
        // Try NDC "123", then "12", then "1".
        // For each, query `series` table: SELECT ... WHERE s.ndc = :ndc AND s.initial_number <= number_part AND s.final_number >= number_part

        // Simplified: find an indicator whose NDC is a prefix of the number.
        String sql = "SELECT i.* FROM indicator i " +
                     "JOIN series s ON i.id = s.indicator_id " +
                     "WHERE i.telephony_type_id = :telephonyTypeId " +
                     "  AND i.origin_country_id = :originCountryId " +
                     "  AND :number LIKE CAST(s.ndc AS TEXT) || '%' " + // Number starts with NDC
                     "  AND i.active = true AND s.active = true " +
                     "ORDER BY LENGTH(CAST(s.ndc AS TEXT)) DESC"; // Prefer longest matching NDC

        Query query = entityManager.createNativeQuery(sql, Indicator.class);
        query.setParameter("telephonyTypeId", telephonyTypeId);
        query.setParameter("originCountryId", originCountryId);
        query.setParameter("number", number);

        List<Indicator> indicators = query.getResultList();
        if (indicators.isEmpty() && Long.valueOf(TelephonyTypeConstants.LOCAL).equals(telephonyTypeId)) {
             // PHP: If local and no indicator found, it might assume the commLocation's indicator.
             // This part needs to be handled in the EnrichmentService.
        }
        return indicators;
    }
    
    @SuppressWarnings("unchecked")
    public List<SpecialRateValue> findSpecialRateValues(LocalDateTime callTime, Long telephonyTypeId, Long operatorId, Long bandId, Long originIndicatorId) {
        // PHP: Obtener_ValorEspecial logic
        String dayOfWeekField = callTime.getDayOfWeek().name().toLowerCase() + "_enabled"; // e.g., monday_enabled

        String sql = "SELECT srv.* FROM special_rate_value srv " +
                     "WHERE srv.active = true " +
                     "  AND (srv.valid_from IS NULL OR srv.valid_from <= :callTime) " +
                     "  AND (srv.valid_to IS NULL OR srv.valid_to >= :callTime) " +
                     "  AND srv." + dayOfWeekField + " = true "; // Dynamic day field
                     // Add holiday_enabled check if you have a holiday lookup mechanism
        
        if (telephonyTypeId != null) sql += " AND (srv.telephony_type_id IS NULL OR srv.telephony_type_id = :telephonyTypeId) ";
        if (operatorId != null) sql += " AND (srv.operator_id IS NULL OR srv.operator_id = :operatorId) ";
        if (bandId != null) sql += " AND (srv.band_id IS NULL OR srv.band_id = :bandId) ";
        if (originIndicatorId != null) sql += " AND (srv.origin_indicator_id IS NULL OR srv.origin_indicator_id = :originIndicatorId) ";
        
        sql += "ORDER BY srv.origin_indicator_id DESC NULLS LAST, srv.telephony_type_id DESC NULLS LAST, srv.operator_id DESC NULLS LAST, srv.band_id DESC NULLS LAST";


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
        // PHP: evaluarPBXEspecial loads all active rules, possibly filtered by commLocation
        String sql = "SELECT psr.* FROM pbx_special_rule psr " +
                     "WHERE psr.active = true AND (psr.comm_location_id IS NULL OR psr.comm_location_id = :commLocationId) " +
                     "ORDER BY psr.comm_location_id DESC NULLS LAST, LENGTH(psr.search_pattern) DESC"; // Prioritize specific rules
        Query query = entityManager.createNativeQuery(sql, PbxSpecialRule.class);
        query.setParameter("commLocationId", commLocationId);
        return query.getResultList();
    }

    public Optional<SpecialService> findSpecialService(String phoneNumber, Long indicatorId, Long originCountryId) {
        // PHP: buscar_NumeroEspecial logic
        String sql = "SELECT ss.* FROM special_service ss " +
                     "WHERE ss.active = true AND ss.phone_number = :phoneNumber " +
                     "  AND (ss.indicator_id IS NULL OR ss.indicator_id = :indicatorId) " + // indicator_id can be null for global special numbers
                     "  AND ss.origin_country_id = :originCountryId " +
                     "ORDER BY ss.indicator_id DESC NULLS LAST LIMIT 1"; // Prefer specific indicator match
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
        // Used in PHP's buscarValor when PREFIJO_BANDAOK is true
        String sql = "SELECT b.* FROM band b " +
                     "WHERE b.active = true AND b.prefix_id = :prefixId ";
        if (originIndicatorId != null) {
            sql += "AND (b.origin_indicator_id IS NULL OR b.origin_indicator_id = :originIndicatorId) ";
        }
        sql += "ORDER BY b.origin_indicator_id DESC NULLS LAST"; // Prioritize specific origin indicator

        Query query = entityManager.createNativeQuery(sql, Band.class);
        query.setParameter("prefixId", prefixId);
        if (originIndicatorId != null) {
            query.setParameter("originIndicatorId", originIndicatorId);
        }
        return query.getResultList();
    }

    @SuppressWarnings("unchecked")
    public List<TrunkRule> findTrunkRules(Long trunkId, Long telephonyTypeId, String indicatorIds, Long originIndicatorId) {
        // PHP: Calcular_Valor_Reglas
        String sql = "SELECT tr.* FROM trunk_rule tr " +
                     "WHERE tr.active = true " +
                     "  AND (tr.trunk_id IS NULL OR tr.trunk_id = :trunkId) " +
                     "  AND tr.telephony_type_id = :telephonyTypeId " +
                     "  AND (tr.origin_indicator_id IS NULL OR tr.origin_indicator_id = :originIndicatorId) " +
                     // indicator_ids is a comma-separated string in DB, needs careful matching
                     // This is a simplified match, real SQL might need string splitting or FIND_IN_SET equivalent
                     "  AND (tr.indicator_ids = '' OR tr.indicator_ids = :indicatorId OR " +
                     "       tr.indicator_ids LIKE :indicatorId || ',%' OR " +
                     "       tr.indicator_ids LIKE '%,' || :indicatorId OR " +
                     "       tr.indicator_ids LIKE '%,' || :indicatorId || ',%') " +
                     "ORDER BY tr.trunk_id DESC NULLS LAST, tr.indicator_ids DESC NULLS LAST, tr.origin_indicator_id DESC NULLS LAST";

        Query query = entityManager.createNativeQuery(sql, TrunkRule.class);
        query.setParameter("trunkId", trunkId);
        query.setParameter("telephonyTypeId", telephonyTypeId);
        query.setParameter("originIndicatorId", originIndicatorId);
        query.setParameter("indicatorId", indicatorIds); // Assuming indicatorIds is a single ID for matching here
        
        return query.getResultList();
    }

    public Optional<TrunkRate> findTrunkRate(Long trunkId, Long operatorId, Long telephonyTypeId) {
        // Part of PHP's buscarTroncal and then used in tarifa troncal logic
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

    // Add other lookup methods as needed for entities like CallCategory, PlantType, JobPosition, etc.
    // Example:
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

    public Optional<TelephonyTypeConfig> findTelephonyTypeConfig(Long telephonyTypeId, Long originCountryId, String number) {
        // PHP: TIPOTELECFG_MIN, TIPOTELECFG_MAX are used to determine if a number falls within a configured range for a type/country
        if (telephonyTypeId == null || originCountryId == null || number == null || !CdrHelper.isNumeric(number)) {
            return Optional.empty();
        }
        // Assuming number is just the numeric part for min/max comparison, length might be more relevant in some contexts.
        // The PHP logic for min/max in TelephonyType/TelephonyTypeConfig is for number *length*.
        int numberLength = number.length();

        String sql = "SELECT ttc.* FROM telephony_type_config ttc " +
                     "WHERE ttc.telephony_type_id = :telephonyTypeId " +
                     "  AND ttc.origin_country_id = :originCountryId " +
                     "  AND ttc.min_value <= :numberLength AND ttc.max_value >= :numberLength " +
                     // "AND ttc.active = true " // TelephonyTypeConfig doesn't extend ActivableEntity in provided files
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