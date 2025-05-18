package com.infomedia.abacox.telephonypricing.cdr;

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

@Service
@Log4j2
@RequiredArgsConstructor
public class EmployeeLookupService {

    @PersistenceContext
    private EntityManager entityManager;

    // Simplified: PHP's ObtenerFuncionario_Arreglo is complex with historical data.
    // This version fetches current employee.
    @Transactional(readOnly = true)
    public Optional<Employee> findEmployeeByExtensionOrAuthCode(String extension, String authCode, Long commLocationId, LocalDateTime callTime) {
        // PHP logic:
        // 1. Try authCode if present and call is outgoing.
        // 2. If not found by authCode or authCode not applicable, try extension.
        // 3. If not found by extension, try extension ranges.

        StringBuilder queryStr = new StringBuilder("SELECT e.* FROM employee e ");
        queryStr.append(" JOIN communication_location cl ON e.communication_location_id = cl.id ");
        queryStr.append(" WHERE e.active = true AND cl.active = true ");

        boolean hasAuthCode = authCode != null && !authCode.isEmpty();
        boolean hasExtension = extension != null && !extension.isEmpty();

        if (hasAuthCode) { // Assuming outgoing for auth code usage
            queryStr.append(" AND e.auth_code = :authCode ");
        } else if (hasExtension) {
            queryStr.append(" AND e.extension = :extension ");
        } else {
            return Optional.empty(); // No valid identifier
        }

        // In PHP, comid (commLocationId) is used to filter, and also global extension/clave settings.
        // For simplicity, we'll filter by commLocationId if provided.
        // PHP's `FunIDValido` and `ObtenerFuncionario_Arreglo` have complex logic for global extensions.
        // This is simplified to current plant.
        if (commLocationId != null) {
            queryStr.append(" AND e.communication_location_id = :commLocationId ");
        }
        // PHP also has date range checks for employee validity (FUNCIONARIO_HISTODESDE/HASTA) - omitted here.
        queryStr.append(" ORDER BY e.created_date DESC LIMIT 1"); // Get the most recent if multiple match (e.g. shared extension)

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
            // If not found by direct match, try ranges (only if searched by extension)
            if (!hasAuthCode && hasExtension) {
                return findEmployeeByExtensionRange(extension, commLocationId, callTime);
            }
            return Optional.empty();
        }
    }
    
    // Simplified version of PHP's ActualizarFuncionarios for range-based "new" employees
    // In a real system, creating employees on the fly like this needs careful consideration.
    public Employee createEmployeeFromRange(String extension, Long subdivisionId, Long commLocationId, String namePrefix) {
        // This is a placeholder. The PHP logic creates a FUNCIONARIO_NOMBRE like "Prefijo Ext".
        // It also checks license limits (ValidarLicFun).
        // For now, we just return a conceptual new Employee if a range matched.
        // In a real system, this would persist a new Employee entity.
        Employee newEmployee = new Employee();
        newEmployee.setExtension(extension);
        newEmployee.setName(namePrefix + " " + extension);
        newEmployee.setSubdivisionId(subdivisionId);
        newEmployee.setCommunicationLocationId(commLocationId);
        newEmployee.setActive(true);
        // Set createdBy, createdDate etc.
        log.info("Conceptually creating new employee for extension {} from range.", extension);
        // entityManager.persist(newEmployee); // If we were to actually save it
        return newEmployee; // This is not persisted, just a DTO-like representation
    }

    // Simplified ObtenerMaxMin
    @Transactional(readOnly = true)
    public ExtensionLimits getExtensionLimits(Long originCountryId, Long commLocationId, Long plantTypeId) {
        ExtensionLimits limits = new ExtensionLimits();

        // Query for min/max length from Employee table
        String empQueryStr = "SELECT COALESCE(MAX(LENGTH(e.extension)), 0) AS max_len, COALESCE(MIN(LENGTH(e.extension)), 0) AS min_len " +
                             "FROM employee e " +
                             "JOIN communication_location cl ON e.communication_location_id = cl.id " +
                             "JOIN indicator i ON cl.indicator_id = i.id " +
                             "WHERE e.active = true AND cl.active = true AND i.active = true " +
                             "  AND e.extension ~ '^[0-9]+$' " + // Numeric extensions
                             "  AND e.extension NOT LIKE '0%' " +
                             "  AND LENGTH(e.extension) BETWEEN 1 AND :maxAllowedLen ";
        if (originCountryId != null) empQueryStr += " AND i.origin_country_id = :originCountryId ";
        if (commLocationId != null) empQueryStr += " AND e.communication_location_id = :commLocationId ";
        if (plantTypeId != null) empQueryStr += " AND cl.plant_type_id = :plantTypeId ";
        
        jakarta.persistence.Query empQuery = entityManager.createNativeQuery(empQueryStr);
        empQuery.setParameter("maxAllowedLen", String.valueOf(CdrConfigService.ACUMTOTAL_MAX_EXTENSION_LENGTH_FOR_INTERNAL_CHECK).length() -1);
        if (originCountryId != null) empQuery.setParameter("originCountryId", originCountryId);
        if (commLocationId != null) empQuery.setParameter("commLocationId", commLocationId);
        if (plantTypeId != null) empQuery.setParameter("plantTypeId", plantTypeId);

        Object[] empRes = (Object[]) empQuery.getSingleResult();
        int empMaxLen = ((Number) empRes[0]).intValue();
        int empMinLen = ((Number) empRes[1]).intValue();

        // Query for min/max length from ExtensionRange table
        String rangeQueryStr = "SELECT COALESCE(MAX(LENGTH(er.range_end::text)), 0) AS max_len, COALESCE(MIN(LENGTH(er.range_start::text)), 0) AS min_len " +
                               "FROM extension_range er " +
                               "JOIN communication_location cl ON er.comm_location_id = cl.id " +
                               "JOIN indicator i ON cl.indicator_id = i.id " +
                               "WHERE er.active = true AND cl.active = true AND i.active = true " +
                               "  AND LENGTH(er.range_start::text) BETWEEN 1 AND :maxAllowedLen " +
                               "  AND LENGTH(er.range_end::text) BETWEEN 1 AND :maxAllowedLen ";
        if (originCountryId != null) rangeQueryStr += " AND i.origin_country_id = :originCountryId ";
        if (commLocationId != null) rangeQueryStr += " AND er.comm_location_id = :commLocationId ";
        if (plantTypeId != null) rangeQueryStr += " AND cl.plant_type_id = :plantTypeId ";

        jakarta.persistence.Query rangeQuery = entityManager.createNativeQuery(rangeQueryStr);
        rangeQuery.setParameter("maxAllowedLen", String.valueOf(CdrConfigService.ACUMTOTAL_MAX_EXTENSION_LENGTH_FOR_INTERNAL_CHECK).length() -1);

        if (originCountryId != null) rangeQuery.setParameter("originCountryId", originCountryId);
        if (commLocationId != null) rangeQuery.setParameter("commLocationId", commLocationId);
        if (plantTypeId != null) rangeQuery.setParameter("plantTypeId", plantTypeId);
        
        Object[] rangeRes = (Object[]) rangeQuery.getSingleResult();
        int rangeMaxLen = ((Number) rangeRes[0]).intValue();
        int rangeMinLen = ((Number) rangeRes[1]).intValue();

        int finalMaxLen = 0;
        if (empMaxLen > 0) finalMaxLen = Math.max(finalMaxLen, empMaxLen);
        if (rangeMaxLen > 0) finalMaxLen = Math.max(finalMaxLen, rangeMaxLen);

        int finalMinLen = 0;
        if (empMinLen > 0) finalMinLen = (finalMinLen == 0) ? empMinLen : Math.min(finalMinLen, empMinLen);
        if (rangeMinLen > 0) finalMinLen = (finalMinLen == 0) ? rangeMinLen : Math.min(finalMinLen, rangeMinLen);
        
        if (finalMaxLen > 0) limits.maxLength = Integer.parseInt("9".repeat(finalMaxLen)); else limits.maxLength = 0;
        if (finalMinLen > 0) limits.minLength = Integer.parseInt("1" + "0".repeat(Math.max(0,finalMinLen - 1))); else limits.minLength = 0;

        // If PHP's $forzar was true for employee, it would overwrite. Here we take the overall min/max.
        // PHP logic for $_LIM_INTERNAS['max'] = 1 * str_repeat('9', $infoPrefijoPBX['MAX']);
        // PHP logic for $_LIM_INTERNAS['min'] = 1 * ('1'.str_repeat('0', $infoPrefijoPBX['MIN']-1));

        // Fetch special extensions (PHP's $_LIM_INTERNAS['full'])
        String specialExtQueryStr = "SELECT DISTINCT e.extension FROM employee e " +
                                    "JOIN communication_location cl ON e.communication_location_id = cl.id " +
                                    "JOIN indicator i ON cl.indicator_id = i.id " +
                                    "WHERE e.active = true AND cl.active = true AND i.active = true " +
                                    "  AND (LENGTH(e.extension) >= :maxExtStandardLength OR e.extension LIKE '0%' OR e.extension LIKE '*%' OR e.extension LIKE '#%') ";
        if (originCountryId != null) specialExtQueryStr += " AND i.origin_country_id = :originCountryId ";
        if (commLocationId != null) specialExtQueryStr += " AND e.communication_location_id = :commLocationId ";
        if (plantTypeId != null) specialExtQueryStr += " AND cl.plant_type_id = :plantTypeId ";

        jakarta.persistence.Query specialExtQuery = entityManager.createNativeQuery(specialExtQueryStr, String.class);
        specialExtQuery.setParameter("maxExtStandardLength", String.valueOf(CdrConfigService.ACUMTOTAL_MAX_EXTENSION_LENGTH_FOR_INTERNAL_CHECK).length());
        if (originCountryId != null) specialExtQuery.setParameter("originCountryId", originCountryId);
        if (commLocationId != null) specialExtQuery.setParameter("commLocationId", commLocationId);
        if (plantTypeId != null) specialExtQuery.setParameter("plantTypeId", plantTypeId);
        
        limits.specialFullExtensions = specialExtQuery.getResultList();
        
        log.debug("Calculated extension limits: minLen={}, maxLen={}, specialCount={}", limits.minLength, limits.maxLength, limits.specialFullExtensions.size());
        return limits;
    }

    public boolean isPossibleExtension(String extensionNumber, ExtensionLimits limits) {
        if (extensionNumber == null || extensionNumber.isEmpty()) return false;

        boolean isNumeric = extensionNumber.matches("\\d+");
        long extNum = -1;
        if (isNumeric) {
            try {
                extNum = Long.parseLong(extensionNumber);
            } catch (NumberFormatException e) {
                isNumeric = false; // Should not happen if matches \d+
            }
        }
        
        boolean withinLimits = isNumeric && extNum >= limits.minLength && extNum <= limits.maxLength;
        boolean isSpecial = limits.specialFullExtensions.contains(extensionNumber);

        return withinLimits || isSpecial;
    }

    @Transactional(readOnly = true)
    public Optional<Employee> findEmployeeByExtensionRange(String extension, Long commLocationId, LocalDateTime callTime) {
        if (extension == null || !extension.matches("\\d+")) { // Must be numeric for range check
            return Optional.empty();
        }
        long extNum = Long.parseLong(extension);

        // PHP's Validar_RangoExt also considers global extensions if not found in current commLocationId.
        // This simplified version only checks for the given commLocationId or globally if commLocationId is null.
        // PHP also has historical date checks (FDESDE, FHASTA) - omitted here.

        String queryStr = "SELECT er.* FROM extension_range er WHERE er.active = true " +
                "AND er.range_start <= :extNum AND er.range_end >= :extNum ";
        if (commLocationId != null) {
            queryStr += "AND er.comm_location_id = :commLocationId ";
        }
        // PHP orders by RANGOEXT_HISTODESDE DESC, RANGOEXT_DESDE DESC, RANGOEXT_HASTA ASC
        // We'll take the first match based on some ordering, e.g., more specific range.
        // For simplicity, just take the first one found.
        queryStr += "ORDER BY (er.range_end - er.range_start) ASC, er.created_date DESC LIMIT 1"; // Prefer smaller ranges, then newer

        jakarta.persistence.Query nativeQuery = entityManager.createNativeQuery(queryStr, ExtensionRange.class);
        nativeQuery.setParameter("extNum", extNum);
        if (commLocationId != null) {
            nativeQuery.setParameter("commLocationId", commLocationId);
        }

        List<ExtensionRange> ranges = nativeQuery.getResultList();
        if (!ranges.isEmpty()) {
            ExtensionRange matchedRange = ranges.get(0);
            log.debug("Extension {} matched range: {}", extension, matchedRange.getId());
            // PHP creates a conceptual employee. We'll do the same.
            // In a real system, you might not create an Employee entity here but rather use the range info directly.
            return Optional.of(createEmployeeFromRange(
                    extension,
                    matchedRange.getSubdivisionId(),
                    matchedRange.getCommLocationId(),
                    matchedRange.getPrefix()
            ));
        }
        return Optional.empty();
    }
}