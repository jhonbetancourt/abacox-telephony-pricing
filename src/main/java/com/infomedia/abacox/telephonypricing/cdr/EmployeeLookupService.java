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
        queryStr.append(" LEFT JOIN communication_location cl ON e.communication_location_id = cl.id "); // LEFT JOIN if commLocationId can be null for global employees
        queryStr.append(" WHERE e.active = true ");
        if (commLocationId != null) { // Only filter by active comm_location if one is provided
             queryStr.append(" AND cl.active = true ");
        }


        boolean hasAuthCode = authCode != null && !authCode.isEmpty();
        boolean hasExtension = extension != null && !extension.isEmpty();

        if (hasAuthCode) {
            queryStr.append(" AND e.auth_code = :authCode ");
        } else if (hasExtension) {
            queryStr.append(" AND e.extension = :extension ");
        } else {
            return Optional.empty();
        }

        if (commLocationId != null) {
            queryStr.append(" AND e.communication_location_id = :commLocationId ");
        }
        // PHP's ObtenerHistoricosFuncionarios sorts by HISTODESDE DESC.
        // Since we are omitting historical lookups for now, this is simplified.
        // If historical were needed, we'd add date range checks and potentially more complex ordering.
        queryStr.append(" ORDER BY e.created_date DESC LIMIT 1"); // Get the most recent if multiple match (e.g. global vs specific)

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
            if (!hasAuthCode && hasExtension) { // Only try range if lookup was by extension and failed
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
        // Audited fields will be set by Spring
        log.info("Conceptually creating new employee for extension {} from range.", extension);
        return newEmployee;
    }

    @Transactional(readOnly = true)
    public ExtensionLimits getExtensionLimits(Long originCountryId, Long commLocationId, Long plantTypeId) {
        // PHP's ObtenerMaxMin
        int maxLenOverall = 0;
        int minLenOverall = 0;
        boolean firstMinSet = false;

        String maxAllowedLenStr = String.valueOf(CdrConfigService.ACUMTOTAL_MAX_EXTENSION_LENGTH_FOR_INTERNAL_CHECK);
        int maxStandardExtLength = maxAllowedLenStr.length() - 1;


        // Query for employee extensions
        String empQueryStr = "SELECT LENGTH(e.extension) as ext_len " +
                             "FROM employee e " +
                             "JOIN communication_location cl ON e.communication_location_id = cl.id " +
                             "JOIN indicator i ON cl.indicator_id = i.id " +
                             "WHERE e.active = true AND cl.active = true AND i.active = true " +
                             "  AND e.extension ~ '^[0-9]+$' " + // Numeric check
                             "  AND e.extension NOT LIKE '0%' " + // Not starting with 0
                             "  AND LENGTH(e.extension) BETWEEN 1 AND :maxStandardExtLength ";
        if (originCountryId != null) empQueryStr += " AND i.origin_country_id = :originCountryId ";
        if (commLocationId != null) empQueryStr += " AND e.communication_location_id = :commLocationId ";
        if (plantTypeId != null) empQueryStr += " AND cl.plant_type_id = :plantTypeId ";

        jakarta.persistence.Query empQuery = entityManager.createNativeQuery(empQueryStr);
        empQuery.setParameter("maxStandardExtLength", maxStandardExtLength);
        if (originCountryId != null) empQuery.setParameter("originCountryId", originCountryId);
        if (commLocationId != null) empQuery.setParameter("commLocationId", commLocationId);
        if (plantTypeId != null) empQuery.setParameter("plantTypeId", plantTypeId);

        List<Number> empLengths = empQuery.getResultList();
        for (Number lenNum : empLengths) {
            int len = lenNum.intValue();
            if (len > maxLenOverall) maxLenOverall = len;
            if (!firstMinSet || len < minLenOverall) {
                minLenOverall = len;
                firstMinSet = true;
            }
        }

        // Query for extension ranges
        String rangeQueryStr = "SELECT LENGTH(er.range_start::text) as len_desde, LENGTH(er.range_end::text) as len_hasta " +
                               "FROM extension_range er " +
                               "JOIN communication_location cl ON er.comm_location_id = cl.id " +
                               "JOIN indicator i ON cl.indicator_id = i.id " +
                               "WHERE er.active = true AND cl.active = true AND i.active = true " +
                               "  AND er.range_start::text ~ '^[0-9]+$' AND er.range_end::text ~ '^[0-9]+$' " + // Ensure numeric before length
                               "  AND LENGTH(er.range_start::text) BETWEEN 1 AND :maxStandardExtLength " +
                               "  AND LENGTH(er.range_end::text) BETWEEN 1 AND :maxStandardExtLength ";
        if (originCountryId != null) rangeQueryStr += " AND i.origin_country_id = :originCountryId ";
        if (commLocationId != null) rangeQueryStr += " AND er.comm_location_id = :commLocationId ";
        if (plantTypeId != null) rangeQueryStr += " AND cl.plant_type_id = :plantTypeId ";

        jakarta.persistence.Query rangeQuery = entityManager.createNativeQuery(rangeQueryStr);
        rangeQuery.setParameter("maxStandardExtLength", maxStandardExtLength);
        if (originCountryId != null) rangeQuery.setParameter("originCountryId", originCountryId);
        if (commLocationId != null) rangeQuery.setParameter("commLocationId", commLocationId);
        if (plantTypeId != null) rangeQuery.setParameter("plantTypeId", plantTypeId);

        List<Object[]> rangeLengthPairs = rangeQuery.getResultList();
        for (Object[] pair : rangeLengthPairs) {
            int lenDesde = ((Number) pair[0]).intValue();
            int lenHasta = ((Number) pair[1]).intValue();
            if (lenHasta > maxLenOverall) maxLenOverall = lenHasta; // Max is based on range_end length
            if (!firstMinSet || lenDesde < minLenOverall) { // Min is based on range_start length
                minLenOverall = lenDesde;
                firstMinSet = true;
            }
        }

        int finalMinVal = CdrConfigService.ACUMTOTAL_MAX_EXTENSION_LENGTH_FOR_INTERNAL_CHECK; // Default high
        int finalMaxVal = 0; // Default low

        if (maxLenOverall > 0) {
            finalMaxVal = Integer.parseInt("9".repeat(maxLenOverall));
        } else { // No numeric extensions found, use default max
            finalMaxVal = CdrConfigService.ACUMTOTAL_MAX_EXTENSION_LENGTH_FOR_INTERNAL_CHECK;
        }

        if (minLenOverall > 0) {
            finalMinVal = Integer.parseInt("1" + "0".repeat(Math.max(0, minLenOverall - 1)));
        } else { // No numeric extensions found, use default min
            finalMinVal = 100; // PHP default
        }
        
        if (finalMinVal > finalMaxVal && finalMaxVal > 0) { // Ensure min is not greater than max
            finalMinVal = finalMaxVal;
        }
         if (finalMinVal == CdrConfigService.ACUMTOTAL_MAX_EXTENSION_LENGTH_FOR_INTERNAL_CHECK && finalMaxVal == 0) { // Both uninitialized
            finalMinVal = 100;
            finalMaxVal = CdrConfigService.ACUMTOTAL_MAX_EXTENSION_LENGTH_FOR_INTERNAL_CHECK;
        }


        // Get special full extensions (long numbers, or starting with 0, #, *)
        String specialExtQueryStr = "SELECT DISTINCT e.extension FROM employee e " +
                                    "JOIN communication_location cl ON e.communication_location_id = cl.id " +
                                    "JOIN indicator i ON cl.indicator_id = i.id " +
                                    "WHERE e.active = true AND cl.active = true AND i.active = true " +
                                    "  AND (LENGTH(e.extension) >= :maxExtStandardLenForFullList OR e.extension LIKE '0%' OR e.extension LIKE '*%' OR e.extension LIKE '#%') ";
        if (originCountryId != null) specialExtQueryStr += " AND i.origin_country_id = :originCountryId ";
        if (commLocationId != null) specialExtQueryStr += " AND e.communication_location_id = :commLocationId ";
        if (plantTypeId != null) specialExtQueryStr += " AND cl.plant_type_id = :plantTypeId ";

        jakarta.persistence.Query specialExtQuery = entityManager.createNativeQuery(specialExtQueryStr, String.class);
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
        // PHP's ExtensionValida logic for numeric part
        if (extensionNumber.matches("\\d+")) { // Purely numeric
            if (extensionNumber.equals("0")) {
                 return true; // '0' is often special (operator)
            }
            if (!extensionNumber.startsWith("0")) { // Positive number not starting with 0
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


        String queryStr = "SELECT er.* FROM extension_range er WHERE er.active = true " +
                "AND er.range_start <= :extNum AND er.range_end >= :extNum ";
        if (commLocationId != null) {
            queryStr += "AND er.comm_location_id = :commLocationId ";
        }
        // PHP's Validar_RangoExt also considers historical validity (RANGOEXT_HISTODESDE)
        // Since historical lookups are omitted, we don't add date checks here.
        queryStr += "ORDER BY (er.range_end - er.range_start) ASC, er.created_date DESC LIMIT 1"; // Prefer tighter ranges

        jakarta.persistence.Query nativeQuery = entityManager.createNativeQuery(queryStr, ExtensionRange.class);
        nativeQuery.setParameter("extNum", extNum);
        if (commLocationId != null) {
            nativeQuery.setParameter("commLocationId", commLocationId);
        }

        List<ExtensionRange> ranges = nativeQuery.getResultList();
        if (!ranges.isEmpty()) {
            ExtensionRange matchedRange = ranges.get(0);
            log.debug("Extension {} matched range: {}", extension, matchedRange.getId());
            // Create a conceptual employee; it's not persisted here.
            // The calling service (e.g., CdrEnrichmentService) would decide if/how to use this.
            Employee conceptualEmployee = createEmployeeFromRange(
                    extension,
                    matchedRange.getSubdivisionId(),
                    matchedRange.getCommLocationId(),
                    matchedRange.getPrefix()
            );
            // Load the actual CommunicationLocation for this conceptual employee
            if (matchedRange.getCommLocationId() != null) {
                CommunicationLocation cl = entityManager.find(CommunicationLocation.class, matchedRange.getCommLocationId());
                conceptualEmployee.setCommunicationLocation(cl); // Set the actual entity
            }

            return Optional.of(conceptualEmployee);
        }
        return Optional.empty();
    }
}