package com.infomedia.abacox.telephonypricing.cdr;

import com.infomedia.abacox.telephonypricing.entity.CommunicationLocation;
import com.infomedia.abacox.telephonypricing.entity.Employee;
import com.infomedia.abacox.telephonypricing.entity.ExtensionRange;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;


import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

@Service
@Log4j2
@RequiredArgsConstructor
public class EmployeeLookupService {

    @PersistenceContext
    private EntityManager entityManager;

    @Transactional(readOnly = true)
    public Optional<Employee> findEmployeeByExtensionOrAuthCode(String extension, String authCode, Long commLocationId, LocalDateTime callTime) {
        StringBuilder queryStr = new StringBuilder("SELECT e.* FROM employee e ");
        queryStr.append(" LEFT JOIN communication_location cl ON e.communication_location_id = cl.id ");
        queryStr.append(" WHERE e.active = true ");

        boolean hasAuthCode = authCode != null && !authCode.isEmpty();
        boolean hasExtension = extension != null && !extension.isEmpty();

        if (hasAuthCode) {
            queryStr.append(" AND e.auth_code = :authCode ");
        } else if (hasExtension) {
            queryStr.append(" AND e.extension = :extension ");
        } else {
            return Optional.empty();
        }

        // If commLocationId is provided, we prefer matches from that location.
        // If not, or if no match, global employees (comm_location_id IS NULL) are considered.
        // PHP's logic for global extensions is complex. This is a simplified approach.
        // If commLocationId is null, it means search globally.
        if (commLocationId != null) {
            queryStr.append(" AND (e.communication_location_id = :commLocationId OR e.communication_location_id IS NULL) "); // Allow global
            queryStr.append(" AND (cl.id IS NULL OR cl.active = true) "); // Ensure linked comm_location is active if present
        } else {
            // Global search, no specific comm_location filter beyond employee's own
             queryStr.append(" AND (cl.id IS NULL OR cl.active = true) ");
        }

        queryStr.append(" ORDER BY e.communication_location_id DESC NULLS LAST, e.created_date DESC LIMIT 1"); // Prefer specific location, then newest

        jakarta.persistence.Query nativeQuery = entityManager.createNativeQuery(queryStr.toString(), Employee.class);

        if (hasAuthCode) {
            nativeQuery.setParameter("authCode", authCode);
        } else if (hasExtension) {
            nativeQuery.setParameter("extension", extension);
        }
        if (commLocationId != null) {
            nativeQuery.setParameter("commLocationId", commLocationId);
        }

        try {
            Employee employee = (Employee) nativeQuery.getSingleResult();
            return Optional.of(employee);
        } catch (jakarta.persistence.NoResultException e) {
            if (!hasAuthCode && hasExtension) {
                return findEmployeeByExtensionRange(extension, commLocationId, callTime);
            }
            return Optional.empty();
        }
    }

    public Employee createEmployeeFromRange(String extension, Long subdivisionId, Long commLocationId, String namePrefix) {
        Employee newEmployee = new Employee();
        newEmployee.setExtension(extension);
        newEmployee.setName(namePrefix + " " + extension);
        newEmployee.setSubdivisionId(subdivisionId);
        newEmployee.setCommunicationLocationId(commLocationId);
        newEmployee.setActive(true);
        log.info("Conceptually creating new employee for extension {} from range.", extension);
        return newEmployee;
    }

