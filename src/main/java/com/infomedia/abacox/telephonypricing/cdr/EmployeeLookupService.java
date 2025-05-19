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
    private final CdrConfigService cdrConfigService;

    /**
     * PHP equivalent: ObtenerFuncionario_Arreglo (core logic part)
     * and FunIDValido
     */
    @Transactional(readOnly = true)
    public Optional<Employee> findEmployeeByExtensionOrAuthCode(String extension, String authCode,
                                                                Long commLocationIdContext, // Can be null for global search
                                                                LocalDateTime callTime) {
        StringBuilder queryStr = new StringBuilder("SELECT e.* FROM employee e ");
        queryStr.append(" LEFT JOIN communication_location cl ON e.communication_location_id = cl.id "); // Join to get plant_type_id
        queryStr.append(" WHERE e.active = true ");

        boolean hasAuthCode = authCode != null && !authCode.isEmpty();
        // PHP: $ext = trim($info_cdr['ext']);
        // PHP: if ($param_tipo == 'ext' && substr($param, 0, 1) == '+') { $param = substr($param, 1); }
        String cleanedExtension = (extension != null) ? CdrParserUtil.cleanPhoneNumber(extension, null, false) : null;
        if (cleanedExtension != null && cleanedExtension.startsWith("+")) {
            cleanedExtension = cleanedExtension.substring(1);
        }
        boolean hasExtension = cleanedExtension != null && !cleanedExtension.isEmpty();

        // PHP: $_FUN_IGNORAR_CLAVE
        boolean isAuthCodeIgnoredType = hasAuthCode && cdrConfigService.getIgnoredAuthCodeDescriptions().stream()
                .anyMatch(ignored -> ignored.equalsIgnoreCase(authCode));

        // PHP: if ($clave != '' && $incoming == 0 && $tipo_origen) { ... $retornar = FunIDValido(...'clave'...) }
        // PHP: if ($ext != '' && !$esclave) { $retornar = FunIDValido(...'ext'...) }
        if (hasAuthCode && !isAuthCodeIgnoredType) {
            queryStr.append(" AND e.auth_code = :authCode ");
        } else if (hasExtension) {
            queryStr.append(" AND e.extension = :extension ");
        } else {
            log.debug("No valid extension or auth code provided for employee lookup.");
            return Optional.empty();
        }

        Long plantTypeIdForGlobalCheck = null;
        if (commLocationIdContext != null) {
            plantTypeIdForGlobalCheck = getPlantTypeIdForCommLocation(commLocationIdContext);
        }

        // PHP: $ext_globales = ObtenerGlobales($link, 'ext_globales');
        // PHP: $claves_globales = ObtenerGlobales($link, 'claves_globales');
        boolean searchGlobally = (hasAuthCode && !isAuthCodeIgnoredType) ?
                                 cdrConfigService.areAuthCodesGlobal(plantTypeIdForGlobalCheck) :
                                 cdrConfigService.areExtensionsGlobal(plantTypeIdForGlobalCheck);

        // PHP: $tipo_local = ($tipo == 2);
        // PHP: if ($tipo_origen && $comid > 0 && $info['comid'] != $comid) ... unset($funid[$key]);
        // This means if not global, it must match commLocationIdContext.
        // If commLocationIdContext is null, it implies a truly global search (e.g., for internal call destination).
        if (!searchGlobally && commLocationIdContext != null) {
            queryStr.append(" AND e.communication_location_id = :commLocationIdContext ");
        }
        // Ensure joined CommunicationLocation is active if it exists
        queryStr.append(" AND (cl.id IS NULL OR cl.active = true) ");


        // PHP: ORDER BY FUNCIONARIO_HISTODESDE DESC (historical omitted)
        // PHP: if ($comid_destino == $fcomid || ($comid_destino == 0 && $comid == $fcomid)) { $retornar = $info; break; }
        // This implies prioritizing match with specific commLocationIdContext if provided, even in global search.
        if (searchGlobally && commLocationIdContext != null) {
            queryStr.append(" ORDER BY CASE WHEN e.communication_location_id = :commLocationIdContext THEN 0 ELSE 1 END, e.created_date DESC LIMIT 1");
        } else {
            queryStr.append(" ORDER BY e.created_date DESC LIMIT 1"); // Simplification for non-historical
        }


        jakarta.persistence.Query nativeQuery = entityManager.createNativeQuery(queryStr.toString(), Employee.class);

        if (hasAuthCode && !isAuthCodeIgnoredType) {
            nativeQuery.setParameter("authCode", authCode);
        } else if (hasExtension) {
            nativeQuery.setParameter("extension", cleanedExtension);
        }

        if (!searchGlobally && commLocationIdContext != null) {
            nativeQuery.setParameter("commLocationIdContext", commLocationIdContext);
        } else if (searchGlobally && commLocationIdContext != null) { // Parameter needed for ORDER BY
            nativeQuery.setParameter("commLocationIdContext", commLocationIdContext);
        }


        try {
            Employee employee = (Employee) nativeQuery.getSingleResult();
            return Optional.of(employee);
        } catch (jakarta.persistence.NoResultException e) {
            // PHP: if ($retornar['id'] <= 0 && ExtensionValida($ext, true)) { $retornar = Validar_RangoExt(...); }
            // PHP's ExtensionValida checks if it's not starting with '0' (unless it's '0')
            if (hasExtension && !cleanedExtension.startsWith("0") && !cleanedExtension.equals("0")) { // PHP: ExtensionValida($ext, true)
                return findEmployeeByExtensionRange(cleanedExtension, commLocationIdContext, callTime);
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
        newEmployee.setName((namePrefix != null ? namePrefix : "Ext") + " " + extension);
        newEmployee.setSubdivisionId(subdivisionId);
        newEmployee.setCommunicationLocationId(commLocationId);
        newEmployee.setActive(true);
        // Note: This employee is conceptual and not persisted here.
        // The calling service (e.g., CdrEnrichmentService) might decide to persist it if auto-creation is enabled.
        log.info("Conceptually representing new employee for extension {} from range.", extension);
        return newEmployee;
    }

    /**
     * PHP equivalent: ObtenerMaxMin and parts of ObtenerExtensionesEspeciales
     */
    @Transactional(readOnly = true)
    public ExtensionLimits getExtensionLimits(Long originCountryId, Long commLocationId, Long plantTypeId) {
        // PHP: $_LIM_INTERNAS = array('min' => 100, 'max' => _ACUMTOTAL_MAXEXT, 'full' => array());
        int finalMinVal = 100; // Default min numeric value
        int finalMaxVal = CdrConfigService.ACUMTOTAL_MAX_EXTENSION_LENGTH_FOR_INTERNAL_CHECK; // Default max numeric value

        int maxLenEmployees = 0;
        int minLenEmployees = Integer.MAX_VALUE;
        boolean empLenSet = false;

        String maxAllowedLenStr = String.valueOf(CdrConfigService.ACUMTOTAL_MAX_EXTENSION_LENGTH_FOR_INTERNAL_CHECK);
        int maxStandardExtLength = maxAllowedLenStr.length() - 1;

        // PHP: $len_extension = bd_formatea_dato("LENGTH", 'FUNCIONARIO_EXTENSION');
        // PHP: $a_numero = bd_formatea_dato("ISNUMERIC", 'FUNCIONARIO_EXTENSION');
        // PHP: WHERE $a_numero AND not(FUNCIONARIO_EXTENSION LIKE '%-%' OR FUNCIONARIO_EXTENSION LIKE '0%')
        // PHP: AND $len_extension BETWEEN 1 AND ".(strlen(_ACUMTOTAL_MAXEXT) - 1);
        StringBuilder empQueryBuilder = new StringBuilder(
            "SELECT CAST(LENGTH(e.extension) AS INTEGER) as ext_len " +
            "FROM employee e " +
            "JOIN communication_location cl ON e.communication_location_id = cl.id " +
            "JOIN indicator i ON cl.indicator_id = i.id " +
            "WHERE e.active = true AND cl.active = true AND i.active = true " +
            "  AND e.extension ~ '^[1-9][0-9]*$' " + // Numeric, not starting with 0, no '-'
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

        // PHP: $len_desde = bd_formatea_dato("LENGTH", 'RANGOEXT_DESDE');
        // PHP: $len_hasta = bd_formatea_dato("LENGTH", 'RANGOEXT_HASTA');
        // PHP: WHERE $desde_numero AND $hasta_numero AND $len_desde BETWEEN 1 AND ... AND $len_hasta BETWEEN 1 AND ...
        StringBuilder rangeQueryBuilder = new StringBuilder(
            "SELECT CAST(LENGTH(er.range_start::text) AS INTEGER) as len_desde, CAST(LENGTH(er.range_end::text) AS INTEGER) as len_hasta " +
            "FROM extension_range er " +
            "JOIN communication_location cl ON er.comm_location_id = cl.id " +
            "JOIN indicator i ON cl.indicator_id = i.id " +
            "WHERE er.active = true AND cl.active = true AND i.active = true " +
            "  AND er.range_start::text ~ '^[0-9]+$' AND er.range_end::text ~ '^[0-9]+$' " + // Ensure they are numeric strings
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
            if (lenHasta > maxLenRanges) maxLenRanges = lenHasta; // Use length of range_end for max
            if (lenDesde < minLenRanges) minLenRanges = lenDesde; // Use length of range_start for min
            rangeLenSet = true;
        }
        if (!rangeLenSet) minLenRanges = 0;

        // PHP: MaxMinGuardar logic
        int effectiveMinLen = Integer.MAX_VALUE;
        int effectiveMaxLen = 0;
        boolean anyLengthFound = false;

        if (empLenSet) {
            effectiveMinLen = Math.min(effectiveMinLen, minLenEmployees);
            effectiveMaxLen = Math.max(effectiveMaxLen, maxLenEmployees);
            anyLengthFound = true;
        }
        if (rangeLenSet) {
            effectiveMinLen = Math.min(effectiveMinLen, minLenRanges);
            effectiveMaxLen = Math.max(effectiveMaxLen, maxLenRanges);
            anyLengthFound = true;
        }

        if (anyLengthFound) {
            if (effectiveMaxLen > 0) {
                finalMaxVal = Integer.parseInt("9".repeat(effectiveMaxLen));
            }
            if (effectiveMinLen > 0) {
                finalMinVal = Integer.parseInt("1" + "0".repeat(Math.max(0, effectiveMinLen - 1)));
            }
        }
        // Ensure min is not greater than max if both were derived from actual data
        if (anyLengthFound && finalMinVal > finalMaxVal && finalMaxVal > 0 && effectiveMinLen > 0 && effectiveMaxLen > 0) {
            finalMinVal = finalMaxVal;
        }


        // PHP: $_LIM_INTERNAS['full'] = ObtenerExtensionesEspeciales(...)
        // PHP: WHERE FUNCIONARIO_EXTENSION NOT LIKE '%-%' AND ($len_extension >= ... OR FUNCIONARIO_EXTENSION LIKE '0%' OR ... '*')
        StringBuilder specialExtQueryBuilder = new StringBuilder(
            "SELECT DISTINCT e.extension FROM employee e " +
            "JOIN communication_location cl ON e.communication_location_id = cl.id " +
            "JOIN indicator i ON cl.indicator_id = i.id " +
            "WHERE e.active = true AND cl.active = true AND i.active = true " +
            "  AND e.extension NOT LIKE '%-%' " + // Exclude ranges defined as extensions
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
        String cleanedExt = CdrParserUtil.cleanPhoneNumber(extensionNumber, null, false); // Basic cleaning
        if (cleanedExt.startsWith("+")) cleanedExt = cleanedExt.substring(1);


        // PHP: $ext_especial = in_array($ext, $_LIM_INTERNAS['full']);
        if (limits.getSpecialFullExtensions() != null && limits.getSpecialFullExtensions().contains(cleanedExt)) {
            return true;
        }

        // PHP: $ext_dentro_limite = ($extension_valida && is_numeric($extension) && $extension >= $_LIM_INTERNAS['min'] && $extension <= $_LIM_INTERNAS['max']);
        if (cleanedExt.matches("\\d+")) { // is_numeric
            // PHP: $extension_valida (not starting with '0' unless it's '0' itself)
            if (cleanedExt.startsWith("0") && !cleanedExt.equals("0")) { // "0" itself can be an operator extension
                return false;
            }
            try {
                long extNumValue = Long.parseLong(cleanedExt);
                return extNumValue >= limits.getMinLength() && extNumValue <= limits.getMaxLength();
            } catch (NumberFormatException e) {
                return false; // Not a valid number if parsing fails
            }
        }
        return false; // Not numeric and not in special list
    }

    /**
     * PHP equivalent: Validar_RangoExt
     */
    @Transactional(readOnly = true)
    public Optional<Employee> findEmployeeByExtensionRange(String extension, Long commLocationIdContext, LocalDateTime callTime) {
        if (extension == null || !extension.matches("\\d+")) { // PHP: is_numeric($destino)
            return Optional.empty();
        }
        long extNum;
        try {
            extNum = Long.parseLong(extension);
        } catch (NumberFormatException e) {
            return Optional.empty();
        }

        Long plantTypeIdForGlobalCheck = null;
        if (commLocationIdContext != null) {
            plantTypeIdForGlobalCheck = getPlantTypeIdForCommLocation(commLocationIdContext);
        }
        boolean searchRangesGlobally = cdrConfigService.areExtensionsGlobal(plantTypeIdForGlobalCheck);

        StringBuilder queryStrBuilder = new StringBuilder(
            "SELECT er.* FROM extension_range er " +
            "JOIN communication_location cl ON er.comm_location_id = cl.id " + // Join to filter by plant_type_id if needed
            "WHERE er.active = true AND cl.active = true " +
            "AND er.range_start <= :extNum AND er.range_end >= :extNum ");

        if (!searchRangesGlobally && commLocationIdContext != null) {
            queryStrBuilder.append("AND er.comm_location_id = :commLocationIdContext ");
        }
        // PHP: ORDER BY RANGOEXT_HISTODESDE DESC, RANGOEXT_DESDE DESC, RANGOEXT_HASTA ASC
        // Historical part omitted. The sort by DESDE DESC, HASTA ASC means prefer tighter ranges.
        // If searching globally, prefer ranges from the current commLocation.
        if (searchRangesGlobally && commLocationIdContext != null) {
            queryStrBuilder.append("ORDER BY CASE WHEN er.comm_location_id = :commLocationIdContext THEN 0 ELSE 1 END, (er.range_end - er.range_start) ASC, er.created_date DESC LIMIT 1");
        } else {
            queryStrBuilder.append("ORDER BY (er.range_end - er.range_start) ASC, er.created_date DESC LIMIT 1");
        }


        jakarta.persistence.Query nativeQuery = entityManager.createNativeQuery(queryStrBuilder.toString(), ExtensionRange.class);
        nativeQuery.setParameter("extNum", extNum);
        if (!searchRangesGlobally && commLocationIdContext != null) {
            nativeQuery.setParameter("commLocationIdContext", commLocationIdContext);
        } else if (searchRangesGlobally && commLocationIdContext != null) { // Parameter needed for ORDER BY
            nativeQuery.setParameter("commLocationIdContext", commLocationIdContext);
        }


        List<ExtensionRange> ranges = nativeQuery.getResultList();
        if (!ranges.isEmpty()) {
            ExtensionRange matchedRange = ranges.get(0); // Get the best match based on ORDER BY
            log.debug("Extension {} matched range: {}", extension, matchedRange.getId());
            Employee conceptualEmployee = createEmployeeFromRange(
                    extension,
                    matchedRange.getSubdivisionId(),
                    matchedRange.getCommLocationId(),
                    matchedRange.getPrefix()
            );
            // Fetch and set the actual CommunicationLocation for the conceptual employee
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
}