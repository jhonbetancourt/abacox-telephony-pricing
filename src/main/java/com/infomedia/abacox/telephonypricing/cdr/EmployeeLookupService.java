// File: com/infomedia/abacox/telephonypricing/cdr/EmployeeLookupService.java
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
    private final CdrConfigService cdrConfigService; // For global extension config

    /**
     * PHP equivalent: ObtenerFuncionario_Arreglo (core logic part)
     * and FunIDValido
     */
    @Transactional(readOnly = true)
    public Optional<Employee> findEmployeeByExtensionOrAuthCode(String extension, String authCode,
                                                                Long commLocationIdContext, // The comm_location_id of the current plant/CDR source
                                                                LocalDateTime callTime) {
        StringBuilder queryStr = new StringBuilder("SELECT e.* FROM employee e ");
        queryStr.append(" LEFT JOIN communication_location cl ON e.communication_location_id = cl.id ");
        queryStr.append(" WHERE e.active = true ");

        boolean hasAuthCode = authCode != null && !authCode.isEmpty();
        boolean hasExtension = extension != null && !extension.isEmpty();
        boolean isAuthCodeIgnoredType = hasAuthCode && cdrConfigService.getIgnoredAuthCodeDescriptions().stream()
                .anyMatch(ignored -> ignored.equalsIgnoreCase(authCode));

        if (hasAuthCode && !isAuthCodeIgnoredType) {
            queryStr.append(" AND e.auth_code = :authCode ");
        } else if (hasExtension) { // If auth code is ignored type, or no auth code, search by extension
            queryStr.append(" AND e.extension = :extension ");
        } else {
            return Optional.empty(); // No valid search criteria
        }

        // PHP: $tipo_fun (0=any, 2=local only)
        // $ext_globales = ObtenerGlobales($link, 'ext_globales');
        // if ($ext_globales) { $tipo_fun = 0; }
        boolean searchGlobally = cdrConfigService.areExtensionsGlobal(
            commLocationIdContext != null ? getPlantTypeIdForCommLocation(commLocationIdContext) : null
        );

        if (!searchGlobally && commLocationIdContext != null) {
            queryStr.append(" AND e.communication_location_id = :commLocationIdContext ");
        }
        // If searchGlobally, no specific comm_location_id filter is applied here for the employee,
        // but we still ensure their linked comm_location (if any) is active.
        queryStr.append(" AND (cl.id IS NULL OR cl.active = true) ");


        // PHP: ORDER BY FUNCIONARIO_HISTODESDE DESC (implicitly handled by historical data structure in PHP)
        // For JPA, if historical versions were separate rows, you'd filter by callTime against validFrom/validTo.
        // Since we omit historical lookups for now, we just take the latest created.
        // PHP's FunIDValido also prefers match in commid_destino if provided, then current commid.
        // Here, if !searchGlobally, it's already filtered by commLocationIdContext.
        // If searchGlobally, we might prefer one from commLocationIdContext if multiple matches.
        if (searchGlobally && commLocationIdContext != null) {
            queryStr.append(" ORDER BY CASE WHEN e.communication_location_id = :commLocationIdContext THEN 0 ELSE 1 END, e.created_date DESC LIMIT 1");
        } else {
            queryStr.append(" ORDER BY e.created_date DESC LIMIT 1");
        }


        jakarta.persistence.Query nativeQuery = entityManager.createNativeQuery(queryStr.toString(), Employee.class);

        if (hasAuthCode && !isAuthCodeIgnoredType) {
            nativeQuery.setParameter("authCode", authCode);
        } else if (hasExtension) {
            nativeQuery.setParameter("extension", extension);
        }

        if (!searchGlobally && commLocationIdContext != null) {
            nativeQuery.setParameter("commLocationIdContext", commLocationIdContext);
        } else if (searchGlobally && commLocationIdContext != null) {
            nativeQuery.setParameter("commLocationIdContext", commLocationIdContext);
        }


        try {
            Employee employee = (Employee) nativeQuery.getSingleResult();
            return Optional.of(employee);
        } catch (jakarta.persistence.NoResultException e) {
            // PHP: if ($retornar['id'] <= 0 && ExtensionValida($ext, true)) { $retornar = Validar_RangoExt(...); }
            if (hasExtension && isPossibleExtension(extension, getExtensionLimits(
                commLocationIdContext != null ? getOriginCountryIdForCommLocation(commLocationIdContext) : null,
                commLocationIdContext,
                commLocationIdContext != null ? getPlantTypeIdForCommLocation(commLocationIdContext) : null
            ))) {
                return findEmployeeByExtensionRange(extension, commLocationIdContext, callTime);
            }
            return Optional.empty();
        }
    }

    /**
     * PHP equivalent: createEmployeeFromRange (conceptual part of Validar_RangoExt)
     */
    public Employee createEmployeeFromRange(String extension, Long subdivisionId, Long commLocationId, String namePrefix) {
        Employee newEmployee = new Employee();
        newEmployee.setExtension(extension);
        newEmployee.setName((namePrefix != null ? namePrefix : "Ext") + " " + extension); // PHP: $value["PREFIJO"].' '.$ext
        newEmployee.setSubdivisionId(subdivisionId);
        newEmployee.setCommunicationLocationId(commLocationId);
        newEmployee.setActive(true); // New conceptual employees are active
        // ID is null as it's not persisted yet, just a conceptual representation
        log.info("Conceptually representing new employee for extension {} from range.", extension);
        return newEmployee;
    }

    /**
     * PHP equivalent: ObtenerMaxMin and parts of ObtenerExtensionesEspeciales
     */
    @Transactional(readOnly = true)
    public ExtensionLimits getExtensionLimits(Long originCountryId, Long commLocationId, Long plantTypeId) {
        int maxLenEmployees = 0;
        int minLenEmployees = Integer.MAX_VALUE;
        boolean empLenSet = false;

        String maxAllowedLenStr = String.valueOf(CdrConfigService.ACUMTOTAL_MAX_EXTENSION_LENGTH_FOR_INTERNAL_CHECK);
        int maxStandardExtLength = maxAllowedLenStr.length() - 1;

        StringBuilder empQueryBuilder = new StringBuilder(
            "SELECT CAST(LENGTH(e.extension) AS INTEGER) as ext_len " + // Ensure integer type
            "FROM employee e " +
            "JOIN communication_location cl ON e.communication_location_id = cl.id " +
            "JOIN indicator i ON cl.indicator_id = i.id " +
            "WHERE e.active = true AND cl.active = true AND i.active = true " +
            "  AND e.extension ~ '^[0-9]+$' " +
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
        if (!empLenSet) minLenEmployees = 0;

        int maxLenRanges = 0;
        int minLenRanges = Integer.MAX_VALUE;
        boolean rangeLenSet = false;

        StringBuilder rangeQueryBuilder = new StringBuilder(
            "SELECT CAST(LENGTH(er.range_start::text) AS INTEGER) as len_desde, CAST(LENGTH(er.range_end::text) AS INTEGER) as len_hasta " +
            "FROM extension_range er " +
            "JOIN communication_location cl ON er.comm_location_id = cl.id " +
            "JOIN indicator i ON cl.indicator_id = i.id " +
            "WHERE er.active = true AND cl.active = true AND i.active = true " +
            "  AND er.range_start::text ~ '^[0-9]+$' AND er.range_end::text ~ '^[0-9]+$' " + // Ensure they are numeric like
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

        if (finalMinLen == Integer.MAX_VALUE) finalMinLen = 0;

        int finalMinVal;
        int finalMaxVal;

        if (finalMaxLen > 0) {
            finalMaxVal = Integer.parseInt("9".repeat(finalMaxLen));
        } else {
            finalMaxVal = CdrConfigService.ACUMTOTAL_MAX_EXTENSION_LENGTH_FOR_INTERNAL_CHECK; // PHP default
        }

        if (finalMinLen > 0) {
            finalMinVal = Integer.parseInt("1" + "0".repeat(Math.max(0, finalMinLen - 1)));
        } else {
            finalMinVal = 100; // PHP default if no min length found
        }

        if (finalMinVal > finalMaxVal && finalMaxVal > 0) { // Ensure min is not greater than max
            finalMinVal = finalMaxVal;
        }
        if (finalMinLen == 0 && finalMaxLen == 0) { // If no data found for lengths
            finalMinVal = 100; // PHP default
            finalMaxVal = CdrConfigService.ACUMTOTAL_MAX_EXTENSION_LENGTH_FOR_INTERNAL_CHECK; // PHP default
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

    /**
     * PHP equivalent: ExtensionPosible
     */
    public boolean isPossibleExtension(String extensionNumber, ExtensionLimits limits) {
        if (extensionNumber == null || extensionNumber.isEmpty()) {
            return false;
        }
        // PHP: $extension_valida = ExtensionValida($extension, true);
        // ExtensionValida checks if it doesn't start with '0' unless it's just '0'.
        // Here, cleanPhoneNumber will handle '+' and other non-digits.
        String cleanedExt = CdrParserUtil.cleanPhoneNumber(extensionNumber, null, false);

        if (limits.getSpecialFullExtensions() != null && limits.getSpecialFullExtensions().contains(cleanedExt)) {
            return true;
        }

        // PHP: $ext_dentro_limite = ($extension_valida && is_numeric($extension) && $extension >= $_LIM_INTERNAS['min'] && $extension <= $_LIM_INTERNAS['max']);
        if (cleanedExt.matches("\\d+")) { // is_numeric
            if (cleanedExt.equals("0")) { // PHP: $extension = '0' se manejó para identificar Operadora
                 return true; // Historically allowed
            }
            // PHP: $extension_valida (not starting with '0' unless it's '0' itself)
            if (cleanedExt.startsWith("0") && !cleanedExt.equals("0")) {
                return false;
            }
            try {
                long extNumValue = Long.parseLong(cleanedExt);
                return extNumValue >= limits.getMinLength() && extNumValue <= limits.getMaxLength();
            } catch (NumberFormatException e) {
                return false; // Should not happen if matches("\\d+")
            }
        }
        // PHP doesn't explicitly check for '+' in ExtensionPosible, but cleanPhoneNumber handles it.
        // Non-numeric (after cleaning) other than special list are not possible extensions.
        return false;
    }

    /**
     * PHP equivalent: Validar_RangoExt
     */
    @Transactional(readOnly = true)
    public Optional<Employee> findEmployeeByExtensionRange(String extension, Long commLocationIdContext, LocalDateTime callTime) {
        if (extension == null || !extension.matches("\\d+")) { // Must be numeric for range check
            return Optional.empty();
        }
        long extNum;
        try {
            extNum = Long.parseLong(extension);
        } catch (NumberFormatException e) {
            return Optional.empty();
        }

        // PHP: $comid_rango = 0; if (!$ext_globales) { $comid_rango = $comid; }
        // PHP: Obtener_RangoExt($link, $_RANGOS_EXT, $comid_rango, $bd);
        // This means if extensions are not global, it only searches ranges for the current commLocationIdContext.
        // If global, it searches all ranges.

        boolean searchRangesGlobally = cdrConfigService.areExtensionsGlobal(
            commLocationIdContext != null ? getPlantTypeIdForCommLocation(commLocationIdContext) : null
        );

        StringBuilder queryStrBuilder = new StringBuilder(
            "SELECT er.* FROM extension_range er " +
            "JOIN communication_location cl ON er.comm_location_id = cl.id " +
            "WHERE er.active = true AND cl.active = true " + // Ensure range and its comm_location are active
            "AND er.range_start <= :extNum AND er.range_end >= :extNum ");

        if (!searchRangesGlobally && commLocationIdContext != null) {
            queryStrBuilder.append("AND er.comm_location_id = :commLocationIdContext ");
        }
        // PHP: ORDER BY RANGOEXT_HISTODESDE DESC, RANGOEXT_DESDE DESC, RANGOEXT_HASTA ASC
        // Omitting historical for now. Order by range tightness (smallest range first), then by comm_location preference.
        if (searchRangesGlobally && commLocationIdContext != null) {
            queryStrBuilder.append("ORDER BY (er.range_end - er.range_start) ASC, CASE WHEN er.comm_location_id = :commLocationIdContext THEN 0 ELSE 1 END, er.created_date DESC LIMIT 1");
        } else {
            queryStrBuilder.append("ORDER BY (er.range_end - er.range_start) ASC, er.created_date DESC LIMIT 1");
        }


        jakarta.persistence.Query nativeQuery = entityManager.createNativeQuery(queryStrBuilder.toString(), ExtensionRange.class);
        nativeQuery.setParameter("extNum", extNum);
        if (!searchRangesGlobally && commLocationIdContext != null) {
            nativeQuery.setParameter("commLocationIdContext", commLocationIdContext);
        } else if (searchRangesGlobally && commLocationIdContext != null) {
            nativeQuery.setParameter("commLocationIdContext", commLocationIdContext);
        }


        List<ExtensionRange> ranges = nativeQuery.getResultList();
        if (!ranges.isEmpty()) {
            ExtensionRange matchedRange = ranges.get(0); // Get the best match based on ordering
            log.debug("Extension {} matched range: {}", extension, matchedRange.getId());
            Employee conceptualEmployee = createEmployeeFromRange(
                    extension,
                    matchedRange.getSubdivisionId(),
                    matchedRange.getCommLocationId(),
                    matchedRange.getPrefix()
            );
            // Populate the conceptual employee's communication location if available
            if (matchedRange.getCommLocationId() != null) {
                CommunicationLocation cl = entityManager.find(CommunicationLocation.class, matchedRange.getCommLocationId());
                conceptualEmployee.setCommunicationLocation(cl);
            }
            return Optional.of(conceptualEmployee);
        }
        return Optional.empty();
    }

    private Long getPlantTypeIdForCommLocation(Long commLocationId) {
        if (commLocationId == null) return null;
        try {
            return entityManager.createQuery("SELECT cl.plantTypeId FROM CommunicationLocation cl WHERE cl.id = :id", Long.class)
                    .setParameter("id", commLocationId)
                    .getSingleResult();
        } catch (jakarta.persistence.NoResultException e) {
            return null;
        }
    }
     private Long getOriginCountryIdForCommLocation(Long commLocationId) {
        if (commLocationId == null) return null;
        try {
            return entityManager.createQuery("SELECT i.originCountryId FROM CommunicationLocation cl JOIN cl.indicator i WHERE cl.id = :id", Long.class)
                    .setParameter("id", commLocationId)
                    .getSingleResult();
        } catch (jakarta.persistence.NoResultException e) {
            return null;
        }
    }
}