    @Transactional(readOnly = true)
    public ExtensionLimits getExtensionLimits(Long originCountryId, Long commLocationId, Long plantTypeId) {
        // PHP's ObtenerMaxMin
        int maxLenEmployees = 0;
        int minLenEmployees = Integer.MAX_VALUE;
        boolean empLenSet = false;

        String maxAllowedLenStr = String.valueOf(CdrConfigService.ACUMTOTAL_MAX_EXTENSION_LENGTH_FOR_INTERNAL_CHECK);
        int maxStandardExtLength = maxAllowedLenStr.length() - 1;

        // Query for employee extensions
        StringBuilder empQueryBuilder = new StringBuilder(
            "SELECT LENGTH(e.extension) as ext_len " +
            "FROM employee e " +
            "JOIN communication_location cl ON e.communication_location_id = cl.id " +
            "JOIN indicator i ON cl.indicator_id = i.id " +
            "WHERE e.active = true AND cl.active = true AND i.active = true " +
            "  AND e.extension ~ '^[0-9]+$' " + // Numeric check (PostgreSQL specific regex)
            "  AND e.extension NOT LIKE '0%' " +
            "  AND LENGTH(e.extension) BETWEEN 1 AND :maxStandardExtLength ");
        if (originCountryId != null) empQueryBuilder.append(" AND i.origin_country_id = :originCountryId ");
        if (commLocationId != null) empQueryBuilder.append(" AND e.communication_location_id = :commLocationId ");
        if (plantTypeId != null) empQueryBuilder.append(" AND cl.plant_type_id = :plantTypeId ");

        jakarta.persistence.Query empQuery = entityManager.createNativeQuery(empQueryBuilder.toString());
        empQuery.setParameter("maxStandardExtLength", maxStandardExtLength);
        if (originCountryId != null) empQuery.setParameter("originCountryId", originCountryId);
        if (commLocationId != null) empQuery.setParameter("commLocationId", commLocationId);
        if (plantTypeId != null) empQuery.setParameter("plantTypeId", plantTypeId);

        List<Number> empLengths = empQuery.getResultList();
        for (Number lenNum : empLengths) {
            int len = lenNum.intValue();
            if (len > maxLenEmployees) maxLenEmployees = len;
            if (len < minLenEmployees) minLenEmployees = len;
            empLenSet = true;
        }
        if (!empLenSet) minLenEmployees = 0; // Ensure it's 0 if no employees found

        // Query for extension ranges
        int maxLenRanges = 0;
        int minLenRanges = Integer.MAX_VALUE;
        boolean rangeLenSet = false;

        StringBuilder rangeQueryBuilder = new StringBuilder(
            "SELECT LENGTH(er.range_start::text) as len_desde, LENGTH(er.range_end::text) as len_hasta " +
            "FROM extension_range er " +
            "JOIN communication_location cl ON er.comm_location_id = cl.id " +
            "JOIN indicator i ON cl.indicator_id = i.id " +
            "WHERE er.active = true AND cl.active = true AND i.active = true " +
            "  AND er.range_start::text ~ '^[0-9]+$' AND er.range_end::text ~ '^[0-9]+$' " +
            "  AND LENGTH(er.range_start::text) BETWEEN 1 AND :maxStandardExtLength " +
            "  AND LENGTH(er.range_end::text) BETWEEN 1 AND :maxStandardExtLength ");
        if (originCountryId != null) rangeQueryBuilder.append(" AND i.origin_country_id = :originCountryId ");
        if (commLocationId != null) rangeQueryBuilder.append(" AND er.comm_location_id = :commLocationId ");
        if (plantTypeId != null) rangeQueryBuilder.append(" AND cl.plant_type_id = :plantTypeId ");

        jakarta.persistence.Query rangeQuery = entityManager.createNativeQuery(rangeQueryBuilder.toString());
        rangeQuery.setParameter("maxStandardExtLength", maxStandardExtLength);
        if (originCountryId != null) rangeQuery.setParameter("originCountryId", originCountryId);
        if (commLocationId != null) rangeQuery.setParameter("commLocationId", commLocationId);
        if (plantTypeId != null) rangeQuery.setParameter("plantTypeId", plantTypeId);

        List<Object[]> rangeLengthPairs = rangeQuery.getResultList();
        for (Object[] pair : rangeLengthPairs) {
            int lenDesde = ((Number) pair[0]).intValue();
            int lenHasta = ((Number) pair[1]).intValue();
            if (lenHasta > maxLenRanges) maxLenRanges = lenHasta;
            if (lenDesde < minLenRanges) minLenRanges = lenDesde;
            rangeLenSet = true;
        }
        if (!rangeLenSet) minLenRanges = 0;

        int finalMinLen = Math.min(minLenEmployees > 0 ? minLenEmployees : Integer.MAX_VALUE, minLenRanges > 0 ? minLenRanges : Integer.MAX_VALUE);
        int finalMaxLen = Math.max(maxLenEmployees, maxLenRanges);

        if (finalMinLen == Integer.MAX_VALUE) finalMinLen = 0; // If neither had lengths

        int finalMinVal = CdrConfigService.ACUMTOTAL_MAX_EXTENSION_LENGTH_FOR_INTERNAL_CHECK;
        int finalMaxVal = 0;

        if (finalMaxLen > 0) {
            finalMaxVal = Integer.parseInt("9".repeat(finalMaxLen));
        } else {
            finalMaxVal = CdrConfigService.ACUMTOTAL_MAX_EXTENSION_LENGTH_FOR_INTERNAL_CHECK;
        }

        if (finalMinLen > 0) {
            finalMinVal = Integer.parseInt("1" + "0".repeat(Math.max(0, finalMinLen - 1)));
        } else {
            finalMinVal = 100; // PHP default if no min length found
        }
        
        if (finalMinVal > finalMaxVal && finalMaxVal > 0) {
            finalMinVal = finalMaxVal;
        }
        if (finalMinLen == 0 && finalMaxLen == 0) { // Both uninitialized from data
            finalMinVal = 100;
            finalMaxVal = CdrConfigService.ACUMTOTAL_MAX_EXTENSION_LENGTH_FOR_INTERNAL_CHECK;
        }


        StringBuilder specialExtQueryBuilder = new StringBuilder(
            "SELECT DISTINCT e.extension FROM employee e " +
            "JOIN communication_location cl ON e.communication_location_id = cl.id " +
            "JOIN indicator i ON cl.indicator_id = i.id " +
            "WHERE e.active = true AND cl.active = true AND i.active = true " +
            "  AND (LENGTH(e.extension) >= :maxExtStandardLenForFullList OR e.extension LIKE '0%' OR e.extension LIKE '*%' OR e.extension LIKE '#%') ");
        if (originCountryId != null) specialExtQueryBuilder.append(" AND i.origin_country_id = :originCountryId ");
        if (commLocationId != null) specialExtQueryBuilder.append(" AND e.communication_location_id = :commLocationId ");
        if (plantTypeId != null) specialExtQueryBuilder.append(" AND cl.plant_type_id = :plantTypeId ");

        jakarta.persistence.Query specialExtQuery = entityManager.createNativeQuery(specialExtQueryBuilder.toString(), String.class);
        specialExtQuery.setParameter("maxExtStandardLenForFullList", maxAllowedLenStr.length());
        if (originCountryId != null) specialExtQuery.setParameter("originCountryId", originCountryId);
        if (commLocationId != null) specialExtQuery.setParameter("commLocationId", commLocationId);
        if (plantTypeId != null) specialExtQuery.setParameter("plantTypeId", plantTypeId);

        List<String> specialFullExtensions = specialExtQuery.getResultList();

        log.debug("Calculated extension limits: minVal={}, maxVal={}, specialCount={}", finalMinVal, finalMaxVal, specialFullExtensions.size());
        return new ExtensionLimits(finalMinVal, finalMaxVal, specialFullExtensions);
    }

