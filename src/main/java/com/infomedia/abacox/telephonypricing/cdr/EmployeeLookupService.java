package com.infomedia.abacox.telephonypricing.cdr;

import com.infomedia.abacox.telephonypricing.entity.Employee;
import com.infomedia.abacox.telephonypricing.entity.ExtensionRange;
import jakarta.persistence.EntityManager;
import jakarta.persistence.NoResultException;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.Query;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

@Service
@Log4j2
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class EmployeeLookupService {

    @PersistenceContext
    private EntityManager entityManager;
    private final CoreLookupService coreLookupService;


    public InternalExtensionLimitsDto getInternalExtensionLimits(Long originCountryId, Long commLocationId) {
        int minLength = 100;
        int maxLength = 0;

        String empSql = "SELECT COALESCE(MAX(LENGTH(e.extension)), 0) AS max_len, COALESCE(MIN(LENGTH(e.extension)), 100) AS min_len " +
                "FROM employee e " +
                "LEFT JOIN communication_location cl ON e.communication_location_id = cl.id " +
                "LEFT JOIN indicator i ON cl.indicator_id = i.id " +
                "WHERE e.active = true AND (cl.id IS NULL OR cl.active = true) AND (i.id IS NULL OR i.active = true) " +
                "  AND e.extension ~ '^[0-9]+$' AND e.extension NOT LIKE '0%' AND LENGTH(e.extension) < 8 ";
        if (originCountryId != null) empSql += " AND (i.id IS NULL OR i.origin_country_id = :originCountryId) ";
        if (commLocationId != null)
            empSql += " AND (e.communication_location_id IS NULL OR e.communication_location_id = :commLocationId) ";

        Query empQuery = entityManager.createNativeQuery(empSql);
        if (originCountryId != null) empQuery.setParameter("originCountryId", originCountryId);
        if (commLocationId != null) empQuery.setParameter("commLocationId", commLocationId);

        try {
            Object[] empResult = (Object[]) empQuery.getSingleResult();
            if (empResult[0] != null) maxLength = Math.max(maxLength, ((Number) empResult[0]).intValue());
            if (empResult[1] != null) minLength = Math.min(minLength, ((Number) empResult[1]).intValue());
        } catch (NoResultException e) {
            // No results
        }

        String rangeSql = "SELECT COALESCE(MAX(LENGTH(er.range_end::text)), 0) AS max_len, COALESCE(MIN(LENGTH(er.range_start::text)), 100) AS min_len " +
                "FROM extension_range er " +
                "LEFT JOIN communication_location cl ON er.comm_location_id = cl.id " +
                "LEFT JOIN indicator i ON cl.indicator_id = i.id " +
                "WHERE er.active = true AND (cl.id IS NULL OR cl.active = true) AND (i.id IS NULL OR i.active = true) " +
                "  AND er.range_start::text ~ '^[0-9]+$' AND er.range_end::text ~ '^[0-9]+$' " +
                "  AND LENGTH(er.range_start::text) < 8 AND LENGTH(er.range_end::text) < 8 " +
                "  AND er.range_end >= er.range_start ";
        if (originCountryId != null) rangeSql += " AND (i.id IS NULL OR i.origin_country_id = :originCountryId) ";
        if (commLocationId != null)
            rangeSql += " AND (er.comm_location_id IS NULL OR er.comm_location_id = :commLocationId) ";

        Query rangeQuery = entityManager.createNativeQuery(rangeSql);
        if (originCountryId != null) rangeQuery.setParameter("originCountryId", originCountryId);
        if (commLocationId != null) rangeQuery.setParameter("commLocationId", commLocationId);

        try {
            Object[] rangeResult = (Object[]) rangeQuery.getSingleResult();
            if (rangeResult[0] != null) maxLength = Math.max(maxLength, ((Number) rangeResult[0]).intValue());
            if (rangeResult[1] != null) minLength = Math.min(minLength, ((Number) rangeResult[1]).intValue());
        } catch (NoResultException e) {
            // No results
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
        if (commLocationId != null)
            specialExtSql += " AND (e.communication_location_id IS NULL OR e.communication_location_id = :commLocationId) ";

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
        if (extension == null || extension.isEmpty() || commLocationId == null || limits == null)
            return Optional.empty();

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
                coreLookupService.findSubdivisionById(bestRange.getSubdivisionId()).ifPresent(virtualEmployee::setSubdivision);
                virtualEmployee.setSubdivisionId(bestRange.getSubdivisionId());
            }
            if (bestRange.getCostCenterId() != null) {
                coreLookupService.findCostCenterById(bestRange.getCostCenterId()).ifPresent(virtualEmployee::setCostCenter);
                virtualEmployee.setCostCenterId(bestRange.getCostCenterId());
            }
            virtualEmployee.setName((bestRange.getPrefix() != null ? bestRange.getPrefix() : "") + " " + extension + " (Range)");
            virtualEmployee.setActive(true);
            return Optional.of(virtualEmployee);
        }
        return Optional.empty();
    }
}
