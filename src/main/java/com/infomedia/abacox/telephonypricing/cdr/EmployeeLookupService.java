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
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

@Service
@Log4j2
@RequiredArgsConstructor
public class EmployeeLookupService {

    @PersistenceContext
    private EntityManager entityManager;
    private final CdrConfigService cdrConfigService;
    private final Map<Long, ExtensionLimits> extensionLimitsCache = new ConcurrentHashMap<>();

    @Transactional(readOnly = true)
    public void resetExtensionLimitsCache() {
        extensionLimitsCache.clear();
    }

    @Transactional(readOnly = true)
    public ExtensionLimits getExtensionLimits(CommunicationLocation commLocation) {
        if (commLocation == null || commLocation.getId() == null) {
            log.warn("getExtensionLimits called with null or invalid commLocation.");
            return new ExtensionLimits(); // Return default empty limits
        }
        return this.extensionLimitsCache.computeIfAbsent(commLocation.getId(), id -> {
            log.debug("Extension limits not found in cache for CommLocation ID: {}. Fetching.", id);
            if (commLocation.getIndicator() != null && commLocation.getIndicator().getOriginCountryId() != null) {
                return getExtensionLimitsLookup(
                        commLocation.getIndicator().getOriginCountryId(),
                        id,
                        commLocation.getPlantTypeId()
                );
            }
            log.warn("Cannot fetch extension limits: Indicator or OriginCountryId is null for CommLocation ID: {}", id);
            return new ExtensionLimits();
        });
    }


    @Transactional(readOnly = true)
    public Optional<Employee> findEmployeeByExtensionOrAuthCode(String extension, String authCode,
                                                                Long commLocationIdContext,
                                                                LocalDateTime callTime) {
        StringBuilder queryStr = new StringBuilder("SELECT e.* FROM employee e ");
        queryStr.append(" LEFT JOIN communication_location cl ON e.communication_location_id = cl.id ");
        queryStr.append(" WHERE e.active = true ");

        boolean hasAuthCode = authCode != null && !authCode.isEmpty();
        String cleanedExtension = (extension != null) ? CdrUtil.cleanPhoneNumber(extension, null, false) : null;
        if (cleanedExtension != null && cleanedExtension.startsWith("+")) {
            cleanedExtension = cleanedExtension.substring(1);
        }
        boolean hasExtension = cleanedExtension != null && !cleanedExtension.isEmpty();

        boolean isAuthCodeIgnoredType = hasAuthCode && cdrConfigService.getIgnoredAuthCodeDescriptions().stream()
                .anyMatch(ignored -> ignored.equalsIgnoreCase(authCode));

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

        boolean searchGlobally = (hasAuthCode && !isAuthCodeIgnoredType) ?
                                 cdrConfigService.areAuthCodesGlobal(plantTypeIdForGlobalCheck) :
                                 cdrConfigService.areExtensionsGlobal(plantTypeIdForGlobalCheck);

        if (!searchGlobally && commLocationIdContext != null) {
            queryStr.append(" AND e.communication_location_id = :commLocationIdContext ");
        }
        queryStr.append(" AND (cl.id IS NULL OR cl.active = true) ");


        if (searchGlobally && commLocationIdContext != null) {
            queryStr.append(" ORDER BY CASE WHEN e.communication_location_id = :commLocationIdContext THEN 0 ELSE 1 END, e.created_date DESC LIMIT 1");
        } else {
            queryStr.append(" ORDER BY e.created_date DESC LIMIT 1");
        }


        jakarta.persistence.Query nativeQuery = entityManager.createNativeQuery(queryStr.toString(), Employee.class);

        if (hasAuthCode && !isAuthCodeIgnoredType) {
            nativeQuery.setParameter("authCode", authCode);
        } else if (hasExtension) {
            nativeQuery.setParameter("extension", cleanedExtension);
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
            // ExtensionValida($ext, true) checks if it's not starting with '0' (unless it's '0' itself)
            // and is generally numeric-like.
            boolean phpExtensionValida = hasExtension &&
                                         (!cleanedExtension.startsWith("0") || cleanedExtension.equals("0")) &&
                                         cleanedExtension.matches("^[0-9#*+]+$"); // Allows #,*,+ as per PHP's ValidarTelefono

            if (phpExtensionValida) {
                return findEmployeeByExtensionRange(cleanedExtension, commLocationIdContext, callTime);
            }
            return Optional.empty();
        }
    }

    public Employee createEmployeeFromRange(String extension, Long subdivisionId, Long commLocationId, String namePrefix) {
        Employee newEmployee = new Employee();
        newEmployee.setExtension(extension);
        newEmployee.setName((namePrefix != null ? namePrefix : "Ext") + " " + extension);
        newEmployee.setSubdivisionId(subdivisionId);
        newEmployee.setCommunicationLocationId(commLocationId);
        newEmployee.setActive(true);
        log.info("Conceptually representing new employee for extension {} from range.", extension);
        return newEmployee;
    }