    public boolean isPossibleExtension(String extensionNumber, ExtensionLimits limits) {
        if (extensionNumber == null || extensionNumber.isEmpty()) {
            return false;
        }
        if (limits.getSpecialFullExtensions() != null && limits.getSpecialFullExtensions().contains(extensionNumber)) {
            return true;
        }
        if (extensionNumber.matches("\\d+")) {
            if (extensionNumber.equals("0")) {
                 return true;
            }
            if (!extensionNumber.startsWith("0")) {
                try {
                    long extNumValue = Long.parseLong(extensionNumber);
                    return extNumValue >= limits.getMinLength() && extNumValue <= limits.getMaxLength();
                } catch (NumberFormatException e) { return false; }
            }
        } else if (extensionNumber.startsWith("+") && extensionNumber.length() > 1 && extensionNumber.substring(1).matches("\\d+")) {
            try {
                long extNumValue = Long.parseLong(extensionNumber.substring(1));
                return extNumValue >= limits.getMinLength() && extNumValue <= limits.getMaxLength();
            } catch (NumberFormatException e) { return false; }
        }
        return false;
    }


    @Transactional(readOnly = true)
    public Optional<Employee> findEmployeeByExtensionRange(String extension, Long commLocationId, LocalDateTime callTime) {
        if (extension == null || !extension.matches("\\d+")) {
            return Optional.empty();
        }
        long extNum;
        try {
            extNum = Long.parseLong(extension);
        } catch (NumberFormatException e) {
            return Optional.empty();
        }

        StringBuilder queryStrBuilder = new StringBuilder("SELECT er.* FROM extension_range er WHERE er.active = true " +
                "AND er.range_start <= :extNum AND er.range_end >= :extNum ");
        if (commLocationId != null) {
            queryStrBuilder.append("AND er.comm_location_id = :commLocationId ");
        }
        queryStrBuilder.append("ORDER BY (er.range_end - er.range_start) ASC, er.created_date DESC LIMIT 1");

        jakarta.persistence.Query nativeQuery = entityManager.createNativeQuery(queryStrBuilder.toString(), ExtensionRange.class);
        nativeQuery.setParameter("extNum", extNum);
        if (commLocationId != null) {
            nativeQuery.setParameter("commLocationId", commLocationId);
        }

        List<ExtensionRange> ranges = nativeQuery.getResultList();
        if (!ranges.isEmpty()) {
            ExtensionRange matchedRange = ranges.get(0);
            log.debug("Extension {} matched range: {}", extension, matchedRange.getId());
            Employee conceptualEmployee = createEmployeeFromRange(
                    extension,
                    matchedRange.getSubdivisionId(),
                    matchedRange.getCommLocationId(),
                    matchedRange.getPrefix()
            );
            if (matchedRange.getCommLocationId() != null) {
                CommunicationLocation cl = entityManager.find(CommunicationLocation.class, matchedRange.getCommLocationId());
                conceptualEmployee.setCommunicationLocation(cl);
            }
            return Optional.of(conceptualEmployee);
        }
        return Optional.empty();
    }

}