    @Transactional(readOnly = true)
    public ExtensionLimits getExtensionLimitsLookup(Long originCountryId, Long commLocationId, Long plantTypeId) {
        int finalMinVal = 100;
        int finalMaxVal = CdrConfigService.ACUMTOTAL_MAX_EXTENSION_LENGTH_FOR_INTERNAL_CHECK;

        int maxLenEmployees = 0;
        int minLenEmployees = Integer.MAX_VALUE;
        boolean empLenSet = false;

        String maxAllowedLenStr = String.valueOf(CdrConfigService.ACUMTOTAL_MAX_EXTENSION_LENGTH_FOR_INTERNAL_CHECK);
        int maxStandardExtLength = maxAllowedLenStr.length() - 1;
        if (maxStandardExtLength < 1) maxStandardExtLength = 1; // Ensure it's at least 1

        StringBuilder empQueryBuilder = new StringBuilder(
            "SELECT CAST(LENGTH(e.extension) AS INTEGER) as ext_len " +
            "FROM employee e " +
            "JOIN communication_location cl ON e.communication_location_id = cl.id " +
            "JOIN indicator i ON cl.indicator_id = i.id " +
            "WHERE e.active = true AND cl.active = true AND i.active = true " +
            "  AND e.extension ~ '^[1-9][0-9]*$' " + // Numeric, not starting with 0
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
        if (!empLenSet) minLenEmployees = 0; // No numeric extensions found fitting criteria

        int maxLenRanges = 0;
        int minLenRanges = Integer.MAX_VALUE;
        boolean rangeLenSet = false;

        StringBuilder rangeQueryBuilder = new StringBuilder(
            "SELECT CAST(LENGTH(er.range_start::text) AS INTEGER) as len_desde, CAST(LENGTH(er.range_end::text) AS INTEGER) as len_hasta " +
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

        // PHP: MaxMinGuardar logic
        // The PHP logic for MaxMinGuardar is a bit complex with $forzar.
        // Simplified: if any lengths were found, use them. Otherwise, stick to defaults.
        int effectiveMinLen = Integer.MAX_VALUE;
        int effectiveMaxLen = 0;

        if (empLenSet) {
            effectiveMinLen = Math.min(effectiveMinLen, minLenEmployees);
            effectiveMaxLen = Math.max(effectiveMaxLen, maxLenEmployees);
        }
        if (rangeLenSet) {
            effectiveMinLen = Math.min(effectiveMinLen, minLenRanges);
            effectiveMaxLen = Math.max(effectiveMaxLen, maxLenRanges);
        }

        if (effectiveMinLen == Integer.MAX_VALUE) effectiveMinLen = 0; // No lengths found

        if (effectiveMaxLen > 0) {
            finalMaxVal = Integer.parseInt("9".repeat(effectiveMaxLen));
        }
        if (effectiveMinLen > 0) {
            finalMinVal = Integer.parseInt("1" + "0".repeat(Math.max(0, effectiveMinLen - 1)));
        }

        // Ensure min is not greater than max if both were derived from actual data
        if (finalMinVal > finalMaxVal && finalMaxVal > 0 && effectiveMinLen > 0 && effectiveMaxLen > 0) {
            finalMinVal = finalMaxVal; // Or adjust based on specific business rule for this conflict
        }


        StringBuilder specialExtQueryBuilder = new StringBuilder(
            "SELECT DISTINCT e.extension FROM employee e " +
            "JOIN communication_location cl ON e.communication_location_id = cl.id " +
            "JOIN indicator i ON cl.indicator_id = i.id " +
            "WHERE e.active = true AND cl.active = true AND i.active = true " +
            "  AND e.extension NOT LIKE '%-%' " +
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
        String cleanedExt = CdrUtil.cleanPhoneNumber(extensionNumber, null, false);
        if (cleanedExt.startsWith("+")) cleanedExt = cleanedExt.substring(1);

        if (limits.getSpecialFullExtensions() != null && limits.getSpecialFullExtensions().contains(cleanedExt)) {
            return true;
        }

        // PHP: $extension_valida = ExtensionValida($extension, true);
        // ExtensionValida checks if it's not starting with '0' (unless it's '0' itself)
        // AND is generally numeric-like (allows #*+).
        // Here, we only care about numeric for range check.
        boolean phpExtensionValidaForNumericRange = (!cleanedExt.startsWith("0") || cleanedExt.equals("0")) &&
                                                    cleanedExt.matches("\\d+"); // Strictly numeric for range

        if (phpExtensionValidaForNumericRange) {
            try {
                long extNumValue = Long.parseLong(cleanedExt);
                return extNumValue >= limits.getMinLength() && extNumValue <= limits.getMaxLength();
            } catch (NumberFormatException e) {
                return false;
            }
        }
        return false;
    }

    @Transactional(readOnly = true)
    public Optional<Employee> findEmployeeByExtensionRange(String extension, Long commLocationIdContext, LocalDateTime callTime) {
        if (extension == null || !extension.matches("\\d+")) {
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
            "JOIN communication_location cl ON er.comm_location_id = cl.id " +
            "WHERE er.active = true AND cl.active = true " +
            "AND er.range_start <= :extNum AND er.range_end >= :extNum ");

        if (!searchRangesGlobally && commLocationIdContext != null) {
            queryStrBuilder.append("AND er.comm_location_id = :commLocationIdContext ");
        }
        // If plantTypeIdForGlobalCheck is needed for global range searches (e.g. ranges are plant-type specific)
        // else if (searchRangesGlobally && plantTypeIdForGlobalCheck != null) {
        //    queryStrBuilder.append("AND cl.plant_type_id = :plantTypeIdForGlobalCheck ");
        // }


        if (searchRangesGlobally && commLocationIdContext != null) {
            queryStrBuilder.append("ORDER BY CASE WHEN er.comm_location_id = :commLocationIdContext THEN 0 ELSE 1 END, (er.range_end - er.range_start) ASC, er.created_date DESC LIMIT 1");
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
        // if (searchRangesGlobally && plantTypeIdForGlobalCheck != null) {
        //     nativeQuery.setParameter("plantTypeIdForGlobalCheck", plantTypeIdForGlobalCheck);
        // }


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